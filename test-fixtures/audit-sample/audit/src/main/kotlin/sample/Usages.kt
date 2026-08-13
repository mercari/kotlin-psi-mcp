package sample

// Fixture for find-usages (see test-fixtures/README.md). Concentrates the
// find-usages-specific surface: every usageType (read / write / read-write /
// call / declaration) and the trailing-lambda parameter path that a plain
// ReferencesSearch misses. Kept in its own file so the find-declaration line
// coordinates in Greeter/Main/Overloads stay stable.

/** Last parameter is a function type, so call sites can use trailing-lambda syntax. */
fun withBlock(label: String, block: () -> Unit) {
    block()
}

/** Mutable top-level property: exercises write / read-write / read usage types. */
var counter: Int = 0

fun mutateCounter() {
    counter = 1
    counter += 2
    counter++
    val snapshot = counter
    println(snapshot)
}

fun useTrailingLambda() {
    withBlock("a") { counter = 10 }
    withBlock("b") { counter = 20 }
    withBlock(label = "c", block = {})
}

// include_comments fixture (find-usages). Plain-text mentions below are NOT code
// references — only a comment/word scan finds them:
//   this line mentions counter once,
//   this line mentions counter again and also withBlock.
