package sample.feature.impl

import sample.testutil.fixtureLabel

/**
 * Lives in src/test → creates a test source root on :feature-impl (verifies module-search splits
 * sourceRoots vs testRoots structurally) and exercises the TEST-scope dep on :testutil.
 */
fun describe(): String = "${fixtureLabel()}:${DefaultFeature().run()}"
