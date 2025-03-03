package com.felbus.contexter

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class ContextBuilderAction : AnAction("Open Context Builder") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ContextBuilder")
        toolWindow?.show(null) // Opens the tool window
    }
}
