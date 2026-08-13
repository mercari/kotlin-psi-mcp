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
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtClass
import java.io.File

class AddParameterTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(AddParameterTool::class.java)

    data class AddParameterResponse(
        val success: Boolean,
        val function: String? = null,          // qualified name or signature
        val file: String? = null,
        val insertedLine: Int = 0,
        val parameterText: String? = null,
        val hadDefaultValue: Boolean = false,
        val callSiteCount: Int = 0,            // references found — warn if >0 without default
        val warning: String? = null,
        val error: String? = null
    )

    override fun getDescription(): String =
        "Append or insert a parameter on a Kotlin function (regular function, primary or secondary " +
        "constructor). PSI-only edit: does NOT update call sites. If the new parameter has no " +
        "default_value and the function has existing references, those call sites will fail to " +
        "compile — the tool reports callSiteCount as a warning in that case. " +
        "Position: 'end' (default), 'start', or an explicit 0-based index."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path to the Kotlin file containing the function."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line of the function declaration (1-based)."
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column of the function declaration (1-based)."
            ),
            "name" to mapOf(
                "type" to "string",
                "description" to "Name of the new parameter."
            ),
            "type" to mapOf(
                "type" to "string",
                "description" to "Type of the new parameter (rendered as Kotlin type text, e.g. 'String', 'List<Int>?', 'com.foo.Bar')."
            ),
            "default_value" to mapOf(
                "type" to "string",
                "description" to "Optional default value expression (e.g. '0', '\"\"', 'emptyList()'). Omit for a required parameter."
            ),
            "position" to mapOf(
                "type" to "string",
                "description" to "Where to insert the parameter: 'end' (default), 'start', or a 0-based index as a string (e.g. '2')."
            ),
            "modifiers" to mapOf(
                "type" to "string",
                "description" to "Optional leading modifiers (e.g. 'val', 'var', 'vararg', '@SerialName(\"x\")'). Added before 'name: type'."
            )
        ),
        "required" to listOf("file_path", "line", "column", "name", "type")
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
            val name = arguments.get("name")?.asString
                ?: return errorResult("Missing 'name'")
            val type = arguments.get("type")?.asString
                ?: return errorResult("Missing 'type'")
            val defaultValue = arguments.get("default_value")?.asString
            val position = arguments.get("position")?.asString ?: "end"
            val modifiers = arguments.get("modifiers")?.asString

            gson.toJson(addParameter(filePath, line, column, name, type, defaultValue, position, modifiers))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in AddParameterTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun addParameter(
        filePath: String,
        line: Int,
        column: Int,
        name: String,
        type: String,
        defaultValue: String?,
        position: String,
        modifiers: String?
    ): AddParameterResponse {
        val resolvedPath = resolveAbsolutePath(filePath)

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
            ?: return AddParameterResponse(success = false, error = NO_PROJECT_MESSAGE)

        ApplicationManager.getApplication().runReadAction<String?> {
            val vf = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            if (vf != null && !ProjectFileIndex.getInstance(project).isInContent(vf))
                notIndexedError(resolvedPath)
            else null
        }?.let { return AddParameterResponse(success = false, error = it) }

        // Resolve target function in a read action.
        val target = ApplicationManager.getApplication().runReadAction<KtFunction?> {
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
            findFunctionTarget(leaf)
        } ?: return AddParameterResponse(
            success = false,
            error = "No Kotlin function/constructor found at line $line, column $column"
        )

        val (functionName, declFile, declLine) = ApplicationManager.getApplication().runReadAction<Triple<String, String?, Int>> {
            val n = when (target) {
                is KtNamedFunction -> target.fqName?.asString() ?: target.name ?: "unknown"
                is KtPrimaryConstructor -> (target.getContainingClassOrObject() as? KtClass)?.fqName?.asString()?.let { "$it.<init>" }
                    ?: "<init>"
                is KtSecondaryConstructor -> target.getContainingClassOrObject().fqName?.asString()?.let { "$it.<init>" }
                    ?: "<init>"
                else -> "unknown"
            }
            val vfPath = target.containingFile?.virtualFile?.path
            val doc = PsiDocumentManager.getInstance(project).getDocument(target.containingFile)
            val lineNum = doc?.getLineNumber(target.textRange.startOffset)?.plus(1) ?: 0
            Triple(n, vfPath, lineNum)
        }

        // Count call sites — warn if no default value and callers exist.
        val callSiteCount = ApplicationManager.getApplication().runReadAction<Int> {
            val searchTarget: PsiElement = when (target) {
                is KtPrimaryConstructor -> target.getContainingClassOrObject() ?: target
                else -> target
            }
            try {
                ReferencesSearch.search(searchTarget, GlobalSearchScope.projectScope(project)).findAll().size
            } catch (e: Exception) {
                logger.warn("ReferencesSearch failed: ${e.message}")
                0
            }
        }

        // Validate position.
        val existingParamCount = ApplicationManager.getApplication().runReadAction<Int> {
            target.valueParameters.size
        }
        val insertIndex = when (position) {
            "end" -> existingParamCount
            "start" -> 0
            else -> position.toIntOrNull()
                ?: return AddParameterResponse(
                    success = false, function = functionName, file = declFile,
                    error = "Invalid 'position': '$position' (expected 'end', 'start', or a numeric index)"
                )
        }
        if (insertIndex < 0 || insertIndex > existingParamCount) {
            return AddParameterResponse(
                success = false, function = functionName, file = declFile,
                error = "Position index $insertIndex out of range (function has $existingParamCount parameters)"
            )
        }

        // Build parameter text: "[modifiers ]name: type[ = default]"
        val paramText = buildString {
            if (!modifiers.isNullOrBlank()) {
                append(modifiers.trim())
                append(' ')
            }
            append(name)
            append(": ")
            append(type)
            if (defaultValue != null) {
                append(" = ")
                append(defaultValue)
            }
        }

        var error: String? = null
        var insertedAtLine = 0

        ApplicationManager.getApplication().invokeAndWait {
            CommandProcessor.getInstance().executeCommand(project, {
                ApplicationManager.getApplication().runWriteAction {
                    try {
                        val factory = KtPsiFactory(project)
                        val newParam = factory.createParameter(paramText)

                        val parameterList = target.valueParameterList
                            ?: throw IllegalStateException("Function has no parameter list (unexpected).")

                        val existing = parameterList.parameters
                        when {
                            existing.isEmpty() -> {
                                parameterList.addParameter(newParam)
                            }
                            insertIndex == 0 -> {
                                parameterList.addParameterBefore(newParam, existing.first())
                            }
                            insertIndex >= existing.size -> {
                                parameterList.addParameterAfter(newParam, existing.last())
                            }
                            else -> {
                                parameterList.addParameterBefore(newParam, existing[insertIndex])
                            }
                        }

                        val vf = target.containingFile.virtualFile
                        val doc = if (vf != null) FileDocumentManager.getInstance().getDocument(vf) else null
                        if (doc != null) {
                            PsiDocumentManager.getInstance(project).commitDocument(doc)
                            FileDocumentManager.getInstance().saveDocument(doc)
                            insertedAtLine = doc.getLineNumber(
                                target.valueParameterList?.textRange?.startOffset ?: target.textRange.startOffset
                            ) + 1
                        }
                    } catch (e: Throwable) {
                        error = "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }, "Add Parameter", null)
        }

        val warning = if (defaultValue == null && callSiteCount > 0) {
            "New parameter has no default value and $callSiteCount call site(s) exist — build will fail until call sites are updated."
        } else null

        return if (error != null) {
            AddParameterResponse(
                success = false,
                function = functionName,
                file = declFile,
                parameterText = paramText,
                hadDefaultValue = defaultValue != null,
                callSiteCount = callSiteCount,
                error = error
            )
        } else {
            AddParameterResponse(
                success = true,
                function = functionName,
                file = declFile,
                insertedLine = if (insertedAtLine > 0) insertedAtLine else declLine,
                parameterText = paramText,
                hadDefaultValue = defaultValue != null,
                callSiteCount = callSiteCount,
                warning = warning
            )
        }
    }

    private fun findFunctionTarget(leaf: PsiElement): KtFunction? {
        var current: PsiElement? = leaf
        while (current != null) {
            if (current is KtNamedFunction || current is KtPrimaryConstructor || current is KtSecondaryConstructor) {
                return current as KtFunction
            }
            current = current.parent
        }
        return null
    }

    private fun errorResult(message: String): String =
        gson.toJson(AddParameterResponse(success = false, error = message))
}
