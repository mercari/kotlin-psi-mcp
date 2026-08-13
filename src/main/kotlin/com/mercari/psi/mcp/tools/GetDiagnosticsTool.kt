package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.ExternalLanguageAnnotators
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class GetDiagnosticsTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(GetDiagnosticsTool::class.java)

    private val defaultExcludedInspections = setOf(
        "EditorConfigVerifyByCore"
    )

    data class Diagnostic(
        val file: String,
        val message: String,
        val severity: String,
        val line: Int,
        val column: Int,
        val endLine: Int,
        val endColumn: Int,
        val source: String,
        val inspectionId: String?
    )

    data class FileDiagnostics(
        val file: String,
        val diagnostics: List<Diagnostic>,
        val count: Int,
        val error: String? = null
    )

    data class DiagnosticsResponse(
        val success: Boolean,
        val files: List<FileDiagnostics>,
        val totalCount: Int,
        val timestamp: Long
    )

    override fun getDescription(): String =
        "Run IDE inspections and Kotlin compiler analysis on one or more files without opening them in the editor. " +
        "IMPORTANT: Pass ALL recently created/changed files in file_paths for accurate results. " +
        "New files must be included so the indexer can resolve cross-file references (e.g. a testFixtures fake needs its main source set interface included)."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path to a single file to inspect. Relative paths are resolved against the project root."
            ),
            "file_paths" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Array of paths to inspect (use this for multiple files). Relative paths are resolved against the project root."
            ),
            "severity_filter" to mapOf(
                "type" to "string",
                "description" to "Filter by minimum severity: ERROR, WARNING, WEAK_WARNING, INFO. Defaults to WARNING.",
                "enum" to listOf("ERROR", "WARNING", "WEAK_WARNING", "INFO"),
                "default" to "WARNING"
            )
        )
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val paths = mutableListOf<String>()

            arguments.get("file_paths")?.asJsonArray?.forEach { paths.add(it.asString) }
            arguments.get("file_path")?.asString?.let { if (paths.isEmpty()) paths.add(it) }

            if (paths.isEmpty()) return errorResult("Provide 'file_path' or 'file_paths'")

            val severityFilter = arguments.get("severity_filter")?.asString ?: "WARNING"
            val result = runAnalysisMulti(paths, severityFilter)
            gson.toJson(result)
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in GetDiagnosticsTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path

        // Resolve relative paths against the project base path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun runAnalysisMulti(filePaths: List<String>, severityFilter: String): DiagnosticsResponse {
        val resolvedPaths = filePaths.map { resolveAbsolutePath(it) }

        // Force VFS + PSI to reload from disk via write action (must happen before read action)
        // All files in resolvedPaths get refreshed, so the agent should pass every
        // changed file (including cross-module dependencies) for accurate analysis.
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val lfs = LocalFileSystem.getInstance()
                val project = servedProject()

                for (path in resolvedPaths) {
                    val vf = lfs.refreshAndFindFileByPath(path) ?: continue
                    vf.refresh(false, false)
                    FileDocumentManager.getInstance().getDocument(vf)?.let {
                        FileDocumentManager.getInstance().reloadFromDisk(it)
                    }
                    // Reload PSI tree from the refreshed VFS content
                    if (project != null) {
                        PsiManager.getInstance(project).findFile(vf)?.let { psiFile ->
                            PsiManager.getInstance(project).reloadFromDisk(psiFile)
                        }
                    }
                }

                // Commit documents and drop caches so K2 sees fresh content
                if (project != null) {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    PsiManager.getInstance(project).dropPsiCaches()
                }
            }
        }

        return ApplicationManager.getApplication().runReadAction<DiagnosticsResponse> {
            val project = servedProject()
                ?: return@runReadAction DiagnosticsResponse(
                    success = false,
                    files = resolvedPaths.map { FileDiagnostics(it, emptyList(), 0, NO_PROJECT_MESSAGE) },
                    totalCount = 0,
                    timestamp = System.currentTimeMillis()
                )

            val minSeverity = severityOrdinal(severityFilter)
            val fileResults = resolvedPaths.map { filePath ->
                analyzeFile(filePath, project, minSeverity)
            }

            DiagnosticsResponse(
                success = true,
                files = fileResults,
                totalCount = fileResults.sumOf { it.count },
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun analyzeFile(filePath: String, project: Project, minSeverity: Int): FileDiagnostics {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return FileDiagnostics(filePath, emptyList(), 0, "File not found")

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return FileDiagnostics(filePath, emptyList(), 0, "Could not get PSI")

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return FileDiagnostics(filePath, emptyList(), 0, "Could not get document")

        val diagnostics = mutableListOf<Diagnostic>()

        // 1. Kotlin compiler diagnostics via K2 Analysis API
        if (psiFile is KtFile) {
            try {
                analyze(psiFile) {
                    for (diagnostic in psiFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)) {
                        val severity = when (diagnostic.severity) {
                            KaSeverity.ERROR -> "ERROR"
                            KaSeverity.WARNING -> "WARNING"
                            KaSeverity.INFO -> "INFO"
                        }
                        if (severityOrdinal(severity) < minSeverity) continue

                        val range = diagnostic.textRanges.firstOrNull() ?: continue
                        val startLine = document.getLineNumber(range.startOffset) + 1
                        val startCol = range.startOffset - document.getLineStartOffset(startLine - 1) + 1
                        val endLine = document.getLineNumber(range.endOffset) + 1
                        val endCol = range.endOffset - document.getLineStartOffset(endLine - 1) + 1

                        diagnostics.add(
                            Diagnostic(
                                file = filePath,
                                message = diagnostic.defaultMessage,
                                severity = severity,
                                line = startLine,
                                column = startCol,
                                endLine = endLine,
                                endColumn = endCol,
                                source = "kotlin-compiler",
                                inspectionId = diagnostic.factoryName
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                logger.warn("Kotlin analysis failed for $filePath: ${e.message}")
            }
        }

        // 2. IDE inspections
        collectInspectionDiagnostics(filePath, psiFile, document, project, minSeverity, diagnostics)

        // 3. External annotators (detekt, ktlint, etc.) — run programmatically
        collectExternalAnnotatorDiagnostics(filePath, psiFile, document, minSeverity, diagnostics)

        // 4. Cached highlighting daemon results (fallback for any remaining daemon-only results)
        collectDaemonHighlights(filePath, document, project, minSeverity, diagnostics)

        diagnostics.sortWith(
            compareByDescending<Diagnostic> { severityOrdinal(it.severity) }.thenBy { it.line }
        )

        return FileDiagnostics(filePath, diagnostics, diagnostics.size)
    }

    private fun collectInspectionDiagnostics(
        filePath: String,
        psiFile: PsiFile,
        document: Document,
        project: Project,
        minSeverity: Int,
        diagnostics: MutableList<Diagnostic>
    ) {
        val inspectionManager = InspectionManager.getInstance(project)
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val toolWrappers = profile.getAllEnabledInspectionTools(project)

        for (toolWrapper in toolWrappers) {
            val tool = toolWrapper.tool
            if (tool !is LocalInspectionToolWrapper) continue

            val inspection = tool.tool
            if (inspection.id in defaultExcludedInspections) continue
            try {
                val problems = inspection.checkFile(psiFile, inspectionManager, false) ?: continue
                for (problem in problems) {
                    val severity = mapHighlightType(problem.highlightType)
                    if (severityOrdinal(severity) < minSeverity) continue

                    val startOffset = problem.psiElement?.textRange?.startOffset ?: continue
                    val endOffset = problem.psiElement?.textRange?.endOffset ?: startOffset

                    val startLine = document.getLineNumber(startOffset) + 1
                    val startCol = startOffset - document.getLineStartOffset(startLine - 1) + 1
                    val endLine = document.getLineNumber(endOffset) + 1
                    val endCol = endOffset - document.getLineStartOffset(endLine - 1) + 1

                    diagnostics.add(
                        Diagnostic(
                            file = filePath,
                            message = problem.descriptionTemplate ?: "No description",
                            severity = severity,
                            line = startLine,
                            column = startCol,
                            endLine = endLine,
                            endColumn = endCol,
                            source = "inspection",
                            inspectionId = inspection.id
                        )
                    )
                }
            } catch (e: Exception) {
                logger.debug("Inspection ${inspection.id} failed: ${e.message}")
            }
        }
    }

    @Suppress("unchecked_cast")
    private fun collectExternalAnnotatorDiagnostics(
        filePath: String,
        psiFile: PsiFile,
        document: Document,
        minSeverity: Int,
        diagnostics: MutableList<Diagnostic>
    ) {
        try {
            val annotators = ExternalLanguageAnnotators.allForFile(psiFile.language, psiFile)
            if (annotators.isEmpty()) return

            for (annotator in annotators) {
                try {
                    val rawAnnotator = annotator as ExternalAnnotator<Any?, Any?>
                    val info = rawAnnotator.collectInformation(psiFile) ?: continue
                    val result = rawAnnotator.doAnnotate(info) ?: continue
                    val annotatorName = annotator.javaClass.simpleName

                    // Try apply() with AnnotationHolder first
                    try {
                        val session = AnnotationSession(psiFile)
                        // AnnotationHolderImpl is an internal class; construct it reflectively and
                        // use it only through the public AnnotationHolder interface. If it's absent
                        // on some IDE build, this throws and we fall through to the catch below,
                        // which parses the doAnnotate result directly.
                        val holder = Class.forName("com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl")
                            .getConstructor(AnnotationSession::class.java, Boolean::class.javaPrimitiveType)
                            .newInstance(session, true) as AnnotationHolder
                        rawAnnotator.apply(psiFile, result, holder)
                        @Suppress("UNCHECKED_CAST")
                        for (annotation in (holder as Iterable<com.intellij.lang.annotation.Annotation>)) {
                            val message = annotation.message ?: continue
                            addExternalAnnotation(filePath, document, message, annotation.severity,
                                annotation.startOffset, annotation.endOffset, annotatorName, minSeverity, diagnostics)
                        }
                    } catch (applyEx: Exception) {
                        // apply() failed (e.g., detekt needs currentElement) — parse doAnnotate result directly
                        if (result is List<*>) {
                            for (item in result) {
                                extractFindingViaReflection(filePath, document, item ?: continue,
                                    annotatorName, minSeverity, diagnostics)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("External annotator ${annotator.javaClass.simpleName} failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.debug("External annotator collection failed for $filePath: ${e.message}")
        }
    }

    private fun addExternalAnnotation(
        filePath: String, document: Document, message: String,
        severity: HighlightSeverity, startOffset: Int, endOffset: Int,
        annotatorName: String, minSeverity: Int, diagnostics: MutableList<Diagnostic>
    ) {
        val severityStr = when {
            severity >= HighlightSeverity.ERROR -> "ERROR"
            severity >= HighlightSeverity.WARNING -> "WARNING"
            severity >= HighlightSeverity.WEAK_WARNING -> "WEAK_WARNING"
            else -> "INFO"
        }
        if (severityOrdinal(severityStr) < minSeverity) return

        val startLine = document.getLineNumber(startOffset) + 1
        val startCol = startOffset - document.getLineStartOffset(startLine - 1) + 1
        val endLine = document.getLineNumber(endOffset) + 1
        val endCol = endOffset - document.getLineStartOffset(endLine - 1) + 1

        diagnostics.add(Diagnostic(
            file = filePath, message = message, severity = severityStr,
            line = startLine, column = startCol, endLine = endLine, endColumn = endCol,
            source = "external-annotator", inspectionId = annotatorName
        ))
    }

    private fun extractFindingViaReflection(
        filePath: String, document: Document, item: Any,
        annotatorName: String, minSeverity: Int, diagnostics: MutableList<Diagnostic>
    ) {
        try {
            // Get message: try messageOrDescription() first (includes rule name), then getMessage()
            val message = tryGetProperty<String>(item, "messageOrDescription")
                ?: tryGetProperty<String>(item, "getMessage")
                ?: item.toString()

            // Get severity from detekt finding
            val severityObj = tryGetProperty<Any>(item, "getSeverity")
            val severityStr = when (severityObj?.toString()?.lowercase()) {
                "error" -> "ERROR"
                "warning" -> "WARNING"
                "info" -> "INFO"
                else -> "WARNING"
            }
            if (severityOrdinal(severityStr) < minSeverity) return

            // Get location → getText() returns TextLocation with start/end offsets
            val location = tryGetProperty<Any>(item, "getLocation")
            val textLocation = if (location != null) tryGetProperty<Any>(location, "getText") else null

            if (textLocation != null) {
                // TextLocation has getStart() and getEnd() returning Int offsets
                val startOffset = tryGetProperty<Int>(textLocation, "getStart") ?: return
                val endOffset = tryGetProperty<Int>(textLocation, "getEnd") ?: startOffset

                val startLine = document.getLineNumber(startOffset) + 1
                val startCol = startOffset - document.getLineStartOffset(startLine - 1) + 1
                val endLine = document.getLineNumber(endOffset) + 1
                val endCol = endOffset - document.getLineStartOffset(endLine - 1) + 1

                // Get rule ID from issue
                val issue = tryGetProperty<Any>(item, "getIssue")
                val ruleId = if (issue != null) tryGetProperty<String>(issue, "getId") else null

                diagnostics.add(Diagnostic(
                    file = filePath,
                    message = "detekt - $message",
                    severity = severityStr,
                    line = startLine, column = startCol,
                    endLine = endLine, endColumn = endCol,
                    source = "external-annotator",
                    inspectionId = ruleId ?: annotatorName
                ))
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract finding from ${item.javaClass.simpleName}: ${e.message}")
        }
    }

    @Suppress("unchecked_cast")
    private fun <T> tryGetProperty(obj: Any, name: String): T? {
        return try {
            val method = obj.javaClass.methods.find { it.name == name && it.parameterCount == 0 }
            method?.invoke(obj) as? T
        } catch (e: Exception) {
            try {
                val field = obj.javaClass.declaredFields.find { it.name == name }
                field?.isAccessible = true
                field?.get(obj) as? T
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun collectDaemonHighlights(
        filePath: String,
        document: Document,
        project: Project,
        minSeverity: Int,
        diagnostics: MutableList<Diagnostic>
    ) {
        // Collect messages already reported by K2/inspections to avoid duplicates
        val existingMessages = diagnostics.map { "${it.line}:${it.message}" }.toSet()

        try {
            DaemonCodeAnalyzerEx.processHighlights(
                document, project, null, 0, document.textLength
            ) { info ->
                val severity = when {
                    info.severity >= HighlightSeverity.ERROR -> "ERROR"
                    info.severity >= HighlightSeverity.WARNING -> "WARNING"
                    info.severity >= HighlightSeverity.WEAK_WARNING -> "WEAK_WARNING"
                    else -> "INFO"
                }
                if (severityOrdinal(severity) >= minSeverity) {
                    val description = info.description ?: return@processHighlights true
                    val startLine = document.getLineNumber(info.startOffset) + 1
                    val startCol = info.startOffset - document.getLineStartOffset(startLine - 1) + 1
                    val endLine = document.getLineNumber(info.endOffset) + 1
                    val endCol = info.endOffset - document.getLineStartOffset(endLine - 1) + 1

                    // Skip duplicates already found by K2 or inspections
                    val key = "${startLine}:${description}"
                    if (key !in existingMessages) {
                        diagnostics.add(
                            Diagnostic(
                                file = filePath,
                                message = description,
                                severity = severity,
                                line = startLine,
                                column = startCol,
                                endLine = endLine,
                                endColumn = endCol,
                                source = "daemon",
                                inspectionId = info.inspectionToolId
                            )
                        )
                    }
                }
                true // continue processing
            }
        } catch (e: Exception) {
            logger.debug("Daemon highlight collection failed for $filePath: ${e.message}")
        }
    }

    private fun mapHighlightType(type: ProblemHighlightType): String {
        return when (type) {
            ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> "ERROR"
            ProblemHighlightType.WARNING, ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
            ProblemHighlightType.WEAK_WARNING -> "WEAK_WARNING"
            else -> "INFO"
        }
    }

    private fun severityOrdinal(severity: String): Int {
        return when (severity) {
            "ERROR" -> 3
            "WARNING" -> 2
            "WEAK_WARNING" -> 1
            "INFO" -> 0
            else -> 0
        }
    }

    private fun errorResult(message: String): String = gson.toJson(
        DiagnosticsResponse(
            success = false,
            files = emptyList(),
            totalCount = 0,
            timestamp = System.currentTimeMillis()
        )
    )
}
