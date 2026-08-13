package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.mercari.psi.mcp.PsiMcpServerManager

private val dumbModeGson = Gson()

/**
 * Shared user-facing message for the retriable dumb-mode (indexing) state. Used by
 * [dumbModeError] (the guarded tools) and FindSymbolsTool so the phrasing — and the
 * pointer to check-sync-status — stays identical across the surface.
 */
internal const val DUMB_MODE_MESSAGE =
    "Project is indexing (dumb mode); this operation is unavailable. " +
    "Retry once check-sync-status reports SMART_MODE."

/**
 * Shared user-facing message for the "no served project" state. [servedProject]
 * returns null for two distinct reasons — no project open, or several open with
 * none selected — so the message names both and points to the settings page the
 * human uses to resolve it (which also tells first-time users the page exists).
 * Mirrors [DUMB_MODE_MESSAGE] so the wording stays identical across the surface
 * instead of the former ~20 divergent "No open project" strings.
 */
internal const val NO_PROJECT_MESSAGE =
    "No project is being served: either no project is open, or several are open and none is " +
    "selected. Pick the intended project in Settings ▸ Tools ▸ PSI MCP Server."

/**
 * The project every tool resolves against: the one the human selected in
 * Settings ▸ Tools ▸ PSI MCP Server (or the sole open project as a default).
 *
 * Returns null when there is no unambiguous target — no project open, or
 * several open with no valid selection — matching the old
 * `openProjects.firstOrNull()` "no project" contract so existing
 * `?: throw` / `?: return error` call sites behave unchanged.
 */
internal fun servedProject(): Project? =
    PsiMcpServerManager.getInstance().selectedProject()

/**
 * Uniform dumb-mode guard for index-dependent tools. Index-backed resolution and
 * symbol search are unavailable while the project is indexing (dumb mode). Rather
 * than block the HTTP request until indexing finishes — which can take minutes on a
 * large project and exceed the MCP client timeout — return a retriable error
 * immediately so the agent retries once indexing completes.
 *
 * Returns the JSON to return straight from execute(), or null when it is safe to
 * proceed (smart mode, or no served project — the tool's own no-project handling
 * then runs).
 */
internal fun dumbModeError(project: Project?): String? {
    if (project == null || !DumbService.getInstance(project).isDumb) return null
    return retriableDumbJson()
}

/**
 * Mid-call companion to [dumbModeError]. The entry guard checks isDumb once; a
 * project can still enter dumb mode *after* it passes, while a read action is in
 * flight, so an index-backed call then throws [IndexNotReadyException]. Tools call
 * this at the top of their execute() catch: if [e] is (or wraps) that exception,
 * it returns the same retriable [DUMB_MODE_MESSAGE] JSON to hand straight back —
 * so the agent retries instead of seeing a non-retriable "Internal error". Returns
 * null for any other exception, leaving the tool's normal error handling to run.
 */
internal fun dumbModeErrorFor(e: Throwable): String? {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is IndexNotReadyException) return retriableDumbJson()
        cause = cause.cause
    }
    return null
}

private fun retriableDumbJson(): String =
    dumbModeGson.toJson(
        linkedMapOf(
            "success" to false,
            "retriable" to true,
            "error" to DUMB_MODE_MESSAGE
        )
    )

/**
 * Uniform "no served project" guard, sibling to [dumbModeError]. Returns the JSON
 * to return straight from execute() when there is no unambiguous served project,
 * or null when one is present (safe to proceed). Lets tools that would otherwise
 * throw from inside a read action surface [NO_PROJECT_MESSAGE] cleanly rather than
 * as a wrapped "Internal error".
 */
internal fun noProjectError(project: Project?): String? {
    if (project != null) return null
    return dumbModeGson.toJson(
        linkedMapOf(
            "success" to false,
            "error" to NO_PROJECT_MESSAGE
        )
    )
}

internal fun notIndexedError(filePath: String): String =
    "File is not indexed by Android Studio: $filePath. " +
    "Make sure the project is open in Android Studio. " +
    "Note: git worktrees and temporary file copies are not indexed — " +
    "either use the original project path or open the worktree directly in Android Studio."
