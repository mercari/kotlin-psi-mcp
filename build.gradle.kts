import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.intellij.platform") version "2.6.0"
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

group = "com.mercari.psi.mcp"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.eclipse.jetty:jetty-server:11.0.20")
    implementation("org.eclipse.jetty:jetty-servlet:11.0.20")

    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin 2.x: the platform + bundled plugins are
    // declared as dependencies (replacing the old `intellij {}` block).
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        bundledPlugin("org.jetbrains.kotlin")          // provides K2 Analysis API
        bundledPlugin("org.jetbrains.plugins.gradle")  // provides GradleConstants
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "262.*"
        }
    }

    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = System.getenv("PUBLISH_TOKEN")
    }

    pluginVerification {
        // One IDE per major branch across the supported range, so a break in any
        // single platform version is caught. Verifying only the compile-time target
        // (251) left 252/253/261 unchecked.
        //
        // Pinned explicitly rather than via recommended()/printProductsReleases:
        // those read data.services.jetbrains.com, which lags the artifact
        // repository badly (it reported 2025.3 as latest while the repo already
        // published 2026.2.x), and recommended() then fails to resolve.
        // Authoritative version list:
        // https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIC/maven-metadata.xml
        //
        // useInstaller = false pulls the repackaged archive instead of the macOS
        // .dmg installer, which is not published for every version.
        //
        // The matrix covers exactly the declared compatibility range (251-262.*).
        //
        // 262 note: 2026.2 removed the Kotlin plugin's K1 sources entirely
        // (intellij-community 9bc28debb2, "[kotlin] remove k1 sources"), which took
        // org.jetbrains.kotlin.idea.refactoring.move.KotlinAwareMoveFilesOrDirectoriesProcessor
        // with it. MoveFileTool now uses the platform's MoveFilesOrDirectoriesProcessor
        // instead (the Kotlin-specific move work happens in the Kotlin plugin's
        // MoveFileHandler extension either way), so 262 is inside the supported range
        // and verified below.
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1", useInstaller = false)
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6", useInstaller = false)
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.3.6.1", useInstaller = false)
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2026.1.5", useInstaller = false)
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2026.2.1", useInstaller = false)

            // Android Studio ships a different platform build than IntelliJ of the
            // same nominal year, with its own Kotlin/Java/Gradle plugin versions —
            // the most likely place for this plugin to break, since it is the
            // primary target. One stable release per platform major:
            //
            //   251 -> 2025.1.4.8  (Narwhal 4 Feature Drop)
            //   252 -> 2025.2.3.9  (Otter 3 Feature Drop)
            //   253 -> 2025.3.4.6  (Panda 4)
            //   261 -> 2026.1.3.7  (Quail 3)
            //
            // Android Studio's first 262-based builds are the Rabbit canaries
            // (2026.2.1 Canary, platformBuild 262.9437) — inside the declared range
            // but not verifiable by download here (see naming-convention note below).
            // Version -> platformBuild
            // mapping comes from https://jb.gg/android-studio-releases-list.xml
            // (the <platformBuild> element); AS resolves through
            // androidStudioInstallers(), so it needs useInstaller = true (default)
            // rather than the archive used for IC above.
            //
            // Only 251 and 252 can be resolved by download. From the Panda (253)
            // line on, Google names installers after the codename
            // (android-studio-panda4-mac_arm.dmg) instead of the version, which
            // this plugin's URL pattern cannot build. The fix landed in plugin
            // 2.12.0 ("handle the new archive name convention"), but 2.12.0+
            // require Gradle 9 — so covering 253/261 by download means upgrading
            // Gradle first. Until then, 261 is covered via the local install below.
            ide(IntelliJPlatformType.AndroidStudio, "2025.1.4.8")
            ide(IntelliJPlatformType.AndroidStudio, "2025.2.3.9")

            // NOTE: `local(<path>)` cannot be used here on plugin 2.6.0. Its helper
            // registers the local IDE into the main INTELLIJ_PLATFORM_DEPENDENCY
            // registry rather than the verifier-only one, so it collides with the
            // intellijIdeaCommunity("2025.1") dependency above and fails with
            // "configuration already contains ... IC-2025.1 (installer)".
            // To verify against a locally installed IDE (e.g. Android Studio 253/261,
            // which cannot be resolved by download — see above), invoke the Plugin
            // Verifier CLI directly; see DEVELOPMENT.md.
        }

        // Fail only on the levels that actually make a plugin unusable. Advisory
        // findings (deprecated / override-only / internal API / scheduled-for-removal)
        // are still printed in full and still written to build/reports/pluginVerifier,
        // they just do not fail the build.
        //
        // For a pre-submission audit, temporarily swap this for
        // `VerifyPluginTask.FailureLevel.ALL` to turn every advisory into a failure.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
        verificationReportsFormats = VerifyPluginTask.VerificationReportsFormats.ALL
    }
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
        kotlinOptions.freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildPlugin {
        archiveBaseName.set("jetbrain-psi-plugin")
    }

    // Ship licensing material inside the plugin jar (lib/*.jar!/META-INF) so the
    // distributed artifact is self-contained for downstream compliance. The MIT
    // LICENSE lives in src/main/resources/META-INF; the third-party notices are
    // copied from the repo-root file so there is a single source of truth.
    processResources {
        from("THIRD-PARTY-NOTICES.md") { into("META-INF") }
    }
}
