package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.util.Processor
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.idea.stubindex.*

class FindSymbolsTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(FindSymbolsTool::class.java)
    
    data class SymbolResult(
        val name: String,
        val type: String, // "class", "function", "property", "field", "method"
        val file: String,
        val line: Int,
        val packageName: String?,
        val signature: String?
    )
    
    data class FindSymbolsResponse(
        val success: Boolean,
        val symbols: List<SymbolResult>,
        val count: Int,
        val timestamp: Long,
        val error: String? = null,
        val retriable: Boolean = false // true when unavailable due to indexing (dumb mode) — retry later
    )

    override fun getDescription(): String =
        "Find symbols (classes, functions, properties) by name in the Kotlin/Java codebase. " +
        "Matching is fuzzy and case-insensitive (IntelliJ camel-hump — e.g. \"iMR\" matches " +
        "\"InMemoryRepository\", \"Greet\" matches \"Greeter\"), ranked best-match first. Returns " +
        "success with an empty list when nothing matches; returns success=false with retriable=true " +
        "if the project is still indexing (dumb mode)."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "symbol_name" to mapOf(
                "type" to "string",
                "description" to "Name or partial name to search for. Fuzzy, case-insensitive camel-hump matching (e.g. \"iMR\" -> \"InMemoryRepository\")."
            ),
            "symbol_type" to mapOf(
                "type" to "string",
                "description" to "Type of symbol: 'class', 'function', 'property', 'all'",
                "enum" to listOf("class", "function", "property", "all"),
                "default" to "all"
            ),
            "scope" to mapOf(
                "type" to "string",
                "description" to "Search scope: 'project' (sources only, excludes library jars and Gradle caches), 'all' (includes libraries)",
                "enum" to listOf("project", "all"),
                "default" to "project"
            ),
            "limit" to mapOf(
                "type" to "integer",
                "description" to "Maximum number of results to return",
                "default" to 50
            )
        ),
        "required" to listOf("symbol_name")
    )

    override fun execute(arguments: JsonObject): String {
        return try {
            val symbolName = arguments.get("symbol_name")?.asString
                ?: return createErrorResult("Missing 'symbol_name' parameter")

            val symbolType = arguments.get("symbol_type")?.asString ?: "all"
            val scope = arguments.get("scope")?.asString ?: "project"
            val limit = arguments.get("limit")?.asInt ?: 50

            gson.toJson(findSymbols(symbolName, symbolType, scope, limit))
        } catch (e: Exception) {
            logger.error("Error in FindSymbolsTool", e)
            createErrorResult("Internal error: ${e.message}")
        }
    }

    private fun findSymbols(pattern: String, symbolType: String, scopeKind: String, limit: Int): FindSymbolsResponse {
        val project = servedProject()
            ?: return errorResponse(NO_PROJECT_MESSAGE)

        // Symbol search reads the stub/short-name indexes, which are unavailable while indexing.
        // Report that as an explicit, retriable "not searchable" state — NOT a legit empty result.
        if (DumbService.getInstance(project).isDumb) {
            return FindSymbolsResponse(
                success = false,
                symbols = emptyList(),
                count = 0,
                timestamp = System.currentTimeMillis(),
                error = DUMB_MODE_MESSAGE,
                retriable = true
            )
        }

        return ApplicationManager.getApplication().runReadAction<FindSymbolsResponse> {
            val results = mutableListOf<SymbolResult>()
            // Dedup key = (source file path, name-identifier offset) of the UNWRAPPED declaration.
            // A Kotlin symbol is surfaced twice — once as native Kotlin PSI by the Kotlin stub
            // indexes, and once as a Java light element (KtLightClass/Method/Field) by
            // PsiShortNamesCache. Both unwrap to the same source declaration, so this collapses the
            // pair while keeping genuinely distinct symbols (e.g. overloads at different offsets).
            val seen = HashSet<String>()
            val scope = when (scopeKind) {
                "all" -> GlobalSearchScope.allScope(project)
                else -> GlobalSearchScope.projectScope(project)
            }

            fun add(element: PsiElement, type: String) {
                if (results.size >= limit) return
                createSymbolResult(element, type, seen)?.let { results.add(it) }
            }

            try {
                // Fuzzy, case-insensitive camel-hump matcher — the same one IntelliJ's Symbols
                // search uses. The leading "*" makes it match ANYWHERE (interior camel-hump), not
                // just from the name start — e.g. "counter" -> "mutateCounter", "load" ->
                // "useOverloads" — exactly like the IDE (which builds the matcher over "*" + pattern
                // in DefaultChooseByNameItemProvider.buildFullPattern). Start matches are preferred
                // for ranking via matchingDegree.
                val matcherBuilder = NameUtil.buildMatcher("*" + pattern)
                    .withCaseSensitivity(NameUtil.MatchingCaseSensitivity.NONE)
                matcherBuilder.preferringStartMatches()
                val matcher = matcherBuilder.build()

                val cache = PsiShortNamesCache.getInstance(project)
                val wantClass = symbolType == "all" || symbolType == "class"
                val wantFunc = symbolType == "all" || symbolType == "function"
                val wantProp = symbolType == "all" || symbolType == "property"

                // Enumerate candidate names (the composite cache includes Kotlin via
                // KotlinShortNamesCache) and keep only matcher hits. This is what the IDE does:
                // there is no index-side matcher push-down — filter names cheaply, resolve only hits.
                val names = HashSet<String>()
                val collect = Processor<String> { name -> if (matcher.matches(name)) names.add(name); true }
                if (wantClass) cache.processAllClassNames(collect, scope, null)
                if (wantFunc) cache.processAllMethodNames(collect, scope, null)
                if (wantProp) cache.processAllFieldNames(collect, scope, null)

                // Rank best-match first (matchingDegree encodes start-match / camel-hump quality),
                // so the `limit` keeps the most relevant symbols. matchingDegree is computed ONCE
                // per name here — a plain sortedByDescending { matchingDegree(it) } re-invokes it on
                // every comparison (O(n log n) calls), which is seconds of wasted work for broad
                // patterns that match a large name set on a big project.
                val ranked = names
                    .map { it to matcher.matchingDegree(it) }
                    .sortedByDescending { it.second }
                    .map { it.first }

                for (name in ranked) {
                    if (results.size >= limit) break
                    // Kotlin stub indexes first, so the kept row keeps the accurate Kotlin label
                    // (function/property, not the light-view method/field).
                    if (wantClass) {
                        KotlinClassShortNameIndex.get(name, project, scope).forEach { add(it, "class") }
                        cache.getClassesByName(name, scope).forEach { add(it, "class") }
                    }
                    if (wantFunc) {
                        KotlinFunctionShortNameIndex.get(name, project, scope).forEach { add(it, "function") }
                        cache.getMethodsByName(name, scope).forEach { add(it, "method") }
                    }
                    if (wantProp) {
                        KotlinPropertyShortNameIndex.get(name, project, scope).forEach { add(it, "property") }
                        cache.getFieldsByName(name, scope).forEach { add(it, "field") }
                    }
                }
            } catch (e: IndexNotReadyException) {
                // Raced into dumb mode after the isDumb() check — report as retriable, not empty.
                return@runReadAction FindSymbolsResponse(
                    success = false,
                    symbols = emptyList(),
                    count = 0,
                    timestamp = System.currentTimeMillis(),
                    error = DUMB_MODE_MESSAGE,
                    retriable = true
                )
            } catch (e: Exception) {
                logger.warn("Error searching for symbols", e)
                return@runReadAction errorResponse("Internal error: ${e.message}")
            }

            FindSymbolsResponse(
                success = true,
                symbols = results,
                count = results.size,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun errorResponse(message: String): FindSymbolsResponse = FindSymbolsResponse(
        success = false,
        symbols = emptyList(),
        count = 0,
        timestamp = System.currentTimeMillis(),
        error = message
    )
    
    private fun createSymbolResult(rawElement: PsiElement, type: String, seen: HashSet<String>): SymbolResult? {
        return try {
            // Skip synthetic Kotlin file-facade classes (the `FooKt` JVM class that holds a file's
            // top-level members) — they aren't user-authored declarations, just noise.
            if (rawElement is KtLightClassForFacade) return null

            // Collapse a Java light element (KtLightClass/Method/Field) to its source Kotlin
            // declaration; a native element unwraps to itself. This is the canonical unwrap used by
            // the Kotlin plugin's own find-usages / compiler-ref handlers.
            val element = rawElement.namedUnwrappedElement ?: rawElement

            val containingFile = element.containingFile
            val virtualFile = containingFile?.virtualFile
            val document = containingFile?.let {
                com.intellij.psi.PsiDocumentManager.getInstance(element.project).getDocument(it)
            }

            // FIXED: Better null safety and error handling
            if (virtualFile == null || document == null) {
                return null
            }

            // Anchor on the name identifier, not the element start — the element's text range
            // includes leading KDoc/annotations, which would report the doc-comment line instead of
            // the declaration line.
            val anchor = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
            val textRange = anchor.textRange
            if (textRange == null) {
                logger.warn("Element has no text range: ${element.javaClass.simpleName}")
                return null
            }

            // Dedup: skip if this source declaration (by file + name-identifier offset) is already in.
            if (!seen.add(virtualFile.path + "#" + textRange.startOffset)) {
                return null
            }

            val line = document.getLineNumber(textRange.startOffset) + 1 // Convert to 1-based line numbers

            val packageName = when (element) {
                is KtNamedDeclaration -> element.fqName?.parent()?.asString()
                is PsiClass -> element.qualifiedName?.substringBeforeLast('.')
                is PsiMethod -> element.containingClass?.qualifiedName?.substringBeforeLast('.')
                is PsiField -> element.containingClass?.qualifiedName?.substringBeforeLast('.')
                else -> null
            }
            
            val signature = when (element) {
                is KtFunction -> "${element.name}(${element.valueParameters.joinToString(", ") { "${it.name}: ${it.typeReference?.text ?: "?"}" }})"
                is PsiMethod -> "${element.name}(${element.parameterList.parameters.joinToString(", ") { "${it.name}: ${it.type.presentableText}" }})"
                is KtProperty -> "${element.name}: ${element.typeReference?.text ?: "?"}"
                is PsiField -> "${element.name}: ${element.type.presentableText}"
                else -> element.text?.take(100) // Fallback to truncated text
            }
            
            SymbolResult(
                name = when (element) {
                    is KtNamedDeclaration -> element.name ?: "unknown"
                    is PsiClass -> element.name ?: "unknown"
                    is PsiMethod -> element.name
                    is PsiField -> element.name
                    else -> element.text?.take(50) ?: "unknown"
                },
                type = type,
                file = virtualFile.path,
                line = line,
                packageName = packageName,
                signature = signature
            )
        } catch (e: Exception) {
            logger.warn("Error creating symbol result for ${rawElement.javaClass.simpleName}: ${e.message}")
            null
        }
    }
    
    private fun createErrorResult(message: String): String {
        val errorResponse = FindSymbolsResponse(
            success = false,
            symbols = emptyList(),
            count = 0,
            timestamp = System.currentTimeMillis(),
            error = message
        )
        
        return gson.toJson(errorResponse)
    }
}