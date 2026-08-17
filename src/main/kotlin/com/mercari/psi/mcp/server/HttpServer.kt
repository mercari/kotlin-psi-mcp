package com.mercari.psi.mcp.server

import com.mercari.psi.mcp.tools.Tool
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.InetSocketAddress
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Authorization for the local HTTP transport, extracted as a pure function so it
 * can be unit-tested without starting Jetty.
 *
 * The server exposes code-modification tools, so it serves only local,
 * non-browser callers:
 *  - the `Host` must be IPv4 loopback (`localhost` or `127.0.0.1`) — the connector
 *    binds `127.0.0.1` only, so this matches what actually accepts connections and
 *    (with the loopback bind) blocks other machines and defeats DNS-rebinding (a
 *    rebind sends a foreign Host header); and
 *  - the request must carry **no** `Origin`. Any Origin means a web page is
 *    calling; no browser client is supported, so every Origin is rejected. This
 *    is stricter than a localhost-origin allowlist and needs no scheme parsing.
 *    Native MCP clients (Claude Code, curl) send no Origin and are unaffected.
 */
internal object RequestGuard {
    // IPv4 loopback only — the connector binds 127.0.0.1, so ::1 never accepts a
    // connection and is deliberately not in this set.
    private val allowedHosts = setOf("localhost", "127.0.0.1")

    /** Host part of the `Host` header: port stripped, IPv6 brackets removed. */
    fun hostOf(hostHeader: String?): String? {
        val raw = hostHeader?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return if (raw.startsWith("[")) raw.substringAfter('[').substringBefore(']')
        else raw.substringBefore(':')
    }

    fun isAllowed(hostHeader: String?, origin: String?): Boolean {
        if (origin != null) return false            // any browser Origin -> reject
        // Host names are case-insensitive, so normalize before the allowlist check
        // (e.g. "LOCALHOST" must be accepted). Uses Locale.ROOT to avoid locale
        // quirks like the Turkish dotless-i.
        val host = hostOf(hostHeader)?.lowercase(java.util.Locale.ROOT) ?: return false
        return host in allowedHosts
    }
}

// MCP server
class PsiHttpServer(private val port: Int = 51234) {
    private var server: Server? = null
    private val tools = mutableMapOf<String, Tool>()
    private val gson = Gson()

    fun registerTool(name: String, tool: Tool) {
        tools[name] = tool
    }

    fun start() {
        // Bind to loopback so the server is never reachable from other machines.
        server = Server(InetSocketAddress("127.0.0.1", port))

        val handler = object : AbstractHandler() {
            override fun handle(
                target: String,
                baseRequest: org.eclipse.jetty.server.Request,
                request: HttpServletRequest,
                response: HttpServletResponse
            ) {
                // Security gate: this server can rename/delete/move/refactor code, so
                // it must only serve local, non-browser callers. Reject a non-loopback
                // Host (DNS-rebinding defense) and ANY request carrying an Origin — no
                // browser client is supported, and native MCP clients (Claude Code,
                // curl) send no Origin. Rule lives in RequestGuard (unit-tested).
                if (!RequestGuard.isAllowed(
                        request.getHeader("Host"),
                        request.getHeader("Origin")
                    )
                ) {
                    sendResponse(response, 403, "Forbidden")
                    baseRequest.isHandled = true
                    return
                }
                when {
                    target == "/health" && request.method == "GET" -> {
                        sendResponse(response, 200, "OK")
                    }

                    target == "/api/tools" && request.method == "GET" -> {
                        handleListTools(response)
                    }

                    target.startsWith("/api/tools/") && request.method == "POST" -> {
                        handleToolExecution(target, request, response)
                    }

                    target == "/mcp" && request.method == "POST" -> {
                        handleMcpRequest(request, response)
                    }

                    target == "/mcp" && request.method == "GET" -> {
                        // Optional SSE stream for server-initiated messages.
                        // We have none yet, so respond 405.
                        sendResponse(response, 405, "Method Not Allowed")
                    }

                    target == "/mcp" && request.method == "OPTIONS" -> {
                        sendResponse(response, 204, "")
                    }

                    else -> {
                        sendResponse(response, 404, "Not found")
                    }
                }
                baseRequest.isHandled = true
            }
        }

        server?.handler = handler
        server?.start()
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun handleListTools(response: HttpServletResponse) {
        val toolSchemas = tools.map { (name, tool) ->
            mapOf(
                "name" to name,
                "description" to tool.getDescription(),
                "inputSchema" to tool.getInputSchema()
            )
        }

        val responseJson = gson.toJson(toolSchemas)
        sendResponse(response, 200, responseJson, "application/json")
    }

    private fun handleToolExecution(
        target: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val toolName = target.removePrefix("/api/tools/")

        val tool = tools[toolName]
        if (tool == null) {
            sendResponse(response, 404, gson.toJson(mapOf("error" to "Tool not found: $toolName")))
            return
        }

        try {
            val requestBody = request.reader.readText()
            val arguments = if (requestBody.isNotBlank()) {
                JsonParser.parseString(requestBody).asJsonObject
            } else {
                JsonObject()
            }

            val result = tool.execute(arguments)
            val responseJson = gson.toJson(mapOf("result" to result))
            sendResponse(response, 200, responseJson, "application/json")
        } catch (e: Exception) {
            val errorResponse = gson.toJson(mapOf("error" to e.message))
            sendResponse(response, 500, errorResponse, "application/json")
        }
    }

    // ---- MCP over Streamable HTTP transport ----
    //
    // Implements JSON-RPC 2.0 on POST /mcp. Supports the three methods MCP clients
    // actually use for tool servers: initialize, tools/list, tools/call.
    // Notifications (method starting with "notifications/") get an empty 202.
    //
    // Reference: https://modelcontextprotocol.io/specification/2025-03-26/basic/transports

    private val protocolVersion = "2024-11-05"
    private val serverName = "jetbrain-psi-mcp-server"

    // Single-sourced from the plugin descriptor, whose <version> patchPluginXml
    // injects from build.gradle.kts at build time — so the version lives in exactly
    // one place (build.gradle.kts). "dev" is a fallback for sandbox/test runs where
    // the descriptor isn't resolvable.
    private val serverVersion =
        PluginManagerCore.getPlugin(PluginId.getId("com.mercari.psi.mcp"))?.version ?: "dev"

    // Surfaced to the model once per session via the MCP `initialize` response.
    // Exactly ONE project is served at a time (the one selected in the IDE's
    // settings), so the agent must confirm it is the right project and that
    // indexing is done before trusting any resolution result.
    private val serverInstructions =
        "This server exposes IntelliJ/Android Studio PSI analysis for a SINGLE selected project, bound " +
                "to the fixed HTTP port $port. One IDE instance owns the port at a time; which project it serves " +
                "is chosen by the human in Settings ▸ Tools ▸ PSI MCP Server (enable switch + project dropdown). " +
                "At the START of each session — and again whenever a result looks wrong (a symbol you expect to " +
                "resolve returns \"could not resolve\" or \"file not indexed\", or find-usages comes back empty) — " +
                "call the 'check-sync-status' tool with the absolute root path of the project you intend to work " +
                "in. Only trust resolution / position-based results when it returns projectMatch=MATCH and " +
                "state=SMART_MODE. If projectMatch=MISMATCH (or it reports no served project), a different project " +
                "is selected — ask the human to pick the intended project in the settings dropdown, or, if it is " +
                "open in another IDE instance, to enable the server there (disabling it in the current owner " +
                "first). If state=DUMB_MODE, indexing is still running (wait and retry)."

    private fun handleMcpRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val requestBody = request.reader.readText()
        if (requestBody.isBlank()) {
            writeJsonRpcError(response, null, -32600, "Invalid Request: empty body")
            return
        }

        val parsed = try {
            JsonParser.parseString(requestBody)
        } catch (e: Exception) {
            writeJsonRpcError(response, null, -32700, "Parse error: ${e.message}")
            return
        }

        // Batch requests (array) vs single request (object).
        if (parsed.isJsonArray) {
            val results = JsonArray()
            for (el in parsed.asJsonArray) {
                if (!el.isJsonObject) continue
                handleSingleMcpMessage(el.asJsonObject)?.let { results.add(it) }
            }
            if (results.size() == 0) {
                // All were notifications — 202 Accepted per spec.
                response.status = 202
                return
            }
            writeJsonResponse(response, 200, gson.toJson(results))
            return
        }

        if (!parsed.isJsonObject) {
            writeJsonRpcError(response, null, -32600, "Invalid Request: expected object or array")
            return
        }

        val result = handleSingleMcpMessage(parsed.asJsonObject)
        if (result == null) {
            // Notification: no response body.
            response.status = 202
            return
        }
        writeJsonResponse(response, 200, gson.toJson(result))
    }

    private fun handleSingleMcpMessage(req: JsonObject): JsonObject? {
        val method = req.get("method")?.takeIf { !it.isJsonNull }?.asString
        val id = req.get("id")

        // Notifications have no id — per JSON-RPC 2.0 we must not respond.
        val isNotification = id == null || id is JsonNull

        if (method == null) {
            return if (isNotification) null
            else jsonRpcError(id, -32600, "Invalid Request: missing 'method'")
        }

        return when {
            // Lifecycle
            method == "initialize" -> {
                if (isNotification) null
                else jsonRpcResult(id, initializeResult())
            }

            method == "ping" -> {
                if (isNotification) null
                else jsonRpcResult(id, JsonObject())
            }

            method.startsWith("notifications/") -> {
                // Fire-and-forget; always 202 via null.
                null
            }
            // Tools
            method == "tools/list" -> {
                if (isNotification) null
                else jsonRpcResult(id, toolsListResult())
            }

            method == "tools/call" -> {
                if (isNotification) null
                else handleToolsCall(id, req.getAsJsonObject("params"))
            }

            else -> {
                if (isNotification) null
                else jsonRpcError(id, -32601, "Method not found: $method")
            }
        }
    }

    private fun initializeResult(): JsonObject {
        val capabilities = JsonObject().apply {
            add("tools", JsonObject().apply { addProperty("listChanged", false) })
        }
        val serverInfo = JsonObject().apply {
            addProperty("name", serverName)
            addProperty("version", serverVersion)
        }
        return JsonObject().apply {
            addProperty("protocolVersion", protocolVersion)
            add("capabilities", capabilities)
            add("serverInfo", serverInfo)
            addProperty("instructions", serverInstructions)
        }
    }

    private fun toolsListResult(): JsonObject {
        val toolsArray = JsonArray()
        for ((name, tool) in tools) {
            val t = JsonObject().apply {
                addProperty("name", name)
                addProperty("description", tool.getDescription())
                add("inputSchema", JsonParser.parseString(gson.toJson(tool.getInputSchema())))
            }
            toolsArray.add(t)
        }
        return JsonObject().apply { add("tools", toolsArray) }
    }

    private fun handleToolsCall(id: com.google.gson.JsonElement?, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.takeIf { !it.isJsonNull }?.asString
            ?: return jsonRpcError(id, -32602, "Invalid params: missing 'name'")
        val tool = tools[toolName]
            ?: return jsonRpcError(id, -32602, "Unknown tool: $toolName")

        val arguments = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject()

        return try {
            val resultText = tool.execute(arguments)
            val content = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", resultText)
                })
            }
            val toolResult = JsonObject().apply {
                add("content", content)
                addProperty("isError", false)
            }
            jsonRpcResult(id, toolResult)
        } catch (e: Exception) {
            val content = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "Error: ${e.message}")
                })
            }
            val toolResult = JsonObject().apply {
                add("content", content)
                addProperty("isError", true)
            }
            jsonRpcResult(id, toolResult)
        }
    }

    // ---- JSON-RPC helpers ----

    private fun jsonRpcResult(id: com.google.gson.JsonElement?, result: JsonObject): JsonObject {
        val obj = JsonObject()
        obj.addProperty("jsonrpc", "2.0")
        if (id != null) obj.add("id", id)
        obj.add("result", result)
        return obj
    }

    private fun jsonRpcError(
        id: com.google.gson.JsonElement?,
        code: Int,
        message: String
    ): JsonObject {
        val err = JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        }
        val obj = JsonObject()
        obj.addProperty("jsonrpc", "2.0")
        if (id != null) obj.add("id", id) else obj.add("id", JsonNull.INSTANCE)
        obj.add("error", err)
        return obj
    }

    private fun writeJsonRpcError(
        response: HttpServletResponse,
        id: com.google.gson.JsonElement?,
        code: Int,
        message: String
    ) {
        writeJsonResponse(response, 200, gson.toJson(jsonRpcError(id, code, message)))
    }

    private fun writeJsonResponse(response: HttpServletResponse, statusCode: Int, body: String) {
        response.status = statusCode
        response.contentType = "application/json"
        response.writer.write(body)
    }

    private fun sendResponse(
        response: HttpServletResponse,
        statusCode: Int,
        body: String,
        contentType: String = "text/plain"
    ) {
        response.status = statusCode
        response.contentType = contentType
        response.writer.write(body)
    }
}