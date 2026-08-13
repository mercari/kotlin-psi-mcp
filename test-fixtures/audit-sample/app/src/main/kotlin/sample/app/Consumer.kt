package sample.app

import sample.core.CORE_VERSION
import sample.core.InMemoryRepository
import sample.core.Repository
import sample.core.defaultRepository
import sample.feature.api.Feature
import sample.feature.impl.DefaultFeature

fun run(): String {
    val repo: Repository = InMemoryRepository("app")
    val a = repo.load("x")
    val b = defaultRepository().load("y")
    // codes against :feature-api (Feature), instantiates via :feature-impl (DefaultFeature)
    val feature: Feature = DefaultFeature()
    val c = feature.run()
    return "$a|$b|$c|$CORE_VERSION"
}

// Anonymous implementer of Repository — a find-implementations edge case: an
// anonymous object has no name identifier, so we can observe exactly how the
// tool reports its name and position (fallback to the `object` keyword).
val anonRepository: Repository = object : Repository {
    override fun load(id: String): String = "anon:$id"
}

// Library-interface reference: CharSequence is a stdlib interface with many
// implementers (String, StringBuilder, ...). Gives find-implementations a
// clickable library target to resolve from project source (scope="all").
val seq: CharSequence = "x"

// Scope-probe: a PROJECT class implementing a JVM library interface
// (java.lang.Runnable). find-implementations on the `Runnable` supertype ref
// should return only this class under scope="project", but this class PLUS the
// JDK's own Runnable implementers under scope="all" — the case that reveals
// whether the scope parameter actually filters.
class LocalRunnable : Runnable {
    override fun run() {}
}
