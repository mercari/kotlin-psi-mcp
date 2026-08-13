package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.facet.FacetManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.codeStyle.NameUtil
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverUtil

/**
 * Structural module info. Names are the canonical Gradle identity path (e.g. ":library:ds:ds4:component")
 * — the same string you write in `project("...")`. No inferred "type", no heuristic name cleaning.
 */
data class ModuleInfo(
    val name: String,
    val dependencies: List<ModuleDependency>,
    val dependents: List<String>,
    val contentRoots: List<String>,
    val sourceRoots: List<String>,
    val testRoots: List<String>,
    val excludeRoots: List<String>,
    val facets: List<String>
)

data class ModuleDependency(
    val name: String,   // target module identity, or library name
    val type: String,   // "MODULE" | "LIBRARY" — the actual dependency kind, not a guess
    val scope: String   // DependencyScope: "COMPILE" | "TEST" | "RUNTIME" | "PROVIDED"
)

class ModuleSearchTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(ModuleSearchTool::class.java)

    data class ModuleSearchResponse(
        val success: Boolean,
        val query: String,
        val matches: List<ModuleInfo>,
        val totalMatches: Int,
        val page: Int,
        val pageSize: Int,
        val hasMore: Boolean,
        val timestamp: Long,
        val error: String? = null
    )

    override fun getDescription(): String =
        "🔍 MODULE SEARCH: Find modules by fuzzy/partial name (IDE camel-hump matching, same as " +
        "find-symbols). Each match carries its DIRECT module dependencies and — the expensive-to-grep " +
        "half — its DIRECT dependents (which modules depend on it), plus content/source roots. Names are " +
        "the Gradle identity path (e.g. ':library:ds:ds4:component'). Edges are direct only (depth 1), not transitive."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "query" to mapOf(
                "type" to "string",
                "description" to "Fuzzy search query for module names (e.g., 'auth', 'user', 'service')"
            ),
            "include_dependencies" to mapOf(
                "type" to "boolean",
                "description" to "Include direct module dependencies and dependents",
                "default" to true
            ),
            "include_content_roots" to mapOf(
                "type" to "boolean",
                "description" to "Include content roots (source, test directories)",
                "default" to false
            ),
            "modules_only" to mapOf(
                "type" to "boolean",
                "description" to "Show only module-to-module dependencies, skip libraries/JDK deps",
                "default" to true
            ),
            "page" to mapOf(
                "type" to "integer",
                "description" to "Page number for pagination (0-based)",
                "minimum" to 0,
                "default" to 0
            ),
            "page_size" to mapOf(
                "type" to "integer",
                "description" to "Number of results per page",
                "minimum" to 1,
                "maximum" to 200,
                "default" to 100
            ),
            "case_sensitive" to mapOf(
                "type" to "boolean",
                "description" to "Enable case sensitive search",
                "default" to false
            )
        ),
        "required" to listOf("query")
    )

    override fun execute(arguments: JsonObject): String {
        noProjectError(servedProject())?.let { return it }
        return try {
            val query = arguments.get("query")?.asString
                ?: throw IllegalArgumentException("Query parameter is required")
            val includeDependencies = arguments.get("include_dependencies")?.asBoolean ?: true
            val includeContentRoots = arguments.get("include_content_roots")?.asBoolean ?: false
            val modulesOnly = arguments.get("modules_only")?.asBoolean ?: true
            val page = arguments.get("page")?.asInt ?: 0
            val pageSize = arguments.get("page_size")?.asInt ?: 100
            val caseSensitive = arguments.get("case_sensitive")?.asBoolean ?: false

            val result = ApplicationManager.getApplication().runReadAction<ModuleSearchResponse> {
                searchModules(query, includeDependencies, includeContentRoots,
                    modulesOnly, page, pageSize, caseSensitive)
            }
            gson.toJson(result)

        } catch (e: Exception) {
            logger.error("Error in ModuleSearchTool", e)
            createErrorResult("Internal error: ${e.message}")
        }
    }

    private fun searchModules(
        query: String,
        includeDependencies: Boolean,
        includeContentRoots: Boolean,
        modulesOnly: Boolean,
        page: Int,
        pageSize: Int,
        caseSensitive: Boolean
    ): ModuleSearchResponse {
        val project = servedProject()
            ?: throw IllegalStateException(NO_PROJECT_MESSAGE)

        val allModules = ModuleManager.getInstance(project).modules

        // Structural identity: holder and its Gradle source-set submodules (.main/.test/flavors)
        // collapse to the same identity path. No name heuristics.
        val identityOf: Map<Module, String> = allModules.associateWith { gradleIdentity(it) }
        val groups: Map<String, List<Module>> = allModules.groupBy { identityOf.getValue(it) }

        // IDE-parity camel-hump matcher (same construction as FindSymbolsTool): leading "*" so
        // interior humps match; matchingDegree ranks best-first.
        val sensitivity = if (caseSensitive) NameUtil.MatchingCaseSensitivity.ALL
                          else NameUtil.MatchingCaseSensitivity.NONE
        val matcherBuilder = NameUtil.buildMatcher("*" + query).withCaseSensitivity(sensitivity)
        matcherBuilder.preferringStartMatches()
        val matcher = matcherBuilder.build()

        // matchingDegree computed ONCE per identity — a plain sortedByDescending { matchingDegree(it) }
        // re-invokes it on every comparison (O(n log n) calls). Same fix/rationale as FindSymbolsTool;
        // negligible here (module count is small) but kept consistent. Stable sort preserves tie order.
        val matchedIdentities = groups.keys
            .filter { matcher.matches(it) }
            .map { it to matcher.matchingDegree(it) }
            .sortedByDescending { it.second }
            .map { it.first }

        val totalMatches = matchedIdentities.size
        val offset = page * pageSize
        val paginated = matchedIdentities.drop(offset).take(pageSize)
        val hasMore = offset + pageSize < totalMatches

        // Direct dependency graph keyed by identity (built once), only if a match will report deps.
        val graph = if (includeDependencies && paginated.isNotEmpty()) {
            buildGraph(allModules, identityOf)
        } else {
            Graph(emptyMap(), emptyMap())
        }

        val moduleInfos = paginated.map { identity ->
            createModuleInfo(identity, groups.getValue(identity), graph,
                includeDependencies, includeContentRoots, modulesOnly)
        }

        return ModuleSearchResponse(
            success = true,
            query = query,
            matches = moduleInfos,
            totalMatches = totalMatches,
            page = page,
            pageSize = pageSize,
            hasMore = hasMore,
            timestamp = System.currentTimeMillis()
        )
    }

    // ---- Dependency graph (identity-keyed, direct edges only) ----

    private data class Graph(
        /** identity -> (dependency identity -> merged scope) */
        val forward: Map<String, Map<String, DependencyScope>>,
        /** identity -> set of identities that depend on it */
        val reverse: Map<String, Set<String>>
    )

    /**
     * Read every module's order entries and re-key both endpoints to their Gradle identity, so the
     * `project(...)` edges that live on `.main`/`.test` source-set submodules are attributed to the
     * holder. Drops self-edges (a holder's own main<->test links). Direct edges only.
     */
    private fun buildGraph(allModules: Array<Module>, identityOf: Map<Module, String>): Graph {
        val forward = HashMap<String, HashMap<String, DependencyScope>>()

        for (m in allModules) {
            val from = identityOf.getValue(m)
            val rootManager = ModuleRootManager.getInstance(m)
            val fromIsTestSourceSet = isTestSourceSet(rootManager)
            for (entry in rootManager.orderEntries) {
                if (entry is ModuleOrderEntry) {
                    val target = entry.module ?: continue      // skip unresolved
                    val to = identityOf[target] ?: continue
                    if (to == from) continue                   // internal source-set link
                    val edgeScope = edgeScopeFor(fromIsTestSourceSet, entry.scope)
                    val inner = forward.getOrPut(from) { HashMap() }
                    val existing = inner[to]
                    // coveringUseCasesOf is DependencyScope's own merge: a dep reached from both
                    // main (COMPILE) and test (TEST) collapses to COMPILE; TEST survives only when
                    // every origin is a test source set.
                    inner[to] = if (existing == null) edgeScope
                                else DependencyScope.coveringUseCasesOf(existing, edgeScope)
                }
            }
        }

        val reverse = HashMap<String, HashSet<String>>()
        forward.forEach { (from, deps) ->
            deps.keys.forEach { to -> reverse.getOrPut(to) { HashSet() }.add(from) }
        }

        return Graph(forward, reverse)
    }

    /**
     * The dependency scope of an edge, derived from the SOURCE SET it leaves — not from
     * [ModuleOrderEntry.getScope] directly. `entry.scope` reflects the dep's scope within its own
     * source-set module's classpath, so a test-source-set edge usually reads `COMPILE` there —
     * verified on BOTH a pure `kotlin("jvm")` project and Android/AGP (its `androidTest` /
     * `testFixtures` edges also come back `COMPILE`; only some, e.g. roborazzi, happen to surface
     * `TEST`). So `entry.scope` is an unreliable "is this test-only" signal. The reliable one is the
     * source set: an edge leaving a test source set (per IntelliJ's own [isTestSourceSet], which
     * counts `androidTest` and `testFixtures` as test) is a test-only dependency of the holder →
     * TEST. Otherwise trust the entry's scope (preserves RUNTIME / PROVIDED for
     * `runtimeOnly` / `compileOnly`).
     */
    private fun edgeScopeFor(fromTestSourceSet: Boolean, entryScope: DependencyScope): DependencyScope =
        if (fromTestSourceSet) DependencyScope.TEST else entryScope

    /**
     * A source-set module whose source folders are ALL test roots. Holder modules (no source
     * folders — those live on the source sets) and main source sets return false.
     */
    private fun isTestSourceSet(rootManager: ModuleRootManager): Boolean {
        val sourceFolders = rootManager.contentEntries.flatMap { it.sourceFolders.toList() }
        return sourceFolders.isNotEmpty() && sourceFolders.all { it.isTestSource }
    }

    private fun createModuleInfo(
        identity: String,
        group: List<Module>,
        graph: Graph,
        includeDependencies: Boolean,
        includeContentRoots: Boolean,
        modulesOnly: Boolean
    ): ModuleInfo {
        val dependencies = if (includeDependencies) {
            val moduleDeps = graph.forward[identity].orEmpty().entries
                .map { (dep, scope) -> ModuleDependency(dep, "MODULE", scope.name) }
                .sortedBy { it.name }
            val libraryDeps = if (!modulesOnly) collectLibraryDeps(group) else emptyList()
            moduleDeps + libraryDeps
        } else {
            emptyList()
        }

        val dependents = if (includeDependencies) {
            graph.reverse[identity].orEmpty().sorted()
        } else {
            emptyList()
        }

        val contentRoots = linkedSetOf<String>()
        val sourceRoots = linkedSetOf<String>()
        val testRoots = linkedSetOf<String>()
        val excludeRoots = linkedSetOf<String>()
        if (includeContentRoots) {
            for (m in group) {
                for (ce in ModuleRootManager.getInstance(m).contentEntries) {
                    contentRoots.add(ce.url)
                    for (sf in ce.sourceFolders) {
                        (if (sf.isTestSource) testRoots else sourceRoots).add(sf.url)
                    }
                    excludeRoots.addAll(ce.excludeFolderUrls)
                }
            }
        }

        val facets = group.flatMap { m ->
            try {
                FacetManager.getInstance(m).allFacets.map { it.name }
            } catch (e: Exception) {
                emptyList()
            }
        }.distinct()

        return ModuleInfo(
            name = identity,
            dependencies = dependencies,
            dependents = dependents,
            contentRoots = contentRoots.toList(),
            sourceRoots = sourceRoots.toList(),
            testRoots = testRoots.toList(),
            excludeRoots = excludeRoots.toList(),
            facets = facets
        )
    }

    private fun collectLibraryDeps(group: List<Module>): List<ModuleDependency> {
        val seen = HashSet<String>()
        val deps = mutableListOf<ModuleDependency>()
        for (m in group) {
            val rootManager = ModuleRootManager.getInstance(m)
            val mIsTestSourceSet = isTestSourceSet(rootManager)
            for (entry in rootManager.orderEntries) {
                if (entry is LibraryOrderEntry) {
                    val name = entry.libraryName ?: entry.presentableName
                    if (seen.add(name)) {
                        val scope = edgeScopeFor(mIsTestSourceSet, entry.scope)
                        deps.add(ModuleDependency(name, "LIBRARY", scope.name))
                    }
                }
            }
        }
        return deps
    }

    /** Canonical Gradle identity path (holder-collapsed). Falls back to the IntelliJ module name. */
    private fun gradleIdentity(module: Module): String =
        try {
            GradleProjectResolverUtil.getGradleIdentityPathOrNull(module)
        } catch (e: Throwable) {
            null
        } ?: module.name

    private fun createErrorResult(message: String): String {
        val errorResponse = ModuleSearchResponse(
            success = false,
            query = "",
            matches = emptyList(),
            totalMatches = 0,
            page = 0,
            pageSize = 0,
            hasMore = false,
            timestamp = System.currentTimeMillis(),
            error = message
        )
        return gson.toJson(errorResponse)
    }
}
