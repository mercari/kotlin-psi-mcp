plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":feature-api"))
    implementation(project(":core"))
    // Test-only edge: makes :testutil a TEST-scope dependency of :feature-impl.
    testImplementation(project(":testutil"))
}
