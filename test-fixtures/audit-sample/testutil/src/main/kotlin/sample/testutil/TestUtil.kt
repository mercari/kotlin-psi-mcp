package sample.testutil

/**
 * Test-only helper. Consumed by :feature-impl via `testImplementation`, so :testutil is a
 * TEST-scope dependency of :feature-impl — the case that verifies module-search reports
 * scope="TEST" (distinct from the COMPILE edges).
 */
fun fixtureLabel(): String = "fixture"
