package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

class SetActiveChangelistTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(SetActiveChangelistTool::class.java)

    private data class Response(val success: Boolean, val active: String, val previous: String?)

    override fun getDescription(): String =
        "Make an existing changelist the active one. Files the IDE newly detects as changed (including edits made by tools) " +
        "are placed in the active changelist."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf("name" to nameSchema("Name of the changelist to activate.")),
        "required" to listOf("name")
    )

    override fun execute(arguments: JsonObject): String {
        return try {
            val name = arguments.stringArg("name") ?: return changelistError("Missing 'name'")
            withChangelistManager { _, clm ->
                val list = clm.findChangeList(name)
                    ?: return@withChangelistManager changelistError("Changelist '$name' not found")
                val previous = clm.defaultChangeList.name
                clm.setDefaultChangeList(list)
                gson.toJson(Response(true, name, previous.takeIf { it != name }))
            }
        } catch (e: Exception) {
            logger.error("Error in SetActiveChangelistTool", e)
            changelistError("Internal error: ${e.message}")
        }
    }
}
