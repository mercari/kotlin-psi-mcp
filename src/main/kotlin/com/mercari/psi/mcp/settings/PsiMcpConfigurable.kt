package com.mercari.psi.mcp.settings

import com.mercari.psi.mcp.PsiMcpServerManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Box

/**
 * Application-level settings page (Settings ▸ Tools ▸ PSI MCP Server).
 *
 * Two controls back the multi-IDE model:
 *  - the **enable** switch is this IDE's bid to own the single fixed port; and
 *  - the **served-project** dropdown picks which open project tools resolve
 *    against.
 *
 * The dropdown is rebuilt from the live open-projects list every time the page
 * opens ([createComponent]), so it self-refreshes without a listener. Changes
 * apply live (no restart) via [PsiMcpServerManager].
 */
class PsiMcpConfigurable : Configurable {

    private val manager get() = PsiMcpServerManager.getInstance()

    private lateinit var enabledCheckBox: JBCheckBox
    private lateinit var projectCombo: ComboBox<Project>
    private lateinit var statusLabel: JBLabel
    private lateinit var reconnectButton: JButton
    private lateinit var mainPanel: JPanel

    override fun getDisplayName(): String = "PSI MCP Server"

    override fun createComponent(): JComponent {
        enabledCheckBox = JBCheckBox("Enable PSI MCP Server (serve on port ${PsiMcpServerManager.PORT})", manager.isEnabled)

        projectCombo = ComboBox(DefaultComboBoxModel(manager.openProjectsSnapshot().toTypedArray())).apply {
            renderer = SimpleListCellRenderer.create<Project>("(no project open)") { value ->
                "${value.name}  —  ${value.basePath ?: "<no path>"}"
            }
            manager.selectedProject()?.let { selectedItem = it }
        }

        statusLabel = JBLabel()

        // "Serve here now" — retries the bind directly (take over the port after
        // another IDE released it) without the disable→enable dance.
        reconnectButton = JButton("Reconnect").apply {
            toolTipText = "Enable and (re)bind port ${PsiMcpServerManager.PORT} in this IDE now, " +
                "taking over from another instance that has released it."
            addActionListener {
                (projectCombo.selectedItem as? Project)?.let { manager.setSelectedProject(it) }
                manager.reconnect()
                enabledCheckBox.isSelected = true
                projectCombo.isEnabled = true
                updateStatus()
            }
        }

        // Cosmetic: grey the dropdown when the switch is off (real effect on Apply).
        enabledCheckBox.addActionListener { projectCombo.isEnabled = enabledCheckBox.isSelected }
        projectCombo.isEnabled = enabledCheckBox.isSelected

        fun createCopyableTextArea(text: String): JBTextArea =
            JBTextArea(text).apply {
                isEditable = false
                background = UIUtil.getPanelBackground()
                font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                lineWrap = false
                border = null
            }

        val port = PsiMcpServerManager.PORT
        val configPanel = FormBuilder.createFormBuilder()
            .addComponent(enabledCheckBox)
            .addLabeledComponent("Served project:", projectCombo)
            .addComponent(statusLabel)
            .addComponent(reconnectButton)
            .addComponent(
                JBLabel("<html><i>Only one project is served at a time. With several open, pick which one " +
                    "tools resolve against. A second IDE that can't take the port will show an error here.</i></html>")
            )
            .addSeparator()
            .addComponent(JBLabel("<html><b>Testing the HTTP API:</b></html>"))
            .addVerticalGap(8)
            .addComponent(JBLabel("<html><i>Check server health:</i></html>"))
            .addComponent(createCopyableTextArea("curl http://localhost:$port/health"))
            .addVerticalGap(12)
            .addComponent(JBLabel("<html><i>List available tools:</i></html>"))
            .addComponent(createCopyableTextArea("curl http://localhost:$port/api/tools"))
            .addVerticalGap(12)
            .addComponent(JBLabel("<html><i>Test find-symbols tool:</i></html>"))
            .addComponent(
                createCopyableTextArea("curl -X POST http://localhost:$port/api/tools/find-symbols \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"symbol_name\": \"MainActivity\"}'")
            )
            .panel

        mainPanel = JPanel(BorderLayout())
        mainPanel.add(configPanel, BorderLayout.NORTH)
        mainPanel.add(Box.createVerticalGlue(), BorderLayout.CENTER)

        updateStatus()
        return mainPanel
    }

    override fun isModified(): Boolean =
        enabledCheckBox.isSelected != manager.isEnabled ||
        (projectCombo.selectedItem as? Project) != manager.selectedProject()

    override fun apply() {
        (projectCombo.selectedItem as? Project)?.let { manager.setSelectedProject(it) }
        // setEnabled applies the start/stop (or rebind attempt) live.
        manager.setEnabled(enabledCheckBox.isSelected)
        updateStatus()
    }

    override fun reset() {
        enabledCheckBox.isSelected = manager.isEnabled
        projectCombo.model = DefaultComboBoxModel(manager.openProjectsSnapshot().toTypedArray())
        manager.selectedProject()?.let { projectCombo.selectedItem = it }
        projectCombo.isEnabled = enabledCheckBox.isSelected
        updateStatus()
    }

    /** Reflect the manager's real runtime state (serving / bind error / off). */
    private fun updateStatus() {
        val defaultFg = UIUtil.getLabelForeground()
        when {
            !manager.isEnabled -> {
                statusLabel.text = "Server is disabled in this IDE."
                statusLabel.foreground = defaultFg
            }
            manager.bindError != null -> {
                statusLabel.text = "<html>⚠ Port ${PsiMcpServerManager.PORT} is held by another IDE instance.<br>" +
                    "Disable the server in that IDE, then click <b>Reconnect</b> here.</html>"
                statusLabel.foreground = JBColor.RED
            }
            manager.isServing -> {
                val served = manager.selectedProject()
                statusLabel.text = "✓ Serving \"${served?.name ?: "—"}\" on port ${PsiMcpServerManager.PORT}."
                statusLabel.foreground = defaultFg
            }
            else -> {
                statusLabel.text = "Server enabled but not serving."
                statusLabel.foreground = defaultFg
            }
        }
    }
}
