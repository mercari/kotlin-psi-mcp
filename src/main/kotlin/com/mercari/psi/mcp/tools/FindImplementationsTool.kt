package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import java.io.File

class FindImplementationsTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(FindImplementationsTool::class.java)

    data class Implementation(
        val name: String,
        val containingClass: String?,
        val file: String,
        val line: Int?,      // null (omitted) when the element has no resolvable
        val column: Int?,    // source position — e.g. a compiled element with no document
        val isAbstract: Boolean = false
    )

    data class FindImplementationsResponse(
        val success: Boolean,
        val targetName: String? = null,
        val targetKind: String? = null,     // class / interface / function / property
        val count: Int = 0,
        val implementations: List<Implementation> = emptyList(),
        val error: String? = null
    )

    override fun getDescription(): String =
        "Find concrete implementations of an interface, abstract class, abstract function, or " +
        "interface property at a given file position. Uses IntelliJ's DefinitionsScopedSearch " +
        "(the same mechanism as the IDE's 'Go to Implementations' action), which handles both " +
        "method overrides and class inheritance. For a class, this is equivalent to listing " +
        "inheritors; for a method, it returns only overriding methods (not every subclass). " +
        "By default (scope='project') only implementers in your own source are returned; pass " +
        "scope='all' to also include implementers from outside your source — third-party " +
        "dependencies, the JDK, and SDKs (see the scope parameter)."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path to the file containing the declaration."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line of the declaration (1-based)."
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column of the declaration (1-based)."
            ),
            "scope" to mapOf(
                "type" to "string",
                "description" to "Which implementers to return. 'project' (default): only those " +
                    "declared in your own source. 'all': also includes implementers from outside " +
                    "your source — third-party dependencies, the JDK, and SDKs. Caveat: for Kotlin " +
                    "stdlib builtins (e.g. kotlin.CharSequence, kotlin.String) the underlying " +
                    "search returns library implementers even under 'project'.",
                "enum" to listOf("project", "all"),
                "default" to "project"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "Maximum number of implementations to return.",
                "default" to 100
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
            val scopeKind = arguments.get("scope")?.asString ?: "project"
            val limit = arguments.get("limit")?.asInt ?: 100

            gson.toJson(find(filePath, line, column, scopeKind, limit))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in FindImplementationsTool", e)
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
        scopeKind: String,
        limit: Int
    ): FindImplementationsResponse {
        val resolvedPath = resolveAbsolutePath(filePath)
        val project = servedProject()
            ?: return FindImplementationsResponse(success = false, error = NO_PROJECT_MESSAGE)


        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
            ?: return FindImplementationsResponse(success = false, error = "File not found: $resolvedPath")

        return ApplicationManager.getApplication().runReadAction<FindImplementationsResponse> {
            if (!ProjectFileIndex.getInstance(project).isInContent(vf)) {
                return@runReadAction FindImplementationsResponse(
                    success = false,
                    error = notIndexedError(resolvedPath)
                )
            }

            val psiFile = PsiManager.getInstance(project).findFile(vf)
                ?: return@runReadAction FindImplementationsResponse(success = false, error = "Could not load PSI file")
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadAction FindImplementationsResponse(success = false, error = "No document")

            val offset = try {
                document.getLineStartOffset(line - 1) + (column - 1)
            } catch (e: Exception) {
                return@runReadAction FindImplementationsResponse(success = false, error = "Invalid line/column")
            }
            if (offset < 0 || offset > document.textLength) {
                return@runReadAction FindImplementationsResponse(success = false, error = "Offset out of range")
            }
            val leaf = psiFile.findElementAt(offset)
                ?: return@runReadAction FindImplementationsResponse(success = false, error = "No PSI element at position")

            val target = findTarget(leaf)
                ?: return@runReadAction FindImplementationsResponse(
                    success = false,
                    error = "No interface/class/function/property found at $line:$column"
                )

            val (targetName, targetKind) = describeTarget(target)
            val scope = when (scopeKind) {
                "all" -> GlobalSearchScope.allScope(project)
                else -> GlobalSearchScope.projectScope(project)
            }

            val impls = mutableListOf<Implementation>()
            try {
                // For methods we explicitly use OverridingMethodsSearch to get only overrides
                // (not every subclass that inherits the method).
                // .findAll() instead of iterating the Query directly — Query.iterator()
                // is scheduled for removal.
                if (target is PsiMethod) {
                    for (impl in OverridingMethodsSearch.search(target, scope, /*checkDeep=*/ true).findAll()) {
                        if (impls.size >= limit) break
                        impls.add(toImplementation(impl, project))
                    }
                } else {
                    // DefinitionsScopedSearch dispatches to the right handler (Kotlin or Java).
                    val query = DefinitionsScopedSearch.search(target, scope, /*checkDeep=*/ true)
                    for (impl in query.findAll()) {
                        if (impls.size >= limit) break
                        impls.add(toImplementation(impl, project))
                    }
                }
            } catch (e: Exception) {
                logger.warn("Implementation search failed: ${e.message}")
                return@runReadAction FindImplementationsResponse(
                    success = false,
                    targetName = targetName,
                    targetKind = targetKind,
                    error = "Search failed: ${e.message}"
                )
            }

            FindImplementationsResponse(
                success = true,
                targetName = targetName,
                targetKind = targetKind,
                count = impls.size,
                implementations = impls
            )
        }
    }

    private fun findTarget(leaf: PsiElement): PsiElement? {
        // Prefer reference resolution for usage sites.
        leaf.reference?.resolve()?.let { if (isSearchable(it)) return it }

        var current: PsiElement? = leaf
        while (current != null) {
            if (isSearchable(current)) return current
            current.reference?.resolve()?.let { if (isSearchable(it)) return it }
            current = current.parent
        }
        return null
    }

    private fun isSearchable(element: PsiElement): Boolean = when (element) {
        is KtClassOrObject, is KtNamedFunction, is KtProperty,
        is PsiClass, is PsiMethod -> true
        else -> false
    }

    private fun describeTarget(element: PsiElement): Pair<String, String> {
        val name = (element as? PsiNamedElement)?.name ?: "<unnamed>"
        val kind = when (element) {
            is KtClass -> if (element.isInterface()) "interface" else "class"
            is KtClassOrObject -> "class"
            is KtNamedFunction -> "function"
            is KtProperty -> "property"
            is PsiClass -> if (element.isInterface) "interface" else "class"
            is PsiMethod -> "method"
            else -> element.javaClass.simpleName
        }
        return name to kind
    }

    private fun toImplementation(element: PsiElement, project: com.intellij.openapi.project.Project): Implementation {
        val containingFile = element.containingFile
        val vf = containingFile?.virtualFile
        val document = containingFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        // Anchor the reported position on the name identifier, not the element
        // start: a Kotlin declaration's textRange begins at its leading KDoc, so
        // element.textRange.startOffset would report the doc-comment line. Falls
        // back to the element for unnamed elements (e.g. anonymous objects).
        val anchor = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
        val offset = anchor.textRange?.startOffset
        // Report null (not 0) when there is no resolvable position — an accurate
        // "unknown" is less misleading to a caller than a fabricated line 0.
        val lineNum: Int?
        val col: Int?
        if (document != null && offset != null) {
            lineNum = document.getLineNumber(offset) + 1
            col = offset - document.getLineStartOffset(lineNum - 1) + 1
        } else {
            lineNum = null
            col = null
        }

        val name = (element as? PsiNamedElement)?.name ?: "<unnamed>"
        val containing = when (element) {
            is KtNamedDeclaration -> (element.parent?.parent as? KtClassOrObject)?.fqName?.asString()
                ?: (element.parent?.parent as? KtClassOrObject)?.name
            is PsiMethod -> element.containingClass?.qualifiedName ?: element.containingClass?.name
            is PsiClass -> element.qualifiedName?.substringBeforeLast('.')
            else -> null
        }
        val isAbstract = when (element) {
            is PsiMethod -> element.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)
            is PsiClass -> element.isInterface || element.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)
            is KtNamedFunction -> element.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)
            is KtClass -> element.isInterface() || element.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)
            else -> false
        }

        return Implementation(
            name = name,
            containingClass = containing,
            file = vf?.path ?: "<unknown>",
            line = lineNum,
            column = col,
            isAbstract = isAbstract
        )
    }

    private fun errorResult(message: String): String =
        gson.toJson(FindImplementationsResponse(success = false, error = message))
}
