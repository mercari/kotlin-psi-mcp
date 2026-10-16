package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

class DeleteChangelistTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(DeleteChangelistTool::class.java)

    private data class Response(val success: Boolean, val deleted: String, val movedFiles: Int, val movedTo: String?)

    override fun getDescription(): String =
        "Delete an IDE changelist. The active changelist cannot be deleted. A non-empty changelist is only deleted when " +
        "'move_to' names another changelist to receive its files; file contents are never touched."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to nameSchema("Changelist to delete."),
            "move_to" to nameSchema("Changelist that receives the files of a non-empty changelist.")
        ),
        "required" to listOf("name")
    )

    override fun execute(arguments: JsonObject): String {
        return try {
            val name = arguments.stringArg("name") ?: return changelistError("Missing 'name'")
            val moveTo = arguments.stringArg("move_to")
            withChangelistManager { _, clm ->
                val list = clm.findChangeList(name)
                    ?: return@withChangelistManager changelistError("Changelist '$name' not found")
                if (list.isDefault) {
                    return@withChangelistManager changelistError(
                        "'$name' is the active changelist; activate another one with set-active-changelist first"
                    )
                }
                val changes = list.changes.toTypedArray()
                var destination: String? = null
                if (changes.isNotEmpty()) {
                    if (moveTo == null) {
                        return@withChangelistManager changelistError(
                            "'$name' holds ${changes.size} file(s); pass 'move_to' to relocate them before deleting"
                        )
                    }
                    if (moveTo == name) return@withChangelistManager changelistError("'move_to' must differ from 'name'")
                    val dest = clm.findChangeList(moveTo)
                        ?: return@withChangelistManager changelistError("Changelist '$moveTo' not found")
                    clm.moveChangesTo(dest, *changes)
                    destination = moveTo
                }
                clm.removeChangeList(list)
                gson.toJson(Response(true, name, changes.size, destination))
            }
        } catch (e: Exception) {
            logger.error("Error in DeleteChangelistTool", e)
            changelistError("Internal error: ${e.message}")
        }
    }
}
