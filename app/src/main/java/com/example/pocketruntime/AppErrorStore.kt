package com.example.pocketruntime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AppErrorStore(runtimeRoot: File) {
    private val errorRoot = File(runtimeRoot, "metadata/errors")

    init {
        require(errorRoot.mkdirs() || errorRoot.isDirectory) {
            "Could not create runtime error storage."
        }
    }

    @Synchronized
    fun record(appId: String, message: String) {
        val file = errorFile(appId)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(message.take(MAX_ERROR_LENGTH))
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

    @Synchronized
    fun get(appId: String): String? {
        val file = errorFile(appId)
        return if (file.isFile) file.readText() else null
    }

    @Synchronized
    fun clear(appId: String) {
        val file = errorFile(appId)
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Could not clear the app error record.")
        }
    }

    private fun errorFile(appId: String): File {
        require(appId.matches(Regex("[a-f0-9-]{36}"))) { "Invalid application identifier." }
        return File(errorRoot, "$appId.txt")
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 4_000
    }
}
