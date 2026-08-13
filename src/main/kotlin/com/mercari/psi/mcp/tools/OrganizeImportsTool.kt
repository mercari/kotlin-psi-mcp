package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.io.File

class OrganizeImportsTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(OrganizeImportsTool::class.java)

    data class OrganizeImportsResponse(
        val success: Boolean,
        val changed: Boolean,
        val fileContent: String? = null,
        val error: String? = null
    )

    override fun getDescription(): String =
        "Organize imports in a file: remove unused imports, sort and group according to the " +
        "project's code style settings. Uses the same OptimizeImportsProcessor as the IDE's " +
        "'Optimize Imports' action (Ctrl+Alt+O). Works for Kotlin and Java. " +
        "Does NOT add missing imports — use text edits or apply-quick-fix for that."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path. Relative paths resolved against project root."
            )
        ),
        "required" to listOf("file_path")
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return errorResult("Missing required 'file_path'")
            gson.toJson(organize(filePath))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in OrganizeImportsTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun organize(filePath: String): OrganizeImportsResponse {
        val resolvedPath = resolveAbsolutePath(filePath)

        // Refresh VFS + reload PSI to ensure we're working with on-disk content.
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val lfs = LocalFileSystem.getInstance()
                val project = servedProject()
                val vf = lfs.refreshAndFindFileByPath(resolvedPath)
                if (vf != null) {
                    vf.refresh(false, false)
                    FileDocumentManager.getInstance().getDocument(vf)?.let {
                        FileDocumentManager.getInstance().reloadFromDisk(it)
                    }
                    if (project != null) {
                        PsiManager.getInstance(project).findFile(vf)?.let { psi ->
                            PsiManager.getInstance(project).reloadFromDisk(psi)
                        }
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                    }
                }
            }
        }

        val project = servedProject()
            ?: return OrganizeImportsResponse(success = false, changed = false, error = NO_PROJECT_MESSAGE)


        // Read "before" content.
        val before = ApplicationManager.getApplication().runReadAction<String?> {
            val vf = LocalFileSystem.getInstance().findFileByPath(resolvedPath) ?: return@runReadAction null
            FileDocumentManager.getInstance().getDocument(vf)?.text
        } ?: return OrganizeImportsResponse(success = false, changed = false, error = "File not found: $resolvedPath")

        var runError: String? = null

        ApplicationManager.getApplication().invokeAndWait {
            CommandProcessor.getInstance().executeCommand(project, {
                ApplicationManager.getApplication().runWriteAction {
                    try {
                        val vf = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                            ?: throw IllegalStateException("File not found after refresh")
                        val psiFile = PsiManager.getInstance(project).findFile(vf)
                            ?: throw IllegalStateException("Could not get PSI for file")

                        // Use the language-specific ImportOptimizer directly — synchronous.
                        val optimizers = LanguageImportStatements.INSTANCE.allForLanguage(psiFile.language)
                        if (optimizers.isEmpty()) {
                            runError = "No import optimizer registered for ${psiFile.language.id}"
                            return@runWriteAction
                        }
                        var ran = false
                        for (optimizer in optimizers) {
                            if (!optimizer.supports(psiFile)) continue
                            optimizer.processFile(psiFile).run()
                            ran = true
                        }
                        if (!ran) {
                            runError = "Import optimizers found but none supported this file"
                            return@runWriteAction
                        }

                        // Commit + save so the file on disk reflects the change.
                        val doc = FileDocumentManager.getInstance().getDocument(vf)
                        if (doc != null) {
                            PsiDocumentManager.getInstance(project).commitDocument(doc)
                            FileDocumentManager.getInstance().saveDocument(doc)
                        }
                    } catch (e: Exception) {
                        runError = e.message ?: e.javaClass.simpleName
                    }
                }
            }, "Organize Imports", null)
        }

        if (runError != null) {
            return OrganizeImportsResponse(success = false, changed = false, error = runError)
        }

        // Read "after" content.
        val after = ApplicationManager.getApplication().runReadAction<String?> {
            val vf = LocalFileSystem.getInstance().findFileByPath(resolvedPath) ?: return@runReadAction null
            FileDocumentManager.getInstance().getDocument(vf)?.text
        }

        return OrganizeImportsResponse(
            success = true,
            changed = before != after,
            fileContent = after
        )
    }

    private fun errorResult(message: String): String =
        gson.toJson(OrganizeImportsResponse(success = false, changed = false, error = message))
}
