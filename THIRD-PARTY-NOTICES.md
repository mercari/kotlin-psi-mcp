# Third-Party Notices

This plugin is distributed as a ZIP whose `lib/` directory bundles the
third-party libraries listed below. Each remains under its own license, and the
**full text of every applicable license is bundled in the artifact** at
`META-INF/licenses/` (inside the plugin jar) — no license is referenced by URL
alone. This file is a convenience summary, regenerated when the bundled
dependency set changes (see `lib/` in the built plugin ZIP).

The plugin's own code is licensed under the MIT License (see `LICENSE`).

| Component | Version | License (as redistributed) | Full text | Project |
|---|---|---|---|---|
| Kotlin Standard Library (`kotlin-stdlib`) | 2.1.0 | Apache-2.0 | `META-INF/licenses/Apache-2.0.txt` | https://github.com/JetBrains/kotlin |
| JetBrains Java Annotations (`annotations`) | 13.0 | Apache-2.0 | `META-INF/licenses/Apache-2.0.txt` | https://github.com/JetBrains/java-annotations |
| Gson (`com.google.code.gson:gson`) | 2.10.1 | Apache-2.0 | `META-INF/licenses/Apache-2.0.txt` | https://github.com/google/gson |
| SLF4J API (`org.slf4j:slf4j-api`) | 2.0.9 | MIT | `META-INF/licenses/slf4j-api-2.0.9-LICENSE.txt` | https://www.slf4j.org |
| Eclipse Jetty — server, util, io, http, security, servlet | 11.0.20 | Apache-2.0 (dual: EPL-2.0 OR Apache-2.0) | `META-INF/licenses/Apache-2.0.txt` | https://eclipse.dev/jetty |
| Jetty Jakarta Servlet API (`jetty-jakarta-servlet-api`) | 5.0.2 | Mixed (Apache-2.0 + EPL-2.0/GPL-2.0-CPE + CDDL-1.0/GPL-2.0-CPE) | `META-INF/licenses/jetty-jakarta-servlet-api-5.0.2-LICENSE.md` (+ `…-NOTICE.md`) | https://eclipse.dev/jetty |

## Notices

- **Apache-2.0 components** (`kotlin-stdlib`, `annotations`, `gson`, and Jetty
  under the option below) are redistributed with a copy of the Apache License
  2.0 as required by its §4(a); the full text is bundled at
  `META-INF/licenses/Apache-2.0.txt`.
- **Eclipse Jetty** (`server`/`util`/`io`/`http`/`security`/`servlet`) is
  dual-licensed EPL-2.0 OR Apache-2.0 and is redistributed here under the
  **Apache-2.0** option (bundled Apache-2.0 text).
- **Jetty Jakarta Servlet API** is **mixed-license**, not wholly one license:
  its sources include Apache-2.0 classes, the EPL-2.0/GPL-2.0-with-classpath-
  exception servlet API, and CDDL-1.0/GPL-2.0 GlassFish schema notices. Rather
  than elect a single license for the whole JAR, its upstream `LICENSE.md` and
  `NOTICE.md` (the authoritative per-file breakdown) are bundled verbatim at
  `META-INF/licenses/jetty-jakarta-servlet-api-5.0.2-LICENSE.md` and
  `…-5.0.2-NOTICE.md`.
- **SLF4J API** — Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland). The exact
  MIT notice shipped in `slf4j-api-2.0.9.jar` is bundled verbatim at
  `META-INF/licenses/slf4j-api-2.0.9-LICENSE.txt`.

## Canonical license URLs

- Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0
- MIT License — https://opensource.org/license/mit
- Eclipse Public License 2.0 — https://www.eclipse.org/legal/epl-2.0/
- GNU GPL v2 with Classpath Exception — https://www.gnu.org/software/classpath/license.html
- Common Development and Distribution License 1.0 — https://opensource.org/license/cddl-1-0

The IntelliJ Platform APIs the plugin compiles against are provided by the host
IDE at runtime and are **not** redistributed in this artifact.
