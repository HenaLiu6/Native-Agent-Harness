package com.example.pocketruntime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class WorkspaceToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesAtomicallyAndBacksUpReplacedFiles() {
        val (tools, appRoot, runtimeRoot) = createTools()
        tools.execute("write_file", """{"path":"index.html","content":"old"}""")

        val result = JSONObject(
            tools.execute("write_file", """{"path":"index.html","content":"new"}""")
        )

        assertEquals("new", File(appRoot, "index.html").readText())
        val backupPath = result.getString("backup")
        assertTrue(File(runtimeRoot, backupPath).isFile)
        assertEquals("old", File(runtimeRoot, backupPath).readText())
    }

    @Test
    fun rejectsTraversalMalformedTypesAndUnknownArguments() {
        val (tools) = createTools()

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("read_file", """{"path":"../other/secret.txt"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("write_file", """{"path":"main.js","content":12}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("list_files", """{"path":".","unexpected":true}""")
        }
    }

    @Test
    fun deletionRequiresExplicitConfirmationAndKeepsBackup() {
        val (tools, appRoot, runtimeRoot) = createTools()
        File(appRoot, "remove.txt").writeText("recoverable")

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("delete_file", """{"path":"remove.txt","confirm":false}""")
        }
        val result = JSONObject(
            tools.execute("delete_file", """{"path":"remove.txt","confirm":true}""")
        )

        assertFalse(File(appRoot, "remove.txt").exists())
        assertEquals("recoverable", File(runtimeRoot, result.getString("backup")).readText())
        assertEquals("remove.txt", result.getString("deleted"))
    }

    @Test
    fun packageInstallRecordsTheExactVersionInTheAppManifest() {
        val installed = mutableListOf<String>()
        val (tools, appRoot) = createTools(installPackage = { name, version ->
            installed.add("$name@$version")
        })

        val result = JSONObject(
            tools.execute("install_package", """{"name":"comfy.js","version":"1.2.3"}""")
        )

        assertEquals(listOf("comfy.js@1.2.3"), installed)
        assertEquals("1.2.3", JSONObject(File(appRoot, "packages.json").readText())
            .getJSONObject("dependencies").getString("comfy.js"))
        assertNotNull(result.optString("manifest"))
    }

    private fun createTools(
        installPackage: (String, String) -> Unit = { _, _ -> }
    ): Triple<WorkspaceTools, File, File> {
        val runtimeRoot = temporaryFolder.newFolder("runtime-${UUID.randomUUID()}")
        val app = LocalApp(
            id = UUID.randomUUID().toString(),
            name = "Test app",
            directory = "test-app",
            createdAt = 1L
        )
        val appRoot = File(runtimeRoot, "apps/${app.directory}").apply { mkdirs() }
        val tools = WorkspaceTools(
            app = app,
            appDirectory = appRoot,
            runtimeRoot = runtimeRoot,
            installPackage = installPackage,
            testApp = { "ready" },
            getErrors = { null }
        )
        return Triple(tools, appRoot, runtimeRoot)
    }
}
