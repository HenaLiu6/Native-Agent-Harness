package com.example.pocketruntime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class AgentControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun completesMultipleToolRoundsAndPersistsAppScopedHistory() {
        val runtimeRoot = temporaryFolder.newFolder("runtime")
        val app = LocalApp(
            id = UUID.randomUUID().toString(),
            name = "Phone project",
            directory = "phone-project",
            createdAt = 1L
        )
        val appDirectory = File(runtimeRoot, "apps/${app.directory}").apply { mkdirs() }
        val fakeProvider = FakeProvider()
        val controller = AgentController(runtimeRoot, fakeProvider)
        val visibleConversationLengths = mutableListOf<Int>()
        val tools = WorkspaceTools(
            app = app,
            appDirectory = appDirectory,
            runtimeRoot = runtimeRoot,
            installPackage = { _, _ -> },
            testApp = { "ready" },
            getErrors = { null }
        )

        val response = controller.send(
            app = app,
            model = "returned-model-id",
            userText = "Create a page",
            tools = tools,
            cancellation = AgentCancellation(),
            onStatus = {},
            onConversationUpdate = { visibleConversationLengths.add(it.length()) }
        )

        assertEquals("The page is ready.", response)
        assertEquals("<h1>Hello</h1>", File(appDirectory, "index.html").readText())
        assertEquals(2, fakeProvider.callCount)
        assertTrue(fakeProvider.sawPhoneInstructions)
        assertTrue(fakeProvider.sawToolResponse)
        assertTrue(fakeProvider.toolNames.containsAll(listOf("write_file", "test_app")))
        assertEquals(4, controller.loadConversation(app).length())
        assertEquals(listOf(1, 2, 3, 4), visibleConversationLengths)

        controller.clearConversation(app)
        assertEquals(0, controller.loadConversation(app).length())
        assertTrue(File(appDirectory, "index.html").isFile)
    }

    private class FakeProvider : AgentProvider {
        var callCount = 0
        var sawPhoneInstructions = false
        var sawToolResponse = false
        var toolNames = emptyList<String>()

        override fun complete(
            model: String,
            messages: JSONArray,
            tools: List<AgentToolDefinition>,
            cancellation: AgentCancellation
        ): JSONObject {
            assertEquals("returned-model-id", model)
            callCount++
            sawPhoneInstructions = messages.getJSONObject(0).getString("content").contains("on a phone")
            toolNames = tools.map { it.name }
            if (callCount == 1) {
                val arguments = JSONObject()
                    .put("path", "index.html")
                    .put("content", "<h1>Hello</h1>")
                val function = JSONObject()
                    .put("name", "write_file")
                    .put("arguments", arguments.toString())
                val call = JSONObject()
                    .put("id", "call-1")
                    .put("type", "function")
                    .put("function", function)
                return JSONObject().put("tool_calls", JSONArray().put(call))
            }

            sawToolResponse = messages.getJSONObject(messages.length() - 1).let {
                it.optString("role") == "tool" && it.optString("tool_call_id") == "call-1"
            }
            return JSONObject().put("content", "The page is ready.")
        }
    }
}
