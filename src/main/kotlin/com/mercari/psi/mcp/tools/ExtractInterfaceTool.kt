package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.refactoring.util.DocCommentPolicy
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfo
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.io.File

@OptIn(KaAllowAnalysisOnEdt::class)
class ExtractInterfaceTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(ExtractInterfaceTool::class.java)

    data class ExtractInterfaceResponse(
        val success: Boolean,
        val dryRun: Boolean = false,
        val sourceClass: String? = null,
        val newInterfaceFile: String? = null,
        val newInterfaceName: String? = null,
        val extractedMembers: List<String> = emptyList(),
        val missingMembers: List<String> = emptyList(),
        val error: String? = null
    )

    override fun getDescription(): String =
        "Extract an interface from a Kotlin class via IntelliJ's KotlinExtractSuperRefactoring. " +
        "Given a class position + a list of member names, creates a new .kt file containing an " +
        "interface with abstract versions of those members and makes the source class implement it. " +
        "Use dry_run=true to preview (which members matched, target file name). " +
        "Notes: only concrete members can be extracted; extension functions, generic-heavy cases " +
        "are not guaranteed. For moving the resulting interface to another module (api/), chain " +
        "this tool with move-file."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Path to the file containing the source class."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line of the class declaration (1-based)."
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column of the class declaration (1-based)."
            ),
            "interface_name" to mapOf(
                "type" to "string",
                "description" to "Name for the new interface (e.g. 'FooRepository')."
            ),
            "member_names" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Names of members (functions/properties) on the class to extract into the interface."
            ),
            "target_directory" to mapOf(
                "type" to "string",
                "description" to "Directory where the new interface file will be created. Must already exist."
            ),
            "dry_run" to mapOf(
                "type" to "boolean",
                "description" to "If true, validate inputs and return preview without creating files. Default false.",
                "default" to false
            ),
            "doc_policy" to mapOf(
                "type" to "string",
                "description" to "How to handle KDoc on moved members: 'move' (default), 'copy', or 'asis'.",
                "enum" to listOf("move", "copy", "asis"),
                "default" to "move"
            )
        ),
        "required" to listOf("file_path", "line", "column", "interface_name", "member_names", "target_directory")
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
            val interfaceName = arguments.get("interface_name")?.asString
                ?: return errorResult("Missing 'interface_name'")
            val memberNamesJson = arguments.get("member_names")
                ?: return errorResult("Missing 'member_names'")
            val memberNames: List<String> = gson.fromJson(
                memberNamesJson, object : TypeToken<List<String>>() {}.type
            )
            if (memberNames.isEmpty()) {
                return errorResult("'member_names' must contain at least one name")
            }
            val targetDir = arguments.get("target_directory")?.asString
                ?: return errorResult("Missing 'target_directory'")
            val dryRun = arguments.get("dry_run")?.asBoolean ?: false
            val docPolicyStr = arguments.get("doc_policy")?.asString ?: "move"

            gson.toJson(extract(filePath, line, column, interfaceName, memberNames, targetDir, dryRun, docPolicyStr))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in ExtractInterfaceTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun extract(
        filePath: String,
        line: Int,
        column: Int,
        interfaceName: String,
        memberNames: List<String>,
        targetDirPath: String,
        dryRun: Boolean,
        docPolicyStr: String
    ): ExtractInterfaceResponse {
        val resolvedPath = resolveAbsolutePath(filePath)
        val resolvedTarget = resolveAbsolutePath(targetDirPath)

        // Force-sync the source file so external edits are visible.
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
                            if (doc.text != onDisk) doc.setText(onDisk)
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
            ?: return ExtractInterfaceResponse(success = false, error = NO_PROJECT_MESSAGE)

        val sourceVf = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            ?: return ExtractInterfaceResponse(success = false, error = "Source file not found")
        val targetVf = LocalFileSystem.getInstance().findFileByPath(resolvedTarget)
            ?: return ExtractInterfaceResponse(success = false, error = "Target directory not found")
        if (!targetVf.isDirectory) {
            return ExtractInterfaceResponse(success = false, error = "Target is not a directory: $resolvedTarget")
        }

        // Resolve class target + member infos.
        data class Resolved(
            val klass: KtClassOrObject,
            val matchedMembers: List<KtNamedDeclaration>,
            val missing: List<String>
        )

        val resolved = ApplicationManager.getApplication().runReadAction<Resolved?> {
            val psiFile = PsiManager.getInstance(project).findFile(sourceVf) ?: return@runReadAction null
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@runReadAction null
            val offset = try {
                document.getLineStartOffset(line - 1) + (column - 1)
            } catch (e: Exception) {
                return@runReadAction null
            }
            if (offset < 0 || offset > document.textLength) return@runReadAction null
            val leaf = psiFile.findElementAt(offset) ?: return@runReadAction null
            var current: PsiElement? = leaf
            var klass: KtClassOrObject? = null
            while (current != null) {
                if (current is KtClassOrObject) { klass = current; break }
                current = current.parent
            }
            if (klass == null) return@runReadAction null
            if (klass is KtClass && klass.isAnnotation()) return@runReadAction null

            val declarations = klass.declarations.filterIsInstance<KtNamedDeclaration>()
            val declByName = declarations.groupBy { it.name ?: "" }
            val matched = mutableListOf<KtNamedDeclaration>()
            val missing = mutableListOf<String>()
            for (name in memberNames) {
                val list = declByName[name].orEmpty()
                if (list.isEmpty()) missing.add(name) else matched.addAll(list)
            }
            Resolved(klass, matched, missing)
        } ?: return ExtractInterfaceResponse(
            success = false,
            error = "Could not resolve a class at $resolvedPath:$line:$column"
        )

        if (resolved.missing.isNotEmpty()) {
            return ExtractInterfaceResponse(
                success = false,
                sourceClass = resolved.klass.fqName?.asString() ?: resolved.klass.name,
                extractedMembers = resolved.matchedMembers.mapNotNull { it.name },
                missingMembers = resolved.missing,
                error = "Members not found on class: ${resolved.missing.joinToString(", ")}"
            )
        }

        val newFileName = "$interfaceName.kt"
        val expectedNewPath = "$resolvedTarget${File.separator}$newFileName"
        val (sourceClassFqn, matchedNames) = ApplicationManager.getApplication().runReadAction<Pair<String?, List<String>>> {
            (resolved.klass.fqName?.asString() ?: resolved.klass.name) to resolved.matchedMembers.mapNotNull { it.name }
        }

        if (dryRun) {
            return ExtractInterfaceResponse(
                success = true,
                dryRun = true,
                sourceClass = sourceClassFqn,
                newInterfaceFile = expectedNewPath,
                newInterfaceName = interfaceName,
                extractedMembers = matchedNames,
                missingMembers = emptyList()
            )
        }

        val docPolicy = when (docPolicyStr.lowercase()) {
            "copy" -> DocCommentPolicy(DocCommentPolicy.COPY)
            "asis" -> DocCommentPolicy(DocCommentPolicy.ASIS)
            else -> DocCommentPolicy(DocCommentPolicy.MOVE)
        }

        var runError: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val targetPsiDir = PsiManager.getInstance(project).findDirectory(targetVf)
                    ?: throw IllegalStateException("Target directory not part of project")

                val memberInfos = resolved.matchedMembers.map { m ->
                    allowAnalysisOnEdt {
                        KotlinMemberInfo(m).also {
                            it.isChecked = true
                            it.isToAbstract = true
                        }
                    }
                }
                // ExtractSuperInfo is an internal Kotlin-plugin API whose sole 7-arg constructor
                // takes a raw DocCommentPolicy (Kotlin refuses the parameterized variant), so we
                // build it reflectively. Loading the class by name (rather than referencing the
                // type) also keeps us decoupled from an internal API that can shift across builds.
                val extractSuperInfoClass = Class.forName(
                    "org.jetbrains.kotlin.idea.refactoring.introduce.extractClass.ExtractSuperInfo"
                )
                val ctor = extractSuperInfoClass.declaredConstructors.first {
                    it.parameterCount == 7
                }
                val extractInfo: Any = ctor.newInstance(
                    resolved.klass,
                    memberInfos,
                    targetPsiDir,
                    newFileName,
                    interfaceName,
                    true,
                    docPolicy
                )
                // The extract-super refactoring ships under different class names / call
                // conventions across IDE builds, so it's dispatched reflectively (see
                // invokeExtractSuperRefactoring). It manages its own command + write action
                // internally; we only satisfy the K2 analysis-on-EDT allowance.
                allowAnalysisOnEdt {
                    invokeExtractSuperRefactoring(extractInfo, extractSuperInfoClass)
                }
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (e: Throwable) {
                runError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        if (runError != null) {
            return ExtractInterfaceResponse(
                success = false,
                sourceClass = sourceClassFqn,
                newInterfaceName = interfaceName,
                extractedMembers = matchedNames,
                error = runError
            )
        }

        return ExtractInterfaceResponse(
            success = true,
            dryRun = false,
            sourceClass = sourceClassFqn,
            newInterfaceFile = expectedNewPath,
            newInterfaceName = interfaceName,
            extractedMembers = matchedNames
        )
    }

    /**
     * Runs the Kotlin "extract super" refactoring, resolving the implementation class reflectively
     * because IDE builds ship it under different names and call conventions:
     *  - IntelliJ IDEA 2025.1 / AS Meerkat (243): `class ExtractSuperRefactoring` — constructor takes
     *    the info, `performRefactoring()` is no-arg.
     *  - Android Studio 253 / 261: `interface KotlinExtractSuperRefactoring` — static `getInstance()`,
     *    `performRefactoring(info)`.
     * Dispatching reflectively lets one binary run on both IntelliJ IDEA and every supported Android
     * Studio. `infoClass` is the (reflectively loaded) ExtractSuperInfo class and `extractInfo` an
     * instance of it.
     */
    private fun invokeExtractSuperRefactoring(extractInfo: Any, infoClass: Class<*>) {
        val pkg = "org.jetbrains.kotlin.idea.refactoring.introduce.extractClass"
        try {
            val concrete = runCatching { Class.forName("$pkg.ExtractSuperRefactoring") }.getOrNull()
            if (concrete != null) {
                // Variant A: concrete class, ctor(info) + no-arg performRefactoring().
                val instance = concrete.getConstructor(infoClass).newInstance(extractInfo)
                concrete.getMethod("performRefactoring").invoke(instance)
            } else {
                // Variant B: interface, static getInstance() + performRefactoring(info).
                val iface = Class.forName("$pkg.KotlinExtractSuperRefactoring")
                val instance = iface.getMethod("getInstance").invoke(null)
                iface.getMethod("performRefactoring", infoClass).invoke(instance, extractInfo)
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Surface the real refactoring failure, not the reflection wrapper.
            throw e.cause ?: e
        }
    }

    private fun errorResult(message: String): String =
        gson.toJson(ExtractInterfaceResponse(success = false, error = message))
}
