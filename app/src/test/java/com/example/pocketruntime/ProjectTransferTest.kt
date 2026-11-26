package com.example.pocketruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectTransferTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportedProjectCanBeImportedFromAStandardRootFolderZip() {
        val source = temporaryFolder.newFolder("weather-app")
        File(source, "index.html").writeText("<h1>Weather</h1>")
        File(source, "assets").mkdir()
        File(source, "assets/icon.svg").writeText("<svg/>")
        val archive = ByteArrayOutputStream()

        ProjectTransfer.exportProject(source, archive)
        val staging = temporaryFolder.newFolder("staging")
        ProjectTransfer.importProject(ByteArrayInputStream(archive.toByteArray()), staging, "Weather App.zip")

        assertEquals("<h1>Weather</h1>", File(staging, "index.html").readText())
        assertEquals("<svg/>", File(staging, "assets/icon.svg").readText())
        assertFalse(File(staging, "weather-app").exists())
    }

    @Test
    fun importRejectsPathTraversalWithoutWritingOutsideTheStagingDirectory() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write("unsafe".toByteArray())
            zip.closeEntry()
        }
        val staging = temporaryFolder.newFolder("staging")

        assertThrows(IllegalArgumentException::class.java) {
            ProjectTransfer.importProject(ByteArrayInputStream(archive.toByteArray()), staging, "project.zip")
        }
        assertFalse(File(temporaryFolder.root, "outside.txt").exists())
    }

    @Test
    fun importRejectsPathsNotPortableToWindows() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("CON.txt"))
            zip.write("reserved".toByteArray())
            zip.closeEntry()
        }
        val staging = temporaryFolder.newFolder("staging")

        assertThrows(IllegalArgumentException::class.java) {
            ProjectTransfer.importProject(ByteArrayInputStream(archive.toByteArray()), staging, "project.zip")
        }
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun importRejectsFilesThatAreNotProjectZips() {
        val staging = temporaryFolder.newFolder("staging")

        assertThrows(IllegalArgumentException::class.java) {
            ProjectTransfer.importProject(ByteArrayInputStream(byteArrayOf(1, 2, 3)), staging, "notes.zip")
        }
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }
}
