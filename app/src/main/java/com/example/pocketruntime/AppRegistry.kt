package com.example.pocketruntime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

data class LocalApp(
    val id: String,
    val name: String,
    val directory: String,
    val createdAt: Long,
    val lastOpenedAt: Long? = null
)

class AppRegistry(private val runtimeRoot: File) {
    val appsRoot = File(runtimeRoot, "apps")
    private val registryFile = File(runtimeRoot, "metadata/apps.json")

    init {
        require(appsRoot.mkdirs() || appsRoot.isDirectory) {
            "Could not create application storage"
        }
        require(File(runtimeRoot, "metadata").mkdirs() || File(runtimeRoot, "metadata").isDirectory) {
            "Could not create runtime metadata storage"
        }
        require(File(runtimeRoot, "packages").mkdirs() || File(runtimeRoot, "packages").isDirectory) {
            "Could not create package storage"
        }
        if (!registryFile.exists()) save(emptyList())
    }

    @Synchronized
    fun list(): List<LocalApp> {
        val entries = JSONArray(registryFile.readText())
        return (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            LocalApp(
                id = item.getString("id"),
                name = item.getString("name"),
                directory = item.getString("directory"),
                createdAt = item.getLong("createdAt"),
                lastOpenedAt = item.takeIf { it.has("lastOpenedAt") && !it.isNull("lastOpenedAt") }
                    ?.getLong("lastOpenedAt")
            )
        }
    }

    @Synchronized
    fun create(displayName: String): LocalApp {
        val name = displayName.trim()
        val existing = list()
        val directory = uniqueDirectory(name, existing)

        val appDirectory = File(appsRoot, directory)
        require(appDirectory.mkdir()) { "Could not create application directory." }
        val app = LocalApp(
            id = UUID.randomUUID().toString(),
            name = name,
            directory = directory,
            createdAt = System.currentTimeMillis()
        )
        try {
            save(existing + app)
        } catch (error: Exception) {
            if (!appDirectory.delete()) {
                error.addSuppressed(IllegalStateException("Could not remove incomplete application directory."))
            }
            throw error
        }
        return app
    }

    @Synchronized
    fun registerImported(displayName: String, stagingDirectory: File): LocalApp {
        val name = displayName.trim()
        val staging = stagingDirectory.canonicalFile
        require(staging.parentFile == appsRoot.canonicalFile && staging.name.startsWith(".import-") && staging.isDirectory) {
            "Imported project is not in the application staging area."
        }
        val existing = list()
        val directory = uniqueDirectory(name, existing)
        val target = File(appsRoot, directory)
        require(staging.renameTo(target)) { "Could not move the imported project into application storage." }
        val app = LocalApp(
            id = UUID.randomUUID().toString(),
            name = name,
            directory = directory,
            createdAt = System.currentTimeMillis()
        )
        try {
            save(existing + app)
        } catch (error: Exception) {
            if (!target.renameTo(staging)) {
                error.addSuppressed(IllegalStateException("Could not restore the staged project after registry failure."))
            }
            throw error
        }
        return app
    }

    @Synchronized
    fun delete(app: LocalApp) {
        val registered = find(app.id)
            ?: throw IllegalArgumentException("Application is no longer registered.")
        require(registered.directory == app.directory) { "Application registry entry changed." }
        val directory = directoryFor(registered)
        val tombstone = File(appsRoot, ".deleted-${registered.id}")
        require(!tombstone.exists() && directory.renameTo(tombstone)) {
            "Could not safely remove the application directory."
        }
        try {
            save(list().filterNot { it.id == registered.id })
        } catch (error: Exception) {
            if (!tombstone.renameTo(directory)) {
                error.addSuppressed(IllegalStateException("Could not restore the application directory after registry failure."))
            }
            throw error
        }
        if (!tombstone.deleteRecursively()) {
            throw IllegalStateException("The app was unregistered, but its files could not be fully removed.")
        }
    }

    @Synchronized
    fun find(id: String): LocalApp? = list().firstOrNull { it.id == id }

    @Synchronized
    fun directoryFor(app: LocalApp): File {
        val registered = find(app.id)
            ?: throw IllegalArgumentException("Application is no longer registered.")
        require(registered.directory == app.directory) { "Application registry entry changed." }
        val root = appsRoot.canonicalFile
        val directory = File(root, registered.directory).canonicalFile
        require(directory.parentFile == root && directory.isDirectory) {
            "Application directory is unavailable or invalid."
        }
        return directory
    }

    @Synchronized
    fun markOpened(app: LocalApp) {
        val entries = list()
        val updated = entries.map {
            if (it.id == app.id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it
        }
        save(updated)
    }

    @Synchronized
    fun registerExisting(displayName: String, directoryName: String): LocalApp {
        require(directoryName.matches(Regex("[a-z0-9][a-z0-9-]*"))) {
            "Invalid application directory name."
        }
        val existing = list()
        require(existing.none { it.directory == directoryName }) {
            "An application with this directory already exists."
        }
        val directory = File(appsRoot, directoryName)
        require(directory.isDirectory) { "Application directory is unavailable." }
        val app = LocalApp(
            id = UUID.randomUUID().toString(),
            name = displayName,
            directory = directoryName,
            createdAt = directory.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        save(existing + app)
        return app
    }

    private fun uniqueDirectory(name: String, existing: List<LocalApp>): String {
        require(name.isNotEmpty()) { "Enter an application name." }
        val base = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        require(base.isNotEmpty()) { "The name must include at least one letter or number." }
        var directory = base
        var suffix = 2
        while (existing.any { it.directory == directory } || File(appsRoot, directory).exists()) {
            directory = "$base-$suffix"
            suffix++
        }
        return directory
    }

    private fun save(apps: List<LocalApp>) {
        val json = JSONArray()
        apps.forEach { app ->
            json.put(
                JSONObject()
                    .put("id", app.id)
                    .put("name", app.name)
                    .put("directory", app.directory)
                    .put("createdAt", app.createdAt)
                    .put("lastOpenedAt", app.lastOpenedAt ?: JSONObject.NULL)
            )
        }
        val temp = File(registryFile.parentFile, "${registryFile.name}.tmp")
        temp.writeText(json.toString())
        try {
            Files.move(
                temp.toPath(),
                registryFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
