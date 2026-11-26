package com.example.pocketruntime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class ProjectTransferServerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun requiresPairingAndStreamsProjectsBetweenPeerAndPhone() {
        val runtimeRoot = File(temporaryFolder.root, "runtime")
        val registry = AppRegistry(runtimeRoot)
        val existing = registry.create("Existing App")
        File(registry.directoryFor(existing), "index.html").writeText("<h1>Existing</h1>")
        val server = ProjectTransferServer(
            registry = registry,
            temporaryDirectory = temporaryFolder.newFolder("http-temp"),
            bindAddress = InetAddress.getByName("127.0.0.1")
        )

        try {
            assertEquals(401, request(server.url + "/api/apps").responseCode)
            val pairResponse = request(
                server.url + "/api/pair",
                method = "POST",
                body = JSONObject().put("code", server.pairingCode).toString().toByteArray(),
                contentType = "application/json"
            )
            assertEquals(200, pairResponse.responseCode)
            val token = JSONObject(pairResponse.readText()).getString("token")

            val appsResponse = request(server.url + "/api/apps", token = token)
            assertEquals(200, appsResponse.responseCode)
            assertEquals(existing.id, JSONObject(appsResponse.readText())
                .getJSONArray("apps").getJSONObject(0).getString("id"))

            val downloaded = request(
                server.url + "/api/apps/${existing.id}/project.zip?token=$token"
            )
            assertEquals(200, downloaded.responseCode)
            val staging = temporaryFolder.newFolder("downloaded")
            ProjectTransfer.importProject(downloaded.inputStream, staging, "existing-app.zip")
            assertEquals("<h1>Existing</h1>", File(staging, "index.html").readText())

            val uploadRoot = temporaryFolder.newFolder("upload")
            File(uploadRoot, "app.js").writeText("document.body.textContent='from PC';")
            val archive = ByteArrayOutputStream()
            ProjectTransfer.exportProject(uploadRoot, archive)
            val uploadResponse = request(
                server.url + "/api/apps/import?name=From%20Computer",
                method = "PUT",
                token = token,
                body = archive.toByteArray(),
                contentType = "application/zip"
            )
            assertEquals(201, uploadResponse.responseCode)
            val imported = JSONObject(uploadResponse.readText())
            assertEquals("From Computer", imported.getString("name"))
            assertTrue(File(registry.directoryFor(registry.find(imported.getString("id"))!!), "app.js").isFile)
            assertEquals(2, registry.list().size)
        } finally {
            server.close()
        }
    }

    private fun request(
        url: String,
        method: String = "GET",
        token: String? = null,
        body: ByteArray? = null,
        contentType: String? = null
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.requestMethod = method
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        contentType?.let { connection.setRequestProperty("Content-Type", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return connection
    }

    private fun HttpURLConnection.readText(): String =
        inputStream.bufferedReader().use { it.readText() }
}
