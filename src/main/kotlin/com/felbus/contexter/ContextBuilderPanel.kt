package com.felbus.contexter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*

class ContextBuilderPanel(private val project: Project) : JPanel(BorderLayout()) {

    // Collect file suggestions (relative paths) by scanning the project's root directory
    private val fileSuggestions: List<String> = run {
        val suggestions = mutableListOf<String>()
        val baseDir = project.guessProjectDir()
        if (baseDir != null) {
            VfsUtilCore.visitChildrenRecursively(baseDir, object : VirtualFileVisitor<Any>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory) {
                        VfsUtilCore.getRelativePath(file, baseDir, '/')?.let { suggestions.add(it) }
                    }
                    return true
                }
            })
        }
        suggestions
    }

    // Create a custom provider for auto-completion
    private val fileSuggestionsProvider = object : TextFieldWithAutoCompletionListProvider<String>(fileSuggestions) {
        override fun getLookupString(item: String): String = item
    }

    // Auto-completion text field for file paths (relative to project root)
    private val autoCompleteFileField = TextFieldWithAutoCompletion<String>(
        project,
        fileSuggestionsProvider,
        true,
        ""
    ).also {
        val standardHeight = JTextField(20).preferredSize.height
        it.preferredSize = Dimension(300, standardHeight)
        it.maximumSize = Dimension(Integer.MAX_VALUE, standardHeight)
        it.minimumSize = Dimension(100, standardHeight)
    }

    // Text area to hold aggregated code context
    private val contextTextArea = JBTextArea(20, 50)

    // Scroll pane for the context area
    private val scrollPane = JBScrollPane(contextTextArea)

    // Free text box for adding extra code manually
    private val extraCodeTextArea = JBTextArea(5, 50)

    // Scroll pane for the extra code box
    private val extraCodeScrollPane = JBScrollPane(extraCodeTextArea)

    // Text box for adding a final prompt question
    private val promptTextArea = JBTextArea(3, 50)

    // Scroll pane for the prompt box
    private val promptScrollPane = JBScrollPane(promptTextArea)

    init {
        // File selection panel
        val filePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("File: "))
            add(autoCompleteFileField)

            val browseButton = JButton("Browse")
            browseButton.addActionListener {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                val baseDir = project.guessProjectDir()
                val virtualFile: VirtualFile? = FileChooser.chooseFile(descriptor, project, baseDir)
                if (virtualFile != null && baseDir != null) {
                    val relativePath = VfsUtilCore.getRelativePath(virtualFile, baseDir, '/')
                    autoCompleteFileField.text = relativePath ?: virtualFile.path
                }
            }
            add(browseButton)

            val addButton = JButton("Add File")
            addButton.addActionListener { addFileContent() }
            add(addButton)
        }

        // Extra code input panel
        val extraCodePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Add extra code:"))
            add(extraCodeScrollPane)
            val addExtraCodeButton = JButton("Add Code to Context")
            addExtraCodeButton.addActionListener { addExtraCode() }
            add(addExtraCodeButton)
        }

        // Prompt input panel
        val promptPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Final Prompt Question:"))
            add(promptScrollPane)
            val addPromptButton = JButton("Add Prompt to Context")
            addPromptButton.addActionListener { addPrompt() }
            add(addPromptButton)
        }

        // Actions panel (Copy & Clear)
        val actionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)

            val copyButton = JButton("Copy to Clipboard")
            copyButton.addActionListener {
                val text = contextTextArea.text
                val selection = StringSelection(text)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            }
            add(copyButton)

            val clearButton = JButton("Clear")
            clearButton.addActionListener {
                contextTextArea.text = ""
            }
            add(clearButton)
        }

        // Main panel
        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(filePanel)
            add(extraCodePanel)
            add(promptPanel)
            add(scrollPane)
            add(actionsPanel)
        }

        add(mainPanel, BorderLayout.CENTER)

        // Load project context from contexter.txt
        loadProjectContext()
    }

    private fun addFileContent() {
        val relativePath = autoCompleteFileField.text.trim()
        if (relativePath.isNotEmpty()) {
            val basePath = project.basePath ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                val file = File(basePath, relativePath)
                val virtualFile = VfsUtil.findFileByIoFile(file, true)
                val fileContent = virtualFile?.let { VfsUtilCore.loadText(it) }
                ApplicationManager.getApplication().invokeLater {
                    if (fileContent != null) {
                        contextTextArea.append("\n// Added file: $relativePath\n")
                        contextTextArea.append(fileContent)
                        contextTextArea.append("\n")
                    } else {
                        contextTextArea.append("\n// Could not locate file: $relativePath\n")
                    }
                    autoCompleteFileField.text = ""
                }
            }
        }
    }

    private fun addExtraCode() {
        val extraCode = extraCodeTextArea.text.trim()
        if (extraCode.isNotEmpty()) {
            contextTextArea.append("\n// Extra Code\n")
            contextTextArea.append(extraCode)
            contextTextArea.append("\n")
            extraCodeTextArea.text = ""
        }
    }

    private fun addPrompt() {
        val prompt = promptTextArea.text.trim()
        if (prompt.isNotEmpty()) {
            contextTextArea.append("\n// Final Prompt Question\n")
            contextTextArea.append(prompt)
            contextTextArea.append("\n")
            promptTextArea.text = ""
        }
    }

    private fun loadProjectContext() {
        val basePath = project.basePath ?: return
        val contextFile = File(basePath, "contexter.txt")
        ApplicationManager.getApplication().executeOnPooledThread {
            val virtualFile = VfsUtil.findFileByIoFile(contextFile, true)
            val fileContent = virtualFile?.let { VfsUtilCore.loadText(it) }
            ApplicationManager.getApplication().invokeLater {
                if (fileContent != null) {
                    contextTextArea.append("// Project context from contexter.txt\n")
                    contextTextArea.append(fileContent)
                    contextTextArea.append("\n")
                } else {
                    contextTextArea.append("// No project context found (contexter.txt not present)\n")
                }
            }
        }
    }
}
