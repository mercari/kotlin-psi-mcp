package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

class CreateChangelistTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(CreateChangelistTool::class.java)

    private data class Response(val success: Boolean, val changelist: ChangelistInfo)

    override fun getDescription(): String =
        "Create a new IDE changelist, optionally making it the active one so that subsequent edits land in it. " +
        "Fails if a changelist with that name already exists."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to nameSchema("Name of the new changelist."),
            "comment" to nameSchema("Optional description."),
            "set_active" to boolSchema("Make it the active changelist. Defaults to false.", false)
        ),
        "required" to listOf("name")
    )

    override fun execute(arguments: JsonObject): String {
        return try {
            val name = arguments.stringArg("name") ?: return changelistError("Missing 'name'")
            withChangelistManager { project, clm ->
                if (clm.findChangeList(name) != null) return@withChangelistManager changelistError("Changelist '$name' already exists")
                val list = clm.addChangeList(name, arguments.stringArg("comment"))
                if (arguments.boolArg("set_active")) clm.setDefaultChangeList(list)
                val created = clm.findChangeList(name) ?: list
                gson.toJson(Response(true, created.toInfo(project, includeFiles = false)))
            }
        } catch (e: Exception) {
            logger.error("Error in CreateChangelistTool", e)
            changelistError("Internal error: ${e.message}")
        }
    }
}
