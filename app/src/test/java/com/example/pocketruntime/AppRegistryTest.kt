package com.example.pocketruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppRegistryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsEmptyDirectoriesAndResolvesNameCollisions() {
        val registry = AppRegistry(File(temporaryFolder.root, "root"))

        val first = registry.create("Weather App")
        val second = registry.create("Weather App")

        assertEquals("weather-app", first.directory)
        assertEquals("weather-app-2", second.directory)
        assertTrue(registry.directoryFor(first).listFiles().orEmpty().isEmpty())
        assertTrue(registry.directoryFor(second).listFiles().orEmpty().isEmpty())
        assertEquals(listOf(first.id, second.id), registry.list().map { it.id })
    }

    @Test
    fun rejectsNamesThatCannotMakeSafeDirectories() {
        val registry = AppRegistry(File(temporaryFolder.root, "root"))

        assertThrows(IllegalArgumentException::class.java) { registry.create("  ") }
        assertThrows(IllegalArgumentException::class.java) { registry.create("🔥") }
    }

    @Test
    fun registersImportedProjectsWithCollisionHandlingAndDeletesOnlyTheSelectedApp() {
        val registry = AppRegistry(File(temporaryFolder.root, "root"))
        val existing = registry.create("Weather App")
        val staging = File(registry.appsRoot, ".import-test").apply {
            mkdir()
            File(this, "index.html").writeText("<h1>Imported</h1>")
        }

        val imported = registry.registerImported("Weather App", staging)

        assertEquals("weather-app-2", imported.directory)
        assertTrue(registry.directoryFor(imported).listFiles().orEmpty().isNotEmpty())
        registry.delete(existing)
        assertEquals(listOf(imported.id), registry.list().map { it.id })
        assertTrue(registry.directoryFor(imported).isDirectory)
    }
}
