package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.io.File

class ApplyQuickFixTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(ApplyQuickFixTool::class.java)

    private val defaultExcludedInspections = setOf(
        "EditorConfigVerifyByCore"
    )

    // Fix families that adjust IDE settings rather than code — noise for agents.
    private val settingsFixFamilyPrefixes = listOf(
        "Enable 'Settings",
        "Disable inspection",
        "Suppress",
        "Edit inspection",
        "Edit settings",
        "Configure",
        "Go to",
        "Show",
        "Open",
        "Inject language",
        "Hide",
        "Copy"
    )

    data class AvailableFix(
        val fixName: String,
        val text: String,
        val line: Int,
        val column: Int,
        val diagnosticMessage: String,
        val inspectionId: String?,
        val source: String
    )

    data class QuickFixResponse(
        val success: Boolean,
        val mode: String,
        val availableFixes: List<AvailableFix> = emptyList(),
        val appliedCount: Int = 0,
        val appliedFixes: List<AvailableFix> = emptyList(),
        val fileContent: String? = null,
        val error: String? = null
    )

    private data class FixCandidate(
        val available: AvailableFix,
        val fix: LocalQuickFix,
        val descriptor: ProblemDescriptor
    )

    override fun getDescription(): String =
        "List or apply IDE quick-fixes for a file. Without fix_name, lists available fixes. " +
        "With fix_name, applies the first matching fix (or all matching if apply_all_matching=true). " +
        "Covers inspection-registered LocalQuickFixes: unused imports, simplify expression, " +
        "convert var/val, redundant code, etc. Kotlin K2 compiler fixes (e.g. 'add missing import' " +
        "for unresolved references) are NOT covered — use text edits for those. " +
        "IDE-settings fixes (Enable/Disable/Suppress/Configure/...) are hidden unless " +
        "include_settings_fixes=true."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path. Relative paths resolved against project root."
            ),
            "fix_name" to mapOf(
                "type" to "string",
                "description" to "Fix family name from list mode. Omit to list."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Filter to a line (1-based)."
            ),
            "inspection_id" to mapOf(
                "type" to "string",
                "description" to "Filter by inspection/rule id (e.g. 'UnusedImport')."
            ),
            "apply_all_matching" to mapOf(
                "type" to "boolean",
                "description" to "If true, apply to all matches. Default: false (first only).",
                "default" to false
            ),
            "include_settings_fixes" to mapOf(
                "type" to "boolean",
                "description" to "Include fixes that modify IDE settings. Default: false.",
                "default" to false
            )
        ),
        "required" to listOf("file_path")
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return errorResult("Missing required 'file_path'")

            val fixName = arguments.get("fix_name")?.asString
            val line = arguments.get("line")?.asInt
            val inspectionId = arguments.get("inspection_id")?.asString
            val applyAll = arguments.get("apply_all_matching")?.asBoolean ?: false
            val includeSettings = arguments.get("include_settings_fixes")?.asBoolean ?: false

            val result = run(filePath, fixName, line, inspectionId, applyAll, includeSettings)
            gson.toJson(result)
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in ApplyQuickFixTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun run(
        filePath: String,
        fixName: String?,
        lineFilter: Int?,
        inspectionIdFilter: String?,
        applyAll: Boolean,
        includeSettings: Boolean
    ): QuickFixResponse {
        val resolvedPath = resolveAbsolutePath(filePath)

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
                        PsiManager.getInstance(project).dropPsiCaches()
                    }
                }
            }
        }

        val project = servedProject()
            ?: return QuickFixResponse(success = false, mode = "list", error = NO_PROJECT_MESSAGE)


        val candidates = ApplicationManager.getApplication().runReadAction<List<FixCandidate>> {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                ?: return@runReadAction emptyList()
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@runReadAction emptyList()
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadAction emptyList()

            val all = collectInspectionCandidates(psiFile, document, project, lineFilter, inspectionIdFilter)
            val filtered = if (includeSettings) all else all.filter { !isSettingsFix(it.available.fixName) }
            filtered.distinctByKey()
        }

        if (fixName == null) {
            return QuickFixResponse(
                success = true,
                mode = "list",
                availableFixes = candidates.map { it.available }
            )
        }

        val matching = candidates.filter { it.available.fixName == fixName }
        if (matching.isEmpty()) {
            return QuickFixResponse(
                success = false,
                mode = "apply",
                error = "No fix with name '$fixName' found" +
                    (lineFilter?.let { " at line $it" } ?: "") +
                    (inspectionIdFilter?.let { " for inspection '$it'" } ?: "")
            )
        }

        val toApply = if (applyAll) matching else listOf(matching.first())
        return applyFixes(resolvedPath, project, toApply)
    }

    private fun collectInspectionCandidates(
        psiFile: PsiFile,
        document: Document,
        project: Project,
        lineFilter: Int?,
        inspectionIdFilter: String?
    ): List<FixCandidate> {
        val candidates = mutableListOf<FixCandidate>()
        val inspectionManager = InspectionManager.getInstance(project)
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile

        for (toolWrapper in profile.getAllEnabledInspectionTools(project)) {
            val tool = toolWrapper.tool
            if (tool !is LocalInspectionToolWrapper) continue
            val inspection = tool.tool
            if (inspection.id in defaultExcludedInspections) continue
            if (inspectionIdFilter != null && inspection.id != inspectionIdFilter) continue

            try {
                val problems = inspection.checkFile(psiFile, inspectionManager, false) ?: continue
                for (problem in problems) {
                    val startOffset = problem.psiElement?.textRange?.startOffset ?: continue
                    val line = document.getLineNumber(startOffset) + 1
                    if (lineFilter != null && line != lineFilter) continue
                    val column = startOffset - document.getLineStartOffset(line - 1) + 1

                    val fixes = problem.fixes ?: continue
                    for (fix in fixes) {
                        if (fix !is LocalQuickFix) continue
                        candidates.add(
                            FixCandidate(
                                available = AvailableFix(
                                    fixName = fix.familyName,
                                    text = fix.name,
                                    line = line,
                                    column = column,
                                    diagnosticMessage = problem.descriptionTemplate ?: "",
                                    inspectionId = inspection.id,
                                    source = "inspection"
                                ),
                                fix = fix,
                                descriptor = problem
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                logger.debug("Inspection ${inspection.id} failed: ${e.message}")
            }
        }
        return candidates
    }

    private fun applyFixes(
        filePath: String,
        project: Project,
        candidates: List<FixCandidate>
    ): QuickFixResponse {
        val applied = mutableListOf<AvailableFix>()
        val failures = mutableListOf<String>()

        ApplicationManager.getApplication().invokeAndWait {
            CommandProcessor.getInstance().executeCommand(project, {
                ApplicationManager.getApplication().runWriteAction {
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                    val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }

                    for (candidate in candidates) {
                        try {
                            if (candidate.descriptor.psiElement?.isValid != true) {
                                failures.add("'${candidate.available.fixName}' @L${candidate.available.line}: PSI invalidated")
                                continue
                            }
                            candidate.fix.applyFix(project, candidate.descriptor)
                            applied.add(candidate.available)
                        } catch (e: Exception) {
                            logger.warn("Fix '${candidate.available.fixName}' failed: ${e.message}")
                            failures.add("'${candidate.available.fixName}' @L${candidate.available.line}: ${e.message}")
                        }
                    }

                    if (document != null) {
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                        FileDocumentManager.getInstance().saveDocument(document)
                    }
                }
            }, "Apply Quick Fix", null)
        }

        val newContent = ApplicationManager.getApplication().runReadAction<String?> {
            val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@runReadAction null
            FileDocumentManager.getInstance().getDocument(vf)?.text
        }

        return QuickFixResponse(
            success = applied.isNotEmpty(),
            mode = "apply",
            appliedCount = applied.size,
            appliedFixes = applied,
            fileContent = newContent,
            error = when {
                applied.isEmpty() && failures.isNotEmpty() -> "All fixes failed: ${failures.joinToString("; ")}"
                applied.isEmpty() -> "No fixes applied"
                failures.isNotEmpty() -> "Applied ${applied.size} but ${failures.size} failed: ${failures.joinToString("; ")}"
                else -> null
            }
        )
    }

    private fun isSettingsFix(familyName: String): Boolean =
        settingsFixFamilyPrefixes.any { familyName.startsWith(it, ignoreCase = true) }

    private fun List<FixCandidate>.distinctByKey(): List<FixCandidate> {
        val seen = mutableSetOf<String>()
        return filter {
            seen.add("${it.available.line}:${it.available.column}:${it.available.fixName}")
        }
    }

    private fun errorResult(message: String): String =
        gson.toJson(QuickFixResponse(success = false, mode = "list", error = message))
}
