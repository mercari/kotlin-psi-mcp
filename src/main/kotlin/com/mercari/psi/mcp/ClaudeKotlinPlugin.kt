package com.mercari.psi.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Per-project startup hook. The server itself is an application-level singleton
 * ([PsiMcpServerManager]); the first project to open in this JVM triggers the
 * (idempotent) bind, and later opens are no-ops for the server. Which project is
 * actually served is decided by the manager's selection, controlled from
 * Settings ▸ Tools ▸ PSI MCP Server — not by open order.
 */
class PsiMcpActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        PsiMcpServerManager.getInstance().onProjectOpened()
    }
}
