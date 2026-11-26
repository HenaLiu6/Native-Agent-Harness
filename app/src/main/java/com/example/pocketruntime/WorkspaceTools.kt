package com.example.pocketruntime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WorkspaceTools(
    app: LocalApp,
    appDirectory: File,
    private val runtimeRoot: File,
    private val installPackage: (String, String) -> Unit,
    private val testApp: () -> String,
    private val getErrors: () -> String?
) {
    private val appId = app.id
    private val root = appDirectory.canonicalFile

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition("list_files", "List files and directories within this app.", schema(
            optional("path", stringType("Relative directory path; defaults to the app root.")),
            optional("recursive", booleanType("Whether to include nested files and directories."))
        )),
        AgentToolDefinition("read_file", "Read a UTF-8 text file within this app.", schema(
            required("path", stringType("Relative file path."))
        )),
        AgentToolDefinition("write_file", "Create or atomically replace a UTF-8 text file within this app. Existing content is backed up first.", schema(
            required("path", stringType("Relative file path.")),
            required("content", stringType("Complete UTF-8 file content."))
        )),
        AgentToolDefinition("delete_file", "Delete one file in this app after first creating a recoverable backup.", schema(
            required("path", stringType("Relative file path.")),
            required("confirm", booleanType("Must be true to explicitly request deletion."))
        )),
        AgentToolDefinition("search_files", "Search text in files within this app.", schema(
            required("query", stringType("Text to search for.")),
            optional("path", stringType("Relative directory path; defaults to the app root."))
        )),
        AgentToolDefinition("install_package", "Install one exact npm package version into the shared package cache and record it in this app's packages.json. Node.js packages and lifecycle scripts are not run.", schema(
            required("name", stringType("Unscoped npm package name.")),
            required("version", stringType("Exact semantic version, such as 1.2.3."))
        )),
        AgentToolDefinition("test_app", "Prepare and validate the selected app, including its entry point and declared exact dependencies.", emptyObjectSchema()),
        AgentToolDefinition("get_errors", "Retrieve the most recent preparation or execution error for this app.", emptyObjectSchema())
    )

    fun execute(name: String, rawArguments: String): String {
        val args = try {
            JSONObject(rawArguments)
        } catch (_: Exception) {
            throw IllegalArgumentException("Tool arguments must be a JSON object.")
        }
        return when (name) {
            "list_files" -> listFiles(args)
            "read_file" -> readFile(args)
            "write_file" -> writeFile(args)
            "delete_file" -> deleteFile(args)
            "search_files" -> searchFiles(args)
            "install_package" -> installPackage(args)
            "test_app" -> {
                requireKeys(args, emptySet())
                JSONObject().put("result", testApp()).toString()
            }
            "get_errors" -> {
                requireKeys(args, emptySet())
                JSONObject().put("error", getErrors() ?: JSONObject.NULL).toString()
            }
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    private fun listFiles(args: JSONObject): String {
        requireKeys(args, emptySet(), setOf("path", "recursive"))
        val directory = resolve(optionalString(args, "path", "."))
        require(directory.isDirectory) { "Directory does not exist." }
        val recursive = optionalBoolean(args, "recursive", false)
        val entries = JSONArray()
        val visitedDirectories = mutableSetOf<String>()
        var count = 0
        fun visit(current: File, relative: String) {
            if (!visitedDirectories.add(current.canonicalPath)) return
            val children = current.listFiles()?.sortedBy { it.name }
                ?: throw IllegalStateException("Could not list directory.")
            children.forEach { child ->
                count++
                require(count <= MAX_LIST_ENTRIES) { "Directory contains too many entries; narrow the path." }
                val childPath = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                val resolvedChild = resolve(childPath)
                val isDirectory = resolvedChild.isDirectory
                entries.put(JSONObject().put("path", childPath).put("type", if (isDirectory) "directory" else "file"))
                if (recursive && isDirectory) visit(resolvedChild, childPath)
            }
        }
        val relative = relativePath(directory)
        visit(directory, if (relative == ".") "" else relative)
        return JSONObject().put("files", entries).toString()
    }

    private fun readFile(args: JSONObject): String {
        requireKeys(args, setOf("path"))
        val file = resolve(stringArgument(args, "path"))
        require(file.isFile) { "File does not exist." }
        require(file.length() <= MAX_TEXT_FILE_BYTES) { "File exceeds the 1 MB text limit." }
        return JSONObject().put("path", relativePath(file)).put("content", file.readText(Charsets.UTF_8)).toString()
    }

    private fun writeFile(args: JSONObject): String {
        requireKeys(args, setOf("path", "content"))
        val file = resolve(stringArgument(args, "path"))
        require(!file.exists() || file.isFile) { "Only files can be replaced." }
        val content = stringArgument(args, "content").toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_TEXT_FILE_BYTES) { "File content exceeds the 1 MB limit." }
        require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) {
            "Could not create the file's parent directory."
        }

        val backup = if (file.isFile) backup(file) else null
        atomicWrite(file, content)
        return JSONObject()
            .put("written", relativePath(file))
            .put("backup", backup ?: JSONObject.NULL)
            .toString()
    }

    private fun deleteFile(args: JSONObject): String {
        requireKeys(args, setOf("path", "confirm"))
        require(booleanArgument(args, "confirm")) { "Deletion requires confirm=true." }
        val file = resolve(stringArgument(args, "path"))
        require(file.isFile) { "Only an existing file can be deleted." }
        val deleted = relativePath(file)
        val backup = backup(file)
        require(file.delete()) { "Could not delete the file." }
        return JSONObject().put("deleted", deleted).put("backup", backup).toString()
    }

    private fun searchFiles(args: JSONObject): String {
        requireKeys(args, setOf("query"), setOf("path"))
        val query = stringArgument(args, "query")
        require(query.isNotBlank() && query.length <= MAX_QUERY_LENGTH) { "Search query must contain 1 to 200 characters." }
        val directory = resolve(optionalString(args, "path", "."))
        require(directory.isDirectory) { "Directory does not exist." }
        val results = JSONArray()
        val visitedDirectories = mutableSetOf<String>()
        var scanned = 0
        fun visit(current: File) {
            if (!visitedDirectories.add(current.canonicalPath)) return
            val children = current.listFiles()
                ?: throw IllegalStateException("Could not search directory.")
            children.forEach { entry ->
                val file = resolve(relativePathFromWorkspace(entry))
                if (file.isDirectory) {
                    visit(file)
                } else if (file.isFile) {
                    scanned++
                    require(scanned <= MAX_SEARCH_FILES) { "Search limit reached; narrow the path." }
                    if (file.length() <= MAX_TEXT_FILE_BYTES) {
                        val content = file.readText(Charsets.UTF_8)
                        content.lineSequence().forEachIndexed { index, line ->
                            if (line.contains(query, ignoreCase = true) && results.length() < MAX_SEARCH_RESULTS) {
                                results.put(
                                    JSONObject()
                                        .put("path", relativePath(file))
                                        .put("line", index + 1)
                                        .put("text", line.take(MAX_LINE_LENGTH))
                                )
                            }
                        }
                    }
                }
            }
        }
        visit(directory)
        return JSONObject().put("matches", results).put("truncated", results.length() >= MAX_SEARCH_RESULTS).toString()
    }

    private fun installPackage(args: JSONObject): String {
        requireKeys(args, setOf("name", "version"))
        val name = stringArgument(args, "name")
        val version = stringArgument(args, "version")
        require(name.matches(Regex("[a-z0-9][a-z0-9._-]*"))) {
            "Only unscoped npm package names are supported."
        }
        require(version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
            "Use an exact package version."
        }
        installPackage(name, version)
        val manifest = resolve("packages.json")
        val manifestJson = if (manifest.isFile) {
            require(manifest.length() <= MAX_TEXT_FILE_BYTES) { "packages.json exceeds the 1 MB limit." }
            JSONObject(manifest.readText(Charsets.UTF_8))
        } else {
            JSONObject()
        }
        val dependencies = if (manifestJson.has("dependencies")) {
            manifestJson.optJSONObject("dependencies")
                ?: throw IllegalArgumentException("packages.json dependencies must be an object.")
        } else {
            JSONObject()
        }
        dependencies.put(name, version)
        manifestJson.put("dependencies", dependencies)
        val manifestBackup = if (manifest.isFile) backup(manifest) else null
        atomicWrite(manifest, manifestJson.toString(2).toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("installed", "$name@$version")
            .put("manifest", "packages.json")
            .put("backup", manifestBackup ?: JSONObject.NULL)
            .toString()
    }

    private fun resolve(relative: String): File {
        require(relative.isNotBlank()) { "Path cannot be empty." }
        require(!relative.startsWith("/") && !relative.startsWith("\\") &&
            !relative.contains('\\') && !Regex("^[A-Za-z]:").containsMatchIn(relative)) {
            "Use a path relative to the selected app."
        }
        require(relative.split('/', '\\').none { it == ".." || it == "." && relative != "." }) {
            "Path traversal is not allowed."
        }
        val candidate = File(root, relative.replace('/', File.separatorChar)).canonicalFile
        require(candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)) {
            "Path escapes the selected app."
        }
        return candidate
    }

    private fun backup(file: File): String {
        val relative = relativePath(file)
        val backupRoot = File(runtimeRoot, "metadata/backups/$appId")
        require(backupRoot.mkdirs() || backupRoot.isDirectory) { "Could not create backup storage." }
        val timestamp = "${System.currentTimeMillis()}-${System.nanoTime()}"
        val backup = File(backupRoot, "$timestamp/$relative")
        require(backup.parentFile?.mkdirs() == true || backup.parentFile?.isDirectory == true) {
            "Could not create backup directory."
        }
        Files.copy(file.toPath(), backup.toPath())
        return "metadata/backups/$appId/$timestamp/$relative"
    }

    private fun atomicWrite(file: File, content: ByteArray) {
        require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) {
            "Could not create the file's parent directory."
        }
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeBytes(content)
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

    private fun relativePath(file: File): String {
        val path = file.canonicalPath
        return if (path == root.path) "." else path.removePrefix(root.path + File.separator)
            .replace(File.separatorChar, '/')
    }

    private fun relativePathFromWorkspace(file: File): String {
        val canonical = file.canonicalFile
        require(canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)) {
            "Path escapes the selected app."
        }
        return relativePath(canonical)
    }

    private fun requireKeys(args: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = args.keys().asSequence().toSet()
        require(keys.containsAll(required)) { "Missing required arguments: ${(required - keys).joinToString()}." }
        require((keys - required - optional).isEmpty()) { "Unknown arguments: ${(keys - required - optional).joinToString()}." }
    }

    private fun stringArgument(args: JSONObject, name: String): String {
        val value = args.opt(name)
        require(value is String) { "Argument '$name' must be a string." }
        return value
    }

    private fun optionalString(args: JSONObject, name: String, default: String): String =
        if (args.has(name)) stringArgument(args, name) else default

    private fun booleanArgument(args: JSONObject, name: String): Boolean {
        val value = args.opt(name)
        require(value is Boolean) { "Argument '$name' must be a boolean." }
        return value
    }

    private fun optionalBoolean(args: JSONObject, name: String, default: Boolean): Boolean =
        if (args.has(name)) booleanArgument(args, name) else default

    private fun schema(vararg fields: Pair<String, JSONObject>): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        fields.forEach { (name, spec) ->
            properties.put(name, spec)
            if (spec.optBoolean("_required")) {
                required.put(name)
                spec.remove("_required")
            }
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
            .put("additionalProperties", false)
    }

    private fun required(name: String, type: JSONObject): Pair<String, JSONObject> =
        name to type.put("_required", true)

    private fun optional(name: String, type: JSONObject): Pair<String, JSONObject> = name to type

    private fun stringType(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun booleanType(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun emptyObjectSchema() = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", JSONArray())
        .put("additionalProperties", false)

    private companion object {
        const val MAX_TEXT_FILE_BYTES = 1_000_000
        const val MAX_LIST_ENTRIES = 2_000
        const val MAX_SEARCH_FILES = 500
        const val MAX_SEARCH_RESULTS = 100
        const val MAX_QUERY_LENGTH = 200
        const val MAX_LINE_LENGTH = 500
    }
}
