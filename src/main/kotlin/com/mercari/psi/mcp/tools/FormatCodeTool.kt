package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.openapi.command.WriteCommandAction
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.intellij.openapi.application.ModalityState

/**
 * Format code according to project code style settings.
 *
 * This is the FIRST PSI-edit tool - a proof of concept that validates:
 * 1. Threading model (HTTP thread → EDT coordination)
 * 2. Write operations (WriteCommandAction)
 * 3. Timeout handling
 * 4. Error propagation
 *
 * This is the beginning of the AI + PSI coding era for Android development!
 */
class FormatCodeTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(FormatCodeTool::class.java)

    data class FormatRequest(
        val file_path: String,
        val start_line: Int? = null,
        val end_line: Int? = null
    )

    data class FormatResponse(
        val success: Boolean,
        val formatted: Boolean,
        val file_path: String?,
        val lines_formatted: String?,
        val timestamp: Long,
        val error: String? = null
    )

    override fun getDescription(): String =
        "✨ CODE FORMATTER: Format code according to project style settings. " +
                "Can format entire file or specific line range. " +
                "Uses IntelliJ's CodeStyleManager for perfect formatting. " +
                "This is the FIRST PSI-edit tool - proof of concept for AI + PSI architecture!"

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path. Relative paths resolved against project root."
            ),
            "start_line" to mapOf(
                "type" to "integer",
                "description" to "Optional: Start line (1-based) for range formatting. If omitted, formats entire file."
            ),
            "end_line" to mapOf(
                "type" to "integer",
                "description" to "Optional: End line (1-based) for range formatting. If omitted, formats entire file."
            )
        ),
        "required" to listOf("file_path")
    )

    override fun execute(arguments: JsonObject): String {
        noProjectError(servedProject())?.let { return it }
        return try {
            val request = parseRequest(arguments)
            logger.info(
                "🎨 Formatting code: ${request.file_path}" +
                        if (request.start_line != null) " (lines ${request.start_line}-${request.end_line})" else " (entire file)"
            )

            val response = formatFile(request)

            if (response.success) {
                logger.info("✅ Formatting completed successfully")
            } else {
                logger.warn("❌ Formatting failed: ${response.error}")
            }

            gson.toJson(response)

        } catch (e: Exception) {
            logger.error("❌ Error in FormatCodeTool", e)
            gson.toJson(
                FormatResponse(
                    success = false,
                    formatted = false,
                    file_path = null,
                    lines_formatted = null,
                    timestamp = System.currentTimeMillis(),
                    error = e.message ?: "Unknown error"
                )
            )
        }
    }

    private fun formatFile(request: FormatRequest): FormatResponse {
        val result = CompletableFuture<FormatResponse>()

        // Log threading info for debugging
        logger.info("📍 Current thread: ${Thread.currentThread().name}")
        logger.info("📍 Is EDT: ${ApplicationManager.getApplication().isDispatchThread}")

        // Schedule on EDT with proper modality
        ApplicationManager.getApplication().invokeLater(
            {
                try {
                    logger.info("📍 Now executing on EDT: ${Thread.currentThread().name}")

                    // Find project first (outside WriteCommandAction)
                    val project = servedProject()
                        ?: throw IllegalStateException(NO_PROJECT_MESSAGE)

                    logger.info("📍 Project: ${project.name}")

                    // Execute write operation
                    WriteCommandAction.runWriteCommandAction(
                        project,
                        "Format Code",
                        null,
                        Runnable {
                            logger.info("📍 Inside WriteCommandAction")

                            // Verify we have write access
                            ApplicationManager.getApplication().assertWriteAccessAllowed()

                            // Find file
                            val virtualFile =
                                LocalFileSystem.getInstance().findFileByPath(request.file_path)
                                    ?: throw IllegalArgumentException("File not found: ${request.file_path}")

                            logger.info("📍 Virtual file: ${virtualFile.name}")

                            // Get PSI file
                            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                                ?: throw IllegalArgumentException("Could not get PSI for file: ${request.file_path}")

                            logger.info("📍 PSI file: ${psiFile.name} (${psiFile.javaClass.simpleName})")

                            // Get code style manager
                            val codeStyleManager = CodeStyleManager.getInstance(project)

                            // Format code
                            if (request.start_line != null && request.end_line != null) {
                                // Format range
                                val document =
                                    PsiDocumentManager.getInstance(project).getDocument(psiFile)
                                        ?: throw IllegalArgumentException("Could not get document for file")

                                val startOffset =
                                    document.getLineStartOffset(request.start_line - 1)
                                val endOffset = document.getLineEndOffset(request.end_line - 1)

                                logger.info("📍 Formatting range: lines ${request.start_line}-${request.end_line} (offsets $startOffset-$endOffset)")

                                codeStyleManager.reformatText(psiFile, startOffset, endOffset)

                                result.complete(
                                    FormatResponse(
                                        success = true,
                                        formatted = true,
                                        file_path = request.file_path,
                                        lines_formatted = "lines ${request.start_line}-${request.end_line}",
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            } else {
                                // Format entire file
                                logger.info("📍 Formatting entire file")

                                codeStyleManager.reformat(psiFile)

                                result.complete(
                                    FormatResponse(
                                        success = true,
                                        formatted = true,
                                        file_path = request.file_path,
                                        lines_formatted = "entire file",
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            }

                            logger.info("✅ Formatting operation completed")
                        })

                } catch (e: Exception) {
                    logger.error("❌ Formatting failed", e)
                    result.complete(
                        FormatResponse(
                            success = false,
                            formatted = false,
                            file_path = request.file_path,
                            lines_formatted = null,
                            timestamp = System.currentTimeMillis(),
                            error = e.message ?: "Unknown error"
                        )
                    )
                }
            },
            ModalityState.defaultModalityState()
        )

        // Wait for EDT to complete with timeout
        return try {
            logger.info("⏳ Waiting for EDT to complete (timeout: 30 seconds)...")
            val response = result.get(30, TimeUnit.SECONDS)
            logger.info("✅ EDT completed successfully")
            response
        } catch (e: TimeoutException) {
            logger.error("⏰ Formatting timed out after 30 seconds")
            FormatResponse(
                success = false,
                formatted = false,
                file_path = request.file_path,
                lines_formatted = null,
                timestamp = System.currentTimeMillis(),
                error = "Operation timed out after 30 seconds. IDE may be busy or blocked by a modal dialog."
            )
        } catch (e: Exception) {
            logger.error("❌ Error waiting for EDT", e)
            FormatResponse(
                success = false,
                formatted = false,
                file_path = request.file_path,
                lines_formatted = null,
                timestamp = System.currentTimeMillis(),
                error = e.cause?.message ?: e.message ?: "Unknown error"
            )
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun parseRequest(arguments: JsonObject): FormatRequest {
        val filePath = arguments.get("file_path")?.asString
            ?: throw IllegalArgumentException("Missing 'file_path' parameter")

        val startLine = arguments.get("start_line")?.asInt
        val endLine = arguments.get("end_line")?.asInt

        // Validate range if provided
        if ((startLine != null && endLine == null) || (startLine == null && endLine != null)) {
            throw IllegalArgumentException("Both start_line and end_line must be provided for range formatting")
        }

        if (startLine != null && endLine != null && startLine > endLine) {
            throw IllegalArgumentException("start_line ($startLine) must be less than or equal to end_line ($endLine)")
        }

        return FormatRequest(
            file_path = resolveAbsolutePath(filePath),
            start_line = startLine,
            end_line = endLine
        )
    }
}
