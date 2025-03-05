package com.felbus.contexter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
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
import javax.swing.*

/**
 * Represents information about a discovered function/method: its signature (e.g. "foo()")
 * and the entire snippet containing that function's body.
 */
data class FunctionInfo(
    val signature: String,
    val content: String
)

class ContextBuilderPanel(private val project: Project) : JPanel(BorderLayout()) {

    // -----------------------------------------------------------------------------------------
    // 1. FILE SUGGESTIONS
    // -----------------------------------------------------------------------------------------

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

    // Create a custom provider for auto-completion of file paths
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
        it.maximumSize = Dimension(Int.MAX_VALUE, standardHeight)
        it.minimumSize = Dimension(100, standardHeight)
    }

    // -----------------------------------------------------------------------------------------
    // 2. FUNCTION/METHOD SUGGESTIONS
    // -----------------------------------------------------------------------------------------

    // Collect function definitions from the project and build up a list of FunctionInfo objects.
    // This is a NAIVE approach: scanning file lines with minimal checks.
    private val functionList: List<FunctionInfo> = run {
        val collected = mutableListOf<FunctionInfo>()
        val baseDir = project.guessProjectDir()
        if (baseDir != null) {
            VfsUtilCore.visitChildrenRecursively(baseDir, object : VirtualFileVisitor<Any>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    // We'll do a simplistic check on file extensions:
                    val supportedExtensions = listOf("java", "kt", "py", "js", "ts", "cpp", "h", "c", "cs")
                    if (!file.isDirectory && supportedExtensions.any { file.name.endsWith(".$it") }) {
                        val fileText = VfsUtilCore.loadText(file)
                        // Very naive check for function definitions
                        val lines = fileText.lines()
                        var i = 0
                        while (i < lines.size) {
                            val line = lines[i]
                            if (isPossibleFunctionSignature(line)) {
                                // Collect the entire function snippet
                                val snippet = collectFunctionSnippet(lines, i, file.extension)
                                // Create a naive "signature"
                                val signature = extractSignature(line).trim()
                                if (signature.isNotEmpty()) {
                                    collected.add(
                                        FunctionInfo(
                                            signature = signature,
                                            content = snippet
                                        )
                                    )
                                }
                            }
                            i++
                        }
                    }
                    return true
                }
            })
        }
        collected
    }

    // A simple map from function signature -> entire snippet
    private val functionMap: Map<String, String> = functionList.associate { it.signature to it.content }

    // Extract just the signatures for autocompletion
    private val functionSuggestions: List<String> = functionList.map { it.signature }

    // Create a provider for function auto-completion
    private val functionSuggestionsProvider = object : TextFieldWithAutoCompletionListProvider<String>(functionSuggestions) {
        override fun getLookupString(item: String): String = item
    }

    // Auto-completion text field for function signatures
    private val autoCompleteFunctionField = TextFieldWithAutoCompletion<String>(
        project,
        functionSuggestionsProvider,
        true,
        ""
    ).also {
        val standardHeight = JTextField(20).preferredSize.height
        it.preferredSize = Dimension(300, standardHeight)
        it.maximumSize = Dimension(Int.MAX_VALUE, standardHeight)
        it.minimumSize = Dimension(100, standardHeight)
    }

    // -----------------------------------------------------------------------------------------
    // 3. CONTEXT TEXT AREA (Main Aggregated Output)
    // -----------------------------------------------------------------------------------------

    // Text area to hold aggregated code context
    private val contextTextArea = JBTextArea(20, 50)

    // Scroll pane for the context area
    private val scrollPane = JBScrollPane(contextTextArea)

    // -----------------------------------------------------------------------------------------
    // 4. EXTRA CODE AND PROMPT TEXT AREAS
    // -----------------------------------------------------------------------------------------

    // Free text box for adding extra code manually
    private val extraCodeTextArea = JBTextArea(5, 50)
    private val extraCodeScrollPane = JBScrollPane(extraCodeTextArea)

    // Text box for adding a final prompt question
    private val promptTextArea = JBTextArea(3, 50)
    private val promptScrollPane = JBScrollPane(promptTextArea)

    // -----------------------------------------------------------------------------------------
    // 5. CONSTRUCTOR: BUILD THE UI
    // -----------------------------------------------------------------------------------------
    init {
        // 5a. File selection panel
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

        // 5b. Function selection panel (NEW)
        val functionPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("Function: "))
            add(autoCompleteFunctionField)

            val addFunctionButton = JButton("Add Function")
            addFunctionButton.addActionListener { addFunctionContent() }
            add(addFunctionButton)
        }

        // 5c. Extra code input panel
        val extraCodePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Add extra code:"))
            add(extraCodeScrollPane)
            val addExtraCodeButton = JButton("Add Code to Context")
            addExtraCodeButton.addActionListener { addExtraCode() }
            add(addExtraCodeButton)
        }

        // 5d. Prompt input panel
        val promptPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Final Prompt Question:"))
            add(promptScrollPane)
            val addPromptButton = JButton("Add Prompt to Context")
            addPromptButton.addActionListener { addPrompt() }
            add(addPromptButton)
        }

        // 5e. Actions panel (Copy & Clear)
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

        // 5f. Main panel
        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(filePanel)        // Existing file search
            add(functionPanel)    // NEW function search
            add(extraCodePanel)   // Extra code
            add(promptPanel)      // Prompt
            add(scrollPane)       // Main text area
            add(actionsPanel)     // Copy & Clear
        }

        add(mainPanel, BorderLayout.CENTER)

        // 5g. Load project context from contexter.txt
        loadProjectContext()
    }

    // -----------------------------------------------------------------------------------------
    // 6. ACTIONS / HELPERS
    // -----------------------------------------------------------------------------------------

    /**
     * Adds the selected file content to the context.
     */
    private fun addFileContent() {
        val relativePath = autoCompleteFileField.text.trim()
        if (relativePath.isNotEmpty()) {
            val baseDir = project.guessProjectDir() ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                val file = baseDir.findFileByRelativePath(relativePath)
                val fileContent = file?.let { VfsUtilCore.loadText(it) }
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

    /**
     * Adds the selected function snippet to the context.
     */
    private fun addFunctionContent() {
        val signature = autoCompleteFunctionField.text.trim()
        if (signature.isNotEmpty()) {
            val snippet = functionMap[signature]
            if (snippet != null) {
                contextTextArea.append("\n// Added function: $signature\n")
                contextTextArea.append(snippet)
                contextTextArea.append("\n")
            } else {
                contextTextArea.append("\n// Could not find function snippet: $signature\n")
            }
            autoCompleteFunctionField.text = ""
        }
    }

    /**
     * Adds arbitrary code to the context.
     */
    private fun addExtraCode() {
        val extraCode = extraCodeTextArea.text.trim()
        if (extraCode.isNotEmpty()) {
            contextTextArea.append("\n// Extra Code\n")
            contextTextArea.append(extraCode)
            contextTextArea.append("\n")
            extraCodeTextArea.text = ""
        }
    }

    /**
     * Adds the final prompt to the context.
     */
    private fun addPrompt() {
        val prompt = promptTextArea.text.trim()
        if (prompt.isNotEmpty()) {
            contextTextArea.append("\n// Final Prompt Question\n")
            contextTextArea.append(prompt)
            contextTextArea.append("\n")
            promptTextArea.text = ""
        }
    }

    /**
     * Loads any existing project context from 'contexter.txt', if present at the project root.
     */
    private fun loadProjectContext() {
        val baseDir = project.guessProjectDir() ?: return
        val contexterFile = baseDir.findChild("contexter.txt") // Tries to find "contexter.txt" in root
        ApplicationManager.getApplication().executeOnPooledThread {
            val fileContent = contexterFile?.let { VfsUtilCore.loadText(it) }
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

    // -----------------------------------------------------------------------------------------
    // 7. FUNCTION-DETECTION HELPERS WITH IMPROVED PYTHON SUPPORT
    // -----------------------------------------------------------------------------------------

    /**
     * Check if a line might be a function or method definition.
     */
    private fun isPossibleFunctionSignature(line: String): Boolean {
        val trimmed = line.trim()
        // Various language-specific checks
        return (trimmed.startsWith("fun ") || // Kotlin
                trimmed.startsWith("def ") || // Python
                trimmed.contains("function ") || // JavaScript/TypeScript
                trimmed.contains("static ") && trimmed.contains("(") && trimmed.contains(")") || // Java/C#
                trimmed.contains("(") && trimmed.contains(")") &&
                (trimmed.contains("public") || trimmed.contains("private") || trimmed.contains("void"))) // Java/C#/C++
    }

    /**
     * Extract a "signature" from the line for the autocompletion list.
     */
    private fun extractSignature(line: String): String {
        // Trim & clip, so it isn't super long
        return line.trim().take(80)
    }

    /**
     * Collect the full function body based on the language.
     * For Python, we track indentation to capture the complete function.
     * For brace languages, we track opening and closing braces.
     */
    private fun collectFunctionSnippet(lines: List<String>, startIndex: Int, fileExtension: String?): String {
        val sb = StringBuilder()

        // Handle Python differently
        if (fileExtension == "py") {
            return collectPythonFunctionSnippet(lines, startIndex)
        } else {
            // For curly brace languages
            return collectBraceFunctionSnippet(lines, startIndex)
        }
    }

    /**
     * Collect Python function by tracking indentation levels.
     */
    private fun collectPythonFunctionSnippet(lines: List<String>, startIndex: Int): String {
        val sb = StringBuilder()

        // Add the function signature line
        sb.appendLine(lines[startIndex])

        // Determine the indentation level of the first line of the function body
        var i = startIndex + 1
        while (i < lines.size && (lines[i].isBlank() || lines[i].trim().startsWith("#"))) {
            sb.appendLine(lines[i]) // Preserve blank lines and comments before body starts
            i++
        }

        if (i >= lines.size) {
            return sb.toString().trim()
        }

        // Get indentation of first body line
        val functionBodyIndent = getIndentLevel(lines[i])

        // Function definition must have some indentation to count as a body
        if (functionBodyIndent == 0) {
            return sb.toString().trim()
        }

        // Add all lines with equal or greater indentation
        while (i < lines.size) {
            val currentLine = lines[i]
            val currentIndent = getIndentLevel(currentLine)

            // If we hit a line with less indentation than the function body,
            // and it's not a blank line or comment, we've exited the function
            if (!currentLine.isBlank() && !currentLine.trim().startsWith("#") && currentIndent < functionBodyIndent) {
                break
            }

            sb.appendLine(currentLine)
            i++
        }

        return sb.toString().trim()
    }

    /**
     * Get the indentation level of a line by counting spaces and tabs.
     */
    private fun getIndentLevel(line: String): Int {
        var indent = 0
        for (c in line) {
            if (c == ' ') indent++
            else if (c == '\t') indent += 4 // Count a tab as 4 spaces
            else break
        }
        return indent
    }

    /**
     * Collect function for curly brace languages by tracking brace balance.
     */
    private fun collectBraceFunctionSnippet(lines: List<String>, startIndex: Int): String {
        val sb = StringBuilder()
        var i = startIndex
        var openBraces = 0
        var foundOpenBrace = false

        // Add first line
        sb.appendLine(lines[i])
        i++

        // Check if the first line already has an open brace
        if (lines[startIndex].contains("{")) {
            openBraces += lines[startIndex].count { it == '{' }
            foundOpenBrace = true
        }

        // If the first line has closing braces too, count them
        if (lines[startIndex].contains("}")) {
            openBraces -= lines[startIndex].count { it == '}' }
        }

        // Parse the rest of the function body
        while (i < lines.size) {
            val currentLine = lines[i]

            // Count braces in this line
            if (currentLine.contains("{")) {
                openBraces += currentLine.count { it == '{' }
                foundOpenBrace = true
            }
            if (currentLine.contains("}")) {
                openBraces -= currentLine.count { it == '}' }
            }

            // Add the line to our snippet
            sb.appendLine(currentLine)

            // For curly-brace languages: break if braces are balanced again
            if (foundOpenBrace && openBraces <= 0) {
                break
            }

            i++
        }

        return sb.toString().trim()
    }
}