package com.mercari.psi.mcp

import com.mercari.psi.mcp.server.PsiHttpServer
import com.mercari.psi.mcp.tools.FindUsagesTool
import com.mercari.psi.mcp.tools.FindSymbolsTool
import com.mercari.psi.mcp.tools.GetContainingContextTool
import com.mercari.psi.mcp.tools.FindDeclarationTool
import com.mercari.psi.mcp.tools.ModuleSearchTool
import com.mercari.psi.mcp.tools.GetDiagnosticsTool
import com.mercari.psi.mcp.tools.GradleSyncTool
import com.mercari.psi.mcp.tools.ApplyQuickFixTool
import com.mercari.psi.mcp.tools.GetTypeInfoTool
import com.mercari.psi.mcp.tools.OrganizeImportsTool
import com.mercari.psi.mcp.tools.RenameTool
import com.mercari.psi.mcp.tools.SafeDeleteTool
import com.mercari.psi.mcp.tools.AddImportTool
import com.mercari.psi.mcp.tools.AddParameterTool
import com.mercari.psi.mcp.tools.MoveFileTool
import com.mercari.psi.mcp.tools.ExtractInterfaceTool
import com.mercari.psi.mcp.tools.FindImplementationsTool
import com.mercari.psi.mcp.tools.GetCallHierarchyTool
import com.mercari.psi.mcp.tools.GetKDocTool
import com.mercari.psi.mcp.tools.FindImportSuggestionsTool
import com.mercari.psi.mcp.tools.FormatCodeTool
import com.mercari.psi.mcp.tools.CheckSyncStatusTool
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.io.File

/**
 * Application-level owner of the (single) PSI MCP HTTP server.
 *
 * The server binds ONE fixed port ([PORT]) per machine — the successful bind
 * is the machine-wide mutex, so exactly one IDE process serves at a time. The
 * human controls it from Settings ▸ Tools ▸ PSI MCP Server:
 *
 *  - [enabled]              — master on/off switch for this IDE instance.
 *  - [selectedProjectPath]  — which open project tools resolve against.
 *
 * When a second IDE instance is enabled while another already holds the port,
 * [ensureStarted] fails to bind and records [bindError]; the settings page shows
 * that error and the human disables the server in the owning IDE before enabling
 * it here. There is no automatic handoff and no polling — a deliberate choice to
 * keep the model simple and legible.
 *
 * Every tool resolves its target project via [selectedProject] (exposed to tools
 * through `ToolUtils.servedProject()`), replacing the old
 * `openProjects.firstOrNull()` guess that silently hit whichever project opened
 * first.
 */
@Service(Service.Level.APP)
@State(
    name = "PsiMcpServerManager",
    storages = [Storage("psi-mcp-server.xml")]
)
class PsiMcpServerManager :
    PersistentStateComponent<PsiMcpServerManager.State>,
    Disposable {

    private val logger = Logger.getInstance(PsiMcpServerManager::class.java)

    data class State(
        var enabled: Boolean = true,
        // Canonical basePath of the project the human picked in the dropdown.
        // null = no explicit pick yet (resolver falls back to the sole open project).
        var selectedProjectPath: String? = null
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    // ---- Runtime (not persisted) ----
    private var httpServer: PsiHttpServer? = null

    @Volatile var isServing: Boolean = false
        private set

    // Human-readable reason the last start attempt failed (e.g. port already
    // held by another IDE). null when serving or cleanly stopped.
    @Volatile var bindError: String? = null
        private set

    val isEnabled: Boolean get() = myState.enabled

    // ---- Server lifecycle ----

    /**
     * Idempotently start the server if [enabled] and not already serving. On a
     * bind failure (another IDE owns [PORT]) records [bindError] instead of
     * throwing, so the settings UI can surface it.
     */
    @Synchronized
    fun ensureStarted() {
        if (!myState.enabled) return
        if (isServing) return

        val server = PsiHttpServer(PORT)
        registerAllTools(server)
        try {
            server.start()
            httpServer = server
            isServing = true
            bindError = null
            logger.info("PSI MCP HTTP server started on port $PORT")
        } catch (e: Exception) {
            // Most commonly a BindException — the port is held by another IDE.
            // Stop the half-started Jetty Server so its thread pool doesn't leak
            // across repeated failed (re)connect attempts.
            try {
                server.stop()
            } catch (stopEx: Exception) {
                logger.warn("Error cleaning up half-started PSI MCP HTTP server", stopEx)
            }
            httpServer = null
            isServing = false
            bindError = describeBindFailure(e)
            logger.warn("PSI MCP HTTP server failed to bind port $PORT: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            logger.warn("Error stopping PSI MCP HTTP server", e)
        }
        httpServer = null
        isServing = false
        bindError = null
        logger.info("PSI MCP HTTP server stopped")
    }

    // The selection state (myState.enabled / selectedProjectPath) is WRITTEN on the
    // EDT (settings Apply / Reconnect) but READ on Jetty HTTP worker threads (every
    // tool call goes through selectedProject()). The accessors below are @Synchronized
    // so an in-flight tool call can't observe a stale/half-updated selection right
    // after the user changes it. ensureStarted()/stop() share this same monitor and
    // are reentrant, so setEnabled()/reconnect() calling into them is safe.

    /** Flip the master switch and apply it live (bind or free the port). */
    @Synchronized
    fun setEnabled(value: Boolean) {
        myState.enabled = value
        if (value) ensureStarted() else stop()
    }

    /**
     * "Serve here now": enable, drop any bind we already hold, and re-attempt.
     * Lets the user take over the port after another IDE released it without the
     * disable→enable dance. Records [bindError] again if the port is still held.
     */
    @Synchronized
    fun reconnect() {
        myState.enabled = true
        stop()
        ensureStarted()
    }

    /**
     * Called from the per-project startup activity. The first open project in
     * this JVM triggers the bind; later opens are no-ops for the server.
     */
    fun onProjectOpened() {
        ensureStarted()
    }

    // ---- Project selection ----

    /**
     * The project tools should resolve against, or null when there is no
     * unambiguous target:
     *  - explicit pick that is still open  → that project
     *  - no pick (or pick no longer open) but exactly one project open → that one
     *  - otherwise (none open, or several open with no valid pick) → null
     */
    @Synchronized
    fun selectedProject(): Project? {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        val wanted = myState.selectedProjectPath
        if (wanted != null) {
            open.firstOrNull { canonical(it.basePath) == canonical(wanted) }?.let { return it }
            // Picked project is no longer open — fall through to the single-open default.
        }
        return open.singleOrNull()
    }

    /** Persist the human's dropdown choice (in-memory repoint; no rebind needed). */
    @Synchronized
    fun setSelectedProject(project: Project) {
        myState.selectedProjectPath = project.basePath?.let { canonical(it) }
    }

    /** Snapshot of open projects for the settings dropdown. */
    fun openProjectsSnapshot(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.toList()

    override fun dispose() {
        stop()
    }

    // ---- Internals ----

    private fun registerAllTools(server: PsiHttpServer) {
        server.registerTool("find-usages", FindUsagesTool())
        server.registerTool("find-symbols", FindSymbolsTool())
        server.registerTool("get-containing-context", GetContainingContextTool())
        server.registerTool("find-declaration", FindDeclarationTool())
        server.registerTool("module-search", ModuleSearchTool())
        server.registerTool("get-diagnostics", GetDiagnosticsTool())
        server.registerTool("gradle-sync", GradleSyncTool())
        server.registerTool("apply-quick-fix", ApplyQuickFixTool())
        server.registerTool("get-type-info", GetTypeInfoTool())
        server.registerTool("organize-imports", OrganizeImportsTool())
        server.registerTool("rename", RenameTool())
        server.registerTool("safe-delete", SafeDeleteTool())
        server.registerTool("add-import", AddImportTool())
        server.registerTool("add-parameter", AddParameterTool())
        server.registerTool("move-file", MoveFileTool())
        server.registerTool("extract-interface", ExtractInterfaceTool())
        server.registerTool("find-implementations", FindImplementationsTool())
        server.registerTool("get-call-hierarchy", GetCallHierarchyTool())
        server.registerTool("get-kdoc", GetKDocTool())
        server.registerTool("find-import-suggestions", FindImportSuggestionsTool())
        server.registerTool("format-code", FormatCodeTool())
        server.registerTool("check-sync-status", CheckSyncStatusTool())
    }

    private fun describeBindFailure(e: Exception): String =
        "Port $PORT is held by another IDE instance (or another process). " +
        "Disable the PSI MCP server in the IDE that currently owns it, then enable it here. " +
        "(${e.message})"

    private fun canonical(path: String?): String? =
        path?.let {
            try {
                File(it).canonicalPath
            } catch (e: Exception) {
                it
            }
        }

    companion object {
        const val PORT = 51234

        fun getInstance(): PsiMcpServerManager =
            ApplicationManager.getApplication().getService(PsiMcpServerManager::class.java)
    }
}
