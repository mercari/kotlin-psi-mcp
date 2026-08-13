package sample.feature.impl

import sample.core.Repository
import sample.core.defaultRepository
import sample.feature.api.Feature

/**
 * Impl-module target: implements :feature-api's [Feature] and consumes :core.
 * Gives :feature-impl two COMPILE deps (:feature-api, :core) and makes it a dependent of both.
 */
class DefaultFeature(private val repo: Repository = defaultRepository()) : Feature {
    override fun run(): String = repo.load("feature")
}
