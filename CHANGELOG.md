# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims
to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Released versions and their notes are published on the
[GitHub Releases](../../releases) page. The section below tracks changes that
have not yet been released.

## [Unreleased]

## [0.1.1] - 2026-10-07

### Changed

- Support IDE 2026.2 (262): compatibility range is now 251–262.\*. `move-file`
  switched from the Kotlin plugin's K1-only
  `KotlinAwareMoveFilesOrDirectoriesProcessor` (removed in 2026.2) to the
  platform's `MoveFilesOrDirectoriesProcessor`; the Kotlin-specific move logic
  runs in the Kotlin plugin's `MoveFileHandler` extension either way.
  ([#3](https://github.com/mercari/kotlin-psi-mcp/issues/3))
- Bump the plugin version to 0.1.1 so Android Studio Rabbit 1 | 2026.2.1
  (262.9437.185) can install it; 0.1.0 was capped at 261.\*.
  ([#8](https://github.com/mercari/kotlin-psi-mcp/pull/8))
- Name the plugin archive `kotlin-psi-mcp-<version>.zip` (containing
  `kotlin-psi-mcp/lib/kotlin-psi-mcp-<version>.jar`), matching the Marketplace
  listing and the 0.1.0 release asset; builds from the repo previously produced
  `jetbrain-psi-plugin-<version>.zip`.
  ([#9](https://github.com/mercari/kotlin-psi-mcp/pull/9))
- Rename the plugin's display name from "PSI MCP Server" to "Kotlin PSI MCP",
  matching its [Marketplace listing](https://plugins.jetbrains.com/plugin/33755-kotlin-psi-mcp).
  "PSI MCP Server" is the name of an unrelated Marketplace plugin, so the IDE's
  plugin page could resolve to that listing. The settings page moves to
  Settings ▸ Tools ▸ Kotlin PSI MCP; the plugin id and saved settings are
  unchanged.
  ([#10](https://github.com/mercari/kotlin-psi-mcp/pull/10))

## [0.1.0] - 2026-08-13

Initial public release. The project began as an internal tool and is published
here as an early, experimental release: it works, but the tool set and HTTP
surface may still change without a major version bump while on `0.x`.

- PSI-backed navigation, search, and type inspection tools
- Refactoring tools: rename, move, safe delete, extract interface, add parameter
- Diagnostics, quick fixes, import handling, and formatting tools
- Local HTTP endpoint for MCP clients
