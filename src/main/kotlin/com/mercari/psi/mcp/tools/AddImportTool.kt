package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath
import java.io.File

class AddImportTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(AddImportTool::class.java)

    data class AddImportResponse(
        val success: Boolean,
        val file: String? = null,
        val language: String? = null,    // "kotlin" / "java"
        val fqn: String? = null,
        val alias: String? = null,
        val added: Boolean = false,       // false if it was already imported
        val wildcard: Boolean = false,
        val error: String? = null
    )

    override fun getDescription(): String =
        "Add an import statement to a Kotlin or Java file. Returns added=false if the import " +
        "already exists (idempotent). Supports wildcard imports (fqn ending in '.*') and " +
        "Kotlin `import ... as Alias`. Does NOT resolve references automatically — use this when " +
        "you know the fully-qualified name you want to add."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path to the .kt or .java file."
            ),
            "fqn" to mapOf(
                "type" to "string",
                "description" to "Fully-qualified name to import, e.g. 'androidx.compose.runtime.Composable'. End with '.*' for wildcard."
            ),
            "alias" to mapOf(
                "type" to "string",
                "description" to "Optional import alias (Kotlin only), e.g. 'import X as Y'. Ignored for Java."
            )
        ),
        "required" to listOf("file_path", "fqn")
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return errorResult("Missing 'file_path'")
            val fqn = arguments.get("fqn")?.asString
                ?: return errorResult("Missing 'fqn'")
            val alias = arguments.get("alias")?.asString

            gson.toJson(addImport(filePath, fqn, alias))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in AddImportTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun addImport(filePath: String, fqn: String, alias: String?): AddImportResponse {
        val resolvedPath = resolveAbsolutePath(filePath)
        val wildcard = fqn.endsWith(".*")

        // Refresh VFS + PSI — force-sync Document to on-disk bytes so stale
        // editor/PSI state (from another AS tab) doesn't win over our external edits.
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
                            // Document diverged from disk (e.g. open editor tab cache).
                            // Try reloadFromDisk first; fall back to setText if it doesn't take.
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
            ?: return AddImportResponse(success = false, error = NO_PROJECT_MESSAGE)

        val vf = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            ?: return AddImportResponse(success = false, file = resolvedPath, error = "File not found")

        val psiFile = ApplicationManager.getApplication().runReadAction<com.intellij.psi.PsiFile?> {
            PsiManager.getInstance(project).findFile(vf)
        } ?: return AddImportResponse(success = false, file = resolvedPath, error = "Could not load PSI file")

        val language = when (psiFile) {
            is KtFile -> "kotlin"
            is PsiJavaFile -> "java"
            else -> return AddImportResponse(
                success = false, file = resolvedPath,
                error = "Unsupported file type: ${psiFile.javaClass.simpleName} (only Kotlin and Java supported)"
            )
        }

        if (alias != null && language != "kotlin") {
            return AddImportResponse(
                success = false, file = resolvedPath, language = language, fqn = fqn, alias = alias,
                error = "Import alias is only supported for Kotlin files"
            )
        }

        var error: String? = null
        var added = false

        ApplicationManager.getApplication().invokeAndWait {
            CommandProcessor.getInstance().executeCommand(project, {
                ApplicationManager.getApplication().runWriteAction {
                    try {
                        added = when (psiFile) {
                            is KtFile -> addKotlinImport(project, psiFile, fqn, alias, wildcard)
                            is PsiJavaFile -> addJavaImport(project, psiFile, fqn, wildcard)
                            else -> false
                        }
                        if (added) {
                            val doc = FileDocumentManager.getInstance().getDocument(vf)
                            if (doc != null) {
                                PsiDocumentManager.getInstance(project).commitDocument(doc)
                                FileDocumentManager.getInstance().saveDocument(doc)
                            }
                        }
                    } catch (e: Throwable) {
                        error = "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }, "Add Import", null)
        }

        return if (error != null) {
            AddImportResponse(
                success = false, file = resolvedPath, language = language,
                fqn = fqn, alias = alias, wildcard = wildcard, error = error
            )
        } else {
            AddImportResponse(
                success = true, file = resolvedPath, language = language,
                fqn = fqn, alias = alias, added = added, wildcard = wildcard
            )
        }
    }

    private fun addKotlinImport(
        project: com.intellij.openapi.project.Project,
        ktFile: KtFile,
        fqn: String,
        alias: String?,
        wildcard: Boolean
    ): Boolean {
        val baseName = if (wildcard) fqn.removeSuffix(".*") else fqn
        val existing = ktFile.importDirectives.firstOrNull { directive ->
            val pathStr = directive.importPath?.pathStr ?: return@firstOrNull false
            val sameAlias = directive.aliasName == alias
            pathStr == fqn && sameAlias
        }
        if (existing != null) return false

        // Build the import statement as text.
        val importText = buildString {
            append("import ")
            append(baseName)
            if (wildcard) append(".*")
            alias?.let { append(" as ").append(it) }
        }

        // Insert into the Document directly — avoids PSI whitespace quirks.
        val vf = ktFile.virtualFile ?: return false
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return false

        val existingDirectives = ktFile.importDirectives.toList()
        val insertOffset: Int = when {
            existingDirectives.isEmpty() -> {
                // No imports yet — insert after package directive (or at top).
                val pkg = ktFile.packageDirective
                val base = pkg?.textRange?.endOffset ?: 0
                base
            }
            else -> {
                // Lexicographic insert: place after the last directive whose path < newPath.
                val anchor = existingDirectives.lastOrNull { directive ->
                    (directive.importPath?.pathStr ?: "") < baseName
                }
                anchor?.textRange?.endOffset ?: existingDirectives.first().textRange.startOffset - 1
            }
        }

        if (existingDirectives.isEmpty() && ktFile.packageDirective == null) {
            // Empty file: just prepend.
            doc.insertString(0, importText + "\n")
        } else if (existingDirectives.isNotEmpty() && insertOffset < existingDirectives.first().textRange.startOffset) {
            // Insert before the first directive.
            doc.insertString(existingDirectives.first().textRange.startOffset, importText + "\n")
        } else {
            doc.insertString(insertOffset, "\n" + importText)
        }
        return true
    }

    private fun addJavaImport(
        project: com.intellij.openapi.project.Project,
        javaFile: PsiJavaFile,
        fqn: String,
        wildcard: Boolean
    ): Boolean {
        val importList = javaFile.importList
            ?: throw IllegalStateException("Java file has no import list")

        val baseName = if (wildcard) fqn.removeSuffix(".*") else fqn

        // Idempotency.
        val alreadyImported = importList.importStatements.any { stmt ->
            if (wildcard) stmt.isOnDemand && stmt.qualifiedName == baseName
            else !stmt.isOnDemand && stmt.qualifiedName == fqn
        }
        if (alreadyImported) return false

        // Validate the symbol actually resolves — preserves previous error behavior.
        if (wildcard) {
            JavaPsiFacade.getInstance(project).findPackage(baseName)
                ?: throw IllegalArgumentException("Package not found: $baseName")
        } else {
            JavaPsiFacade.getInstance(project)
                .findClass(fqn, com.intellij.psi.search.GlobalSearchScope.allScope(project))
                ?: throw IllegalArgumentException("Class not found on classpath: $fqn")
        }

        // Use direct Document insertion (same approach as Kotlin) to control whitespace.
        val importText = if (wildcard) "import $baseName.*;" else "import $fqn;"
        val vf = javaFile.virtualFile ?: return false
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return false

        val existingImports = importList.importStatements.toList()
        val newSortKey = if (wildcard) baseName else fqn

        when {
            existingImports.isEmpty() -> {
                val pkgStmt = javaFile.packageStatement
                if (pkgStmt != null) {
                    // Ensure a blank line after the package, and a newline after our import
                    // so the following class declaration stays on its own line.
                    val end = pkgStmt.textRange.endOffset
                    val suffixStart = end
                    val after = doc.text.substring(suffixStart).takeWhile { it == '\n' || it == '\r' }
                    val prefix = if (after.startsWith("\n\n") || after.startsWith("\r\n\r\n")) "" else "\n"
                    val trailing = if (doc.text.getOrNull(suffixStart + after.length) == null) "\n" else "\n"
                    doc.insertString(end + after.length, "$prefix$importText$trailing")
                } else {
                    doc.insertString(0, "$importText\n\n")
                }
            }
            else -> {
                val anchor = existingImports.lastOrNull { stmt ->
                    (stmt.qualifiedName ?: "") < newSortKey
                }
                if (anchor != null) {
                    doc.insertString(anchor.textRange.endOffset, "\n$importText")
                } else {
                    val first = existingImports.first()
                    doc.insertString(first.textRange.startOffset, "$importText\n")
                }
            }
        }
        return true
    }

    private fun errorResult(message: String): String =
        gson.toJson(AddImportResponse(success = false, error = message))
}
