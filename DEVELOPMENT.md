# Development Guide

This guide covers building, testing, and contributing to the PSI MCP Server
plugin.

## Project Structure

```
jetbrain-psi-mcp-server/
├── src/main/
│   ├── kotlin/com/mercari/psi/mcp/   # plugin implementation
│   │   ├── ClaudeKotlinPlugin.kt      # PsiMcpActivity (startup hook)
│   │   ├── PsiMcpServerManager.kt     # app service: lifecycle + tool registry + state
│   │   ├── server/HttpServer.kt       # Jetty HTTP + MCP transport
│   │   ├── settings/                  # Settings UI
│   │   └── tools/                     # one class per MCP tool
│   └── resources/META-INF/            # plugin.xml, bundled LICENSE + notices
├── test-fixtures/audit-sample/        # a multi-module fixture project for runtime testing
├── build.gradle.kts                   # plugin version + build config
└── Makefile                          # version + build helpers
```

There is a single component — the IntelliJ plugin — which speaks MCP directly
over HTTP.

## Building

```bash
./gradlew buildPlugin      # -> build/distributions/jetbrain-psi-plugin-<version>.zip
./gradlew compileKotlin    # fast compile check
./gradlew verifyPlugin     # IntelliJ plugin structure/compat checks
```

`make build` runs `./gradlew buildPlugin`; `make package` then copies the
already-built ZIP into `release/` (run it after `make build` — it does not build
on its own). To try the built ZIP in a real IDE, install it via *Settings ▸
Plugins ▸ Install Plugin from Disk*; for a quicker dev loop use `./gradlew runIde`
(below).

## Development & Testing

Run the unit tests (JUnit) — e.g. the HTTP transport's request-guard security
tests in `src/test`:

```bash
./gradlew test
```

Run the plugin in a sandbox IDE with a real project to exercise the PSI tools:

```bash
# Launch a sandbox IDE with the audit-sample fixture opened
./gradlew runIde --args="$(pwd)/test-fixtures/audit-sample"
```

Once it is up and the project has finished indexing, smoke-test the transport:

```bash
curl http://127.0.0.1:51234/health                       # -> OK
curl -s -X POST http://127.0.0.1:51234/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -c 200
```

`test-fixtures/README.md` documents the fixture and a full per-tool test matrix.

## Adding a Tool

1. **Implement it** — add a class under
   `src/main/kotlin/com/mercari/psi/mcp/tools/` implementing the `Tool`
   interface (`execute`, `getDescription`, `getInputSchema`).
2. **Register it** — add a `server.registerTool("your-tool", YourTool())` line
   in `PsiMcpServerManager` (and import it). This is the *only* registration
   site — there is no separate MCP-server module.
3. **Document it** — add it to the tool list in `README.md`.
4. **Verify** — `./gradlew compileKotlin verifyPlugin`, then exercise it against
   the fixture via `runIde`.

Guidelines: use PSI/K2 APIs for semantic analysis, validate inputs, follow the
`success`/`error` response conventions in [ARCHITECTURE.md](ARCHITECTURE.md),
and match the style of the surrounding tools.

## Development Environment

### Prerequisites
- **JDK 21**
- **IntelliJ IDEA or Android Studio** — the plugin depends on the bundled
  Kotlin, Java, and Gradle plugins, so it only runs on these IDEs.

### IDE setup
1. Import the project into IntelliJ IDEA.
2. Ensure the Kotlin plugin is enabled and the project SDK is JDK 21.
3. Run `./gradlew build` once to fetch dependencies.

## Versioning

Single artifact, single version. The version is defined in `build.gradle.kts`
(`version = "x.y.z"`); bump it with the `make` helpers below.

```bash
make versions              # print the current version
make bump-plugin-patch     # or bump-plugin-minor / bump-plugin-major
```

See the Release Workflow section below for tagging and publishing a release.

## Release Workflow

Releases are plugin-only. Each release ships one artifact:
`build/distributions/jetbrain-psi-plugin-x.y.z.zip`.

1. Bump the version: `make bump-plugin-patch` (or `minor` / `major`).
2. Build: `make build` (runs `./gradlew buildPlugin`).
3. Smoke-test the zip locally (install from disk in Android Studio, hit `/health` and `/mcp`).
4. Commit and tag:
   - `git commit -am "chore: release $(make -s versions | sed 's/Plugin: v//')"`
   - `git tag plugin-v$(make -s versions | sed 's/Plugin: v//')`
   - `git push && git push --tags`
5. Create a GitHub Release for the `plugin-vx.y.z` tag and upload the zip from `build/distributions/`.

Users upgrade by downloading the new zip and installing from disk.

## Contributing

The tool surface is intentionally kept small and curated, so we are not actively
seeking feature contributions. If you do open a pull request, all contributions
are subject to the [Mercari CLA](https://www.mercari.com/cla/) — by submitting one
you are deemed to accept and agree to be bound by its terms. Please follow the
[Code of Conduct](CODE_OF_CONDUCT.md), and report security issues privately per
[SECURITY.md](SECURITY.md).

## Maintainers

- [@worker8](https://github.com/worker8)
- [@karthi2007](https://github.com/karthi2007)

## Additional Resources

- [ARCHITECTURE.md](ARCHITECTURE.md) — design and protocol details
- [test-fixtures/README.md](test-fixtures/README.md) — runtime test fixture + matrix
- IntelliJ Platform SDK — https://plugins.jetbrains.com/docs/intellij/
- Model Context Protocol — https://modelcontextprotocol.io/
