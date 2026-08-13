package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

class GetContainingContextTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(GetContainingContextTool::class.java)
    
    data class ContainingContext(
        val containingClass: ClassInfo?,
        val containingMethod: MethodInfo?,
        val element: ElementInfo
    )
    
    data class ClassInfo(
        val name: String,
        val fullName: String?,
        val file: String,
        val line: Int,
        val isAbstract: Boolean,
        val isInterface: Boolean,
        val packageName: String?
    )
    
    data class MethodInfo(
        val name: String,
        val file: String,
        val line: Int,
        val parameters: List<String>,
        val returnType: String?,
        val isAbstract: Boolean,
        val isStatic: Boolean,
        val visibility: String
    )
    
    data class ElementInfo(
        val text: String,
        val type: String, // "class", "method", "property", "statement", etc.
        val file: String,
        val line: Int,
        val column: Int
    )
    
    data class GetContainingContextResponse(
        val success: Boolean,
        val context: ContainingContext?,
        val timestamp: Long,
        val error: String? = null
    )

    override fun getDescription(): String = "📍 CODE CONTEXT ANALYZER: Find the containing class and method for any code location using IntelliJ's PSI analysis - essential for understanding code context and navigation"

    override fun getInputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "file_path" to mapOf(
                "type" to "string", 
                "description" to "Absolute path to the file"
            ),
            "line" to mapOf(
                "type" to "integer",
                "description" to "Line number (1-based)"
            ),
            "column" to mapOf(
                "type" to "integer",
                "description" to "Column number (1-based)",
                "default" to 1
            )
        ),
        "required" to listOf("file_path", "line")
    )
    
    override fun execute(arguments: JsonObject): String {
        noProjectError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return createErrorResult("Missing 'file_path' parameter")
            
            val line = arguments.get("line")?.asInt
                ?: return createErrorResult("Missing 'line' parameter")
            
            val column = arguments.get("column")?.asInt ?: 1
            
            val context = findContainingContext(filePath, line, column)
            
            val response = GetContainingContextResponse(
                success = true,
                context = context,
                timestamp = System.currentTimeMillis()
            )
            
            gson.toJson(response)
            
        } catch (e: Exception) {
            logger.error("Error in GetContainingContextTool", e)
            createErrorResult("Internal error: ${e.message}")
        }
    }
    
    private fun findContainingContext(filePath: String, line: Int, column: Int): ContainingContext? {
        return ApplicationManager.getApplication().runReadAction<ContainingContext?> {
            val project = servedProject()
                ?: throw IllegalStateException(NO_PROJECT_MESSAGE)
            
            try {
            // Find the file
            val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
            if (virtualFile == null) return@runReadAction null
            
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile == null) return@runReadAction null
            
            // Convert line/column to offset (1-based to 0-based)
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            if (document == null) return@runReadAction null
            
            val offset = try {
                val lineStartOffset = document.getLineStartOffset(line - 1)
                lineStartOffset + (column - 1)
            } catch (e: Exception) {
                logger.warn("Invalid line/column position", e)
                return@runReadAction null
            }
            
            // Find element at position
            val element = psiFile.findElementAt(offset)
            if (element == null) return@runReadAction null
            
            // Find containing class
            val containingClass = findContainingClass(element)
            
            // Find containing method
            val containingMethod = findContainingMethod(element)
            
            // Create element info
            val elementInfo = createElementInfo(element, filePath, line, column)
            
                ContainingContext(
                    containingClass = containingClass,
                    containingMethod = containingMethod,
                    element = elementInfo
                )
                
            } catch (e: Exception) {
                logger.warn("Error finding containing context", e)
                null
            }
        }
    }
    
    private fun findContainingClass(element: PsiElement): ClassInfo? {
        // Try Kotlin class first
        val ktClass = PsiTreeUtil.getParentOfType(element, KtClass::class.java)
        if (ktClass != null) {
            return createClassInfoFromKt(ktClass)
        }
        
        // Try Java class
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (psiClass != null) {
            return createClassInfoFromPsi(psiClass)
        }
        
        return null
    }
    
    private fun findContainingMethod(element: PsiElement): MethodInfo? {
        // Try Kotlin function first
        val ktFunction = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
        if (ktFunction != null) {
            return createMethodInfoFromKt(ktFunction)
        }
        
        // Try Java method
        val psiMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (psiMethod != null) {
            return createMethodInfoFromPsi(psiMethod)
        }
        
        return null
    }
    
    private fun createClassInfoFromKt(ktClass: KtClass): ClassInfo? {
        return try {
            val containingFile = ktClass.containingFile
            val virtualFile = containingFile?.virtualFile
            val document = containingFile?.let { 
                PsiDocumentManager.getInstance(ktClass.project).getDocument(it)
            }
            
            val line = declLine(ktClass, document)

            ClassInfo(
                name = ktClass.name ?: "unknown",
                fullName = ktClass.fqName?.asString(),
                file = virtualFile?.path ?: "unknown",
                line = line,
                isAbstract = ktClass.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD),
                isInterface = ktClass.isInterface(),
                packageName = ktClass.fqName?.parent()?.asString()
            )
        } catch (e: Exception) {
            logger.warn("Error creating class info from Kotlin class", e)
            null
        }
    }
    
    private fun createClassInfoFromPsi(psiClass: PsiClass): ClassInfo? {
        return try {
            val containingFile = psiClass.containingFile
            val virtualFile = containingFile?.virtualFile
            val document = containingFile?.let { 
                PsiDocumentManager.getInstance(psiClass.project).getDocument(it)
            }
            
            val line = declLine(psiClass, document)

            ClassInfo(
                name = psiClass.name ?: "unknown",
                fullName = psiClass.qualifiedName,
                file = virtualFile?.path ?: "unknown",
                line = line,
                isAbstract = psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
                isInterface = psiClass.isInterface,
                packageName = psiClass.qualifiedName?.substringBeforeLast('.', "")
            )
        } catch (e: Exception) {
            logger.warn("Error creating class info from PSI class", e)
            null
        }
    }
    
    private fun createMethodInfoFromKt(ktFunction: KtNamedFunction): MethodInfo? {
        return try {
            val containingFile = ktFunction.containingFile
            val virtualFile = containingFile?.virtualFile
            val document = containingFile?.let { 
                PsiDocumentManager.getInstance(ktFunction.project).getDocument(it)
            }
            
            val line = declLine(ktFunction, document)

            val parameters = ktFunction.valueParameters.map { param ->
                "${param.name}: ${param.typeReference?.text ?: "Any"}"
            }
            
            MethodInfo(
                name = ktFunction.name ?: "unknown",
                file = virtualFile?.path ?: "unknown",
                line = line,
                parameters = parameters,
                returnType = ktFunction.typeReference?.text,
                isAbstract = ktFunction.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD),
                isStatic = false, // Kotlin doesn't have static methods in the same way
                visibility = when {
                    ktFunction.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) -> "private"
                    ktFunction.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD) -> "protected"
                    ktFunction.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD) -> "internal"
                    else -> "public"
                }
            )
        } catch (e: Exception) {
            logger.warn("Error creating method info from Kotlin function", e)
            null
        }
    }
    
    private fun createMethodInfoFromPsi(psiMethod: PsiMethod): MethodInfo? {
        return try {
            val containingFile = psiMethod.containingFile
            val virtualFile = containingFile?.virtualFile
            val document = containingFile?.let { 
                PsiDocumentManager.getInstance(psiMethod.project).getDocument(it)
            }
            
            val line = declLine(psiMethod, document)

            val parameters = psiMethod.parameterList.parameters.map { param ->
                "${param.name}: ${param.type.presentableText}"
            }
            
            val visibility = when {
                psiMethod.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC) -> "public"
                psiMethod.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE) -> "private"
                psiMethod.hasModifierProperty(com.intellij.psi.PsiModifier.PROTECTED) -> "protected"
                else -> "package-private"
            }
            
            MethodInfo(
                name = psiMethod.name,
                file = virtualFile?.path ?: "unknown",
                line = line,
                parameters = parameters,
                returnType = psiMethod.returnType?.presentableText,
                isAbstract = psiMethod.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT),
                isStatic = psiMethod.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC),
                visibility = visibility
            )
        } catch (e: Exception) {
            logger.warn("Error creating method info from PSI method", e)
            null
        }
    }
    
    /**
     * 1-based line of the declaration's name identifier. Anchoring on the name identifier (rather
     * than textRange.startOffset, which includes leading KDoc/annotations) makes the reported line
     * point at the `class`/`fun` itself, consistent with find-symbols / find-usages.
     */
    private fun declLine(element: PsiElement, document: Document?): Int {
        if (document == null) return 0
        val nameId = (element as? PsiNameIdentifierOwner)?.nameIdentifier
            ?: (element as? KtNamedDeclaration)?.nameIdentifier
        val offset = (nameId ?: element).textRange?.startOffset ?: 0
        return document.getLineNumber(offset) + 1
    }

    private fun createElementInfo(element: PsiElement, filePath: String, line: Int, column: Int): ElementInfo {
        val elementType = when (element.parent) {
            is KtClass -> "class"
            is KtNamedFunction -> "method"
            is KtProperty -> "property"
            is PsiClass -> "class"
            is PsiMethod -> "method"
            else -> "statement"
        }
        
        return ElementInfo(
            text = element.text.take(100), // Limit text length
            type = elementType,
            file = filePath,
            line = line,
            column = column
        )
    }
    
    private fun createErrorResult(message: String): String {
        val errorResponse = GetContainingContextResponse(
            success = false,
            context = null,
            timestamp = System.currentTimeMillis(),
            error = message
        )
        
        return gson.toJson(errorResponse)
    }
}