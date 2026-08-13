package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import java.io.File

class SafeDeleteTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(SafeDeleteTool::class.java)

    data class UsageLocation(
        val file: String,
        val line: Int,
        val column: Int
    )

    data class SafeDeleteResponse(
        val success: Boolean,
        val dryRun: Boolean = false,
        val name: String? = null,
        val kind: String? = null,                        // class / function / property / parameter / object
        val declarationFile: String? = null,
        val usageCount: Int = 0,
        val usages: List<UsageLocation> = emptyList(),
        val error: String? = null
    )

    override fun getDescription(): String =
        "Safely delete a Kotlin/Java symbol (class, function, property, parameter, variable) at " +
        "a file position. Uses IntelliJ's SafeDeleteProcessor. " +
        "Dry-run reports the references that would need to be removed (via ReferencesSearch). " +
        "Real run refuses when references exist unless force=true is set; with force=true the " +
        "processor removes the declaration and call sites it can clean up, and leaves errors " +
        "at sites it can't. " +
        "WARNING: force=true can break the build — verify with find-usages first."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path. Relative paths resolved against project root."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line of the symbol to delete (1-based)."
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column of the symbol (1-based)."
            ),
            "dry_run" to mapOf(
                "type" to "boolean",
                "description" to "If true, return preview (usages + conflicts) without applying. Default false.",
                "default" to false
            ),
            "force" to mapOf(
                "type" to "boolean",
                "description" to "If true, proceed even when conflicts are present. Default false.",
                "default" to false
            ),
            "search_in_comments" to mapOf(
                "type" to "boolean",
                "description" to "Also report/remove occurrences inside comments. Default false.",
                "default" to false
            ),
            "search_in_strings" to mapOf(
                "type" to "boolean",
                "description" to "Also report/remove occurrences inside string literals. Default false.",
                "default" to false
            )
        ),
        "required" to listOf("file_path", "line", "column")
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
            val dryRun = arguments.get("dry_run")?.asBoolean ?: false
            val force = arguments.get("force")?.asBoolean ?: false
            val searchInComments = arguments.get("search_in_comments")?.asBoolean ?: false
            val searchInStrings = arguments.get("search_in_strings")?.asBoolean ?: false

            gson.toJson(safeDelete(filePath, line, column, dryRun, force, searchInComments, searchInStrings))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in SafeDeleteTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun safeDelete(
        filePath: String,
        line: Int,
        column: Int,
        dryRun: Boolean,
        force: Boolean,
        searchInComments: Boolean,
        searchInStrings: Boolean
    ): SafeDeleteResponse {
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
            ?: return SafeDeleteResponse(success = false, error = NO_PROJECT_MESSAGE)

        ApplicationManager.getApplication().runReadAction<String?> {
            val vf = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            if (vf != null && !ProjectFileIndex.getInstance(project).isInContent(vf))
                notIndexedError(resolvedPath)
            else null
        }?.let { return SafeDeleteResponse(success = false, error = it) }

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
            findDeleteTarget(leaf)
        } ?: return SafeDeleteResponse(
            success = false,
            error = "No deletable symbol at line $line, column $column"
        )

        val (name, kind, declFile) = ApplicationManager.getApplication().runReadAction<Triple<String?, String, String?>> {
            Triple(
                (target as? PsiNamedElement)?.name,
                elementKind(target),
                target.containingFile?.virtualFile?.path
            )
        }
        if (name == null) {
            return SafeDeleteResponse(success = false, error = "Target has no name: ${target.javaClass.simpleName}")
        }

        // Validate the target is eligible for safe-delete.
        val validElement = ApplicationManager.getApplication().runReadAction<Boolean> {
            SafeDeleteProcessor.validElement(target)
        }
        if (!validElement) {
            return SafeDeleteResponse(
                success = false, name = name, kind = kind, declarationFile = declFile,
                error = "Safe-delete is not supported for this element type ($kind)"
            )
        }

        val elements = arrayOf(target)

        // Find references via ReferencesSearch (public API). Search with the element's own
        // useScope (the no-scope overload) rather than an explicit projectScope — the latter
        // silently misses cross-file Kotlin references under K2, which would defeat the safety
        // guard below (0 usages → proceed to apply → SafeDeleteProcessor pops a modal conflicts
        // dialog and hangs the server thread). Matches the proven search in FindUsagesTool.
        val locations = ApplicationManager.getApplication().runReadAction<List<UsageLocation>> {
            val locs = mutableListOf<UsageLocation>()
            val refs: Collection<PsiReference> = try {
                ReferencesSearch.search(target).findAll()
            } catch (e: Exception) {
                logger.warn("ReferencesSearch failed: ${e.message}")
                emptyList()
            }
            for (ref in refs) {
                val element = ref.element
                val containingFile = element.containingFile ?: continue
                val vf = containingFile.virtualFile ?: continue
                val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: continue
                val startOffset = element.textRange?.startOffset ?: continue
                val lineNum = document.getLineNumber(startOffset) + 1
                val col = startOffset - document.getLineStartOffset(lineNum - 1) + 1
                locs.add(UsageLocation(vf.path, lineNum, col))
            }
            locs
        }

        if (dryRun) {
            return SafeDeleteResponse(
                success = true,
                dryRun = true,
                name = name,
                kind = kind,
                declarationFile = declFile,
                usageCount = locations.size,
                usages = locations
            )
        }

        if (locations.isNotEmpty() && !force) {
            return SafeDeleteResponse(
                success = false,
                name = name,
                kind = kind,
                declarationFile = declFile,
                usageCount = locations.size,
                usages = locations,
                error = "${locations.size} reference(s) exist. Pass force=true to proceed (may break the build)."
            )
        }

        // Apply the delete.
        var runError: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val processor = SafeDeleteProcessor.createInstance(
                    project,
                    null,
                    elements,
                    searchInComments,
                    searchInStrings,
                    false
                )
                processor.setPreviewUsages(false)
                processor.run()
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (e: Throwable) {
                runError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        return if (runError != null) {
            SafeDeleteResponse(
                success = false,
                name = name,
                kind = kind,
                declarationFile = declFile,
                usageCount = locations.size,
                usages = locations,
                error = runError
            )
        } else {
            SafeDeleteResponse(
                success = true,
                dryRun = false,
                name = name,
                kind = kind,
                declarationFile = declFile,
                usageCount = locations.size,
                usages = locations
            )
        }
    }

    private fun findDeleteTarget(leaf: PsiElement): PsiElement? {
        leaf.reference?.resolve()?.let { if (isDeletable(it)) return it }

        var current: PsiElement? = leaf
        while (current != null) {
            if (isDeletable(current)) return current
            current.reference?.resolve()?.let { if (isDeletable(it)) return it }
            current = current.parent
        }
        return null
    }

    private fun isDeletable(element: PsiElement): Boolean = when (element) {
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
        gson.toJson(SafeDeleteResponse(success = false, error = message))
}
