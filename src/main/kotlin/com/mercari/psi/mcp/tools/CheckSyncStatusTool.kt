package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mercari.psi.mcp.PsiMcpServerManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import java.io.File

/**
 * Readiness check for the project currently SERVED on this port.
 *
 * One IDE instance owns the fixed port at a time, and within it exactly one
 * project is served — the one the human selected in Settings ▸ Tools ▸ PSI MCP
 * Server (or the sole open project by default). Every other tool resolves against
 * that same [servedProject]; this tool reports the ground truth so an agent can
 * tell WHY resolution is failing before blaming a tool:
 *
 *  - projectMatch: is the served project actually the checkout you expect
 *    (root_project_path) — or is a different project selected / open?
 *  - state: is it done indexing (SMART_MODE) or still building indexes
 *    (DUMB_MODE, in which cross-module resolution cannot work)?
 *
 * When no single project is served (none open, or several open with none
 * selected), it says so and lists [availableProjects] so the human can pick one.
 */
class CheckSyncStatusTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(CheckSyncStatusTool::class.java)

    data class ProjectInfo(val name: String, val basePath: String?)

    data class CheckSyncResponse(
        val success: Boolean,
        val expectedRoot: String? = null,
        val projectName: String? = null,
        val projectBasePath: String? = null,
        val projectMatch: String? = null, // "MATCH" | "MISMATCH"
        val state: String? = null,        // "SMART_MODE" | "DUMB_MODE"
        val availableProjects: List<ProjectInfo>? = null,
        val message: String,
        val error: String? = null
    )

    override fun getDescription(): String =
        "Check whether the Android Studio project served on this port is the one you expect, and " +
        "whether it has finished indexing. Call this when a position-based tool unexpectedly returns " +
        "'could not resolve' or 'file not indexed'. Returns projectMatch (MATCH/MISMATCH of the served " +
        "project's root against root_project_path) and state (SMART_MODE = indexed and ready; " +
        "DUMB_MODE = still indexing, so index-backed resolution such as cross-module find-declaration " +
        "and find-usages will fail — wait and retry). MISMATCH, or 'no project served', means the wrong " +
        "project is selected — pick the intended one in Settings ▸ Tools ▸ PSI MCP Server (the served " +
        "project dropdown); availableProjects lists what is open to choose from. CAVEAT: SMART_MODE " +
        "reflects indexing state only, not freshness — files changed outside the IDE (e.g. git " +
        "checkout) may not be picked up; if you just did that, run \"Sync Gradle Project\" in the IDE " +
        "first."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "root_project_path" to mapOf(
                "type" to "string",
                "description" to "Absolute path of the project root you expect to be served on this port."
            )
        ),
        "required" to listOf("root_project_path")
    )

    override fun execute(arguments: JsonObject): String {
        return try {
            val rootPath = arguments.get("root_project_path")?.asString
                ?: return gson.toJson(
                    CheckSyncResponse(
                        success = false,
                        message = "Missing 'root_project_path' parameter",
                        error = "Missing 'root_project_path' parameter"
                    )
                )
            gson.toJson(checkStatus(rootPath))
        } catch (e: Exception) {
            logger.error("Error in CheckSyncStatusTool", e)
            gson.toJson(
                CheckSyncResponse(
                    success = false,
                    message = "Internal error while checking sync status",
                    error = e.message
                )
            )
        }
    }

    private fun checkStatus(rootPath: String): CheckSyncResponse {
        return ApplicationManager.getApplication().runReadAction<CheckSyncResponse> {
            val expectedCanon = canonical(rootPath)
            val open = PsiMcpServerManager.getInstance().openProjectsSnapshot()
            val available = open.map { ProjectInfo(it.name, it.basePath?.let(::canonical) ?: it.basePath) }

            val project = servedProject()
                ?: return@runReadAction CheckSyncResponse(
                    success = false,
                    expectedRoot = expectedCanon,
                    availableProjects = available,
                    message = when {
                        open.isEmpty() ->
                            "No project is open on this port. Open the project in Android Studio."
                        else ->
                            "No single project is selected to serve, but ${open.size} are open. " +
                            "Pick the one you want in Settings ▸ Tools ▸ PSI MCP Server (served project " +
                            "dropdown). Open projects: ${available.joinToString { it.name }}."
                    },
                    error = "No served project"
                )

            val basePath = project.basePath
            val baseCanon = basePath?.let { canonical(it) }
            val match = if (baseCanon != null && baseCanon == expectedCanon) "MATCH" else "MISMATCH"
            val state = if (DumbService.getInstance(project).isDumb) "DUMB_MODE" else "SMART_MODE"

            CheckSyncResponse(
                success = true,
                expectedRoot = expectedCanon,
                projectName = project.name,
                projectBasePath = baseCanon ?: basePath,
                projectMatch = match,
                state = state,
                availableProjects = available,
                message = buildMessage(match, state, project.name, baseCanon, expectedCanon)
            )
        }
    }

    private fun buildMessage(
        match: String,
        state: String,
        name: String,
        baseCanon: String?,
        expectedCanon: String
    ): String = when {
        match == "MISMATCH" ->
            "MISMATCH: this port serves project '$name' at ${baseCanon ?: "<unknown base path>"}, " +
            "not the expected root $expectedCanon. A different project is selected — pick the intended " +
            "one in Settings ▸ Tools ▸ PSI MCP Server, or (if it is open in another IDE) enable the " +
            "server there after disabling it here."
        state == "DUMB_MODE" ->
            "Project '$name' matches, but it is in DUMB_MODE (indexing / not ready). Index-backed " +
            "resolution (cross-module find-declaration, find-usages, etc.) will fail. Wait and retry."
        else ->
            "Project '$name' matches and is in SMART_MODE (indexed). Safe to run resolution tools. " +
            "CAVEAT: SMART_MODE means indexing FINISHED — it does NOT guarantee the IDE's view matches " +
            "disk. Changes made OUTSIDE the IDE (e.g. a git checkout / branch switch, pull, or edits " +
            "from another tool) may not have been picked up, so results can be stale. If you just " +
            "changed files outside the IDE, run \"Sync Gradle Project\" there first (or File ▸ Reload " +
            "All from Disk for source-only changes) before trusting resolution."
    }

    private fun canonical(path: String): String =
        try {
            File(path).canonicalPath
        } catch (e: Exception) {
            path
        }
}
