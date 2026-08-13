# Test fixtures

Fixture projects used to exercise the PSI MCP tools at runtime. These are **not**
part of the plugin build — the root `settings.gradle.kts` does not `include`
them, so they never affect the plugin's own compilation. They exist to be
*opened inside the runIde sandbox* (or a real Android Studio) so the tools have a
real, indexed project to navigate.

## `audit-sample/`

A single Kotlin/JVM project with **six modules**, so one open project can audit
same-module resolution, cross-module resolution, and the module dependency graph
without switching:

| Module | Purpose |
|---|---|
| `:audit` | Deterministic single-module symbols: same-file / same-module resolution, overloads, negative (not-found) cases. Isolated in the dep graph (no deps, no dependents). |
| `:core` | Cross-module declaration targets (interface, class, top-level fun, const). Depended on by `:app` and `:feature-impl` → a module with **multiple** direct dependents. |
| `:app` | Depends on `:core`, `:feature-api`, `:feature-impl`; drives **cross-module** resolution — the path where a generic `PsiElement.reference.resolve()` returns `null` and only the Kotlin `mainReference` + K2 `analyze{}` fallback resolves. |
| `:feature-api` | Interface-only api module. Depended on by `:app` and `:feature-impl` (second multi-dependent module). |
| `:feature-impl` | Implements `:feature-api`, consumes `:core` (COMPILE); uses `:testutil` via `testImplementation` (TEST). Has a `src/test` root. |
| `:testutil` | Test-only helper — a **TEST**-scope dependency of `:feature-impl`. |

A single-module project cannot reproduce the cross-module case (in-module symbols
resolve via the cheap path), which is why `:core`/`:app` are separate modules with
`:app` depending on `:core`. The extra `:feature-*`/`:testutil` edges back the
`module-search` fixture (direct deps, dependents with multiplicity, COMPILE vs TEST
scope, and an isolated module).

### Running

The PSI MCP HTTP server binds `:51234` by default. If a real IntelliJ/Android
Studio is already serving on `:51234`, quit it first (only one process can hold
the port).

From the repo root, launch the sandbox with the fixture opened:

```bash
./gradlew runIde --args="$(pwd)/test-fixtures/audit-sample"
```

Wait for the sandbox IDE to open, import the Gradle project, and finish indexing
**all three modules** (watch the status bar). Then confirm the server is up and
serving this project:

```bash
curl -s http://127.0.0.1:51234/health          # -> OK
```

Confirm the right project is open and indexed before trusting any result:

```bash
curl -s -X POST http://127.0.0.1:51234/api/tools/check-sync-status \
  -H 'Content-Type: application/json' \
  -d "{\"root_project_path\":\"$(pwd)/test-fixtures/audit-sample\"}" | pp
# expect projectMatch=MATCH and state=SMART_MODE
```

If `check-sync-status` reports `MISMATCH`, a different project is open on `:51234`;
if `DUMB_MODE`, indexing is still running — wait and retry.

> Note: opening the repo root in an IDE may prompt to link this nested Gradle
> project. It's harmless; ignore or decline.

### Reading responses

Tool responses come back as JSON embedded in a `"result"` string (JSON inside
JSON), so reading them takes a double-parse. Define this helper once, then pipe
any tool call through it to pretty-print:

```bash
pp() { python3 -c 'import sys,json; print(json.dumps(json.loads(json.loads(sys.stdin.read())["result"]), indent=2, ensure_ascii=False))'; }
```

- the first `json.loads` unwraps the `"result"` envelope, the second parses the
  inner string, and `indent=2` pretty-prints it.
- `ensure_ascii=False` keeps `'`, emoji (🔍) and other non-ASCII readable
  instead of escapes like `'`.

Don't pipe `/health` or `check-sync-status`-less plain responses that aren't JSON
through `pp`. (`/health` returns plain `OK`.)

### Base paths

Paths must be absolute. Set these once and reuse them in every call below:

```bash
SAMPLE="$(pwd)/test-fixtures/audit-sample/audit/src/main/kotlin/sample"
CORE="$(pwd)/test-fixtures/audit-sample/core/src/main/kotlin/sample/core"
APP="$(pwd)/test-fixtures/audit-sample/app/src/main/kotlin/sample/app"
```

---

## `:audit` module — single-module symbols

Source: `$SAMPLE` (`Greeter.kt`, `Main.kt`, `Overloads.kt`). Coordinates are
1-based `line:column`, pointing at the start of the identifier.

### Symbol map

| Symbol | Kind | Declared at | Referenced at |
|---|---|---|---|
| `Greeter` | class | `Greeter.kt` 6:7 | `Greeter.kt` 18:19, `Main.kt` 4:19 |
| `greet` | method | `Greeter.kt` 8:9 | `Greeter.kt` 19:20, `Main.kt` 5:21 |
| `buildMessage` | private method | `Greeter.kt` 12:17 | `Greeter.kt` 9:16 |
| `topLevelGreeting` | top-level fun | `Greeter.kt` 17:5 | `Main.kt` 6:13 |
| `name` | ctor property | `Greeter.kt` 6:19 | `Greeter.kt` 9:29 |
| `who` | parameter | `Greeter.kt` 12:30 | `Greeter.kt` 13:25 |
| `format(Int)` | overload | `Overloads.kt` 11:9 | `Overloads.kt` 19:23 |
| `format(String)` | overload | `Overloads.kt` 13:9 | `Overloads.kt` 20:23 |
| `format(Int, Int)` | overload | `Overloads.kt` 15:9 | `Overloads.kt` 21:23 |
| `useOverloads` | top-level fun | `Overloads.kt` 18:5 | — |
| `a` (local val) | local variable | `Overloads.kt` 19:9 | `Overloads.kt` 22:12 |

### find-declaration test matrix

Every row is an input position (1-based `line:col`) and the declaration the tool
must return. Run each against `/api/tools/find-declaration` and pipe through `pp`.
The last rows are **negative** cases — the tool must NOT invent an answer.

| # | Scenario | Input | Expected result | What it guards |
|---|---|---|---|---|
| D1 | call → decl, same file | `Greeter.kt` 9:16 | `buildMessage`, `Greeter.kt` line 12 | basic reference resolve |
| D2 | call → decl, cross file | `Main.kt` 5:21 | `greet`, `Greeter.kt` line 8 | cross-file resolve |
| D3 | constructor call | `Main.kt` 4:19 | `Greeter` class, `Greeter.kt` line 6, `type:"declaration"` | ctor resolution |
| D4 | property usage → ctor property | `Greeter.kt` 9:29 | `name`, `Greeter.kt` line 6 | property resolve |
| D5 | parameter in string template | `Greeter.kt` 13:25 | `who`, `Greeter.kt` line 12 | param + template ref |
| D6 | local val usage → local decl | `Overloads.kt` 22:12 | `a` (local var), `Overloads.kt` line 19 | local variable resolve |
| D7 | click ON a declaration name | `Overloads.kt` 18:5 | `useOverloads` itself, line 18, `isLocal:true` | go-to-decl identity |
| D8 | **overload: Int arg** | `Overloads.kt` 19:23 | `format`, `Overloads.kt` line **11** | overload resolution |
| D9 | **overload: String arg** | `Overloads.kt` 20:23 | `format`, `Overloads.kt` line **13** | overload resolution |
| D10 | **overload: (Int, Int) args** | `Overloads.kt` 21:23 | `format`, `Overloads.kt` line **15** | overload resolution |
| D11 | negative: numeric literal | `Overloads.kt` 19:30 (the `42`) | `success:false` — NOT `useOverloads`, NOT `format` | honest not-found |
| D12 | negative: string-literal text | `Greeter.kt` 13:17 (the `H` in `"Hello"`) | `success:false` — NOT `buildMessage` | honest not-found |
| D13 | **library resolution** | `Main.kt` 5:5 (`println`) | `println`, pkg `kotlin.io`, file `ConsoleKt.class`, `isLocal:false` | resolves into a **compiled dependency** |

**The overload block (D8–D10) is the headline test.** All three call sites are
lexically identical (`Formatter.format(...)`) and differ only by argument type,
so a correct tool returns three *different* declaration lines (11 / 13 / 15). A
tool that resolves "first `format` by name" returns line 11 for all three — that
is the failure mode to catch.

**The negative block (D11–D12) guards the documented K2 regression** where a
click that does not land on a resolvable reference used to return the *enclosing*
declaration instead of an honest miss. D11/D12 sit inside a function body but on
a non-reference token; the correct response is `success:false` with no
`declaration`, never the surrounding `useOverloads` / `buildMessage`.

**D13 (library resolution) is the airtight cross-boundary check.** `println` is
declared in the kotlin-stdlib jar as bytecode (`ConsoleKt.class`), not in any
source this project owns, so a correct result points *into that binary*
(`isLocal:false`, file ending in `.class` / `.kotlin_builtins`). Because the
declaration exists only as a compiled artifact, a pass here can't be explained by
the IDE flattening the source modules into one scope — unlike a source→source hit
(X1–X5), which in principle could. That's what makes it the strongest guard that
resolution truly leaves the project's own source. (Any stdlib symbol works; e.g.
the `String` return type on `Greeter.kt` 8:18 resolves to `kotlin.String`.)

Example (D8 — Int overload must resolve to line 11):

```bash
curl -s -X POST http://127.0.0.1:51234/api/tools/find-declaration \
  -H 'Content-Type: application/json' \
  -d "{\"file_path\":\"$SAMPLE/Overloads.kt\",\"line\":19,\"column\":23}" | pp
# expect declaration.line == 11  (int overload), NOT 13 or 15
```

---

## `:audit` module — `find-usages`

`find-usages` is the merged tool (it folds in the former
`find-references-with-context`). For the symbol
at a position it runs a semantic `ReferencesSearch` and returns:

```
{ success, targetSymbol, targetKind,
  byType: { "<usageType>": <count>, ... },  // over the FULL set (before `limit`)
  usages: [ {
    file, line, column,          // the USAGE (reference/call site), 1-based
    usageType,
    containingSymbol: {          // the declaration this usage sits inside (null for imports)
      name,                      // "Class.function" / "function" / "Class"
      line, column               // that declaration's name-identifier position (same file)
    },
    snippet
  } ],
  count,       // usages returned (after `limit`)
  totalCount,  // usages found (before `limit`)
  truncated }
```

`containingSymbol` is an **object**, not a bare name. Its `line`/`column` point at the enclosing
declaration's own name identifier — i.e. where you'd re-invoke `find-usages` to walk one hop up
the call/usage chain (no name-to-declaration lookup needed). It is `null` only for file-level
`import` usages; every other usage type (call / read / write / read-write / comment / declaration /
trailing-lambda) carries it, so it works for any target kind (class, function, variable, parameter).

`usageType` ∈ `read` / `write` / `read-write` / `call` / `import` / `comment` /
`declaration` / `trailing-lambda`. `byType` is computed over the full pre-`limit`
set, so its counts stay correct even when `usages` is truncated (the reason it is
worth returning rather than leaving the caller to aggregate). `comment` usages
appear only with `"include_comments": true` — a project-scope word scan of
comments/KDoc, deduped against resolved references. The dedicated surface lives in
`Usages.kt` — kept separate so it never shifts the find-declaration coordinates
above.

Source: `$SAMPLE/Usages.kt`. Coordinates are 1-based `line:col` at the start of
the identifier.

### Symbol map

| Symbol | Kind | Declared at | Usages (`line:col` → `usageType`) |
|---|---|---|---|
| `withBlock` | fun (last param is a lambda) | 10:5 | 26:5 call · 27:5 call · 28:5 call |
| `block` | parameter (function type) | 10:30 | 11:5 call · 28:28 read (named arg) · **26 trailing-lambda** · **27 trailing-lambda** |
| `counter` | top-level `var` | 15:5 | 18:5 write · 19:5 read-write · 20:5 read-write · 21:20 read · 26:22 write · 27:22 write · *(33, 34 comment)* |
| `block` comment mentions | — | — | 33 · 34 mention `counter`; 34 also mentions `withBlock` (plain text, not references) |

### find-usages test matrix

| # | Scenario | Input | Expected | What it guards |
|---|---|---|---|---|
| U1 | usageType variety + `byType` | `Usages.kt` 15:5 (`counter`) | `totalCount:6`; `byType:{write:3, "read-write":2, read:1}` | `=`→write, `+=`/`++`→read-write, plain→read; grouping counts |
| U2 | call sites + containingSymbol object | `Usages.kt` 10:5 (`withBlock`) | 3 usages, all `usageType:"call"`, each `containingSymbol:{name:"useTrailingLambda", line:25, column:5}` | callee detection + enclosing-decl naming **and** its name-identifier position |
| U3 | **trailing-lambda regression gate** | `Usages.kt` 10:30 (`block`) | `totalCount:4` — `11:5` call, `28:28` read, **`26` + `27` `usageType:"trailing-lambda"`** | the merged tool's trailing-lambda union |
| U4 | limit / truncated / totalCount / `byType` | `Usages.kt` 15:5 (`counter`), `limit:2` | `count:2`, `truncated:true`, `totalCount:6`, `byType` still `{write:3, "read-write":2, read:1}` | `byType`/`totalCount` accurate under truncation |
| U5 | scope-bounded write inside lambda | (part of U1) `26:22` / `27:22` | `usageType:"write"` (not `read`) | assignment detection stops at the `KtFunctionLiteral` boundary |
| U6 | comment search (opt-in) | `Usages.kt` 15:5 (`counter`), `include_comments:true` | `totalCount:8`, `byType` adds `comment:2` (lines 33, 34) | `include_comments` word scan + `byType` merge |
| U7 | comment search off by default | `Usages.kt` 15:5 (`counter`) | no `comment` entries; `totalCount:6` | comments excluded unless opted in |
| U8 | containingSymbol is a lookup-free chain anchor | `Greeter.kt` 17:5 (`topLevelGreeting`) | 1 `call` usage at `Main.kt:6`, `containingSymbol:{name:"main", line:3, column:5}` | the enclosing decl's name-identifier position — feedable straight back into find-usages |
| U8b | chain hop 2 (terminates at entry point) | feed U8's `containingSymbol` → `Main.kt` 3:5 | `targetSymbol:"main"`, `targetKind:"function"`, `totalCount:0` (uncalled entry point) | the hop is lookup-free and lands on the right declaration; chain ends cleanly |
| U9 | import → null; call-in-constructor → class fallback | `Repository.kt` 14:5 (`defaultRepository`, cross-module) | `byType:{call:2, import:2}`; both `import` usages have `containingSymbol:null`; `Consumer.kt:13` call → `{name:"run", line:10, column:5}`; `DefaultFeature.kt:11` call (in a constructor default) → `{name:"DefaultFeature", line:11, column:7}` | `null` only for file-level imports; a usage not inside a function falls back to the enclosing class |

**U3 is the headline gate (plan §3.3 AC #2).** The two trailing-lambda call
sites (`withBlock("a") { ... }` on lines 26–27) create **no textual reference**
to the `block` parameter, so a plain `ReferencesSearch` finds only `2` usages
(the in-body `block()` on line 11 and the explicit named argument `block = {}` on
line 28). Trailing-lambda handling was `FindUsagesTool`'s own feature all along —
the merge (Option A) **must preserve** it via `findTrailingLambdaUsages(...)`,
which unions in the two synthetic refs to reach `totalCount:4`. This gate exists
because a naive port that kept only the `ReferencesSearch` path would silently
regress.

- **Regressed (trailing-lambda union dropped):** `totalCount:2`, no `trailing-lambda` entries — **fail**.
- **Correct (merge preserves it):** `totalCount:4`, exactly two `trailing-lambda` entries at lines 26 and 27.

The new field to check is `usageType` — the old tool returned the two lambdas
but never labeled them; the merged tool tags them `"trailing-lambda"`.

Example (U3 — trailing lambdas must be counted):

```bash
curl -s -X POST http://127.0.0.1:51234/api/tools/find-usages \
  -H 'Content-Type: application/json' \
  -d "{\"file_path\":\"$SAMPLE/Usages.kt\",\"line\":10,\"column\":30}" | pp
# expect totalCount == 4, with two usages whose usageType == "trailing-lambda" (lines 26, 27)
```

Example (U4 — limit bounds the output but not the count):

```bash
curl -s -X POST http://127.0.0.1:51234/api/tools/find-usages \
  -H 'Content-Type: application/json' \
  -d "{\"file_path\":\"$SAMPLE/Usages.kt\",\"line\":15,\"column\":5,\"limit\":2}" | pp
# expect count == 2, truncated == true, totalCount == 6, byType == {write:3, read-write:2, read:1}
```

Example (U6 — opt-in comment search adds `comment` usages):

```bash
curl -s -X POST http://127.0.0.1:51234/api/tools/find-usages \
  -H 'Content-Type: application/json' \
  -d "{\"file_path\":\"$SAMPLE/Usages.kt\",\"line\":15,\"column\":5,\"include_comments\":true}" | pp
# expect totalCount == 8, byType includes comment:2 (lines 33, 34); default (no flag) == 6, no comment
```

---

## `:core` / `:app` modules — cross-module resolution

Every input below is a **usage in `:app`**; the tool must resolve **across the
module boundary** to the declaration in `:core`. If any row returns
`success:false` or resolves to something in `:app`, cross-module resolution is
broken.

### Symbol map

Coordinates are 1-based `line:col` at the start of the identifier.

| Symbol | Kind | Declared at (`:core`) | Referenced at (`:app`) |
|---|---|---|---|
| `Repository` | interface | `Repository.kt` 4:11 | `Consumer.kt` 9:15 |
| `load` | interface method | `Repository.kt` 5:9 | `Consumer.kt` 10:18 |
| `InMemoryRepository` | class | `Repository.kt` 9:7 | `Consumer.kt` 9:28 |
| `defaultRepository` | top-level fun | `Repository.kt` 14:5 | `Consumer.kt` 11:13 |
| `CORE_VERSION` | const val | `Repository.kt` 17:11 | `Consumer.kt` 12:20 |

### Cross-module find-declaration matrix

| # | Scenario | Input (`:app` usage) | Expected declaration (`:core`) |
|---|---|---|---|
| X1 | cross-module fun call | `Consumer.kt` 11:13 (`defaultRepository`) | `defaultRepository`, `Repository.kt` line 14 |
| X2 | cross-module type ref | `Consumer.kt` 9:15 (`Repository`) | `Repository` interface, `Repository.kt` line 4 |
| X3 | cross-module ctor call | `Consumer.kt` 9:28 (`InMemoryRepository`) | `InMemoryRepository` (primary ctor), `Repository.kt` line 9 |
| X4 | cross-module member call | `Consumer.kt` 10:18 (`load`) | `load`, `Repository.kt` line 5 |
| X5 | cross-module const (in string template) | `Consumer.kt` 12:20 (`CORE_VERSION`) | `CORE_VERSION`, `Repository.kt` line 17 |

Notes:
- **X4** resolves to the interface member `Repository.load` (line 5), not the
  override `InMemoryRepository.load` (line 10), because `repo`'s static type is
  `Repository` — that's go-to-*declaration* semantics. Finding the override is
  `find-implementation`'s job, not this tool's.
- No negative/not-found rows here — that path is covered by `:audit` D11–D12 and
  doesn't depend on module boundaries.

Example (X1 — must cross into `:core`):

```bash
curl -s -X POST http://127.0.0.1:51234/api/tools/find-declaration \
  -H 'Content-Type: application/json' \
  -d "{\"file_path\":\"$APP/Consumer.kt\",\"line\":11,\"column\":13}" | pp
# expect declaration.file endsWith core/.../Repository.kt AND declaration.line == 14
```

### Bonus: cross-module find-usages (reverse direction)

`find-usages` also crosses modules. From a `:core` declaration, usages in `:app`
must appear:

```bash
# defaultRepository declaration (core) -> usage in app/Consumer.kt:11
curl -s -X POST http://127.0.0.1:51234/api/tools/find-usages \
  -H 'Content-Type: application/json' \
  -d "{\"file_path\":\"$CORE/Repository.kt\",\"line\":14,\"column\":5}" | pp
# expect a usage in app/.../Consumer.kt
```

---

## `module-search` — module dependency graph

`module-search` returns each match's **direct** deps and dependents (identity-keyed,
depth 1), read structurally from `ModuleRootManager.orderEntries` and collapsed to the
Gradle holder identity — no name heuristics. The `:feature-*`/`:testutil` edges make the
graph rich enough to exercise every path. Match names come back as Gradle identity paths.

### Expected dependency matrix

| query | match (identity) | dependencies (`modules_only`) | dependents |
|---|---|---|---|
| `core` | `:core` | — | `:app`, `:feature-impl` |
| `feature-api` | `:feature-api` | — | `:app`, `:feature-impl` |
| `feature-impl` | `:feature-impl` | `:core` (COMPILE), `:feature-api` (COMPILE), `:testutil` (TEST) | `:app` |
| `app` | `:app` | `:core` (COMPILE), `:feature-api` (COMPILE), `:feature-impl` (COMPILE) | — |
| `testutil` | `:testutil` | — | `:feature-impl` |
| `audit` | `:audit` | — | — (isolated) |

What each row guards:
- `:core`, `:feature-api` — **multiple** direct dependents (reverse-edge inversion).
- `:feature-impl` — mixed scopes: two COMPILE deps + one **TEST** dep (`:testutil`), and a
  `src/test` root (with `include_content_roots=true`, `testRoots` non-empty and distinct from
  `sourceRoots`).
- `:app` — a hub with deps but zero dependents.
- `:audit` — isolated: `dependencies: []`, `dependents: []` (not an error).

The `:testutil` **TEST** scope is the reason this pure-`kotlin("jvm")` fixture matters. In
"module per source set" mode a `testImplementation` edge lives on the *test* source-set module,
where its `ModuleOrderEntry.scope` is `COMPILE` (it's a compile dep of the test module's own
classpath). This holds on **both** JVM and Android — a real Android app's `androidTest`/`testFixtures` edges
also report `COMPILE` via `entry.scope` (only a few, e.g. roborazzi, happen to surface `TEST`), so
`entry.scope` is an unreliable "is this test-only" signal on either. `module-search` instead
derives the scope from the source set the edge leaves (a source-set module whose source folders
are all `isTestSource` — which counts `androidTest` and `testFixtures` as test), so `:testutil`
reads `TEST` here and, on a real Android app, test/androidTest/testFixtures-only deps read `TEST`
too. This fixture is the deterministic JVM check; a real Android app is the Android check.

### Matcher (shared with `find-symbols`)

Camel-hump `MinusculeMatcher` over the identity path: `fimpl` → `:feature-impl`, `feat` →
{`:feature-api`, `:feature-impl`}, `xyzzy` → `success:true, totalMatches:0` (empty, not an error).

Verified live on `audit-sample` (2026-07): the full matrix above, including `:testutil` →
`TEST` and the `:feature-impl` `src/test` root split. To re-verify, open `audit-sample` in the
IDE and let the Gradle sync finish first (the module graph comes from the workspace model).

## `get-type-info` — resolved type at a position

Returns the resolved type of the expression/declaration at `(file, line, column)` (K2 Analysis
API): `rendered`/`renderedFqn`, `nullability`, `typeArguments`, `fullyQualifiedName`, and for
function types `functionParameters`/`functionReturnType`. A position with **no value type**
(class/object/typealias/type-parameter name, a keyword, or an unresolvable expression) returns
`success:true, typeInfo:null` with a `reason` — **not** `success:false`, and never a raw exception.

| # | Position | Expected |
|---|---|---|
| TY1 | `Greeter.kt` 6:7 (`class Greeter`) | `success:true, typeInfo:null, reason:"class 'Greeter' has no value type"` (guards the old `ClassCastException`) |
| TY2 | `Repository.kt` 4:11 (`interface Repository`) | `success:true, typeInfo:null, reason:"interface 'Repository' has no value type"` |
| TY3 | `Usages.kt` 15:5 (`var counter: Int`) | `success:true`, `rendered:"Int"`, `kind:"class"`, `NON_NULLABLE` |
| TY4 | `Repository.kt` 17:11 (`const CORE_VERSION: String`) | `success:true`, `rendered:"String"` |
| TY5 | `Usages.kt` 10:30 (`block: () -> Unit`) | `success:true`, `rendered:"() -> Unit"`, `isFunctionType:true`, `functionReturnType:"Unit"` |
| TY6 | `Greeter.kt` 8:9 (`fun greet(): String`) | `success:true`, `rendered:"String"` (declared return type) |
| TY7 | `Greeter.kt` 1:1 (package line) | `success:true, typeInfo:null, reason:"no Kotlin expression or declaration at this position"` |

`kind:"class"` for a function type (TY5) is **correct** — a Kotlin function type is a `FunctionN`
class type; `isFunctionType:true` is the precise signal. Kotlin-only (Java → clean
`success:false "Only Kotlin files supported"`). Nullability (`Foo?` → `NULLABLE`) is exercised on
a real Android app, not this fixture.

## `get-kdoc` — doc comment at a position

Fetches the KDoc/JavaDoc of the declaration at `(file, line, column)`; a usage resolves to its
declaration first, and a **constructor call resolves to its class's doc**. Returns `success:true`
with `hasDoc:false` when the declaration is undocumented, or when the position has no documentable
declaration (then `targetName:null` + a `reason`). `success:false` is reserved for real failures.

| # | Position | Expected |
|---|---|---|
| KD1 | `Repository.kt` 14:5 (`fun defaultRepository`) | `hasDoc:true`, `kind:function`, `fqn:sample.core.defaultRepository`, text "…top-level function declared in :core." |
| KD2 | `Consumer.kt` 13:13 (**cross-module** usage `defaultRepository()`) | resolves to `sample.core.defaultRepository`, same doc (guards cross-module resolve) |
| KD3 | `Consumer.kt` 11:15 (cross-module **type ref** `Repository`) | resolves to `sample.core.Repository`, interface doc |
| KD4 | `Consumer.kt` 11:28 (**constructor call** `InMemoryRepository(...)`) | `kind:class`, `fqn:sample.core.InMemoryRepository`, `hasDoc:true` (class doc — guards the constructor→class redirect) |
| KD5 | `Consumer.kt` 10:5 (undocumented `fun run`) | `success:true`, `targetName:"run"`, `hasDoc:false` (found, no doc) |
| KD6 | `Consumer.kt` 1:1 (package line, no declaration) | `success:true`, `targetName:null`, `hasDoc:false`, `reason:"no documentable declaration at this position"` |

KD4 vs KD5 vs KD6 are the three distinct "no rich answer" shapes: class-via-constructor now *has*
a doc (KD4), a found-but-undocumented declaration (KD5, `targetName` set), and no declaration at
all (KD6, `targetName` null + `reason`) — all `success:true`.

## Resolved issues this fixture guards against (2026-07 audit)

Both `find-symbol` defects below are **fixed** in `find-symbols` (renamed; camel-hump
fuzzy). This fixture now guards the fix:

- ~~`find-symbol` returned every Kotlin symbol twice~~ — the Kotlin stub index and the
  Java `PsiShortNamesCache` light view both surface each Kotlin symbol. **Fixed**: dedup
  by `namedUnwrappedElement` + `(file, name-identifier offset)` (see S/T matrix — `Greeter`
  returns one row).
- ~~`find-symbol` line pointed at leading KDoc/annotations~~ (e.g. `Greeter` reported line 3).
  **Fixed**: the line is anchored to the name identifier (`Greeter` → line 6, `counter` → 15).

`find-symbols` behavior verified live (T1–T8): fuzzy camel-hump matching that mirrors the
IDE's Symbols search (`counter`→{counter, mutateCounter}, `iMR`→InMemoryRepository,
`Greet`→{Greeter, greet, topLevelGreeting}), synthetic file-facade classes (`FooKt`) filtered,
empty result = `success:true, count:0` (not an error), and indexing = `success:false,
retriable:true` (dumb mode, not a silent empty).

## Concurrency — parallel tool calls are not serialized

Every tool call resolves its target through `selectedProject()`, which is
`@Synchronized` on the `PsiMcpServerManager` singleton (PR #36 review asked
whether that serializes parallel tool calls). It does not: the monitor is held
only for the microsecond selection read (`openProjects.filter{}.singleOrNull()`)
and is released before the tool does any work — the real work runs inside
`runReadAction { … }` (IntelliJ's read/write lock, where read actions are
concurrent). This section is the runtime check that proves it on `audit-sample`.

**Why a naive check fails on this fixture.** Individual calls here are ~1 ms, so:

- a *single* call, or firing a handful of `curl`s, can't show serialization —
  the difference only appears in aggregate; and
- spawning N `curl` processes measures process-spawn cost, not the server.

So two things are required for a clean measurement: drive it from **one process
with threads** (blocking socket I/O overlaps, so you measure the server, not `curl`
process-spawn cost), and send enough calls that the batch time dwarfs fixed
per-request overhead. `concurrency-probe.py` does both, probing with `module-search`
(its real `query` param — it walks the module graph inside `runReadAction`, so each
call genuinely reaches `servedProject()` and a read action). Do **not** probe with
the wrong param: `find-symbols` expects `symbol_name`, not `query`, and a wrong
param returns an instant error *before* `servedProject()` and any read action — so
you'd measure only HTTP plumbing, never PSI concurrency:

```bash
python3 test-fixtures/concurrency-probe.py 64
```

**Reading it.** The concurrent batch should finish faster than the sequential one;
if the monitor serialized tool *execution* the two would take about the same time.
It doesn't — the calls overlap inside their read actions. The *size* of the margin
isn't the point and isn't worth quoting as a figure: it simply scales with how much
work each call does. `module-search` cost grows with the number of modules, so on
tiny `audit-sample` each call is sub-millisecond and the margin is small (and noisy
— fixed per-request and client-thread overhead dominate); on a real, many-module
project each call does real work and the gap is obvious. Either way the direction is
the same: concurrent faster than sequential ⇒ not serialized. The probe prints the
result it measured at the end, as evidence the calls did real work.

Any served, indexed project works; `audit-sample` is the standard one because it
is small and deterministic — this check needs a busy server, not specific symbols.

## Security gate — loopback bind + Host/Origin validation

The HTTP transport exposes code-**modification** tools, so it serves only local,
non-browser callers (the rule lives in `RequestGuard`, `HttpServer.kt`):

- the connector binds **`127.0.0.1`** (IPv4 loopback) — unreachable from other machines;
- a request is served only if its `Host` is loopback (`localhost` / `127.0.0.1`,
  case-insensitive) **and** it carries **no `Origin`** header. Any `Origin` is
  rejected with `403` — no browser client is supported.

`curl` is the right tool to verify this: it sends **no `Origin`** by default (so a
plain call looks like a legitimate native client), and `-H` lets you forge an
`Origin` or `Host` to simulate a browser or a DNS-rebind. The browser itself adds
`Origin` to every cross-origin request and page JavaScript cannot remove it, so a
malicious web page cannot reproduce the "no Origin" case for the POST endpoints.

Run these against a server that is up (`/health` returns `OK`). `-w '%{http_code}'`
prints just the status code so allow (`200`) vs deny (`403`) is unambiguous:

```bash
# 1) legit native client (no Origin), loopback host -> ALLOWED
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:51234/health              # 200

# 2) drive-by website (forged Origin) -> REJECTED   <- the key proof
curl -s -o /dev/null -w '%{http_code}\n' -H 'Origin: http://evil.com' \
  http://127.0.0.1:51234/health                                                     # 403

# 3) even a localhost-origin web page -> REJECTED (stricter than an allowlist)
curl -s -o /dev/null -w '%{http_code}\n' -H 'Origin: http://localhost:5173' \
  http://127.0.0.1:51234/health                                                     # 403

# 4) the real mutation path (POST /mcp): no Origin works, forged Origin is rejected
curl -s -X POST http://127.0.0.1:51234/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -c 120                 # JSON tool list
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:51234/mcp \
  -H 'Content-Type: application/json' -H 'Origin: http://evil.com' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'                               # 403

# 5) DNS-rebinding (forged foreign Host while hitting 127.0.0.1) -> REJECTED
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: evil.com' \
  http://127.0.0.1:51234/health                                                     # 403

# 6) case-insensitive host -> ALLOWED
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: LOCALHOST:51234' \
  http://127.0.0.1:51234/health                                                     # 200

# 7) LAN exposure: other machines can't reach it (loopback bind). Replace en0 with
#    your active interface (macOS); expect it to FAIL to connect:
curl -s -o /dev/null -w '%{http_code}\n' --max-time 3 \
  http://$(ipconfig getifaddr en0):51234/health                                     # connection refused / timeout
```

**Reading it.** Test 2 is the discriminator: a build without the gate returns
`200`, the hardened build returns `403`. Test 7 proves the loopback bind — the same
`/health` that works on `127.0.0.1` refuses to connect on your LAN IP. `curl` uses
the `Host` you pass via `-H` even when connecting to `127.0.0.1`, which is what
makes test 5 a valid rebind simulation.
