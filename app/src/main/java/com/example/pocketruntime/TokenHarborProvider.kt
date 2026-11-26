package com.example.pocketruntime

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class ProviderModel(val id: String, val label: String)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject
)

interface AgentProvider {
    fun complete(
        model: String,
        messages: JSONArray,
        tools: List<AgentToolDefinition>,
        cancellation: AgentCancellation
    ): JSONObject
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TokenHarborProvider(private val secretStore: SecretStore) : AgentProvider {
    fun listModels(): List<ProviderModel> {
        val apiKey = secretStore.retrieve()
            ?: throw ProviderException("Add a Token Harbor API key in Settings first.")
        val response = request("/models", apiKey, null)
        val data = response.optJSONArray("data")
            ?: throw ProviderException("Token Harbor returned an invalid model list.")
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val label = listOf("name", "display_name", "title")
                .firstNotNullOfOrNull { field -> item.optString(field).takeIf(String::isNotBlank) }
                ?: id
            ProviderModel(id = id, label = label)
        }.distinctBy { it.id }
    }

    override fun complete(
        model: String,
        messages: JSONArray,
        tools: List<AgentToolDefinition>,
        cancellation: AgentCancellation
    ): JSONObject {
        require(model.isNotBlank()) { "Select a model in Settings first." }
        val apiKey = secretStore.retrieve()
            ?: throw ProviderException("Add a Token Harbor API key in Settings first.")
        val providerTools = JSONArray()
        tools.forEach { tool ->
            providerTools.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", tool.parameters)
                    )
            )
        }
        val requestBody = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", providerTools)
            .put("tool_choice", "auto")
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (requestBody.size > MAX_REQUEST_BYTES) {
            throw ProviderException("This request is too large. Shorten the conversation or reduce the files included.")
        }

        val response = request("/chat/completions", apiKey, requestBody, cancellation)
        val choices = response.optJSONArray("choices")
        val message = choices?.optJSONObject(0)?.optJSONObject("message")
            ?: throw ProviderException("Token Harbor returned an invalid chat response.")
        return message
    }

    private fun request(
        path: String,
        apiKey: String,
        body: ByteArray?,
        cancellation: AgentCancellation? = null
    ): JSONObject {
        val connection = try {
            URL("$BASE_URL$path").openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw ProviderException("Could not connect to Token Harbor.", error)
        }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = RESPONSE_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("Authorization", listOf("Bearer", apiKey).joinToString(" "))
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "PocketRuntime/1.0")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
        }
        cancellation?.attach(connection)

        try {
            cancellation?.throwIfCancelled()
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            cancellation?.throwIfCancelled()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                ?: throw ProviderException("Token Harbor request failed (HTTP $status).")
            val responseText = stream.use { readBounded(it, MAX_RESPONSE_BYTES) }
                .toString(Charsets.UTF_8)
            if (status !in 200..299) {
                val detail = try {
                    JSONObject(responseText).optJSONObject("error")
                        ?.optString("message")
                        .orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val safeDetail = detail.take(300).replace(apiKey, "[redacted]")
                val explanation = when (status) {
                    401, 403 -> "Token Harbor rejected the API key. Check it in Settings."
                    429 -> "Token Harbor rate limit reached. Wait and try again."
                    400 -> if (
                        body != null &&
                        (safeDetail.contains("tool", ignoreCase = true) ||
                            safeDetail.contains("function", ignoreCase = true))
                    ) {
                        "The selected model may not support tool calling. Choose a model with tool-call support. $safeDetail"
                    } else {
                        safeDetail.ifBlank { "Token Harbor rejected the request (HTTP 400)." }
                    }
                    else -> safeDetail.ifBlank { "Token Harbor request failed (HTTP $status)." }
                }
                throw ProviderException(explanation)
            }
            return try {
                JSONObject(responseText)
            } catch (error: Exception) {
                throw ProviderException("Token Harbor returned invalid JSON.", error)
            }
        } catch (error: ProviderException) {
            throw error
        } catch (error: Exception) {
            cancellation?.throwIfCancelled()
            val safeMessage = error.message.orEmpty().replace(apiKey, "[redacted]")
            throw ProviderException(
                if (safeMessage.contains("connect timed out", ignoreCase = true)) {
                    "Could not connect to Token Harbor. Check your internet connection and retry."
                } else if (safeMessage.contains("timeout", ignoreCase = true)) {
                    "Token Harbor did not return a response within 3 minutes. The selected model may be busy; wait and retry or choose another available model."
                } else {
                    "Could not complete the Token Harbor request."
                },
                error
            )
        } finally {
            connection.disconnect()
            cancellation?.detach(connection)
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw ProviderException("Token Harbor response exceeded the size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val BASE_URL = "https://tokenharbor.ai/v1"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val RESPONSE_TIMEOUT_MS = 180_000
        const val MAX_REQUEST_BYTES = 4_000_000
        const val MAX_RESPONSE_BYTES = 4_000_000
    }
}
