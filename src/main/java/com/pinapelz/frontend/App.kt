package com.pinapelz.frontend

import io.javalin.Javalin
import com.pinapelz.Retriever
import com.pinapelz.FileSystem
import java.sql.ResultSet
import java.text.SimpleDateFormat

fun startFrontend(retriever: Retriever, fileSystem: FileSystem) {
    val app = Javalin.create{};

    app.get("/") { ctx ->
        val directoryId = ctx.queryParam("dir")?.toIntOrNull() ?: 1
        ctx.html(generateMainHtml(directoryId))
    }

    app.get("/splitter") { ctx ->
        ctx.html(generateFileSplitterHtml())
    }

    app.get("/api/directories") { ctx ->
        val directories = mutableListOf<Map<String, Any>>()
        val rs = fileSystem.getAllDirectories()

        while (rs.next()) {
            directories.add(mapOf(
                "id" to rs.getInt("directory_id"),
                "path" to rs.getString("path"),
                "fileCount" to rs.getInt("file_count"),
                "created" to rs.getTimestamp("created_at").toString()
            ))
        }
        rs.close()

        ctx.json(directories)
    }

    app.get("/api/directory/{id}") { ctx ->
        val directoryId = ctx.pathParam("id").toInt()
        val rs = fileSystem.getDirectoryById(directoryId)

        if (rs.next()) {
            val directory = mapOf(
                "id" to rs.getInt("directory_id"),
                "path" to rs.getString("path"),
                "fileCount" to rs.getInt("file_count"),
                "created" to rs.getTimestamp("created_at").toString()
            )
            rs.close()
            ctx.json(directory)
        } else {
            rs.close()
            ctx.status(404).result("Directory not found")
        }
    }

    app.get("/api/files") { ctx ->
        val directoryId = ctx.queryParam("dir")?.toIntOrNull() ?: 1
        val search = ctx.queryParam("search") ?: ""
        val mimeTypeFilter = ctx.queryParam("mimeType") ?: ""
        val sortBy = ctx.queryParam("sortBy") ?: "created_at"

        val files = mutableListOf<Map<String, Any>>()
        val rs: ResultSet = fileSystem.getFilesByDirectoryId(directoryId, search, mimeTypeFilter, sortBy)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        while (rs.next()) {
            files.add(mapOf(
                "id" to rs.getInt("file_id"),
                "name" to rs.getString("file_name"),
                "description" to (rs.getString("file_description") ?: ""),
                "size" to formatFileSize(rs.getLong("size")),
                "mimeType" to (rs.getString("mime_type") ?: "unknown"),
                "created" to dateFormat.format(rs.getTimestamp("created_at"))
            ))
        }
        rs.close()

        val html = generateFileTableHtml(files, search, mimeTypeFilter)
        ctx.html(html)
        ctx.header("HX-Trigger", "updateFileCount")
        ctx.header("X-File-Count", files.size.toString())
    }

    app.get("/api/directories-html") { ctx ->
        val directories = mutableListOf<Map<String, Any>>()
        val rs = fileSystem.getAllDirectories()

        while (rs.next()) {
            directories.add(mapOf(
                "id" to rs.getInt("directory_id"),
                "path" to rs.getString("path"),
                "fileCount" to rs.getInt("file_count"),
                "created" to rs.getTimestamp("created_at").toString()
            ))
        }
        rs.close()

        val html = generateDirectoryListHtml(directories)
        ctx.html(html)
    }

    app.post("/api/directories") { ctx ->
        val path = ctx.formParam("path")
        if (path.isNullOrBlank()) {
            ctx.status(400).json(mapOf(
                "success" to false,
                "message" to "Directory path is required"
            ))
            return@post
        }

        val trimmedPath = path.trim()

        val validationError = validateDirectoryName(trimmedPath)
        if (validationError != null) {
            ctx.status(400).json(mapOf(
                "success" to false,
                "message" to validationError
            ))
            return@post
        }

        try {
            val directoryId = fileSystem.createDirectory(trimmedPath)
            ctx.json(mapOf(
                "success" to true,
                "id" to directoryId,
                "path" to trimmedPath,
                "message" to "Directory created successfully"
            ))
        } catch (e: Exception) {
            ctx.status(500).json(mapOf(
                "success" to false,
                "message" to "Failed to create directory: ${e.message}"
            ))
        }
    }

    app.delete("/api/files/{id}") { ctx ->
        val fileId = ctx.pathParam("id").toIntOrNull()
        if (fileId == null) {
            ctx.status(400).json(mapOf(
                "success" to false,
                "message" to "Invalid file ID"
            ))
            return@delete
        }

        try {
            val deleted = fileSystem.deleteFile(fileId)
            if (deleted) {
                ctx.json(mapOf(
                    "success" to true,
                    "message" to "File deleted successfully"
                ))
            } else {
                ctx.status(404).json(mapOf(
                    "success" to false,
                    "message" to "File not found"
                ))
            }
        } catch (e: Exception) {
            ctx.status(500).json(mapOf(
                "success" to false,
                "message" to "Failed to delete file: ${e.message}"
            ))
        }
    }

    app.delete("/api/directories/{id}") { ctx ->
        val directoryId = ctx.pathParam("id").toIntOrNull()
        if (directoryId == null) {
            ctx.status(400).json(mapOf(
                "success" to false,
                "message" to "Invalid directory ID"
            ))
            return@delete
        }

        try {
            val deleted = fileSystem.deleteDirectory(directoryId)
            if (deleted) {
                ctx.json(mapOf(
                    "success" to true,
                    "message" to "Directory deleted successfully"
                ))
            } else {
                ctx.status(404).json(mapOf(
                    "success" to false,
                    "message" to "Directory not found"
                ))
            }
        } catch (e: Exception) {
            ctx.status(500).json(mapOf(
                "success" to false,
                "message" to "Failed to delete directory: ${e.message}"
            ))
        }
    }

    app.get("/fetch") { ctx ->
        val fileId = ctx.queryParam("fileId")
        val fileMetadata = fileSystem.getFileById(Integer.parseInt(fileId));
        print("Retrieving: " + fileMetadata.fileName)
        ctx.redirect(retriever.getFileUrl(fileMetadata.channelId.toString(),
            fileMetadata.messageId.toString(), fileMetadata.fileName));
    }
    app.start(7070)
}

fun validateDirectoryName(path: String): String? {
    if (path.length !in 1..100) {
        return "Directory name must be 1-100 characters long"
    }
    val invalidChars = Regex("[<>:\"/\\\\|?*\\x00-\\x1f]")
    if (invalidChars.containsMatchIn(path)) {
        return "Directory name contains invalid characters"
    }

    if (path == "." || path == "..") {
        return "Invalid directory name"
    }
    if (path.startsWith(" ") || path.endsWith(" ") || path.endsWith(".")) {
        return "Directory name cannot start/end with spaces or end with dots"
    }

    return null
}

fun generateMainHtml(directoryId: Int): String {
    return HtmlTemplates.generateMainPage(directoryId)
}

fun generateDirectoryListHtml(directories: List<Map<String, Any>>): String {
    return HtmlTemplates.generateDirectoryList(directories)
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

fun generateFileTableHtml(files: List<Map<String, Any>>, search: String = "", mimeTypeFilter: String = ""): String {
    return HtmlTemplates.generateFileTable(files, search, mimeTypeFilter)
}

fun generateFileSplitterHtml(): String {
    return HtmlTemplates.generateFileSplitterPage()
}
