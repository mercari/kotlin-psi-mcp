package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

private val changelistGson = Gson()

/** Blocks (bounded) until the ChangeListManager has processed pending dirty scopes. Never call on the EDT. */
internal fun awaitChangeListUpdate(clm: ChangeListManager, timeoutSeconds: Long = 10) {
    val latch = java.util.concurrent.CountDownLatch(1)
    clm.invokeAfterUpdate(false) { latch.countDown() }
    latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
}

/** `{"success":false,"error":...}` — the uniform failure shape (see ARCHITECTURE.md). */
internal fun changelistError(message: String): String =
    changelistGson.toJson(linkedMapOf("success" to false, "error" to message))

internal data class ChangelistFile(val path: String, val status: String)

internal data class ChangelistInfo(
    val name: String,
    val comment: String,
    val isActive: Boolean,
    val fileCount: Int,
    val files: List<ChangelistFile>?
)

/**
 * Resolves the served project, or returns the ready-to-return JSON error. Changelist
 * tools never touch the index, so no dumb-mode guard is applied.
 */
internal inline fun withChangelistManager(
    block: (Project, ChangeListManager) -> String
): String {
    val project = servedProject()
    noProjectError(project)?.let { return it }
    project!!
    val clm = ChangeListManager.getInstance(project)
    // Refresh VFS-visible state so the lists reflect what is on disk. Must not run on the EDT.
    if (!ApplicationManager.getApplication().isDispatchThread) awaitChangeListUpdate(clm)
    return block(project, clm)
}

internal fun LocalChangeList.toInfo(project: Project, includeFiles: Boolean): ChangelistInfo {
    val files = changes.map { it.toFile(project) }.sortedBy { it.path }
    return ChangelistInfo(
        name = name,
        comment = comment.orEmpty(),
        isActive = isDefault,
        fileCount = files.size,
        files = if (includeFiles) files else null
    )
}

private fun Change.toFile(project: Project): ChangelistFile {
    val path = (afterRevision ?: beforeRevision)?.file?.path.orEmpty()
    return ChangelistFile(relativeToProject(project, path), type.name)
}

internal fun relativeToProject(project: Project, absolutePath: String): String {
    val base = project.basePath ?: return absolutePath
    return if (absolutePath.startsWith("$base/")) absolutePath.removePrefix("$base/") else absolutePath
}

internal fun resolveProjectPath(project: Project, path: String): String =
    if (File(path).isAbsolute) path else File(project.basePath.orEmpty(), path).path

internal fun findVirtualFile(project: Project, path: String): VirtualFile? =
    LocalFileSystem.getInstance().refreshAndFindFileByPath(resolveProjectPath(project, path).replace('\\', '/'))

internal fun JsonObject.stringArg(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.boolArg(key: String, default: Boolean = false): Boolean =
    get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: default

internal fun nameSchema(description: String): Map<String, Any> =
    mapOf("type" to "string", "description" to description)

internal fun boolSchema(description: String, default: Boolean): Map<String, Any> =
    mapOf("type" to "boolean", "description" to description, "default" to default)
