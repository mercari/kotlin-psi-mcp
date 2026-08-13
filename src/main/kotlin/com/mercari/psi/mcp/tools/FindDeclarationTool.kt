package com.mercari.psi.mcp.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*

@OptIn(KaExperimentalApi::class)
class FindDeclarationTool : Tool {
    private val gson = Gson()
    private val logger = Logger.getInstance(FindDeclarationTool::class.java)
    
    data class DeclarationResult(
        val name: String,
        val type: String, // "class", "function", "property", "parameter", "variable", etc.
        val file: String,
        val line: Int,
        val column: Int,
        val packageName: String?,
        val signature: String?,
        val containingClass: String?,
        val isLocal: Boolean, // true if defined in same file, false if external
        val annotations: List<String> // List of annotation names (e.g., ["Composable", "Preview", "JvmStatic"])
   )
    
    data class FindDeclarationResponse(
        val success: Boolean,
        val declaration: DeclarationResult?,
        val sourceElement: String?, // What element was clicked on
        val sourceElementType: String?,
        val timestamp: Long,
        val error: String? = null
    )

    override fun getDescription(): String = "🎯 GO TO DECLARATION: Navigate to the exact declaration of the symbol at a file position using IntelliJ's PSI analysis. Points to the precise line where a symbol is declared, not documentation or surrounding context. Returns success:false when the symbol at the position cannot be resolved (does not fall back to the enclosing declaration)."
    
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
        noProjectError(servedProject())?.let { return it }
        return try {
            val filePath = arguments.get("file_path")?.asString
                ?: return createErrorResult("Missing 'file_path' parameter")
            
            val line = arguments.get("line")?.asInt
                ?: return createErrorResult("Missing 'line' parameter")
            
            val column = arguments.get("column")?.asInt
                ?: return createErrorResult("Missing 'column' parameter")
            
            val result = findDeclaration(filePath, line, column)
            
            gson.toJson(result)
            
        } catch (e: Exception) {
            dumbModeErrorFor(e)?.let { return it }
            logger.error("Error in FindDeclarationTool", e)
            createErrorResult("Internal error: ${e.message}")
        }
    }
    
    private fun resolveAbsolutePath(path: String): String {
        if (File(path).isAbsolute) return path
        val project = servedProject()
        val basePath = project?.basePath ?: return path
        return File(basePath, path).absolutePath
    }

    private fun findDeclaration(filePath: String, line: Int, column: Int): FindDeclarationResponse {
        val resolvedPath = resolveAbsolutePath(filePath)
        return ApplicationManager.getApplication().runReadAction<FindDeclarationResponse> {
            val project = servedProject()
                ?: throw IllegalStateException(NO_PROJECT_MESSAGE)

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            ?: throw IllegalArgumentException("File not found: $resolvedPath")

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: throw IllegalArgumentException("Could not get PSI for file: $resolvedPath")
        
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: throw IllegalArgumentException("Could not get document for file: $resolvedPath")
        
        // Convert 1-based input to 0-based internal coordinates
        val offset = try {
            val lineStartOffset = document.getLineStartOffset(line - 1)
            lineStartOffset + (column - 1)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid line/column position: line=$line, column=$column. Error: ${e.message}")
        }
        
        // Validate offset is within document bounds
        if (offset < 0 || offset >= document.textLength) {
            throw IllegalArgumentException("Position out of bounds: offset=$offset, document length=${document.textLength}")
        }
        
        val element = psiFile.findElementAt(offset)
            ?: throw IllegalArgumentException("No element found at line $line, column $column")
        
        logger.info("Finding declaration for element: '${element.text}' (type: ${element.javaClass.simpleName}) at $resolvedPath:$line:$column")
        
        // Resolve to the declaration
        val declaration = resolveToDeclaration(element)
        if (declaration == null) {
            return@runReadAction FindDeclarationResponse(
                success = false,
                declaration = null,
                sourceElement = element.text,
                sourceElementType = element.javaClass.simpleName,
                timestamp = System.currentTimeMillis(),
                error = "Could not resolve declaration for '${element.text}'"
            )
        }

        val declarationResult = createDeclarationResult(declaration, psiFile)
        if (declarationResult == null) {
            return@runReadAction FindDeclarationResponse(
                success = false,
                declaration = null,
                sourceElement = element.text,
                sourceElementType = element.javaClass.simpleName,
                timestamp = System.currentTimeMillis(),
                error = "Could not create declaration result"
            )
        }
        
            FindDeclarationResponse(
                success = true,
                declaration = declarationResult,
                sourceElement = element.text,
                sourceElementType = element.javaClass.simpleName,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    private fun resolveToDeclaration(element: PsiElement): PsiElement? {
        // This method should only be called from within a read action
        ApplicationManager.getApplication().assertReadAccessAllowed()

        // (1) Kotlin references (calls, type references, property accesses, ...).
        //     The generic PsiElement.reference path returns null for
        //     cross-module / library symbols under the K2 Kotlin plugin (local
        //     symbols happen to resolve, external ones do not), so we resolve via
        //     the Kotlin main reference and, if that comes back empty, via the
        //     K2 Analysis API.
        val ktRef = PsiTreeUtil.getParentOfType(element, KtSimpleNameExpression::class.java, false)
        if (ktRef != null) {
            resolveKotlinReference(ktRef)?.let { return it }
        }

        // (2) Generic PSI reference resolution — primarily Java. Walk up only
        //     within the current expression and stop at the first enclosing
        //     declaration, so we never fall through and return that declaration.
        var current: PsiElement? = element
        while (current != null && current !is KtDeclaration && current !is PsiMember) {
            current.reference?.resolve()?.let { return it }
            current = current.parent
        }

        // (3) The cursor may sit directly on a declaration's own name identifier
        //     ("go to declaration" invoked on the declaration itself).
        //
        //     IMPORTANT: only return an enclosing declaration when the click is
        //     on its name identifier — NOT when we merely walked up into it from
        //     an unresolved reference in its body. That was the bug: clicking a
        //     call such as `SectionTitle(...)` whose reference failed to resolve
        //     returned the *enclosing* function instead of an honest "not found".
        return findDeclarationByNameIdentifier(element)
    }

    /**
     * Resolves a Kotlin reference expression to its declaration PSI. Tries the
     * cheap main-reference [resolve][org.jetbrains.kotlin.idea.references.KtReference.resolve]
     * first, then falls back to the K2 Analysis API, which reliably resolves
     * cross-module and library symbols.
     */
    private fun resolveKotlinReference(refExpr: KtSimpleNameExpression): PsiElement? {
        val ktSimpleNameReference = refExpr.mainReference
        try {
            ktSimpleNameReference.resolve()?.let { return it }
        } catch (e: Exception) {
            logger.warn("mainReference.resolve() failed for '${refExpr.text}': ${e.message}")
        }
        return try {
            analyze(refExpr) {
                ktSimpleNameReference.resolveToSymbols().firstNotNullOfOrNull { it.psi }
            }
        } catch (e: Exception) {
            logger.warn("K2 resolveToSymbols failed for '${refExpr.text}': ${e.message}")
            null
        }
    }

    /**
     * Returns the nearest enclosing declaration only if [element] lies within
     * that declaration's name identifier. Returns null if the first enclosing
     * declaration is reached without the click being on its name (i.e. the click
     * was on a reference inside the declaration's body).
     */
    private fun findDeclarationByNameIdentifier(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            if (isDeclarationElement(current)) {
                val nameId = getNameIdentifier(current)
                return if (nameId != null && nameId.textRange.contains(element.textRange)) current else null
            }
            current = current.parent
        }
        return null
    }
    
    private fun isDeclarationElement(element: PsiElement): Boolean {
        return when (element) {
            is PsiParameter,
            is PsiVariable,
            is PsiMethod,
            is PsiClass,
            is PsiField,
            is KtParameter,
            is KtProperty,
            is KtVariableDeclaration,
            is KtNamedFunction,
            is KtClass,
            is KtObjectDeclaration,
            is KtTypeAlias,
            is PsiNameIdentifierOwner -> true
            else -> false
        }
    }
    
    private fun createDeclarationResult(declaration: PsiElement, sourceFile: PsiFile): DeclarationResult? {
        return try {
            val containingFile = declaration.containingFile
            val virtualFile = containingFile?.virtualFile
            val document = containingFile?.let { 
                PsiDocumentManager.getInstance(declaration.project).getDocument(it)
            }
            
            if (virtualFile == null || document == null) {
                return null
            }
            
            // Get the name identifier for precise positioning
            val nameIdentifier = getNameIdentifier(declaration)
            val positionElement = nameIdentifier ?: declaration
            
            val textRange = positionElement.textRange
            if (textRange == null) {
                logger.warn("Element has no text range: ${declaration.javaClass.simpleName}")
                return null
            }
            
            // Get precise line/column for the name identifier
            val docLineNumber = document.getLineNumber(textRange.startOffset)
            val line = docLineNumber + 1 // Convert to 1-based
            val column = textRange.startOffset - document.getLineStartOffset(docLineNumber) + 1 // Convert to 1-based
            
            // Determine element type
            val elementType = when (declaration) {
                is KtClass -> "class"
                is KtNamedFunction -> "function"
                is KtProperty -> "property"
                is KtParameter -> "parameter"
                is KtVariableDeclaration -> "variable"
                is PsiClass -> "class"
                is PsiMethod -> "method"
                is PsiField -> "field"
                is PsiParameter -> "parameter"
                is PsiVariable -> "variable"
                else -> "declaration"
            }
            
            // Get package name
            val packageName = when (declaration) {
                is KtNamedDeclaration -> declaration.fqName?.parent()?.asString()
                is PsiClass -> declaration.qualifiedName?.substringBeforeLast('.')
                is PsiMethod -> declaration.containingClass?.qualifiedName?.substringBeforeLast('.')
                is PsiField -> declaration.containingClass?.qualifiedName?.substringBeforeLast('.')
                else -> null
            }
            
            // Get signature
            val signature = when (declaration) {
                is KtNamedFunction -> "${declaration.name}(${declaration.valueParameters.joinToString(", ") { "${it.name}: ${it.typeReference?.text ?: "?"}" }})"
                is PsiMethod -> "${declaration.name}(${declaration.parameterList.parameters.joinToString(", ") { "${it.name}: ${it.type.presentableText}" }})"
                is KtProperty -> "${declaration.name}: ${declaration.typeReference?.text ?: "?"}"
                is PsiField -> "${declaration.name}: ${declaration.type.presentableText}"
                is KtParameter -> "${declaration.name}: ${declaration.typeReference?.text ?: "?"}"
                is PsiParameter -> "${declaration.name}: ${declaration.type.presentableText}"
                else -> declaration.text?.take(100)
            }
            
            // Get containing class
            val containingClass = findContainingClass(declaration)
            
            // Check if declaration is in the same file as source
            val isLocal = containingFile == sourceFile
            
            // Extract annotations
            val annotations = extractAnnotations(declaration)
            
            DeclarationResult(
                name = getElementName(declaration),
                type = elementType,
                file = virtualFile.path,
                line = line,
                column = column,
                packageName = packageName,
                signature = signature,
                containingClass = containingClass,
                isLocal = isLocal,
                annotations = annotations
            )
        } catch (e: Exception) {
            logger.warn("Error creating declaration result for ${declaration.javaClass.simpleName}: ${e.message}")
            null
        }
    }
    
    private fun getNameIdentifier(element: PsiElement): PsiElement? {
        return when (element) {
            is PsiNameIdentifierOwner -> element.nameIdentifier
            is KtNamedDeclaration -> element.nameIdentifier
            else -> null
        }
    }
    
    private fun getElementName(element: PsiElement): String {
        return when (element) {
            is KtNamedDeclaration -> element.name ?: "unknown"
            is PsiNamedElement -> element.name ?: "unknown"
            else -> element.text?.take(50) ?: "unknown"
        }
    }
    
    private fun findContainingClass(element: PsiElement): String? {
        var current = element.parent
        while (current != null) {
            when (current) {
                is KtClass -> return current.name
                is PsiClass -> return current.name
            }
            current = current.parent
        }
        return null
    }
    
    private fun extractAnnotations(element: PsiElement): List<String> {
        val annotations = mutableListOf<String>()
        
        when (element) {
            is KtModifierListOwner -> {
                element.annotationEntries.forEach { annotation ->
                    val annotationName = annotation.shortName?.asString()
                    if (annotationName != null) {
                        annotations.add(annotationName)
                    }
                }
            }
            is PsiModifierListOwner -> {
                element.annotations.forEach { annotation ->
                    val annotationName = annotation.qualifiedName?.substringAfterLast('.') 
                        ?: annotation.nameReferenceElement?.referenceName
                    if (annotationName != null) {
                        annotations.add(annotationName)
                    }
                }
            }
        }
        
        return annotations
    }
    
    private fun createErrorResult(message: String): String {
        val errorResponse = FindDeclarationResponse(
            success = false,
            declaration = null,
            sourceElement = null,
            sourceElementType = null,
            timestamp = System.currentTimeMillis(),
            error = message
        )
        
        return gson.toJson(errorResponse)
    }
}