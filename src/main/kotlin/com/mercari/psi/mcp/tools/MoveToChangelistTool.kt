package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.changes.Change

class MoveToChangelistTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(MoveToChangelistTool::class.java)

    private data class Response(
        val success: Boolean,
        val changelist: String,
        val moved: List<String>,
        val skipped: Map<String, String>
    )

    override fun getDescription(): String =
        "Move files into an IDE changelist. Files must already have uncommitted changes; unversioned files are only " +
        "handled when 'add_unversioned' is true (they are added to VCS tracking). Paths are project-relative or absolute. " +
        "Files that are unchanged or missing are reported in 'skipped' rather than failing the call."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "paths" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Files to move."
            ),
            "name" to nameSchema("Target changelist name."),
            "create_if_missing" to boolSchema("Create the changelist if it does not exist. Defaults to false.", false),
            "add_unversioned" to boolSchema("Add unversioned files to VCS and place them in the changelist. Defaults to false.", false)
        ),
        "required" to listOf("paths", "name")
    )

    override fun execute(arguments: JsonObject): String {
        return try {
            val name = arguments.stringArg("name") ?: return changelistError("Missing 'name'")
            val paths = arguments.getAsJsonArray("paths")?.map { it.asString }?.filter { it.isNotBlank() }
            if (paths.isNullOrEmpty()) return changelistError("Missing 'paths'")
            withChangelistManager { project, clm ->
                val target = clm.findChangeList(name)
                    ?: if (arguments.boolArg("create_if_missing")) clm.addChangeList(name, null)
                    else return@withChangelistManager changelistError("Changelist '$name' not found")

                val addUnversioned = arguments.boolArg("add_unversioned")
                val changes = LinkedHashMap<Change, String>()
                val skipped = LinkedHashMap<String, String>()
                val toAdd = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
                val dirtyScopes = com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project)
                val resolved = paths.map { it to findVirtualFile(project, it) }
                // The IDE may not have noticed new/edited files yet; mark them dirty and wait for the update.
                resolved.forEach { (_, vf) -> vf?.let { dirtyScopes.fileDirty(it) } }
                awaitChangeListUpdate(clm)
                for ((path, vf) in resolved) {
                    if (vf == null) { skipped[path] = "file not found"; continue }
                    val change = clm.getChange(vf)
                    when {
                        change != null -> changes[change] = path
                        clm.isUnversioned(vf) ->
                            if (addUnversioned) toAdd += vf else skipped[path] = "unversioned (pass add_unversioned=true)"
                        else -> skipped[path] = "no uncommitted changes"
                    }
                }

                if (changes.isNotEmpty()) clm.moveChangesTo(target, *changes.keys.toTypedArray())
                val added = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
                if (toAdd.isNotEmpty()) {
                    val vcsManager = com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance(project)
                    for (vf in toAdd) {
                        val env = vcsManager.getVcsFor(vf)?.checkinEnvironment
                        if (env == null) { skipped[relativeToProject(project, vf.path)] = "no VCS for file"; continue }
                        env.scheduleUnversionedFilesForAddition(listOf(vf))
                        com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(vf)
                        added += vf
                    }
                    awaitChangeListUpdate(clm)
                    val newChanges = added.mapNotNull { clm.getChange(it) }
                    if (newChanges.isNotEmpty()) clm.moveChangesTo(target, *newChanges.toTypedArray())
                }

                val moved = changes.values.toList() + added.map { relativeToProject(project, it.path) }
                gson.toJson(Response(true, name, moved, skipped))
            }
        } catch (e: Exception) {
            logger.error("Error in MoveToChangelistTool", e)
            changelistError("Internal error: ${e.message}")
        }
    }
}
