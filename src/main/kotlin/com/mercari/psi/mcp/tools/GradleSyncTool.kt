package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GradleSyncTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(GradleSyncTool::class.java)

    data class SyncResponse(
        val success: Boolean,
        val message: String,
        val timestamp: Long
    )

    override fun getDescription(): String =
        "Trigger a Gradle sync in Android Studio. Required after creating new modules or changing build.gradle.kts dependencies. " +
        "Blocks until sync completes. This updates the IDE's project model so get-diagnostics can resolve references in new modules."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "timeout_seconds" to mapOf(
                "type" to "number",
                "description" to "Maximum time to wait for sync completion in seconds. Defaults to 300 (5 minutes).",
                "default" to 300
            )
        )
    )

    override fun execute(arguments: JsonObject): String {
        return try {
            val timeoutSeconds = arguments.get("timeout_seconds")?.asLong ?: 300
            val result = triggerSync(timeoutSeconds)
            gson.toJson(result)
        } catch (e: Exception) {
            logger.error("Error in GradleSyncTool", e)
            gson.toJson(SyncResponse(false, "Internal error: ${e.message}", System.currentTimeMillis()))
        }
    }

    private fun triggerSync(timeoutSeconds: Long): SyncResponse {
        val project = servedProject()
            ?: return SyncResponse(false, NO_PROJECT_MESSAGE, System.currentTimeMillis())

        val projectPath = project.basePath
            ?: return SyncResponse(false, "Could not determine project path", System.currentTimeMillis())

        val latch = CountDownLatch(1)
        var syncSuccess = false
        var syncError: String? = null

        ApplicationManager.getApplication().invokeLater {
            try {
                ExternalSystemUtil.refreshProject(
                    projectPath,
                    com.intellij.openapi.externalSystem.importing.ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                        .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                        .callback(object : com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback {
                            override fun onSuccess(externalProject: com.intellij.openapi.externalSystem.model.DataNode<com.intellij.openapi.externalSystem.model.project.ProjectData>?) {
                                syncSuccess = true
                                latch.countDown()
                            }

                            override fun onFailure(errorMessage: String, errorDetails: String?) {
                                syncError = errorMessage
                                latch.countDown()
                            }
                        })
                )
            } catch (e: Exception) {
                syncError = e.message
                latch.countDown()
            }
        }

        val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)

        return when {
            !completed -> SyncResponse(false, "Sync timed out after ${timeoutSeconds}s", System.currentTimeMillis())
            syncSuccess -> SyncResponse(true, "Gradle sync completed successfully", System.currentTimeMillis())
            else -> SyncResponse(false, "Sync failed: ${syncError ?: "unknown error"}", System.currentTimeMillis())
        }
    }
}
