package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeNullability
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtVariableDeclaration
import org.jetbrains.kotlin.types.Variance
import java.io.File

@OptIn(KaExperimentalApi::class)
class GetTypeInfoTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(GetTypeInfoTool::class.java)

    data class TypeInfo(
        val rendered: String,                    // Short rendering: List<String>?
        val renderedFqn: String,                 // Qualified rendering: kotlin.collections.List<kotlin.String>?
        val nullability: String,                 // NULLABLE / NON_NULLABLE / UNKNOWN
        val fullyQualifiedName: String?,         // kotlin.collections.List (for class types)
        val typeArguments: List<String>,         // ["kotlin.String"]
        val kind: String,                        // "class" / "function" / "type-parameter" / "flexible" / "error" / "other"
        val isFunctionType: Boolean,
        val functionParameters: List<String>?,   // For function types
        val functionReturnType: String?          // For function types
    )

    data class GetTypeInfoResponse(
        val success: Boolean,
        val typeInfo: TypeInfo? = null,
        val reason: String? = null,              // Why typeInfo is null on success (benign "no type here")
        val elementText: String? = null,         // The actual text at the location
        val elementPsiType: String? = null,      // PSI node class (KtNameReferenceExpression, etc.)
        val resolvedVia: String? = null,         // "expression" / "declaration" / null
        val error: String? = null
    )

    override fun getDescription(): String =
        "Get resolved type information for an expression at a file position. " +
        "Works for Kotlin expressions, property/parameter/variable declarations. " +
        "Returns the rendered type, nullability, type arguments, fully qualified name, " +
        "and for function types, the parameter/return types. Uses K2 Analysis API. " +
        "A position with no value type (a class/object/typealias name, a keyword, or an " +
        "expression whose type can't be resolved) returns success:true with typeInfo:null and a `reason`."

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Absolute or relative path. Relative paths resolved against project root."
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line number (1-based)"
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column number (1-based)"
            )
        ),
        "required" to listOf("file_path", "line", "column")
    )

    override fun execute(arguments: JsonObject): String {
        dumbModeError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return errorResult("Missing required 'file_path'")
            val line = arguments.get("line")?.asInt
                ?: return errorResult("Missing required 'line'")
            val column = arguments.get("column")?.asInt
                ?: return errorResult("Missing required 'column'")

            val result = getTypeInfo(filePath, line, column)
            gson.toJson(result)
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in GetTypeInfoTool", e)
            errorResult("Internal error: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun getTypeInfo(filePath: String, line: Int, column: Int): GetTypeInfoResponse {
        val resolvedPath = resolveAbsolutePath(filePath)

        return ApplicationManager.getApplication().runReadAction<GetTypeInfoResponse> {
            val project = servedProject()
                ?: return@runReadAction errorResponse(NO_PROJECT_MESSAGE)

            val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                ?: return@runReadAction errorResponse("File not found: $resolvedPath")

            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@runReadAction errorResponse("Could not get PSI for file")

            if (psiFile !is KtFile) {
                return@runReadAction errorResponse(
                    "Only Kotlin files supported (file is ${psiFile.javaClass.simpleName})"
                )
            }

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadAction errorResponse("Could not get document")

            val offset = try {
                document.getLineStartOffset(line - 1) + (column - 1)
            } catch (e: Exception) {
                return@runReadAction errorResponse("Invalid line/column: line=$line, column=$column")
            }

            if (offset < 0 || offset > document.textLength) {
                return@runReadAction errorResponse("Offset out of bounds: $offset")
            }

            val leaf = psiFile.findElementAt(offset)
                ?: return@runReadAction errorResponse("No element at line $line, column $column")

            analyzeElement(leaf)
        }
    }

    private fun analyzeElement(leaf: PsiElement): GetTypeInfoResponse {
        // Walk up from leaf. Prefer the innermost non-declaration expression. If we hit a
        // declaration first (the cursor is on a property/function/parameter identifier), use
        // its declared/return type instead of continuing up — the enclosing block would give Unit.
        var current: PsiElement? = leaf
        while (current != null) {
            if (current is KtExpression && current !is KtDeclaration) {
                return analyzeExpression(leaf, current)
            }
            if (current is KtNamedDeclaration) {
                return analyzeDeclaration(leaf, current)
            }
            current = current.parent
        }
        // Benign "no type here" — not a failure. (§9 convention: success:true, typeInfo:null.)
        return GetTypeInfoResponse(
            success = true,
            typeInfo = null,
            reason = "no Kotlin expression or declaration at this position",
            elementText = leaf.text,
            elementPsiType = leaf.javaClass.simpleName
        )
    }

    private fun analyzeExpression(leaf: PsiElement, expression: KtExpression): GetTypeInfoResponse {
        return try {
            analyze(expression) {
                val type = expression.expressionType
                if (type == null) {
                    // Benign "no type here" — not a failure. (§9 convention.)
                    GetTypeInfoResponse(
                        success = true,
                        typeInfo = null,
                        reason = "expression has no resolvable type",
                        elementText = expression.text.take(120),
                        elementPsiType = expression.javaClass.simpleName
                    )
                } else {
                    GetTypeInfoResponse(
                        success = true,
                        typeInfo = buildTypeInfo(type),
                        elementText = expression.text.take(120),
                        elementPsiType = expression.javaClass.simpleName,
                        resolvedVia = "expression"
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("Expression analysis failed: ${e.message}")
            GetTypeInfoResponse(
                success = false,
                elementText = leaf.text,
                elementPsiType = expression.javaClass.simpleName,
                error = "Analysis failed: ${e.message}"
            )
        }
    }

    private fun analyzeDeclaration(leaf: PsiElement, declaration: KtNamedDeclaration): GetTypeInfoResponse {
        // Only value declarations (property/parameter/function/destructuring entry) have a return
        // type. A class/object/typealias/type-parameter name is not a value expression — asking for
        // its `returnType` throws ClassCastException. Discriminate on the callable/variable interfaces
        // (compile-available across IC and AS; KtDeclarationWithReturnType is not), then report a
        // benign "no value type" (not a failure, not a CCE).
        if (declaration !is KtCallableDeclaration && declaration !is KtVariableDeclaration) {
            return GetTypeInfoResponse(
                success = true,
                typeInfo = null,
                reason = "${declarationKindWord(declaration)} '${declaration.name ?: leaf.text}' has no value type",
                elementText = declaration.name ?: leaf.text,
                elementPsiType = declaration.javaClass.simpleName
            )
        }
        return try {
            analyze(declaration) {
                val type = declaration.returnType
                GetTypeInfoResponse(
                    success = true,
                    typeInfo = buildTypeInfo(type),
                    elementText = declaration.name ?: leaf.text,
                    elementPsiType = declaration.javaClass.simpleName,
                    resolvedVia = "declaration"
                )
            }
        } catch (e: Exception) {
            logger.warn("Declaration analysis failed: ${e.message}")
            GetTypeInfoResponse(
                success = false,
                elementText = leaf.text,
                elementPsiType = declaration.javaClass.simpleName,
                error = "Analysis failed: ${e.message}"
            )
        }
    }

    /** Human word for a non-value declaration kind, for the `reason` string. */
    private fun declarationKindWord(d: KtNamedDeclaration): String = when {
        d is KtTypeAlias -> "type alias"
        d is KtTypeParameter -> "type parameter"
        d is KtEnumEntry -> "enum entry"
        d is KtObjectDeclaration -> "object"
        d is KtClass && d.isInterface() -> "interface"
        d is KtClass -> "class"
        else -> "declaration"
    }

    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildTypeInfo(type: KaType): TypeInfo {
        val renderedShort = type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        val renderedFqn = type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)

        val nullability = when (type.nullability) {
            KaTypeNullability.NULLABLE -> "NULLABLE"
            KaTypeNullability.NON_NULLABLE -> "NON_NULLABLE"
            KaTypeNullability.UNKNOWN -> "UNKNOWN"
        }

        val fqn = (type as? KaClassType)?.classId?.asFqNameString()
        val typeArgs = (type as? KaClassType)?.typeArguments?.mapNotNull { arg ->
            arg.type?.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
        } ?: emptyList()

        val isFunctionType = type is KaFunctionType
        val (funcParams, funcReturn) = if (type is KaFunctionType) {
            val params = type.parameterTypes.map {
                it.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
            }
            val ret = type.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
            params to ret
        } else {
            null to null
        }

        val kind = when (type) {
            is KaClassType -> "class"
            is KaFunctionType -> "function"
            else -> type.javaClass.simpleName
        }

        return TypeInfo(
            rendered = renderedShort,
            renderedFqn = renderedFqn,
            nullability = nullability,
            fullyQualifiedName = fqn,
            typeArguments = typeArgs,
            kind = kind,
            isFunctionType = isFunctionType,
            functionParameters = funcParams,
            functionReturnType = funcReturn
        )
    }

    private fun errorResponse(message: String): GetTypeInfoResponse =
        GetTypeInfoResponse(success = false, error = message)

    private fun errorResult(message: String): String =
        gson.toJson(errorResponse(message))
}
