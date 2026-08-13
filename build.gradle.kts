import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

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
            untilBuild = "261.*"
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
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
        }
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
