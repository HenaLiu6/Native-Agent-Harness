package com.example.pocketruntime

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ProjectTransfer {
    private const val MAX_FILES = 10_000
    private const val MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 250L * 1024 * 1024
    private const val BUFFER_SIZE = 8 * 1024
    private val windowsReservedName = Regex(
        "(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$"
    )

    fun exportProject(projectDirectory: File, output: OutputStream) {
        val root = projectDirectory.canonicalFile
        require(root.isDirectory) { "Project directory is unavailable." }
        val rootName = root.name
        val visitedDirectories = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()
        var entryCount = 0
        var totalBytes = 0L

        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("$rootName/"))
            zip.closeEntry()

            fun addDirectory(directory: File, relativePath: String) {
                if (!visitedDirectories.add(directory.canonicalPath)) return
                val children = directory.listFiles()?.sortedBy { it.name }
                    ?: throw IllegalStateException("Could not list project files.")
                for (child in children) {
                    val canonical = child.canonicalFile
                    require(canonical.path.startsWith(root.path + File.separator)) {
                        "Project contains a file outside its directory."
                    }
                    val relative = "$relativePath/${child.name}"
                    validateEntryPath(relative)
                    require(seenPaths.add(relative.lowercase(Locale.ROOT))) {
                        "Project contains paths that collide on common desktop filesystems."
                    }
                    entryCount++
                    require(entryCount <= MAX_FILES) { "Project has too many files to export." }
                    if (canonical.isDirectory) {
                        zip.putNextEntry(ZipEntry("$relative/"))
                        zip.closeEntry()
                        addDirectory(canonical, relative)
                    } else {
                        require(canonical.isFile) { "Project contains an unsupported file type." }
                        require(totalBytes + canonical.length() <= MAX_UNCOMPRESSED_BYTES) {
                            "Project exceeds the 200 MB transfer limit."
                        }
                        zip.putNextEntry(ZipEntry(relative))
                        canonical.inputStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                                    "Project exceeds the 200 MB transfer limit."
                                }
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            addDirectory(root, rootName)
            zip.finish()
        }
    }

    fun importProject(input: InputStream, stagingDirectory: File, archiveName: String) {
        require(stagingDirectory.isDirectory && stagingDirectory.listFiles().isNullOrEmpty()) {
            "Project import staging directory must be empty."
        }
        val root = stagingDirectory.canonicalFile
        val seenPaths = mutableSetOf<String>()
        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        ZipInputStream(LimitedInputStream(input, MAX_ARCHIVE_BYTES)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_FILES) { "Archive has too many entries." }
                val segments = validateEntryPath(entry.name)
                val relativePath = segments.joinToString(File.separator)
                val collisionKey = relativePath.lowercase(Locale.ROOT)
                require(seenPaths.add(collisionKey)) { "Archive contains duplicate file paths." }
                val destination = File(root, relativePath).canonicalFile
                require(destination.path.startsWith(root.path + File.separator)) {
                    "Archive contains a path outside the project."
                }
                if (entry.isDirectory) {
                    require(destination.mkdirs() || destination.isDirectory) {
                        "Could not create an imported project directory."
                    }
                } else {
                    require(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true) {
                        "Could not create an imported project's parent directory."
                    }
                    require(!destination.exists()) { "Archive contains conflicting file paths." }
                    destination.outputStream().use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                                "Archive exceeds the 200 MB uncompressed transfer limit."
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(entryCount > 0) { "Selected file is not a valid project ZIP." }
        flattenProjectRootIfPresent(root, archiveName)
    }

    private fun validateEntryPath(entryName: String): List<String> {
        require(entryName.isNotBlank() && !entryName.startsWith('/') && !entryName.contains('\\')) {
            "Archive contains an invalid path."
        }
        val normalized = entryName.removeSuffix("/")
        val segments = normalized.split('/')
        require(segments.isNotEmpty() && segments.all { segment ->
            segment.isNotBlank() &&
                segment != "." &&
                segment != ".." &&
                !segment.endsWith('.') &&
                !segment.endsWith(' ') &&
                segment.none { it in "<>:\"|?*" } &&
                !windowsReservedName.matches(segment)
        }) { "Archive contains a non-portable or unsafe path." }
        return segments
    }

    private fun flattenProjectRootIfPresent(root: File, archiveName: String) {
        val entries = root.listFiles()?.toList()
            ?: throw IllegalStateException("Could not inspect imported project files.")
        if (entries.size != 1 || !entries[0].isDirectory) return

        val wrapper = entries[0]
        val expectedRoot = archiveName.substringBeforeLast('.', archiveName)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        val hasProjectEntryPoint = listOf("index.html", "packages.json", "app.js", "style.css")
            .any { File(wrapper, it).isFile }
        val wrapperContents = wrapper.list()
            ?: throw IllegalStateException("Could not inspect the imported project folder.")
        if (wrapper.name != expectedRoot && !hasProjectEntryPoint && wrapperContents.isNotEmpty()) return

        val children = wrapper.listFiles()
            ?: throw IllegalStateException("Could not inspect the imported project folder.")
        for (child in children) {
            val destination = File(root, child.name)
            require(!destination.exists() && child.renameTo(destination)) {
                "Could not move files out of the archive's project folder."
            }
        }
        require(wrapper.delete()) { "Could not finish importing the project folder." }
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var bytesRead = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val readCount = super.read(buffer, offset, length)
            if (readCount > 0) count(readCount)
            return readCount
        }

        private fun count(count: Int) {
            bytesRead += count
            require(bytesRead <= limit) { "Archive exceeds the 250 MB compressed transfer limit." }
        }
    }
}
