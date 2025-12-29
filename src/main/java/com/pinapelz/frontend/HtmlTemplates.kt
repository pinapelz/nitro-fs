package com.pinapelz.frontend

import java.io.InputStream

object HtmlTemplates {
    
    private fun loadTemplate(templatePath: String): String {
        val inputStream: InputStream = this::class.java.classLoader.getResourceAsStream("templates/$templatePath")
            ?: throw IllegalArgumentException("Template not found: $templatePath")
        return inputStream.bufferedReader().use { it.readText() }
    }
    
    private fun String.substitute(vararg pairs: Pair<String, Any>): String {
        var result = this
        for ((placeholder, value) in pairs) {
            result = result.replace("{{$placeholder}}", value.toString())
        }
        return result
    }
    
    fun generateMainPage(directoryId: Int): String {
        val template = loadTemplate("main.html")
        return template.substitute(
            "directoryId" to directoryId
        )
    }
    
    fun generateDirectoryList(directories: List<Map<String, Any>>): String {
        if (directories.isEmpty()) {
            return loadTemplate("empty-directories.html")
        }
        
        val directoryTemplate = loadTemplate("directory-item.html")
        val directoryItems = directories.joinToString("") { dir ->
            val path = dir["path"] as String
            val displayName = if (path.isEmpty()) "root" else path
            val fileCount = dir["fileCount"] as Int
            val iconClass = if (path.isEmpty()) "fa-home" else "fa-folder"
            val deleteButton = if (path.isNotEmpty()) {
                """
                <div class="directory-actions">
                    <button class="btn-delete btn-sm" onclick="deleteDirectory(${dir["id"]}, '$displayName')" title="Delete directory">
                        <i class="fas fa-trash"></i>
                    </button>
                </div>
                """.trimIndent()
            } else ""
            
            directoryTemplate.substitute(
                "id" to dir["id"]!!,
                "path" to path.replace("'", "\\'"),
                "displayName" to displayName,
                "iconClass" to iconClass,
                "fileCount" to fileCount,
                "deleteButton" to deleteButton
            )
        }
        
        return directoryItems
    }
    
    fun generateFileTable(files: List<Map<String, Any>>, search: String = "", mimeTypeFilter: String = ""): String {
        if (files.isEmpty()) {
            return if (search.isNotEmpty() || mimeTypeFilter.isNotEmpty()) {
                loadTemplate("empty-search-results.html")
            } else {
                loadTemplate("empty-files.html")
            }
        }
        
        val fileRowTemplate = loadTemplate("file-row.html")
        val fileRows = files.joinToString("") { file ->
            val mimeType = file["mimeType"] as? String
            val fileIcon = getFileIcon(mimeType)
            val fileName = file["name"] as String
            val fileType = mimeType?.split("/")?.get(0) ?: "file"
            val createdDate = (file["created"] as String).split(" ")[0]
            
            fileRowTemplate.substitute(
                "id" to file["id"]!!,
                "fileIcon" to fileIcon,
                "name" to fileName,
                "description" to (file["description"] ?: ""),
                "size" to (file["size"] ?: ""),
                "fileType" to fileType,
                "createdDate" to createdDate,
                "escapedName" to fileName.replace("'", "\\'")
            )
        }
        
        val tableTemplate = loadTemplate("file-table.html")
        return tableTemplate.substitute(
            "fileRows" to fileRows,
            "fileCount" to files.size
        )
    }
    
    private fun getFileIcon(mimeType: String?): String {
        if (mimeType == null) return "fas fa-file"

        return when {
            mimeType.startsWith("image/") -> "fas fa-file-image"
            mimeType.startsWith("video/") -> "fas fa-file-video"
            mimeType.startsWith("audio/") -> "fas fa-file-audio"
            mimeType.contains("pdf") -> "fas fa-file-pdf"
            mimeType.startsWith("text/") -> "fas fa-file-alt"
            mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("rar") -> "fas fa-file-archive"
            else -> "fas fa-file"
        }
    }
}