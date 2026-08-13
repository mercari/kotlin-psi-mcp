# Android Studio PSI MCP Server

Bring Android Studio's powerful semantic code analysis to AI assistants via the Model Context Protocol (MCP).

Get precise, context-aware code understanding that goes far beyond text-based searching - find usages, navigate to definitions, explore inheritance hierarchies, and more.

While the underlying approach applies to any JetBrains IDE in principle, this plugin requires an IDE that bundles the Kotlin, Java, and Gradle plugins — it declares a hard dependency on each (see `plugin.xml`) and is built around Kotlin's K2 semantic analysis, with partial Java support. In practice that means IntelliJ IDEA and Android Studio, which are the only IDEs it is actively developed and tested against.



## Quick Start
1. Install the Android Studio plugin:
   - Download `jetbrain-psi-plugin-x.y.z.zip` from [releases](https://github.com/mercari/jetbrain-psi-mcp-server/releases)
   - Install in Android Studio: `Settings > Plugins > Install Plugin from Disk`
   - Restart IDE
   - (optional) In `Settings > Tools > PSI MCP Server`, choose which open project the server serves (the HTTP port is fixed at `51234`)

2. Point your AI assistant at the plugin's HTTP MCP endpoint. For Claude Code, in your claude config file:

    ```json
    "mcpServers": {
      "jetbrain-psi-mcp-server": {
        "type": "http",
        "url": "http://localhost:51234/mcp"
      }
    }
    ```

   The plugin speaks MCP directly over HTTP at `/mcp`.

3. Now launch `$ claude` and make sure you leave your Android Studio opened and make sure that it's done indexing (important!).
4. Try to search for something, e.g.
```shell
> find usages of ClickableRow
```

If your model doesn't automatically invoke PSI MCP, you can be explicit:
```shell
> find usages of ClickableRow (using PSI MCP)
```

## Testing
1. Make sure MCP server is working, run this:

For Claude:
```shell
claude mcp list | grep jetbrain-psi-mcp-server
```

You should see the server listed and `✓ Connected`.

2. Make sure the Android Studio Plugin is working, curl the health endpoint, you should see "OK" reply:
```shell
$ curl http://localhost:51234/health
OK%
```

3. Verify MCP transport directly:
```shell
$ curl -s -X POST http://localhost:51234/mcp \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -c 200
```

## Available Tools

**Navigation & analysis:**
- `find-declaration` - Go to the declaration of the symbol at a location
- `find-usages` - Semantic references to the symbol at a location
- `find-implementations` - Implementations/overrides of an interface or abstract member
- `find-symbols` - Fuzzy (camel-hump) symbol search by name across the codebase
- `get-containing-context` - The class/method enclosing a location
- `get-call-hierarchy` - Incoming/outgoing call hierarchy of a function
- `get-type-info` - Resolved type at a position (K2 Analysis API)
- `get-kdoc` - KDoc/JavaDoc of the declaration at a position
- `get-diagnostics` - Inspection/compiler warnings and errors for file(s)

**Project structure:**
- `module-search` - Find modules by fuzzy/partial name with their dependencies, dependents, and content roots (relevance-ranked, paginated)
- `check-sync-status` - Verify the served project matches and has finished indexing
- `gradle-sync` - Trigger a Gradle sync

**Refactoring & edits:**
- `rename` - Rename a symbol across the project
- `safe-delete` - Delete a symbol if it is unused (reports blockers)
- `move-file` - Move a file, updating references and package
- `extract-interface` - Extract an interface from a class
- `add-import` - Add an import
- `add-parameter` - Add a parameter to a function
- `find-import-suggestions` - Suggest imports for an unresolved reference
- `organize-imports` - Remove unused imports and sort them
- `format-code` - Reformat a file or line range
- `apply-quick-fix` - List or apply IDE quick-fixes

All tools use IntelliJ's semantic analysis for precise, context-aware results that understand language syntax, scoping, and inheritance. `module-search` is query-scoped and paginated so it stays usable on large projects (2000+ modules) without blowing the response-size budget.

## Examples

**Symbol Analysis:**
```
"Find all usages of the calculateTotal function"
"What class contains the code at line 50 of MainActivity.kt?"
"Find all subclasses of BaseActivity"
"Navigate to the definition of the onClick variable"
```

**Module Analysis:**
```
"Find all authentication-related modules"
"Search for modules containing 'payment' and show their dependencies"
"Find modules using fuzzy search: 'cart', 'auth', 'ds4'"
"Which modules does the ':feature:checkout' module depend on?"
```

## Troubleshooting Connection Issues

If your MCP server fails to connect:

1. **Verify the plugin is running** - `curl http://localhost:51234/health` should return `OK`
2. **Verify the endpoint** - your MCP config URL must be `http://localhost:51234/mcp` (the plugin's port is fixed at `51234`)
3. **Restart Android Studio** - Required after installing or upgrading the plugin
4. **Check indexing** - The plugin needs the project to be done indexing before PSI tools work reliably

## Documentation

- **[DEVELOPMENT.md](DEVELOPMENT.md)** - Building, testing, and contributing
- **[ARCHITECTURE.md](ARCHITECTURE.md)** - Technical details and design decisions

## Disclaimer

```
This is an independent open-source project published by Mercari, Inc. It is not
affiliated with, endorsed by, or sponsored by JetBrains s.r.o. or Google LLC.
"IntelliJ", "IntelliJ IDEA", "JetBrains", and "Android Studio" are trademarks of
their respective owners and are used here only to describe compatibility.
```
