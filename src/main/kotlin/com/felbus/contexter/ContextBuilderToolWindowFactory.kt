package com.felbus.contexter

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ContextBuilderToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contextBuilderPanel = ContextBuilderPanel(project)

        // Use getInstance() instead of SERVICE
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(contextBuilderPanel, "", false)

        toolWindow.contentManager.addContent(content)
    }
}
