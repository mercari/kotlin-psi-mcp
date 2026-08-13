package sample.core

/** Cross-module target: an interface declared in :core, consumed from :app. */
interface Repository {
    fun load(id: String): String
}

/** Cross-module target: a concrete class declared in :core. */
class InMemoryRepository(val label: String) : Repository {
    override fun load(id: String): String = "$label:$id"
}

/** Cross-module target: a top-level function declared in :core. */
fun defaultRepository(): Repository = InMemoryRepository("default")

/** Cross-module target: a top-level constant declared in :core. */
const val CORE_VERSION: String = "1.0.0"
