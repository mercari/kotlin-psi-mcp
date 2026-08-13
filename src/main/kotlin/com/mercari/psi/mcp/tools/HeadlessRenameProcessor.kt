package com.mercari.psi.mcp.tools

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.ConflictsDialogBase
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * A [RenameProcessor] that never opens modal UI, so it can run from the HTTP server thread.
 *
 * The stock processor pops two modal dialogs mid-refactoring — the automatic-renaming dialog and,
 * inside `preprocessUsages`, the conflicts dialog (built directly via [prepareConflictsDialog], not
 * routed through an overridable `showConflicts`). In a headless/server context nothing dismisses
 * them, so the EDT blocks forever and the whole request hangs. Both overrides below auto-proceed;
 * any conflicts are captured into [capturedConflicts] for the tool to surface as warnings.
 *
 * (Approach mirrors the hechtcarmel jetbrains-index-mcp plugin's HeadlessRenameProcessor.)
 */
internal class HeadlessRenameProcessor(
    project: Project,
    element: PsiElement,
    newName: String,
    searchInComments: Boolean,
    searchTextOccurrences: Boolean
) : RenameProcessor(project, element, newName, searchInComments, searchTextOccurrences) {

    /** Conflict messages collected instead of shown; surfaced by the tool as result warnings. */
    val capturedConflicts = mutableListOf<String>()

    override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer): Boolean {
        // Apply every suggested automatic rename without prompting.
        for (element in automaticVariableRenamer.elements) {
            val suggestedName = automaticVariableRenamer.getNewName(element) ?: continue
            val namedElement = element as? PsiNamedElement ?: continue
            automaticVariableRenamer.setRename(namedElement, suggestedName)
        }
        return true
    }

    public override fun prepareConflictsDialog(
        conflicts: MultiMap<PsiElement, String>,
        usages: Array<out UsageInfo>?
    ): ConflictsDialogBase {
        capturedConflicts.addAll(conflicts.values().map { it.replace(Regex("<[^>]+>"), "").trim() })
        // A stub that always proceeds and never shows — keeps the rename headless.
        return object : ConflictsDialogBase {
            override fun setCommandName(name: String?) {}
            override fun showAndGet(): Boolean = true
            override fun isShowConflicts(): Boolean = false
        }
    }
}
