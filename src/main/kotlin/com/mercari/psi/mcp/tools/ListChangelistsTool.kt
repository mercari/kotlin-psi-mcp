package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

class ListChangelistsTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(ListChangelistsTool::class.java)

    private data class Response(
        val success: Boolean,
        val changelists: List<ChangelistInfo>,
        val unversionedFiles: List<String>?
    )

    override fun getDescription(): String =
        "List the IDE changelists (Version Control ▸ Changelists) with their active flag and the files each one holds. " +
        "Use this to see which uncommitted changes belong to which task before editing or committing."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "include_files" to boolSchema("Include the file list of each changelist. Defaults to true.", true),
            "include_unversioned" to boolSchema("Also list unversioned (untracked) files. Defaults to false.", false)
        )
    )

    override fun execute(arguments: JsonObject): String = try {
        withChangelistManager { project, clm ->
            val includeFiles = arguments.boolArg("include_files", true)
            val unversioned = if (arguments.boolArg("include_unversioned")) {
                clm.unversionedFilesPaths.map { relativeToProject(project, it.path) }.sorted()
            } else null
            gson.toJson(
                Response(true, clm.changeLists.map { it.toInfo(project, includeFiles) }, unversioned)
            )
        }
    } catch (e: Exception) {
        logger.error("Error in ListChangelistsTool", e)
        changelistError("Internal error: ${e.message}")
    }
}
