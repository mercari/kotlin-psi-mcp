package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import java.io.File

class FindImportSuggestionsTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(FindImportSuggestionsTool::class.java)

    data class ImportSuggestion(
        val fqn: String,
        val kind: String,              // class / function / property / field / method
        val sourceFile: String,
        val sameModule: Boolean = false
    )

    data class FindImportSuggestionsResponse(
        val success: Boolean,
        val shortName: String? = null,
        val alreadyResolved: Boolean = false,     // true if the ref at position is already resolved
        val count: Int = 0,
        val suggestions: List<ImportSuggestion> = emptyList(),
        val error: String? = null
    )

    override fun getDescription(): String =
        "Given a position (or explicit short name), return candidate fully-qualified names that " +
        "could be imported to resolve it. Searches Kotlin stub indexes + Java short-name cache in " +
        "project scope by default. Suggestions are ranked: same-module hits first, then alphabetical. " +
        "Use this to answer 'what should I pass as fqn to add-import?'. " +
        "If only short_name is provided, acts as a plain FQN lookup. " +
        "If file_path + line + column are provided, also reports whether the reference at that " +
        "position is already resolved (in which case no import is needed)."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Optional: path to the file containing the unresolved reference."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Optional: line of the reference (1-based). Requires file_path."
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Optional: column of the reference (1-based). Requires file_path."
            ),
            "short_name" to mapOf(
                "type" to "string",
                "description" to "Short name to look up (e.g. 'MutableStateFlow'). Required if file_path/line/column are not provided."
            ),
            "scope" to mapOf(
                "type" to "string",
                "description" to "Search scope: 'project' (default) or 'all' (includes library symbols).",
                "enum" to listOf("project", "all"),
                "default" to "project"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "Maximum number of suggestions to return. Default 20.",
                "default" to 20
            )
        ),
        "required" to listOf<String>(),
        // The real contract is an either/or that a flat `required` can't express:
        // supply `short_name`, OR (`file_path` + `line` + `column`) together. Both
        // groups at once is also valid (explicit name + position-based
        // already-resolved check), so this is `anyOf` (>=1 branch), NOT `oneOf`
        // (exactly one) — `oneOf` would reject the both-provided call the code
        // supports. The runtime guard in execute() still enforces this for clients
        // that ignore anyOf.
        "anyOf" to listOf(
            mapOf("required" to listOf("short_name")),
            mapOf("required" to listOf("file_path", "line", "column"))
        )
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
            val line = arguments.get("line")?.asInt
            val column = arguments.get("column")?.asInt
            val explicitShortName = arguments.get("short_name")?.asString
            val scopeKind = arguments.get("scope")?.asString ?: "project"
            val limit = arguments.get("limit")?.asInt ?: 20

            if (explicitShortName == null && (filePath == null || line == null || column == null)) {
                return errorResult(
                    "Provide either 'short_name' or ('file_path' + 'line' + 'column')"
                )
            }

            gson.toJson(suggest(filePath, line, column, explicitShortName, scopeKind, limit))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in FindImportSuggestionsTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun suggest(
        filePath: String?,
        line: Int?,
        column: Int?,
        explicitShortName: String?,
        scopeKind: String,
        limit: Int
    ): FindImportSuggestionsResponse {
        val project = servedProject()
            ?: return FindImportSuggestionsResponse(success = false, error = NO_PROJECT_MESSAGE)

        val scope = when (scopeKind) {
            "all" -> GlobalSearchScope.allScope(project)
            else -> GlobalSearchScope.projectScope(project)
        }

        return ApplicationManager.getApplication().runReadAction<FindImportSuggestionsResponse> {
            // Position-based: resolve short name + check if already resolved + get context file.
            var shortName: String? = explicitShortName
            var alreadyResolved = false
            var contextFile: KtFile? = null
            var contextModuleName: String? = null

            if (filePath != null && line != null && column != null) {
                val resolvedPath = resolveAbsolutePath(filePath)
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
                    ?: return@runReadAction FindImportSuggestionsResponse(
                        success = false, error = "File not found: $resolvedPath"
                    )
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@runReadAction FindImportSuggestionsResponse(
                        success = false, error = "Could not load PSI"
                    )
                contextFile = psiFile as? KtFile
                contextModuleName = com.intellij.openapi.module.ModuleUtilCore
                    .findModuleForFile(vf, project)?.name

                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runReadAction FindImportSuggestionsResponse(
                        success = false, error = "No document"
                    )
                val offset = try {
                    document.getLineStartOffset(line - 1) + (column - 1)
                } catch (e: Exception) {
                    return@runReadAction FindImportSuggestionsResponse(
                        success = false, error = "Invalid line/column"
                    )
                }
                val leaf = psiFile.findElementAt(offset)
                    ?: return@runReadAction FindImportSuggestionsResponse(
                        success = false, error = "No PSI at position"
                    )

                // Extract short name + check resolution.
                val refExpr = leaf.getParentOfType<KtReferenceExpression>(strict = false)
                if (refExpr != null) {
                    shortName = shortName ?: leaf.text.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
                        ?: refExpr.text.substringAfterLast('.')
                    val allRefs: Array<out PsiReference> = refExpr.references
                    alreadyResolved = allRefs.isNotEmpty() && allRefs.any { it.resolve() != null }
                } else {
                    shortName = shortName ?: leaf.text.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
                }
            }

            val finalShortName = shortName
                ?: return@runReadAction FindImportSuggestionsResponse(
                    success = false,
                    error = "Could not determine a short name at the given position. Pass 'short_name' explicitly."
                )

            if (alreadyResolved) {
                return@runReadAction FindImportSuggestionsResponse(
                    success = true,
                    shortName = finalShortName,
                    alreadyResolved = true,
                    count = 0
                )
            }

            val suggestions = collectSuggestions(project, finalShortName, scope, contextModuleName, limit)

            FindImportSuggestionsResponse(
                success = true,
                shortName = finalShortName,
                alreadyResolved = false,
                count = suggestions.size,
                suggestions = suggestions
            )
        }
    }

    private fun collectSuggestions(
        project: Project,
        name: String,
        scope: GlobalSearchScope,
        contextModuleName: String?,
        limit: Int
    ): List<ImportSuggestion> {
        val results = mutableListOf<ImportSuggestion>()
        val seenFqns = mutableSetOf<String>()

        // Kotlin classes
        try {
            for (ktClass in KotlinClassShortNameIndex.get(name, project, scope)) {
                val fqn = ktClass.fqName?.asString() ?: continue
                addUnique(results, seenFqns, ktClass, fqn, "class", contextModuleName, project)
                if (results.size >= limit) return results.sorted()
            }
        } catch (e: Exception) { logger.warn("KotlinClassShortNameIndex failed: ${e.message}") }

        // Kotlin top-level functions
        try {
            for (ktFn in KotlinFunctionShortNameIndex.get(name, project, scope)) {
                if (ktFn.parent !is KtFile) continue // only top-level importable functions
                val fqn = ktFn.fqName?.asString() ?: continue
                addUnique(results, seenFqns, ktFn, fqn, "function", contextModuleName, project)
                if (results.size >= limit) return results.sorted()
            }
        } catch (e: Exception) { logger.warn("KotlinFunctionShortNameIndex failed: ${e.message}") }

        // Kotlin top-level properties
        try {
            for (ktProp in KotlinPropertyShortNameIndex.get(name, project, scope)) {
                if (ktProp.parent !is KtFile) continue
                val fqn = ktProp.fqName?.asString() ?: continue
                addUnique(results, seenFqns, ktProp, fqn, "property", contextModuleName, project)
                if (results.size >= limit) return results.sorted()
            }
        } catch (e: Exception) { logger.warn("KotlinPropertyShortNameIndex failed: ${e.message}") }

        // Java classes
        try {
            val cache = PsiShortNamesCache.getInstance(project)
            for (psiClass in cache.getClassesByName(name, scope)) {
                val fqn = psiClass.qualifiedName ?: continue
                addUnique(results, seenFqns, psiClass, fqn, "class", contextModuleName, project)
                if (results.size >= limit) return results.sorted()
            }
        } catch (e: Exception) { logger.warn("Java class short-name search failed: ${e.message}") }

        return results.sorted()
    }

    private fun List<ImportSuggestion>.sorted(): List<ImportSuggestion> =
        sortedWith(compareByDescending<ImportSuggestion> { it.sameModule }.thenBy { it.fqn })

    private fun addUnique(
        results: MutableList<ImportSuggestion>,
        seenFqns: MutableSet<String>,
        element: PsiElement,
        fqn: String,
        kind: String,
        contextModuleName: String?,
        project: Project
    ) {
        if (!seenFqns.add(fqn)) return
        val vf = element.containingFile?.virtualFile
        val sourceFile = vf?.path ?: "<unknown>"
        val sameModule = if (contextModuleName != null && vf != null) {
            com.intellij.openapi.module.ModuleUtilCore
                .findModuleForFile(vf, project)?.name == contextModuleName
        } else false
        results.add(ImportSuggestion(fqn = fqn, kind = kind, sourceFile = sourceFile, sameModule = sameModule))
    }

    private fun errorResult(message: String): String =
        gson.toJson(FindImportSuggestionsResponse(success = false, error = message))
}
