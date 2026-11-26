package com.example.pocketruntime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

class AgentCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeConnection: java.net.HttpURLConnection? = null

    fun cancel() {
        cancelled.set(true)
        activeConnection?.disconnect()
    }

    fun throwIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Agent task was cancelled.")
        }
    }

    internal fun attach(connection: java.net.HttpURLConnection) {
        activeConnection = connection
        if (cancelled.get()) connection.disconnect()
    }

    internal fun detach(connection: java.net.HttpURLConnection) {
        if (activeConnection === connection) activeConnection = null
    }
}

class AgentController(
    private val runtimeRoot: File,
    private val provider: AgentProvider
) {
    private val conversationRoot = File(runtimeRoot, "agent")

    init {
        require(conversationRoot.mkdirs() || conversationRoot.isDirectory) {
            "Could not create conversation storage."
        }
    }

    fun loadConversation(app: LocalApp): JSONArray = readHistory(app)

    fun clearConversation(app: LocalApp) {
        val file = conversationFile(app)
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Could not clear this app's conversation.")
        }
    }

    fun send(
        app: LocalApp,
        model: String,
        userText: String,
        tools: WorkspaceTools,
        cancellation: AgentCancellation,
        onStatus: (String) -> Unit,
        onConversationUpdate: (JSONArray) -> Unit = {}
    ): String {
        require(userText.isNotBlank()) { "Enter a request first." }
        require(userText.length <= MAX_USER_MESSAGE_LENGTH) {
            "Request exceeds the 20,000 character limit."
        }
        var history = readHistory(app)
        history.put(JSONObject().put("role", "user").put("content", userText))
        writeHistory(app, history)
        onConversationUpdate(JSONArray(history.toString()))

        var rounds = 0
        var toolCalls = 0
        while (rounds < MAX_TOOL_ROUNDS) {
            cancellation.throwIfCancelled()
            rounds++
            onStatus("Thinking…")
            val requestMessages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt(app)))
            for (index in 0 until history.length()) requestMessages.put(history.getJSONObject(index))

            val assistant = provider.complete(model, requestMessages, tools.definitions(), cancellation)
                .put("role", "assistant")
            history.put(assistant)
            writeHistory(app, history)
            onConversationUpdate(JSONArray(history.toString()))

            val calls = assistant.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val response = assistant.optString("content").trim()
                if (response.isEmpty()) {
                    throw ProviderException("The selected model returned neither a response nor a tool call.")
                }
                return response
            }

            for (index in 0 until calls.length()) {
                cancellation.throwIfCancelled()
                toolCalls++
                if (toolCalls > MAX_TOOL_CALLS) {
                    throw ProviderException("The agent reached the tool-call safety limit. Review the conversation and retry.")
                }
                val call = calls.optJSONObject(index)
                    ?: throw ProviderException("The selected model returned an invalid tool call.")
                val callId = call.optString("id")
                val function = call.optJSONObject("function")
                    ?: throw ProviderException("The selected model returned an invalid tool call.")
                val toolName = function.optString("name")
                val arguments = function.optString("arguments")
                if (callId.isBlank() || toolName.isBlank()) {
                    throw ProviderException("The selected model returned a tool call without an identifier or name.")
                }
                onStatus("Using $toolName…")
                val result = try {
                    tools.execute(toolName, arguments)
                } catch (error: Exception) {
                    JSONObject()
                        .put("error", error.message ?: "Tool execution failed.")
                        .toString()
                }
                history.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result)
                )
                writeHistory(app, history)
                onConversationUpdate(JSONArray(history.toString()))
            }
        }
        throw ProviderException("The agent reached the round limit before finishing. Review the conversation and retry.")
    }

    private fun readHistory(app: LocalApp): JSONArray {
        val file = conversationFile(app)
        if (!file.exists()) return JSONArray()
        val root = JSONObject(file.readText())
        return root.optJSONArray("messages")
            ?: throw IllegalStateException("Stored conversation is invalid. Clear it to start a new one.")
    }

    private fun writeHistory(app: LocalApp, history: JSONArray) {
        val file = conversationFile(app)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(JSONObject().put("messages", history).toString())
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun conversationFile(app: LocalApp): File {
        require(app.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid application identifier." }
        return File(conversationRoot, "${app.id}.json")
    }

    private fun systemPrompt(app: LocalApp): String = """
        You are Pocket Runtime's app-building assistant for the selected local project "${app.name}".
        The user is working on a phone. Keep changes focused on browser-compatible HTML, CSS, and JavaScript, and avoid features that require a desktop, shell, Node.js, or native Android code.
        The selected workspace is apps/${app.directory}. The available tools can access only this app's files. Treat all tool arguments as untrusted; never attempt path traversal or access another app, runtime data, settings, credentials, or shared package files directly.
        Supported conventions are a simple index.html entry point, app.js, style.css, and packages.json with exact dependency versions. Use relative browser imports and install packages only with install_package. Package installation does not run lifecycle scripts and Node.js-dependent packages may not work in WebView.
        Use list_files and read_file to inspect before changing existing work. Use write_file for complete small text files, install_package for shared npm dependencies, test_app to validate and prepare, and get_errors to diagnose failures.
        Do not claim that a file, dependency, or test changed or succeeded unless the corresponding tool returned success. If a tool reports an error, explain it accurately. Keep responses concise and suitable for a mobile screen.
    """.trimIndent()

    private companion object {
        const val MAX_USER_MESSAGE_LENGTH = 20_000
        const val MAX_TOOL_CALLS = 16
        const val MAX_TOOL_ROUNDS = 8
    }
}
