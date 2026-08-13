package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import org.jetbrains.kotlin.kdoc.psi.api.KDocElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtReferenceExpression
import java.io.File

class GetCallHierarchyTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(GetCallHierarchyTool::class.java)

    data class HierarchyNode(
        val name: String,
        val containingClass: String?,
        val file: String,
        val line: Int,
        val column: Int,
        val depth: Int,
        val children: List<HierarchyNode>,
        val truncated: Boolean = false
    )

    data class GetCallHierarchyResponse(
        val success: Boolean,
        val direction: String? = null,     // "callers" / "callees"
        val rootName: String? = null,
        val maxDepth: Int = 0,
        val totalNodes: Int = 0,
        val root: HierarchyNode? = null,
        val error: String? = null
    )

    override fun getDescription(): String =
        "Get the call hierarchy of a Kotlin/Java function or method at a given position. " +
        "direction='callers' walks incoming calls (who calls this, and who calls them...); " +
        "direction='callees' walks outgoing calls (what does this call, and what do those call...). " +
        "Bounded by max_depth and max_per_node to prevent tree explosion. Uses ReferencesSearch " +
        "for callers (project scope by default) and PSI traversal for callees. Cycles in the " +
        "call graph are detected and pruned."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Path to the file containing the function."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line of the function declaration (1-based)."
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column of the function declaration (1-based)."
            ),
            "direction" to mapOf(
                "type" to "string",
                "description" to "'callers' for incoming calls, 'callees' for outgoing calls.",
                "enum" to listOf("callers", "callees"),
                "default" to "callers"
            ),
            "max_depth" to mapOf(
                "type" to "integer",
                "description" to "Maximum recursion depth (root = depth 0). Default 2.",
                "default" to 2
            ),
            "max_per_node" to mapOf(
                "type" to "integer",
                "description" to "Maximum children to expand per node (prevents explosion). Default 10.",
                "default" to 10
            ),
            "scope" to mapOf(
                "type" to "string",
                "description" to "Search scope for callers: 'project' (default) or 'all'.",
                "enum" to listOf("project", "all"),
                "default" to "project"
            )
        ),
        "required" to listOf("file_path", "line", "column")
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return errorResult("Missing 'file_path'")
            val line = arguments.get("line")?.asInt
                ?: return errorResult("Missing 'line'")
            val column = arguments.get("column")?.asInt
                ?: return errorResult("Missing 'column'")
            val direction = arguments.get("direction")?.asString ?: "callers"
            val maxDepth = arguments.get("max_depth")?.asInt ?: 2
            val maxPerNode = arguments.get("max_per_node")?.asInt ?: 10
            val scopeKind = arguments.get("scope")?.asString ?: "project"

            gson.toJson(compute(filePath, line, column, direction, maxDepth, maxPerNode, scopeKind))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in GetCallHierarchyTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun compute(
        filePath: String,
        line: Int,
        column: Int,
        direction: String,
        maxDepth: Int,
        maxPerNode: Int,
        scopeKind: String
    ): GetCallHierarchyResponse {
        val resolvedPath = resolveAbsolutePath(filePath)
        val project = servedProject()
            ?: return GetCallHierarchyResponse(success = false, error = NO_PROJECT_MESSAGE)

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
            ?: return GetCallHierarchyResponse(success = false, error = "File not found: $resolvedPath")

        val scope = when (scopeKind) {
            "all" -> GlobalSearchScope.allScope(project)
            else -> GlobalSearchScope.projectScope(project)
        }

        return ApplicationManager.getApplication().runReadAction<GetCallHierarchyResponse> {
            if (!ProjectFileIndex.getInstance(project).isInContent(vf)) {
                return@runReadAction GetCallHierarchyResponse(
                    success = false,
                    error = notIndexedError(resolvedPath)
                )
            }

            val psiFile = PsiManager.getInstance(project).findFile(vf)
                ?: return@runReadAction GetCallHierarchyResponse(success = false, error = "Could not load PSI")
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadAction GetCallHierarchyResponse(success = false, error = "No document")

            val offset = try {
                document.getLineStartOffset(line - 1) + (column - 1)
            } catch (e: Exception) {
                return@runReadAction GetCallHierarchyResponse(success = false, error = "Invalid line/column")
            }
            val leaf = psiFile.findElementAt(offset)
                ?: return@runReadAction GetCallHierarchyResponse(success = false, error = "No PSI at position")

            val rootFn = findFunctionTarget(leaf)
                ?: return@runReadAction GetCallHierarchyResponse(
                    success = false, error = "No function/method found at $line:$column"
                )

            val counter = IntArray(1)
            val visited = mutableSetOf<String>()
            val root = when (direction) {
                "callees" -> buildCalleeTree(rootFn, project, 0, maxDepth, maxPerNode, visited, counter)
                else -> buildCallerTree(rootFn, project, scope, 0, maxDepth, maxPerNode, visited, counter)
            }

            GetCallHierarchyResponse(
                success = true,
                direction = direction,
                rootName = describe(rootFn).first,
                maxDepth = maxDepth,
                totalNodes = counter[0],
                root = root
            )
        }
    }

    // ---- Caller tree: for each fn, who calls it? ----

    private fun buildCallerTree(
        fn: PsiElement,
        project: Project,
        scope: GlobalSearchScope,
        depth: Int,
        maxDepth: Int,
        maxPerNode: Int,
        visited: MutableSet<String>,
        counter: IntArray
    ): HierarchyNode {
        counter[0]++
        val node = toNode(fn, depth, children = emptyList())
        val key = identity(fn) ?: return node
        if (!visited.add(key)) return node // cycle
        if (depth >= maxDepth) return node

        val callerFns = mutableListOf<PsiElement>()
        val seen = mutableSetOf<String>()
        try {
            // Lazy + early-terminating: forEach streams references and returning false aborts the
            // search, so we stop once maxPerNode distinct callers are collected rather than
            // materializing every reference (findAll() timed out on very heavily-called symbols).
            // Skip references inside KDoc/comments — a doc-comment `[link]` resolves to the
            // documented function's enclosing declaration and would be a phantom caller. (Imports
            // are already dropped: they have no enclosing function.)
            ReferencesSearch.search(fn, scope).forEach(Processor { ref ->
                if (!isInCommentOrKDoc(ref.element)) {
                    val callerFn = enclosingFunction(ref.element)
                    val k = callerFn?.let { identity(it) }
                    if (callerFn != null && k != null && seen.add(k)) callerFns.add(callerFn)
                }
                callerFns.size < maxPerNode   // continue while under cap; false stops the search
            })
        } catch (e: Exception) {
            logger.warn("ReferencesSearch failed for ${describe(fn).first}: ${e.message}")
        }

        val truncated = callerFns.size >= maxPerNode
        val children = callerFns.map { c ->
            buildCallerTree(c, project, scope, depth + 1, maxDepth, maxPerNode, visited, counter)
        }
        return node.copy(children = children, truncated = truncated)
    }

    // ---- Callee tree: for each fn, what does it call? ----

    private fun buildCalleeTree(
        fn: PsiElement,
        project: Project,
        depth: Int,
        maxDepth: Int,
        maxPerNode: Int,
        visited: MutableSet<String>,
        counter: IntArray
    ): HierarchyNode {
        counter[0]++
        val node = toNode(fn, depth, children = emptyList())
        val key = identity(fn) ?: return node
        if (!visited.add(key)) return node
        if (depth >= maxDepth) return node

        val calleeFns = mutableListOf<PsiElement>()
        val seen = mutableSetOf<String>()
        try {
            collectCallees(fn, calleeFns, seen, maxPerNode)
        } catch (e: Exception) {
            logger.warn("Callee collection failed for ${describe(fn).first}: ${e.message}")
        }
        val truncated = calleeFns.size >= maxPerNode
        val children = calleeFns.map { c ->
            buildCalleeTree(c, project, depth + 1, maxDepth, maxPerNode, visited, counter)
        }
        return node.copy(children = children, truncated = truncated)
    }

    private fun collectCallees(
        fn: PsiElement,
        out: MutableList<PsiElement>,
        seen: MutableSet<String>,
        cap: Int
    ) {
        when (fn) {
            is KtFunction -> {
                val body = fn.bodyExpression ?: fn.bodyBlockExpression ?: return
                collectKotlinCallees(body, out, seen, cap)
            }
            is PsiMethod -> {
                val body = fn.body ?: return
                body.accept(object : com.intellij.psi.JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: com.intellij.psi.PsiMethodCallExpression) {
                        super.visitMethodCallExpression(expression)
                        if (out.size >= cap) return
                        val resolved = expression.resolveMethod() ?: return
                        val k = identity(resolved) ?: return
                        if (seen.add(k)) out.add(resolved)
                    }
                })
            }
            is KtProperty -> {
                fn.accessors.forEach { acc ->
                    acc.bodyExpression?.let { collectKotlinCallees(it, out, seen, cap) }
                }
                fn.initializer?.let { collectKotlinCallees(it, out, seen, cap) }
            }
        }
    }

    private fun collectKotlinCallees(
        root: PsiElement,
        out: MutableList<PsiElement>,
        seen: MutableSet<String>,
        cap: Int
    ) {
        PsiTreeUtil.processElements(root) { element ->
            if (out.size >= cap) return@processElements false
            val refExpr = when (element) {
                is KtCallExpression -> element.calleeExpression as? KtReferenceExpression
                is KtReferenceExpression -> element
                else -> null
            } ?: return@processElements true
            val resolved = refExpr.references.firstNotNullOfOrNull { it.resolve() } ?: return@processElements true
            if (resolved is KtNamedFunction || resolved is PsiMethod ||
                (resolved is KtProperty && resolved.accessors.isNotEmpty())
            ) {
                val k = identity(resolved) ?: return@processElements true
                if (seen.add(k)) out.add(resolved)
            }
            true
        }
    }

    // ---- Helpers ----

    private fun findFunctionTarget(leaf: PsiElement): PsiElement? {
        // Prefer reference resolution from a usage.
        leaf.reference?.resolve()?.let { if (isFunctionLike(it)) return it }
        var current: PsiElement? = leaf
        while (current != null) {
            if (isFunctionLike(current)) return current
            current.reference?.resolve()?.let { if (isFunctionLike(it)) return it }
            current = current.parent
        }
        return null
    }

    private fun isFunctionLike(element: PsiElement): Boolean = when (element) {
        is KtNamedFunction, is KtPropertyAccessor, is PsiMethod, is KtProperty -> true
        else -> false
    }

    /** True when the reference sits inside a comment or KDoc (a doc `[link]`, not a real call). */
    private fun isInCommentOrKDoc(element: PsiElement): Boolean =
        element is PsiComment ||
        PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null ||
        PsiTreeUtil.getParentOfType(element, KDocElement::class.java, false) != null

    private fun enclosingFunction(element: PsiElement): PsiElement? {
        // Walk up until we hit a named function / method / property (accessor).
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is KtNamedFunction, is PsiMethod, is KtPropertyAccessor, is KtProperty -> return current
            }
            current = current.parent
        }
        return null
    }

    private fun identity(element: PsiElement): String? {
        val file = element.containingFile?.virtualFile?.path ?: return null
        val offset = element.textRange?.startOffset ?: return null
        return "$file:$offset"
    }

    private fun toNode(element: PsiElement, depth: Int, children: List<HierarchyNode>): HierarchyNode {
        val (name, containing) = describe(element)
        val file = element.containingFile?.virtualFile?.path ?: "<unknown>"
        val doc = element.containingFile?.let {
            PsiDocumentManager.getInstance(element.project).getDocument(it)
        }
        // Anchor on the name identifier, not textRange.startOffset (which includes leading
        // KDoc/annotations), so the reported line points at the declaration, not its doc comment.
        val offset = nameIdentifierOffset(element)
        val lineNum = if (doc != null && offset != null) doc.getLineNumber(offset) + 1 else 0
        val col = if (doc != null && offset != null) offset - doc.getLineStartOffset(lineNum - 1) + 1 else 0
        return HierarchyNode(
            name = name,
            containingClass = containing,
            file = file,
            line = lineNum,
            column = col,
            depth = depth,
            children = children
        )
    }

    /** Offset of the declaration's name identifier (falls back to the element start). */
    private fun nameIdentifierOffset(element: PsiElement): Int? {
        val nameId = (element as? PsiNameIdentifierOwner)?.nameIdentifier
            ?: (element as? KtNamedDeclaration)?.nameIdentifier
        return (nameId ?: element).textRange?.startOffset
    }

    private fun describe(element: PsiElement): Pair<String, String?> {
        val name = (element as? PsiNamedElement)?.name ?: "<unnamed>"
        val containing = when (element) {
            is KtNamedFunction, is KtProperty, is KtCallableDeclaration ->
                (element.parent?.parent as? KtClassOrObject)?.fqName?.asString()
                    ?: (element.parent?.parent as? KtClassOrObject)?.name
            is KtPropertyAccessor -> (element.property.parent?.parent as? KtClassOrObject)?.fqName?.asString()
            is PsiMethod -> element.containingClass?.qualifiedName ?: element.containingClass?.name
            is PsiClass -> element.qualifiedName?.substringBeforeLast('.')
            else -> null
        }
        return name to containing
    }

    private fun errorResult(message: String): String =
        gson.toJson(GetCallHierarchyResponse(success = false, error = message))
}
