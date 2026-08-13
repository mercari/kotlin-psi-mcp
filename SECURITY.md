# Security Policy

## Reporting a vulnerability

Please **do not** report security vulnerabilities through public GitHub issues,
pull requests, or discussions.

Instead, report them privately:

- Preferred: use GitHub's **[Report a vulnerability](../../security/advisories/new)**
  (Security → Advisories) to open a private advisory.
- Or email **security@mercari.com** with the details.

Please include:

- a description of the issue and its impact,
- steps to reproduce (proof-of-concept if possible),
- affected version(s) and environment (IDE + version).

We will acknowledge your report, keep you informed of progress, and coordinate
a fix and disclosure timeline with you.

## Scope notes

The plugin runs a local HTTP server that exposes code-analysis and
code-modification tools. It binds to loopback (`127.0.0.1`) and validates the
request `Origin`/`Host` to prevent other machines and web pages from reaching
it. Reports about that boundary — network exposure, cross-origin access, or
ways to invoke tools without a legitimate local MCP client — are in scope and
especially welcome.

## Supported versions

Only the latest released version receives security fixes.
