package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.io.File

/**
 * find-usages — semantic reference search for the symbol at a (file, line, column) position.
 *
 * Merged tool: folds the rich output shape of the former
 * `find-references-with-context` into `FindUsagesTool` while keeping this tool's trailing-lambda
 * parameter handling, and adopts the K2-safe declaration resolution used by `FindDeclarationTool`.
 *
 * Per reference it reports: precise 1-based line/column, usage type
 * (read / write / read-write / call / import / comment / declaration / trailing-lambda), the
 * containing `Class.function` symbol, and a grep -C style source snippet with a caret pointer.
 *
 * `byType` gives per-usageType counts over the FULL result set (before `limit`), so the counts
 * stay accurate even when `usages` is truncated. With `include_comments`, plain-text mentions in
 * comments/KDoc are also found (project scope, like the IDE's "Usage in comments").
 */
@OptIn(KaExperimentalApi::class)
class FindUsagesTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(FindUsagesTool::class.java)

    data class UsageResult(
        val file: String,
        val line: Int,                   // the USAGE (call/reference site), 1-based
        val column: Int,
        val usageType: String,           // read / write / read-write / call / import / comment / declaration / trailing-lambda
        val containingSymbol: ContainingSymbol?, // the enclosing declaration this usage sits in (null for imports)
        val snippet: String              // Source context with line numbers and caret pointer
    )

    /**
     * The named declaration a usage sits inside. `line`/`column` point at the declaration's own
     * name identifier — i.e. where you'd invoke `find-usages` again to walk one hop up the call
     * chain. It lives in the same file as the usage (a usage is always lexically inside its
     * container), so it reuses the usage's top-level `file`.
     */
    data class ContainingSymbol(
        val name: String,   // "ClassName.functionName" / "functionName" / "ClassName"
        val line: Int,      // declaration name-identifier position, 1-based
        val column: Int
    )

    data class FindUsagesResponse(
        val success: Boolean,
        val targetSymbol: String? = null,
        val targetKind: String? = null, // class / function / property / parameter / variable / object / other
        val byType: Map<String, Int> = emptyMap(), // usageType -> count, over the FULL set (before limit)
        val usages: List<UsageResult> = emptyList(),
        val count: Int = 0,             // number of usages returned (after limit)
        val totalCount: Int = 0,        // total usages found (before limit)
        val truncated: Boolean = false,
        val error: String? = null
    )

    override fun getDescription(): String =
        "🔍 PRECISE USAGE TRACKING: IntelliJ-powered semantic reference finding that understands " +
        "Kotlin syntax, scoping, and imports. Finds actual code usage, not false positive text " +
        "matches like grep. Each usage includes its type (read/write/call/import/comment/declaration), " +
        "the containing class/function, and a grep -C style source snippet; `byType` gives per-type " +
        "counts over the full result set (accurate even when the list is truncated by `limit`). Handles " +
        "Compose trailing lambdas (a function's last/content parameter consumed as `Screen(...) { ... }`). " +
        "Set `include_comments` to also find plain-text mentions in comments/KDoc (project scope)."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path. Relative paths resolved against project root."
            ),
            "line" to mapOf("type" to "integer", "description" to "Line number of the symbol (1-based)."),
            "column" to mapOf("type" to "integer", "description" to "Column of the symbol (1-based)."),
            "context_lines" to mapOf(
                "type" to "integer",
                "description" to "Lines of surrounding context for each usage snippet (default 2).",
                "default" to 2
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "Max usages to return (default 100). `byType`/`totalCount` still reflect the full set.",
                "default" to 100
            ),
            "include_comments" to mapOf(
                "type" to "boolean",
                "description" to "Also find plain-text mentions of the symbol in comments/KDoc (project scope), " +
                    "reported with usageType \"comment\". Default false. Matches occurrences by word, so it can " +
                    "include unrelated same-name text.",
                "default" to false
            )
        ),
        "required" to listOf("file_path", "line", "column")
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return errorResult("Missing 'file_path' parameter")
            val line = arguments.get("line")?.asInt
                ?: return errorResult("Missing or invalid 'line' parameter")
            val column = arguments.get("column")?.asInt
                ?: return errorResult("Missing or invalid 'column' parameter")
            val contextLines = arguments.get("context_lines")?.asInt ?: 2
            val limit = arguments.get("limit")?.asInt ?: 100
            val includeComments = arguments.get("include_comments")?.asBoolean ?: false

            gson.toJson(find(filePath, line, column, contextLines, limit, includeComments))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in FindUsagesTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun find(
        filePath: String,
        line: Int,
        column: Int,
        contextLines: Int,
        limit: Int,
        includeComments: Boolean
    ): FindUsagesResponse {
        val resolvedPath = resolveAbsolutePath(filePath)
        val project = servedProject()
            ?: return FindUsagesResponse(success = false, error = NO_PROJECT_MESSAGE)

        return ApplicationManager.getApplication().runReadAction<FindUsagesResponse> {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                ?: return@runReadAction FindUsagesResponse(success = false, error = "File not found: $resolvedPath")

            if (!ProjectFileIndex.getInstance(project).isInContent(virtualFile)) {
                return@runReadAction FindUsagesResponse(success = false, error = notIndexedError(resolvedPath))
            }

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@runReadAction FindUsagesResponse(success = false, error = "Could not get PSI for file: $resolvedPath")

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadAction FindUsagesResponse(success = false, error = "Could not get document for file: $resolvedPath")

            val offset = try {
                document.getLineStartOffset(line - 1) + (column - 1)
            } catch (e: Exception) {
                return@runReadAction FindUsagesResponse(
                    success = false,
                    error = "Invalid line/column position: line=$line, column=$column. Error: ${e.message}"
                )
            }
            if (offset < 0 || offset >= document.textLength) {
                return@runReadAction FindUsagesResponse(
                    success = false,
                    error = "Position out of bounds: offset=$offset, document length=${document.textLength}"
                )
            }

            val leaf = psiFile.findElementAt(offset)
                ?: return@runReadAction FindUsagesResponse(success = false, error = "No element found at line $line, column $column")

            val target = resolveToDeclaration(leaf)
                ?: return@runReadAction FindUsagesResponse(
                    success = false,
                    error = "No resolvable symbol at position (found: ${leaf.javaClass.simpleName} '${leaf.text}')"
                )

            logger.info("Finding usages for '${getElementName(target)}' (${target.javaClass.simpleName}) at $resolvedPath:$line:$column")

            // Search for references. Parameters get special handling so that Compose-style
            // trailing lambdas (which create no textual reference to the parameter name) count.
            val references: List<PsiReference> = if (target is KtParameter) {
                ReferencesSearch.search(target).findAll() + findTrailingLambdaUsages(target)
            } else {
                ReferencesSearch.search(target).findAll().toList()
            }

            // Classify every usage cheaply (usageType + coords, no snippet) so `byType`/`totalCount`
            // stay accurate under truncation and comment matches can be deduped against references.
            // The expensive per-usage work (snippet + containingSymbol) is done only for the
            // returned slice below — mirroring how the IDE renders usages lazily rather than
            // materializing all of them.
            val refRaws = references.mapNotNull { ref ->
                try {
                    buildRawUsage(ref, project)
                } catch (e: Exception) {
                    logger.debug("Failed to classify usage: ${e.message}")
                    null
                }
            }

            val commentRaws = if (includeComments) {
                val seen = refRaws.mapTo(HashSet()) { Triple(it.file, it.line, it.column) }
                findCommentUsages(target, project, seen)
            } else {
                emptyList()
            }

            val allRaws = refRaws + commentRaws
            val byType = allRaws.groupingBy { it.usageType }.eachCount().toSortedMap()

            // Deterministic, relevance-aware ordering BEFORE truncation, so `take(limit)` retains a
            // predictable, useful slice instead of whatever order ReferencesSearch happened to return.
            // compareBy sorts ascending and false < true, so each boolean key puts its "false" group first.
            val targetFile = target.containingFile?.virtualFile?.path
            val ordered = allRaws.sortedWith(
                compareBy(
                    { it.usageType == "comment" },  // real references (false) before comment mentions (true)
                    { it.file != targetFile },       // declaration's own file (false) before other files (true)
                    { it.file },                     // then grouped by file,
                    { it.line },                     // top-to-bottom
                    { it.column }
                )
            )

            val usages = ordered.take(limit).map { r ->
                UsageResult(
                    file = r.file,
                    line = r.line,
                    column = r.column,
                    usageType = r.usageType,
                    containingSymbol = findContainingSymbol(r.element, r.document),
                    snippet = buildSnippet(r.document, r.line, r.column, r.refLength, contextLines)
                )
            }

            FindUsagesResponse(
                success = true,
                targetSymbol = getElementName(target),
                targetKind = getElementKind(target),
                byType = byType,
                usages = usages,
                count = usages.size,
                totalCount = allRaws.size,
                truncated = allRaws.size > limit
            )
        }
    }

    /**
     * Lightweight classified usage: everything needed for `byType`/`totalCount`/dedup and to build
     * the snippet later, without doing the snippet or containingSymbol work up front. [element] is
     * retained only so `containingSymbol` can be computed for the returned slice.
     */
    private class RawUsage(
        val file: String,
        val line: Int,
        val column: Int,
        val usageType: String,
        val element: PsiElement,
        val document: Document,
        val refLength: Int
    )

    /**
     * Plain-text mentions of the target's name in comments and KDoc (project scope), like the IDE's
     * "Usage in comments". Word-based, so it can include unrelated same-name text. Matches whose
     * (file, line, column) coincide with an already-found reference (e.g. a resolved KDoc `[link]`)
     * are skipped via [seen] to avoid double counting.
     */
    private fun findCommentUsages(
        target: PsiElement,
        project: Project,
        seen: Set<Triple<String, Int, Int>>
    ): List<RawUsage> {
        val name = (target as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() } ?: return emptyList()
        val results = mutableListOf<RawUsage>()
        // Dedup comment hits both against resolved references (`seen`, e.g. a KDoc `[link]`) and
        // against each other — processElementsWithWord can fire more than once per occurrence.
        val dedup = seen.toMutableSet()
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            { element, offsetInElement ->
                try {
                    // `IN_COMMENTS` is only a per-file index filter, not per-occurrence — the scan
                    // also surfaces the declaration identifier — so confirm the hit really sits
                    // inside a comment/KDoc.
                    if (element is PsiComment || PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) {
                        val file = element.containingFile
                        val vf = file?.virtualFile
                        val doc = file?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
                        if (vf != null && doc != null) {
                            val abs = element.textRange.startOffset + offsetInElement
                            val ln = doc.getLineNumber(abs) + 1
                            val col = abs - doc.getLineStartOffset(ln - 1) + 1
                            val key = Triple(vf.path, ln, col)
                            if (dedup.add(key)) {
                                results.add(
                                    RawUsage(
                                        file = vf.path,
                                        line = ln,
                                        column = col,
                                        usageType = "comment",
                                        element = element,
                                        document = doc,
                                        refLength = name.length
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Failed to classify comment usage: ${e.message}")
                }
                true
            },
            GlobalSearchScope.projectScope(project),
            name,
            UsageSearchContext.IN_COMMENTS,
            true
        )
        return results
    }

    // ---------------------------------------------------------------------------------------------
    // Declaration resolution (K2-safe; mirrors FindDeclarationTool so cross-module Kotlin symbols
    // resolve, and we never fall through to an *enclosing* declaration from an unresolved reference).
    // ---------------------------------------------------------------------------------------------

    private fun resolveToDeclaration(element: PsiElement): PsiElement? {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        // (1) Kotlin references — generic PsiElement.reference returns null for cross-module /
        //     library symbols under K2, so resolve via the Kotlin main reference then the
        //     K2 Analysis API.
        val ktRef = PsiTreeUtil.getParentOfType(element, KtSimpleNameExpression::class.java, false)
        if (ktRef != null) {
            resolveKotlinReference(ktRef)?.let { return it }
        }

        // (2) Generic PSI reference resolution — primarily Java. Walk up only within the current
        //     expression and stop at the first enclosing declaration so we never fall through.
        var current: PsiElement? = element
        while (current != null && current !is KtDeclaration && current !is PsiMember) {
            current.reference?.resolve()?.let { return it }
            current = current.parent
        }

        // (3) The cursor may sit directly on a declaration's own name identifier
        //     (find-usages invoked on the declaration itself).
        return findDeclarationByNameIdentifier(element)
    }

    private fun resolveKotlinReference(refExpr: KtSimpleNameExpression): PsiElement? {
        val ref = refExpr.mainReference
        try {
            ref.resolve()?.let { return it }
        } catch (e: Exception) {
            logger.warn("mainReference.resolve() failed for '${refExpr.text}': ${e.message}")
        }
        return try {
            analyze(refExpr) {
                ref.resolveToSymbols().firstNotNullOfOrNull { it.psi }
            }
        } catch (e: Exception) {
            logger.warn("K2 resolveToSymbols failed for '${refExpr.text}': ${e.message}")
            null
        }
    }

    private fun findDeclarationByNameIdentifier(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            if (isDeclarationElement(current)) {
                val nameId = getNameIdentifier(current)
                return if (nameId != null && nameId.textRange.contains(element.textRange)) current else null
            }
            current = current.parent
        }
        return null
    }

    private fun isDeclarationElement(element: PsiElement): Boolean = when (element) {
        is PsiParameter,
        is PsiVariable,
        is PsiMethod,
        is PsiClass,
        is PsiField,
        is KtParameter,
        is KtProperty,
        is KtVariableDeclaration,
        is KtNamedFunction,
        is KtClass,
        is KtObjectDeclaration,
        is KtTypeAlias,
        is PsiNameIdentifierOwner -> true
        else -> false
    }

    private fun getNameIdentifier(element: PsiElement): PsiElement? = when (element) {
        is PsiNameIdentifierOwner -> element.nameIdentifier
        is KtNamedDeclaration -> element.nameIdentifier
        else -> null
    }

    // ---------------------------------------------------------------------------------------------
    // Trailing lambda usages (unchanged behavior from the original FindUsagesTool).
    // ---------------------------------------------------------------------------------------------

    private fun findTrailingLambdaUsages(parameter: KtParameter): List<PsiReference> {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        val ownerFunction = parameter.ownerFunction ?: return emptyList()
        val parameters = ownerFunction.valueParameters
        val parameterIndex = parameters.indexOf(parameter)
        if (parameterIndex == -1) return emptyList()

        val functionCalls = ReferencesSearch.search(ownerFunction).findAll()
        val trailingLambdaRefs = mutableListOf<PsiReference>()

        for (callRef in functionCalls) {
            val callElement = callRef.element.parentOfType<KtCallExpression>() ?: continue
            val lambdaArguments = callElement.lambdaArguments
            if (lambdaArguments.isEmpty()) continue

            val isLastParameter = (parameterIndex == parameters.size - 1)
            if (isLastParameter) {
                val lambdaExpr = lambdaArguments.first().getLambdaExpression()
                if (lambdaExpr != null) {
                    trailingLambdaRefs.add(TrailingLambdaReference(lambdaExpr, parameter))
                }
            }
        }
        return trailingLambdaRefs
    }

    private class TrailingLambdaReference(
        private val lambdaExpression: KtLambdaExpression,
        private val parameter: KtParameter
    ) : PsiReference {
        override fun getElement(): PsiElement = lambdaExpression
        override fun resolve(): PsiElement = parameter
        override fun getRangeInElement(): TextRange = TextRange(0, 1)
        override fun getCanonicalText(): String = parameter.name ?: "content"
        override fun handleElementRename(newElementName: String): PsiElement = lambdaExpression
        override fun bindToElement(element: PsiElement): PsiElement = lambdaExpression
        override fun isReferenceTo(element: PsiElement): Boolean = element == parameter
        override fun isSoft(): Boolean = false
    }

    // ---------------------------------------------------------------------------------------------
    // Result formatting (ported from FindReferencesWithContextTool).
    // ---------------------------------------------------------------------------------------------

    /**
     * Classify a reference into a [RawUsage] — resolves its precise position and usageType but does
     * NOT build the snippet or containingSymbol (deferred to the returned slice for performance).
     */
    private fun buildRawUsage(reference: PsiReference, project: Project): RawUsage? {
        val element = reference.element
        val containingFile = element.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null

        // Precise offset of the reference *within* the element (matters for qualified names).
        val rangeInElement = reference.rangeInElement
        val refOffset = element.textRange.startOffset + rangeInElement.startOffset

        val line = document.getLineNumber(refOffset) + 1
        val column = refOffset - document.getLineStartOffset(line - 1) + 1

        return RawUsage(
            file = virtualFile.path,
            line = line,
            column = column,
            usageType = detectUsageType(element, reference),
            element = element,
            document = document,
            refLength = rangeInElement.length.coerceAtLeast(1)
        )
    }

    private fun detectUsageType(element: PsiElement, reference: PsiReference): String {
        // Synthetic trailing-lambda usages are argument-passing call sites.
        if (reference is TrailingLambdaReference) return "trailing-lambda"

        if (PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java, false) != null) return "import"
        if (isDeclarationIdentifier(element)) return "declaration"

        val refExpr: PsiElement = run {
            val parent = element.parent
            if (parent is KtQualifiedExpression && parent.selectorExpression === element) parent else element
        }

        val refParent = refExpr.parent
        if (refParent is KtCallExpression && refParent.calleeExpression === refExpr) return "call"

        val binExpr = PsiTreeUtil.getParentOfType(
            refExpr,
            KtBinaryExpression::class.java,
            true,
            KtBlockExpression::class.java,
            KtFunctionLiteral::class.java
        )
        if (binExpr != null && PsiTreeUtil.isAncestor(binExpr.left, refExpr, false)) {
            return when (binExpr.operationToken) {
                KtTokens.EQ -> "write"
                KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ,
                KtTokens.DIVEQ, KtTokens.PERCEQ -> "read-write"
                else -> "read"
            }
        }

        val unaryExpr = PsiTreeUtil.getParentOfType(refExpr, KtUnaryExpression::class.java, true)
        if (unaryExpr != null && PsiTreeUtil.isAncestor(unaryExpr.baseExpression, refExpr, false)) {
            if (unaryExpr.operationToken == KtTokens.PLUSPLUS || unaryExpr.operationToken == KtTokens.MINUSMINUS) {
                return "read-write"
            }
        }

        return "read"
    }

    private fun isDeclarationIdentifier(element: PsiElement): Boolean {
        val parent = element.parent ?: return false
        return parent is PsiNameIdentifierOwner && parent.nameIdentifier === element
    }

    /**
     * The named declaration enclosing [element], with its name-identifier position (in [document],
     * the usage's own file). The position is the chain target: `find-usages` on it walks one hop up.
     * Prefers the innermost callable/member and falls back to the enclosing class; returns null when
     * there is no enclosing named declaration (e.g. a file-level import).
     */
    private fun findContainingSymbol(element: PsiElement, document: Document): ContainingSymbol? {
        val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
        val javaMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        val property = PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
        val javaField = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
        val ktClass = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java, false)
        val javaClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

        // Innermost enclosing named declaration = what you'd re-query for the next hop up.
        val target: PsiElement = function ?: javaMethod ?: property ?: javaField ?: ktClass ?: javaClass
            ?: return null

        val nameId = getNameIdentifier(target) ?: return null
        val simpleName = (target as? PsiNamedElement)?.name ?: return null

        // Qualify a member with its enclosing class for readability ("Class.function").
        val name = if (target === function || target === javaMethod || target === property || target === javaField) {
            val enclosingClassName = when {
                ktClass != null && PsiTreeUtil.isAncestor(ktClass, target, false) -> ktClass.name
                javaClass != null && PsiTreeUtil.isAncestor(javaClass, target, false) -> javaClass.name
                else -> null
            }
            if (enclosingClassName != null) "$enclosingClassName.$simpleName" else simpleName
        } else {
            simpleName
        }

        val offset = nameId.textRange.startOffset
        val ln = document.getLineNumber(offset) + 1
        val col = offset - document.getLineStartOffset(ln - 1) + 1
        return ContainingSymbol(name, ln, col)
    }

    private fun buildSnippet(
        document: Document,
        line: Int,
        column: Int,
        refLength: Int,
        contextLines: Int
    ): String {
        val totalLines = document.lineCount
        val startLine = (line - 1 - contextLines).coerceAtLeast(0)
        val endLine = (line - 1 + contextLines).coerceAtMost(totalLines - 1)
        val maxLineNumWidth = (endLine + 1).toString().length

        val sb = StringBuilder()
        for (l in startLine..endLine) {
            val lineText = document.getText(TextRange(document.getLineStartOffset(l), document.getLineEndOffset(l)))
            val lineNum = (l + 1).toString().padStart(maxLineNumWidth)
            val marker = if (l == line - 1) ">" else " "
            sb.append("$marker $lineNum | $lineText\n")
            if (l == line - 1) {
                val padding = " ".repeat(maxLineNumWidth + 4 + (column - 1))
                val carets = "^".repeat(refLength.coerceAtLeast(1))
                sb.append("$padding$carets\n")
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private fun getElementName(element: PsiElement): String? = when (element) {
        is PsiNameIdentifierOwner -> element.name
        else -> element.text?.take(50)
    }

    private fun getElementKind(element: PsiElement): String = when (element) {
        is KtClass, is PsiClass -> "class"
        is KtNamedFunction, is PsiMethod -> "function"
        is KtProperty -> "property"
        is KtParameter -> "parameter"
        is KtVariableDeclaration -> "variable"
        is KtObjectDeclaration -> "object"
        is PsiParameter -> "parameter"
        is PsiField -> "field"
        is PsiVariable -> "variable"
        else -> element.javaClass.simpleName
    }

    private fun errorResult(message: String): String =
        gson.toJson(FindUsagesResponse(success = false, error = message))
}
