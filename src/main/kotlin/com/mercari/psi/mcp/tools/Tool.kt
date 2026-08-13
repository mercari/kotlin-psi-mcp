package com.mercari.psi.mcp.tools

import com.google.gson.JsonObject

interface Tool {
    fun execute(arguments: JsonObject): String
    fun getDescription(): String
    fun getInputSchema(): Map<String, Any>
}