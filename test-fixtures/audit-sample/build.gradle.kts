// Multi-module Kotlin/JVM fixture for auditing the PSI MCP tools against a real,
// indexed project. It is intentionally NOT part of the plugin build (the root
// settings.gradle.kts does not `include` it), so it never affects the plugin's
// own compilation.
//
// Modules:
//   :audit        — single-module symbols (same-file / same-module resolution,
//                   overloads, negative cases). Isolated: no deps, no dependents.
//   :core         — cross-module declaration targets. Depended on by :app and
//                   :feature-impl (a module with multiple direct dependents).
//   :app          — depends on :core, :feature-api, :feature-impl; drives
//                   cross-module resolution (the path where a generic
//                   PsiElement.reference.resolve() returns null and only the
//                   Kotlin mainReference + K2 analyze{} fallback resolves).
//   :feature-api  — an interface-only api module. Depended on by :app and
//                   :feature-impl (second multi-dependent module).
//   :feature-impl — implements :feature-api, consumes :core (COMPILE), and uses
//                   :testutil via testImplementation (TEST). Exercises
//                   module-search's dependency scopes + source/test root split.
//   :testutil     — test-only helper; a TEST-scope dependency of :feature-impl.
//
// The inter-module edges also back the module-search fixture: direct deps,
// direct dependents with multiplicity (:core has two), COMPILE vs TEST scope,
// and an isolated module (:audit).
plugins {
    kotlin("jvm") version "2.1.0" apply false
}
