package com.pinapelz.frontend

import io.javalin.Javalin
import com.pinapelz.Retriever
import com.pinapelz.FileSystem
import java.io.File
import java.net.URLEncoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun startFrontend(retriever: Retriever, fileSystem: FileSystem, webhooksFile: String) {
    // Initialize WebhookManager if webhooks file exists
    val webhookManager = if (File(webhooksFile).exists()) {
        try {
            WebhookManager(webhooksFile)
        } catch (e: Exception) {
            println("Warning: Failed to initialize webhook manager: ${e.message}")
            null
        }
    } else {
        println("Warning: Webhooks file not found: $webhooksFile")
        null
    }
    val app = Javalin.create{}

    app.get("/") { ctx ->
        val directoryId = ctx.queryParam("dir")?.toIntOrNull() ?: 1
        ctx.html(generateMainHtml(directoryId))
    }

    app.get("/splitter") { ctx ->
        ctx.html(generateFileSplitterHtml())
    }

    app.post("/api/split") { ctx ->
        val manager = MultipartFileManager(fileSystem, webhookManager)
        val result = manager.handleSplitRequest(ctx)
        ctx.json(result)
    }

    app.get("/api/directories") { ctx ->
        val directories = mutableListOf<Map<String, Any>>()
        val rs = fileSystem.getAllDirectories()
        for (d in rs) {
            directories.add(
                mapOf(
                    "id" to d.directoryId,
                    "path" to d.path,
                    "fileCount" to d.fileCount,
                    "created" to d.createdAt.toString()
                )
            )
        }


        ctx.json(directories)
    }

    app.get("/api/directory/{id}") { ctx ->
        val directoryId = ctx.pathParam("id").toInt()
        val d = fileSystem.getDirectoryById(directoryId)

        if (d != null) {
            val directory = mapOf(
                "id" to d.directoryId,
                "path" to d.path,
                "fileCount" to d.fileCount,
                "created" to d.createdAt.toString()
            )
            ctx.json(directory)
        } else {
            ctx.status(404).result("Directory not found")
        }

    }

    app.get("/api/files") { ctx ->
        val directoryId = ctx.queryParam("dir")?.toIntOrNull() ?: 1
        val search = ctx.queryParam("search") ?: ""
        val mimeTypeFilter = ctx.queryParam("mimeType") ?: ""
        val sortBy = ctx.queryParam("sortBy") ?: "created_at"

        val files = mutableListOf<Map<String, Any>>()
        val fileEntries = fileSystem.getFilesByDirectoryId(
            directoryId,
            search,
            mimeTypeFilter,
            sortBy
        )

        for (f in fileEntries) {
            files.add(
                mapOf(
                    "id" to f.fileId,
                    "name" to f.fileName,
                    "description" to (f.description ?: ""),
                    "size" to formatFileSize(f.size),
                    "mimeType" to (f.mimeType ?: "unknown"),
                    "created" to DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault())
                        .format(f.createdAt)
                )
            )
        }


        val partials = fileSystem.getGroupedPartials(directoryId, search)
        for (p in partials) {
            files.add(
                mapOf(
                    "id" to "partial:${p.originalFilename}|$directoryId",
                    "name" to p.originalFilename,
                    "description" to (p.description ?: ""),
                    "size" to formatFileSize(p.size),
                    "mimeType" to (p.mimeType ?: "application/octet-stream"),
                    "created" to DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault())
                        .format(p.createdAt)
                )
            )
        }

        val html = generateFileTableHtml(files, search, mimeTypeFilter)
        ctx.html(html)
        ctx.header("HX-Trigger", "updateFileCount")
        ctx.header("X-File-Count", files.size.toString())

    }

    app.get("/api/directories-html") { ctx ->
        val directories = mutableListOf<Map<String, Any>>()
        val directoriesResult = fileSystem.getAllDirectories()

        for (d in directoriesResult) {
            directories.add(
                mapOf(
                    "id" to d.directoryId,
                    "path" to d.path,
                    "fileCount" to d.fileCount,
                    "created" to d.createdAt.toString()
                )
            )
        }

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
        val idStr = ctx.pathParam("id")

        try {
            val deleted = if (idStr.startsWith("partial:")) {
                val data = idStr.substring("partial:".length).split("|")
                val filename = data[0]
                val dirId = data[1].toInt()
                fileSystem.deleteFilePartials(filename, dirId)
            } else {
                val fileId = idStr.toIntOrNull()
                if (fileId == null) {
                    ctx.status(400).json(mapOf(
                        "success" to false,
                        "message" to "Invalid file ID"
                    ))
                    return@delete
                }
                fileSystem.deleteFile(fileId)
            }
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

    app.get("/api/reassemble") { ctx ->
        val filename = ctx.queryParam("filename") ?: throw io.javalin.http.BadRequestResponse("filename required")
        val dirId = ctx.queryParam("dir")?.toIntOrNull() ?: throw io.javalin.http.BadRequestResponse("dir id required")

        data class PartInfo(val channelId: String, val messageId: String, val partName: String, val isWebhook: Boolean)
        val parts = mutableListOf<PartInfo>()
        var mimeType = "application/octet-stream"

        for (p in fileSystem.getFilePartialsByOriginalFilename(filename, dirId)) {
            parts.add(
                PartInfo(
                    p.channelId,
                    p.messageId,
                    p.partName,
                    p.uploadedViaWebhook
                )
            )
            mimeType = p.mimeType ?: mimeType
        }


        if (parts.isEmpty()) {
            ctx.status(404).result("No parts found for $filename")
            return@get
        }

        ctx.header("Content-Disposition", "attachment; filename=\"$filename\"")
        ctx.contentType(mimeType)

        ctx.async {
            try {
                val outputStream = ctx.res().outputStream
                for ((index, part) in parts.withIndex()) {
                    var success = false
                    var lastError: Exception? = null
                    for (attempt in 1..3) {
                        try {
                            val url = retriever.getFileUrl(part.channelId, part.messageId, part.partName, part.isWebhook)
                            println("Fetching part ${index + 1}/${parts.size} from: $url (attempt $attempt)")
                            val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                            connection.requestMethod = "GET"
                            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                            connection.connectTimeout = 30000
                            connection.readTimeout = 30000
                            val responseCode = connection.responseCode
                            if (responseCode == 200) {
                                connection.inputStream.use { input ->
                                    input.copyTo(outputStream)
                                }
                                println("Successfully fetched part ${index + 1}/${parts.size}")
                                success = true
                                break
                            } else {
                                println("HTTP $responseCode for part ${index + 1} on attempt $attempt")
                                lastError = Exception("HTTP $responseCode: ${connection.responseMessage}")
                                if (attempt < 3) Thread.sleep(1000 * attempt.toLong())
                            }
                        } catch (e: Exception) {
                            println("Error fetching part ${index + 1} on attempt $attempt: ${e.message}")
                            lastError = e
                            if (attempt < 3) Thread.sleep(1000 * attempt.toLong())
                        }
                    }

                    if (!success) {
                        ctx.status(500)
                        ctx.result("Error: Failed to retrieve part ${index + 1} after 3 attempts. ${lastError?.message}")
                        return@async
                    }
                }
                outputStream.flush()
            } catch (e: Exception) {
                println("Error during file reassembly: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    app.get("/fetch") { ctx ->
        val fileIdStr = ctx.queryParam("fileId") ?: ""
        if (fileIdStr.startsWith("partial:")) {
            val data = fileIdStr.substring("partial:".length).split("|")
            val filename = data[0]
            val dirId = data[1]
            ctx.redirect("/api/reassemble?filename=${URLEncoder.encode(filename, "UTF-8")}&dir=$dirId")
            return@get
        }

        try {
            val fileMetadata = fileSystem.getFileById(Integer.parseInt(fileIdStr))
            println("Retrieving: " + fileMetadata.fileName)
            val fileUrl = retriever.getFileUrl(fileMetadata.channelId.toString(),
                fileMetadata.messageId.toString(), fileMetadata.fileName)
            ctx.redirect(fileUrl)
        } catch (e: Exception) {
            println("Failed to retrieve file: ${e.message}")
            ctx.status(404).result("Error: File not found or has been deleted from Discord. ${e.message}")
        }
    }
    app.start(7070)
}

fun validateDirectoryName(path: String): String? {
    if (path.length !in 1..100) {
        return "Directory name must be 1-100 characters long"
    }

    if (!path.all { it.code <= 127 }) {
        return "Directory name can only contain ASCII characters"
    }

    val invalidChars = Regex("[<>:\"/\\\\|?*\\x00-\\x1f#%&+@\\[\\]{}^`~;=']")
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
