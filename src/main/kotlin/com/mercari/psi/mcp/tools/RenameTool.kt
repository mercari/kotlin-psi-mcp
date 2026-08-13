package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import java.io.File

class RenameTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(RenameTool::class.java)

    data class UsageLocation(
        val file: String,
        val line: Int,
        val column: Int
    )

    data class FileChangeCount(
        val file: String,
        val changes: Int
    )

    data class RenameResponse(
        val success: Boolean,
        val dryRun: Boolean = false,
        val oldName: String? = null,
        val newName: String? = null,
        val kind: String? = null,                        // class / function / property / parameter / other
        val totalChanges: Int = 0,
        val affectedFiles: List<FileChangeCount> = emptyList(),
        val usages: List<UsageLocation> = emptyList(),   // all usage sites
        val warnings: List<String> = emptyList(),        // refactoring conflicts (captured, not blocking)
        val error: String? = null
    )

    override fun getDescription(): String =
        "Rename a Kotlin/Java symbol (class, function, property, parameter, variable) at a file " +
        "position, updating all references across the project. Semantic rename via IntelliJ's " +
        "RenameProcessor (handles imports, overrides, getters/setters). " +
        "Use dry_run=true to preview affected files/usage locations without mutating. " +
        "WARNING: dry_run=false applies the rename across the whole project — verify the target " +
        "with find-declaration or find-usages before using."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path. Relative paths resolved against project root."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line of the symbol to rename (1-based)."
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column of the symbol (1-based)."
            ),
            "new_name" to mapOf(
                "type" to "string",
                "description" to "New identifier for the symbol."
            ),
            "dry_run" to mapOf(
                "type" to "boolean",
                "description" to "If true, return preview (usage locations + affected files) without applying. Default false.",
                "default" to false
            ),
            "search_in_comments" to mapOf(
                "type" to "boolean",
                "description" to "Also rename occurrences inside comments. Default false.",
                "default" to false
            ),
            "search_in_strings" to mapOf(
                "type" to "boolean",
                "description" to "Also rename occurrences inside string literals. Default false.",
                "default" to false
            )
        ),
        "required" to listOf("file_path", "line", "column", "new_name")
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return errorResult("Missing 'file_path'")
            val line = arguments.get("line")?.asInt
                ?: return errorResult("Missing 'line'")
            val column = arguments.get("column")?.asInt
                ?: return errorResult("Missing 'column'")
            val newName = arguments.get("new_name")?.asString
                ?: return errorResult("Missing 'new_name'")
            val dryRun = arguments.get("dry_run")?.asBoolean ?: false
            val searchInComments = arguments.get("search_in_comments")?.asBoolean ?: false
            val searchInStrings = arguments.get("search_in_strings")?.asBoolean ?: false

            gson.toJson(rename(filePath, line, column, newName, dryRun, searchInComments, searchInStrings))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in RenameTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun rename(
        filePath: String,
        line: Int,
        column: Int,
        newName: String,
        dryRun: Boolean,
        searchInComments: Boolean,
        searchInStrings: Boolean
    ): RenameResponse {
        val resolvedPath = resolveAbsolutePath(filePath)

        // Refresh VFS + PSI — force-sync Document to on-disk bytes so stale
        // editor/PSI state (from another AS tab) doesn't win over external edits.
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val lfs = LocalFileSystem.getInstance()
                val project = servedProject()
                val vf = lfs.refreshAndFindFileByPath(resolvedPath)
                if (vf != null) {
                    vf.refresh(false, false)
                    val fdm = FileDocumentManager.getInstance()
                    val doc = fdm.getDocument(vf)
                    if (doc != null) {
                        val onDisk = String(vf.contentsToByteArray(), vf.charset)
                        if (doc.text != onDisk) {
                            fdm.reloadFromDisk(doc)
                            if (doc.text != onDisk) {
                                doc.setText(onDisk)
                            }
                        }
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
            ?: return RenameResponse(success = false, error = NO_PROJECT_MESSAGE)

        // Resolve target declaration inside a read action.
        val target = ApplicationManager.getApplication().runReadAction<PsiElement?> {
            val vf = LocalFileSystem.getInstance().findFileByPath(resolvedPath) ?: return@runReadAction null
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@runReadAction null
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@runReadAction null
            val offset = try {
                document.getLineStartOffset(line - 1) + (column - 1)
            } catch (e: Exception) {
                return@runReadAction null
            }
            if (offset < 0 || offset > document.textLength) return@runReadAction null
            val leaf = psiFile.findElementAt(offset) ?: return@runReadAction null
            findRenameTarget(leaf)
        } ?: return RenameResponse(
            success = false,
            error = "No renameable symbol at line $line, column $column"
        )

        val (oldName, kind) = ApplicationManager.getApplication().runReadAction<Pair<String?, String>> {
            val name = (target as? PsiNamedElement)?.name
            val k = elementKind(target)
            name to k
        }

        if (oldName == null) {
            return RenameResponse(success = false, error = "Target has no name: ${target.javaClass.simpleName}")
        }
        if (oldName == newName) {
            return RenameResponse(success = false, oldName = oldName, newName = newName, kind = kind,
                error = "new_name is identical to current name")
        }

        // Find usages (read action).
        val usages = try {
            ApplicationManager.getApplication().runReadAction<Array<UsageInfo>> {
                val processor = HeadlessRenameProcessor(project, target, newName, searchInComments, searchInStrings)
                processor.findUsages()
            }
        } catch (e: Exception) {
            return RenameResponse(
                success = false, oldName = oldName, newName = newName, kind = kind,
                error = "Failed to find usages: ${e.message}"
            )
        }

        // Build usage locations + per-file counts.
        val (locations, fileCounts) = ApplicationManager.getApplication().runReadAction<Pair<List<UsageLocation>, List<FileChangeCount>>> {
            val locs = mutableListOf<UsageLocation>()
            val fileMap = linkedMapOf<String, Int>()
            for (usage in usages) {
                val element = usage.element ?: continue
                val containingFile = element.containingFile ?: continue
                val vf = containingFile.virtualFile ?: continue
                val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: continue
                val startOffset = usage.navigationOffset.takeIf { it >= 0 }
                    ?: element.textRange?.startOffset
                    ?: continue
                val line = document.getLineNumber(startOffset) + 1
                val col = startOffset - document.getLineStartOffset(line - 1) + 1
                locs.add(UsageLocation(vf.path, line, col))
                fileMap.merge(vf.path, 1, Int::plus)
            }
            // Also count the declaration file.
            (target.containingFile?.virtualFile?.path)?.let { fileMap.merge(it, 1, Int::plus) }
            locs to fileMap.map { (file, n) -> FileChangeCount(file, n) }
        }

        if (dryRun) {
            return RenameResponse(
                success = true,
                dryRun = true,
                oldName = oldName,
                newName = newName,
                kind = kind,
                totalChanges = fileCounts.sumOf { it.changes },
                affectedFiles = fileCounts,
                usages = locations
            )
        }

        // Apply the rename via the headless processor so conflict / automatic-rename dialogs
        // never block the server thread; captured conflicts are surfaced as warnings.
        var runError: String? = null
        val conflicts = mutableListOf<String>()
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val processor = HeadlessRenameProcessor(project, target, newName, searchInComments, searchInStrings)
                processor.setPreviewUsages(false)
                processor.run()
                conflicts.addAll(processor.capturedConflicts)
                // Save modified documents.
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (e: Throwable) {
                runError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        return if (runError != null) {
            RenameResponse(
                success = false, oldName = oldName, newName = newName, kind = kind,
                totalChanges = fileCounts.sumOf { it.changes },
                affectedFiles = fileCounts,
                usages = locations,
                error = runError
            )
        } else {
            RenameResponse(
                success = true,
                dryRun = false,
                oldName = oldName,
                newName = newName,
                kind = kind,
                totalChanges = fileCounts.sumOf { it.changes },
                affectedFiles = fileCounts,
                usages = locations,
                warnings = conflicts
            )
        }
    }

    private fun findRenameTarget(leaf: PsiElement): PsiElement? {
        // If the element resolves via a reference, prefer the referenced declaration.
        leaf.reference?.resolve()?.let { if (isRenameable(it)) return it }

        var current: PsiElement? = leaf
        while (current != null) {
            if (isRenameable(current)) return current
            current.reference?.resolve()?.let { if (isRenameable(it)) return it }
            current = current.parent
        }
        return null
    }

    private fun isRenameable(element: PsiElement): Boolean = when (element) {
        is KtClass, is KtNamedFunction, is KtProperty, is KtObjectDeclaration, is KtParameter,
        is KtNamedDeclaration,
        is PsiClass, is PsiMethod, is PsiNameIdentifierOwner -> (element as? PsiNamedElement)?.name != null
        else -> false
    }

    private fun elementKind(element: PsiElement): String = when (element) {
        is KtClass, is PsiClass -> "class"
        is KtNamedFunction, is PsiMethod -> "function"
        is KtProperty -> "property"
        is KtObjectDeclaration -> "object"
        is KtParameter -> "parameter"
        else -> element.javaClass.simpleName
    }

    private fun errorResult(message: String): String =
        gson.toJson(RenameResponse(success = false, error = message))
}
