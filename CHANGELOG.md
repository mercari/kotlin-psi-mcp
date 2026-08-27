# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims
to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Released versions and their notes are published on the
[GitHub Releases](../../releases) page. The section below tracks changes that
have not yet been released.

## [Unreleased]

### Changed

- Support IDE 2026.2 (262): compatibility range is now 251–262.\*. `move-file`
  switched from the Kotlin plugin's K1-only
  `KotlinAwareMoveFilesOrDirectoriesProcessor` (removed in 2026.2) to the
  platform's `MoveFilesOrDirectoriesProcessor`; the Kotlin-specific move logic
  runs in the Kotlin plugin's `MoveFileHandler` extension either way.
  ([#3](https://github.com/mercari/kotlin-psi-mcp/issues/3))

## [0.1.0] - 2026-08-13

Initial public release. The project began as an internal tool and is published
here as an early, experimental release: it works, but the tool set and HTTP
surface may still change without a major version bump while on `0.x`.

- PSI-backed navigation, search, and type inspection tools
- Refactoring tools: rename, move, safe delete, extract interface, add parameter
- Diagnostics, quick fixes, import handling, and formatting tools
- Local HTTP endpoint for MCP clients
