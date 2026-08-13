package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.io.File

class GetKDocTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(GetKDocTool::class.java)

    data class GetKDocResponse(
        val success: Boolean,
        val targetName: String? = null,
        val targetKind: String? = null,         // class / interface / function / property / etc.
        val targetFqn: String? = null,
        val hasDoc: Boolean = false,
        val text: String? = null,               // cleaned (no /** */ / leading * markers)
        val raw: String? = null,                // raw text with markers
        val reason: String? = null,             // why there's no target (benign; distinct from "found, no doc")
        val error: String? = null
    )

    override fun getDescription(): String =
        "Fetch the KDoc (Kotlin) or JavaDoc (Java) text of the declaration at a file position. " +
        "If the position is a usage (reference), resolves to the declaration first; a constructor " +
        "call resolves to its class's doc. Returns both the cleaned text (no /** */ markers or " +
        "leading asterisks) and the raw text. Returns success=true with hasDoc=false when the " +
        "declaration has no doc comment, or when the position has no documentable declaration (with a `reason`)."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Path to the file."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line of the declaration or usage (1-based)."
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column of the declaration or usage (1-based)."
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

            gson.toJson(get(filePath, line, column))
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in GetKDocTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun get(filePath: String, line: Int, column: Int): GetKDocResponse {
        val resolvedPath = resolveAbsolutePath(filePath)
        val project = servedProject()
            ?: return GetKDocResponse(success = false, error = NO_PROJECT_MESSAGE)

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
            ?: return GetKDocResponse(success = false, error = "File not found: $resolvedPath")

        return ApplicationManager.getApplication().runReadAction<GetKDocResponse> {
            val psiFile = PsiManager.getInstance(project).findFile(vf)
                ?: return@runReadAction GetKDocResponse(success = false, error = "Could not load PSI")
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadAction GetKDocResponse(success = false, error = "No document")

            val offset = try {
                document.getLineStartOffset(line - 1) + (column - 1)
            } catch (e: Exception) {
                return@runReadAction GetKDocResponse(success = false, error = "Invalid line/column")
            }
            val leaf = psiFile.findElementAt(offset)
                ?: return@runReadAction GetKDocResponse(success = false, error = "No PSI at position")

            val resolved = findTarget(leaf)
                // Benign "nothing to document here" — not a failure (matches get-type-info; §9).
                ?: return@runReadAction GetKDocResponse(
                    success = true,
                    hasDoc = false,
                    reason = "no documentable declaration at this position"
                )
            // A constructor's doc lives on its class, so `Foo(...)` resolves to the constructor —
            // redirect to the class to surface the class KDoc.
            val target = docOwnerOf(resolved)

            val (name, kind, fqn) = describe(target)
            val (rawDoc, cleanedDoc) = extractDoc(target)

            GetKDocResponse(
                success = true,
                targetName = name,
                targetKind = kind,
                targetFqn = fqn,
                hasDoc = rawDoc != null,
                text = cleanedDoc,
                raw = rawDoc
            )
        }
    }

    private fun findTarget(leaf: PsiElement): PsiElement? {
        // Prefer reference resolution (usage → declaration).
        leaf.reference?.resolve()?.let { if (hasDocCapability(it)) return it }
        var current: PsiElement? = leaf
        while (current != null) {
            if (hasDocCapability(current)) return current
            current.reference?.resolve()?.let { if (hasDocCapability(it)) return it }
            current = current.parent
        }
        return null
    }

    /** A constructor's KDoc lives on its class — redirect so `Foo(...)` yields the class doc. */
    private fun docOwnerOf(element: PsiElement): PsiElement = when {
        element is KtConstructor<*> -> element.getContainingClassOrObject()
        element is PsiMethod && element.isConstructor -> element.containingClass ?: element
        else -> element
    }

    private fun hasDocCapability(element: PsiElement): Boolean = when (element) {
        is KtDeclaration, is PsiClass, is PsiMethod, is PsiField -> true
        else -> false
    }

    private fun describe(element: PsiElement): Triple<String, String, String?> {
        val name = (element as? PsiNamedElement)?.name ?: "<unnamed>"
        val kind = when (element) {
            is KtClass -> if (element.isInterface()) "interface" else "class"
            is KtObjectDeclaration -> "object"
            is KtClassOrObject -> "class"
            is KtNamedFunction -> "function"
            is KtProperty -> "property"
            is KtTypeAlias -> "typealias"
            is PsiClass -> if (element.isInterface) "interface" else "class"
            is PsiMethod -> "method"
            is PsiField -> "field"
            else -> element.javaClass.simpleName
        }
        val fqn = when (element) {
            is KtClassOrObject -> element.fqName?.asString()
            is KtNamedFunction -> element.fqName?.asString()
            is KtProperty -> element.fqName?.asString()
            is KtTypeAlias -> element.fqName?.asString()
            is PsiClass -> element.qualifiedName
            is PsiMethod -> element.containingClass?.qualifiedName?.let { "$it.${element.name}" }
            is PsiField -> element.containingClass?.qualifiedName?.let { "$it.${element.name}" }
            else -> null
        }
        return Triple(name, kind, fqn)
    }

    private fun extractDoc(element: PsiElement): Pair<String?, String?> {
        val raw = when (element) {
            is KtDeclaration -> element.docComment?.text
            is PsiDocCommentOwner -> element.docComment?.text
            else -> null
        } ?: return null to null
        return raw to cleanDocText(raw)
    }

    private fun cleanDocText(raw: String): String {
        // Strip /** */ markers and leading asterisks; preserve KDoc @tags.
        val withoutMarkers = raw.trim()
            .removePrefix("/**")
            .removeSuffix("*/")
        return withoutMarkers
            .lines()
            .joinToString("\n") { it.trim().removePrefix("*").removePrefix(" ") }
            .trim()
    }

    private fun errorResult(message: String): String =
        gson.toJson(GetKDocResponse(success = false, error = message))
}
