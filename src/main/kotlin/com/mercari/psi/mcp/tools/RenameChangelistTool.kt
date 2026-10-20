package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

class RenameChangelistTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(RenameChangelistTool::class.java)

    private data class Response(val success: Boolean, val changelist: ChangelistInfo)

    override fun getDescription(): String =
        "Rename an IDE changelist and/or change its comment. Provide 'new_name', 'comment', or both."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to nameSchema("Current name of the changelist."),
            "new_name" to nameSchema("New name."),
            "comment" to nameSchema("New comment.")
        ),
        "required" to listOf("name")
    )

    override fun execute(arguments: JsonObject): String {
        return try {
            val name = arguments.stringArg("name") ?: return changelistError("Missing 'name'")
            val newName = arguments.stringArg("new_name")
            val comment = arguments.get("comment")?.takeIf { !it.isJsonNull }?.asString
            if (newName == null && comment == null) return changelistError("Provide 'new_name' and/or 'comment'")
            withChangelistManager { project, clm ->
                if (clm.findChangeList(name) == null) return@withChangelistManager changelistError("Changelist '$name' not found")
                if (newName != null && newName != name && clm.findChangeList(newName) != null) {
                    return@withChangelistManager changelistError("Changelist '$newName' already exists")
                }
                var current = name
                if (newName != null && newName != name) {
                    clm.editName(name, newName)
                    current = newName
                }
                if (comment != null) clm.editComment(current, comment)
                val list = clm.findChangeList(current)
                    ?: return@withChangelistManager changelistError("Rename failed: '$current' not found afterwards")
                gson.toJson(Response(true, list.toInfo(project, includeFiles = false)))
            }
        } catch (e: Exception) {
            logger.error("Error in RenameChangelistTool", e)
            changelistError("Internal error: ${e.message}")
        }
    }
}
