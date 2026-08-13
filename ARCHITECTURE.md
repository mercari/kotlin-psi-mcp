# Architecture Guide

This document covers the technical architecture, design decisions, and
implementation details of the PSI MCP Server plugin.

## System Architecture

### High-level overview

```
AI client  ⇄  IntelliJ/Android Studio plugin  ⇄  PSI analysis  ⇄  codebase
          MCP over HTTP (JSON-RPC 2.0)        IntelliJ Platform APIs
```

There is a **single component**: an IntelliJ Platform plugin that embeds an HTTP
server and speaks the Model Context Protocol directly. The AI client connects
straight to the plugin's HTTP endpoint.

### Components

All classes live under `com.mercari.psi.mcp`:

1. **`PsiMcpActivity`** (`postStartupActivity`, `DumbAware`)
   - Per-project startup hook. On the first project open in the JVM it triggers
     the (idempotent) server bind; later opens are no-ops for the server.

2. **`PsiMcpServerManager`** (application-level service, `PersistentStateComponent`)
   - Owns the server lifecycle and the tool registry (registers all tools).
   - Holds the persisted state and resolves which project is currently served.
   - Application-level because exactly one server owns the port per machine.

3. **`server.PsiHttpServer`** (Jetty)
   - The HTTP transport. Routes `/health`, `/api/tools*`, and `/mcp`.
   - Binds to loopback and enforces the security gate (see below).

4. **`tools.*`** — one class per MCP tool, each implementing the `Tool` interface.

5. **`settings.PsiMcpConfigurable`** — the Settings ▸ Tools ▸ PSI MCP Server UI
   (application-level: the enable switch + served-project dropdown are
   machine-wide).

### Single-project model

Exactly **one project is served at a time**, chosen by the human in the settings
dropdown (or the sole open project by default). Because resolution results are
only meaningful for the served, fully-indexed project, clients should call the
`check-sync-status` tool at the start of a session and whenever a result looks
wrong, and only trust results when it reports `projectMatch=MATCH` and
`state=SMART_MODE`.

## Communication Protocol

### MCP over Streamable HTTP

The plugin implements JSON-RPC 2.0 on `POST /mcp`. Supported methods:
`initialize`, `ping`, `tools/list`, `tools/call`, and `notifications/*`
(fire-and-forget → `202`). Protocol version `2024-11-05`; server name
`jetbrain-psi-mcp-server`.

A minimal client configuration:

```json
{
  "mcpServers": {
    "jetbrain-psi-mcp-server": { "type": "http", "url": "http://localhost:51234/mcp" }
  }
}
```

### HTTP endpoints

- `GET /health` — liveness check (returns `OK`).
- `GET /api/tools` — list tools (legacy REST view).
- `POST /api/tools/{toolName}` — execute a tool (legacy REST view).
- `POST /mcp` — the MCP JSON-RPC transport (the one AI clients use).

### Response conventions

Every tool's JSON payload carries `success: Boolean` and `error: String?`.

- `success: false` is reserved for a **genuine failure** — the tool could not
  perform the operation: no open project, file not indexed, invalid
  position/arguments, or a thrown exception. Such results carry a human-readable
  `error`.
- A **legitimate "nothing found"** is NOT a failure and is `success: true`: a
  query tool returns `success: true` with an empty collection (e.g. `count: 0`);
  a single-result tool returns `success: true` with a `null` result field (e.g.
  `declaration: null`, `typeInfo: null`).
- The MCP protocol-level `isError` flag (in `tools/call`) is set **only** when
  `tool.execute()` throws; a payload `success: false` does not by itself set
  `isError`.

> Known deviation pending alignment: `find-declaration` currently returns
> `success: false` for a legitimate "not found" (a position that resolves to no
> declaration), rather than `success: true` with `declaration: null`.
> (`get-type-info` already follows the convention: a benign no-type position
> returns `success: true` with `typeInfo: null` and a `reason`.)

## Tool Implementation

### Tool interface

```kotlin
interface Tool {
    fun execute(arguments: JsonObject): String   // returns a JSON string
    fun getDescription(): String
    fun getInputSchema(): Map<String, Any>
}
```

### Registration

Tools are registered in `PsiMcpServerManager` when the server starts, e.g.:

```kotlin
server.registerTool("find-usages", FindUsagesTool())
server.registerTool("find-declaration", FindDeclarationTool())
// ...22 tools total
```

The current set spans navigation/analysis (`find-usages`, `find-declaration`,
`find-implementations`, `find-symbols`, `get-containing-context`,
`get-call-hierarchy`, `get-type-info`, `get-kdoc`, `get-diagnostics`),
project structure (`module-search`, `check-sync-status`, `gradle-sync`), and
refactoring/edits (`rename`, `safe-delete`, `move-file`, `extract-interface`,
`add-import`, `add-parameter`, `find-import-suggestions`, `organize-imports`,
`format-code`, `apply-quick-fix`). See `README.md` for the user-facing list.

### Language support

The plugin is built around **Kotlin** semantic analysis (the K2 Analysis API),
with **partial Java** support in a few tools. It therefore requires an IDE that
bundles the Kotlin, Java, and Gradle plugins (IntelliJ IDEA and Android Studio).

## Security

The server exposes code-analysis **and code-modification** tools, so the
transport is locked down:

- **Loopback bind** — the connector binds `127.0.0.1` (IPv4 loopback), so it is
  unreachable from other machines.
- **Origin/Host validation** — a request is served only if its `Host` is IPv4
  loopback (`localhost` / `127.0.0.1`; DNS-rebinding defense) **and** it carries
  no `Origin` header. Any `Origin` is rejected with `403` — no browser client is
  supported, and native MCP clients (Claude Code, curl) send no `Origin`. The
  rule lives in the unit-tested `RequestGuard`.
- **No CORS** — the server sends no `Access-Control-Allow-Origin` header at all
  (there is no allowed browser origin).

File access is limited to what IntelliJ exposes for the served project.

## Configuration & State

State is persisted by `PsiMcpServerManager`:

```kotlin
@State(name = "PsiMcpServerManager", storages = [Storage("psi-mcp-server.xml")])
class PsiMcpServerManager : PersistentStateComponent<PsiMcpServerManager.State>, Disposable {
    data class State(
        var enabled: Boolean = true,               // server on by default
        var selectedProjectPath: String? = null,   // which project is served
    )
}
```

The HTTP port is fixed at **51234** (one server owns the port per machine).

## Packaging & Distribution

A single IntelliJ plugin ZIP (`build/distributions/jetbrain-psi-plugin-<version>.zip`),
installed via *Settings ▸ Plugins ▸ Install Plugin from Disk*. The IntelliJ
Platform APIs the plugin compiles against are provided by the host IDE at
runtime and are not redistributed. See `THIRD-PARTY-NOTICES.md` for bundled
libraries.
