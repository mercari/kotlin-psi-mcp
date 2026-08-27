package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class MoveFileTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(MoveFileTool::class.java)

    data class UsageLocation(
        val file: String,
        val line: Int,
        val column: Int
    )

    data class MoveFileResponse(
        val success: Boolean,
        val dryRun: Boolean = false,
        val sourcePath: String? = null,
        val targetDirectory: String? = null,
        val newPath: String? = null,
        val oldPackage: String? = null,
        val newPackage: String? = null,
        val usageCount: Int = 0,
        val usages: List<UsageLocation> = emptyList(),
        val error: String? = null
    )

    override fun getDescription(): String =
        "Move a Kotlin or Java file to a different directory, updating the package declaration " +
        "(for Kotlin, if the new location maps to a different package) and all cross-file " +
        "imports/references. Uses IntelliJ's move-files refactoring. " +
        "Dry-run enumerates project references to top-level declarations in the file (via " +
        "ReferencesSearch). WARNING: dry_run=false moves the file and rewrites references " +
        "project-wide — verify the target with find-usages first."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "source_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or project-relative path to the .kt/.java file to move."
            ),
            "target_directory" to mapOf(
                "type" to "string",
                "description" to "Absolute or project-relative path to the destination directory. Must already exist."
            ),
            "dry_run" to mapOf(
                "type" to "boolean",
                "description" to "If true, return preview (affected references + new package) without moving. Default false.",
                "default" to false
            ),
            "search_in_comments" to mapOf(
                "type" to "boolean",
                "description" to "Also update occurrences inside comments. Default false.",
                "default" to false
            ),
            "search_in_non_code" to mapOf(
                "type" to "boolean",
                "description" to "Also update occurrences in non-code files (e.g. XML, properties). Default false.",
                "default" to false
            )
        ),
        "required" to listOf("source_path", "target_directory")
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val sourcePath = arguments.get("source_path")?.asString
                ?: return errorResult("Missing 'source_path'")
            val targetDir = arguments.get("target_directory")?.asString
                ?: return errorResult("Missing 'target_directory'")
            val dryRun = arguments.get("dry_run")?.asBoolean ?: false
            val searchInComments = arguments.get("search_in_comments")?.asBoolean ?: false
            val searchInNonCode = arguments.get("search_in_non_code")?.asBoolean ?: false

            gson.toJson(move(sourcePath, targetDir, dryRun, searchInComments, searchInNonCode))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in MoveFileTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun move(
        sourcePath: String,
        targetDirPath: String,
        dryRun: Boolean,
        searchInComments: Boolean,
        searchInNonCode: Boolean
    ): MoveFileResponse {
        val resolvedSource = resolveAbsolutePath(sourcePath)
        val resolvedTarget = resolveAbsolutePath(targetDirPath)

        // Refresh VFS so we see current on-disk state for both paths.
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val lfs = LocalFileSystem.getInstance()
                val project = servedProject()
                val vf = lfs.refreshAndFindFileByPath(resolvedSource)
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
                lfs.refreshAndFindFileByPath(resolvedTarget)?.refresh(false, false)
            }
        }

        val project = servedProject()
            ?: return MoveFileResponse(success = false, error = NO_PROJECT_MESSAGE)

        val sourceVf = LocalFileSystem.getInstance().findFileByPath(resolvedSource)
            ?: return MoveFileResponse(
                success = false, sourcePath = resolvedSource, targetDirectory = resolvedTarget,
                error = "Source file not found: $resolvedSource"
            )

        val sourceInContent = ApplicationManager.getApplication().runReadAction<Boolean> {
            ProjectFileIndex.getInstance(project).isInContent(sourceVf)
        }
        if (!sourceInContent) {
            return MoveFileResponse(
                success = false, sourcePath = resolvedSource, targetDirectory = resolvedTarget,
                error = notIndexedError(resolvedSource)
            )
        }

        val targetVf = LocalFileSystem.getInstance().findFileByPath(resolvedTarget)
            ?: return MoveFileResponse(
                success = false, sourcePath = resolvedSource, targetDirectory = resolvedTarget,
                error = "Target directory not found: $resolvedTarget"
            )

        if (sourceVf.isDirectory) {
            return MoveFileResponse(
                success = false, sourcePath = resolvedSource, targetDirectory = resolvedTarget,
                error = "Source is a directory; only single-file moves are supported"
            )
        }
        if (!targetVf.isDirectory) {
            return MoveFileResponse(
                success = false, sourcePath = resolvedSource, targetDirectory = resolvedTarget,
                error = "Target is not a directory: $resolvedTarget"
            )
        }

        val psiFile = ApplicationManager.getApplication().runReadAction<PsiFile?> {
            PsiManager.getInstance(project).findFile(sourceVf)
        } ?: return MoveFileResponse(
            success = false, sourcePath = resolvedSource, targetDirectory = resolvedTarget,
            error = "Could not load PSI for source file"
        )

        if (psiFile !is KtFile && psiFile.language.id != "JAVA") {
            return MoveFileResponse(
                success = false, sourcePath = resolvedSource, targetDirectory = resolvedTarget,
                error = "Only Kotlin and Java files are supported (got: ${psiFile.language.id})"
            )
        }

        val targetPsiDir = ApplicationManager.getApplication().runReadAction<PsiDirectory?> {
            PsiManager.getInstance(project).findDirectory(targetVf)
        } ?: return MoveFileResponse(
            success = false, sourcePath = resolvedSource, targetDirectory = resolvedTarget,
            error = "Target directory is not part of the project"
        )

        // Resolve old + new packages (Kotlin: by KtFile.packageFqName; Java: by inferred directory package).
        val (oldPackage, newPackage) = ApplicationManager.getApplication().runReadAction<Pair<String?, String?>> {
            val old = (psiFile as? KtFile)?.packageFqName?.asString()
                ?: psiFile.language.id.takeIf { it == "JAVA" }?.let {
                    (psiFile as? com.intellij.psi.PsiJavaFile)?.packageName
                }
            val new = com.intellij.psi.JavaDirectoryService.getInstance().getPackage(targetPsiDir)?.qualifiedName
            old to new
        }

        // Collect usage locations for top-level named declarations in the file (for reporting).
        // Search with each declaration's own useScope (no-scope overload) — an explicit
        // projectScope silently misses cross-file Kotlin references under K2, under-reporting the
        // usages an LLM relies on to judge the move. Matches the proven search in FindUsagesTool.
        val usages = ApplicationManager.getApplication().runReadAction<List<UsageLocation>> {
            val locs = mutableListOf<UsageLocation>()
            val topLevelDecls: List<PsiElement> = when (psiFile) {
                is KtFile -> psiFile.declarations.filterIsInstance<PsiNamedElement>()
                else -> psiFile.children.filterIsInstance<PsiNamedElement>()
            }
            for (decl in topLevelDecls) {
                try {
                    for (ref in ReferencesSearch.search(decl).findAll()) {
                        val refEl = ref.element
                        val containingFile = refEl.containingFile ?: continue
                        val vf = containingFile.virtualFile ?: continue
                        if (vf.path == resolvedSource) continue // skip self-references
                        val doc = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: continue
                        val startOffset = refEl.textRange?.startOffset ?: continue
                        val lineNum = doc.getLineNumber(startOffset) + 1
                        val col = startOffset - doc.getLineStartOffset(lineNum - 1) + 1
                        locs.add(UsageLocation(vf.path, lineNum, col))
                    }
                } catch (e: Exception) {
                    logger.warn("ReferencesSearch failed for ${(decl as? PsiNamedElement)?.name}: ${e.message}")
                }
            }
            locs
        }

        val expectedNewPath = "$resolvedTarget${File.separator}${sourceVf.name}"

        if (dryRun) {
            return MoveFileResponse(
                success = true,
                dryRun = true,
                sourcePath = resolvedSource,
                targetDirectory = resolvedTarget,
                newPath = expectedNewPath,
                oldPackage = oldPackage,
                newPackage = newPackage,
                usageCount = usages.size,
                usages = usages
            )
        }

        // No-op case: already in the target directory.
        if (sourceVf.parent?.path == targetVf.path) {
            return MoveFileResponse(
                success = true, dryRun = false,
                sourcePath = resolvedSource, targetDirectory = resolvedTarget,
                newPath = resolvedSource, oldPackage = oldPackage, newPackage = newPackage,
                usageCount = 0, usages = emptyList()
            )
        }

        var runError: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            CommandProcessor.getInstance().executeCommand(project, {
                try {
                    val processor = MoveFilesOrDirectoriesProcessor(
                        project,
                        arrayOf(psiFile),
                        targetPsiDir,
                        /* searchForReferences = */ true,
                        searchInComments,
                        searchInNonCode,
                        /* moveCallback = */ null,
                        /* prepareSuccessfulCallback = */ null
                    )
                    processor.setPreviewUsages(false)
                    processor.run()

                    // Defensive: ensure the moved Kotlin file's `package` line matches the
                    // package inferred from the target directory. The processor normally
                    // handles this, but we've seen reports where it doesn't.
                    if (newPackage != null && psiFile is KtFile) {
                        val actualPkg = psiFile.packageFqName.asString()
                        if (actualPkg != newPackage) {
                            ApplicationManager.getApplication().runWriteAction {
                                rewritePackageLine(project, psiFile, newPackage)
                            }
                        }
                    }

                    FileDocumentManager.getInstance().saveAllDocuments()
                } catch (e: Throwable) {
                    runError = "${e.javaClass.simpleName}: ${e.message}"
                }
            }, "Move File", null)
        }

        if (runError != null) {
            return MoveFileResponse(
                success = false,
                sourcePath = resolvedSource,
                targetDirectory = resolvedTarget,
                oldPackage = oldPackage,
                newPackage = newPackage,
                usageCount = usages.size,
                usages = usages,
                error = runError
            )
        }

        // Refresh & locate the new file.
        val actualNewVf: VirtualFile? = ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            VfsUtil.findFileByIoFile(File(expectedNewPath), true)
        }

        return MoveFileResponse(
            success = true,
            dryRun = false,
            sourcePath = resolvedSource,
            targetDirectory = resolvedTarget,
            newPath = actualNewVf?.path ?: expectedNewPath,
            oldPackage = oldPackage,
            newPackage = newPackage,
            usageCount = usages.size,
            usages = usages
        )
    }

    private fun rewritePackageLine(project: Project, ktFile: KtFile, newPackage: String) {
        val factory = org.jetbrains.kotlin.psi.KtPsiFactory(project)
        val existingDirective = ktFile.packageDirective
        val newDirectiveText = if (newPackage.isBlank()) "" else "package $newPackage"
        if (existingDirective == null || existingDirective.text.isBlank()) {
            if (newDirectiveText.isEmpty()) return
            // Prepend package + blank line.
            val newFile = factory.createFile("dummy.kt", "$newDirectiveText\n")
            val newDirective = newFile.packageDirective ?: return
            val firstChild = ktFile.firstChild
            ktFile.addBefore(newDirective, firstChild)
            ktFile.addBefore(factory.createNewLine(1), firstChild)
        } else {
            val replacement = factory.createPackageDirective(
                org.jetbrains.kotlin.name.FqName(newPackage)
            )
            existingDirective.replace(replacement)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun errorResult(message: String): String =
        gson.toJson(MoveFileResponse(success = false, error = message))
}
