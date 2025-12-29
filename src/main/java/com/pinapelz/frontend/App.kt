package com.pinapelz.frontend

import io.javalin.Javalin
import io.javalin.http.staticfiles.Location
import com.pinapelz.Retriever
import com.pinapelz.FileSystem
import java.sql.ResultSet
import java.text.SimpleDateFormat

fun startFrontend(retriever: Retriever, fileSystem: FileSystem) {
    val app = Javalin.create {
        it.staticFiles.add("/public", Location.CLASSPATH)
    }

    app.get("/") { ctx ->
        val directoryId = ctx.queryParam("dir")?.toIntOrNull() ?: 1
        ctx.html(generateMainHtml(directoryId))
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
        val rs: ResultSet = fileSystem.getFilesByDirectoryIdFiltered(directoryId, search, mimeTypeFilter, sortBy)
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

        // Server-side validation
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
    if (path.length < 1 || path.length > 100) {
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
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>nitro-fs</title>
            <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
            <script src="https://unpkg.com/htmx.org@1.9.10"></script>
            <style>
                * {
                    margin: 0;
                    padding: 0;
                    box-sizing: border-box;
                }

                body {
                    background-color: #36393f;
                    color: #dcddde;
                    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                    font-size: 14px;
                    line-height: 1.4;
                }

                .app-container {
                    min-height: 100vh;
                    display: flex;
                    flex-direction: column;
                }

                .header {
                    background-color: #2f3136;
                    padding: 12px 20px;
                    border-bottom: 1px solid #202225;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    position: sticky;
                    top: 0;
                    z-index: 100;
                }

                .header-left {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                }

                .header-title {
                    font-size: 16px;
                    font-weight: 600;
                    color: #ffffff;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .header-subtitle {
                    font-size: 12px;
                    color: #72767d;
                }

                .header-actions {
                    display: flex;
                    gap: 8px;
                }

                .btn {
                    background-color: transparent;
                    border: none;
                    color: #b9bbbe;
                    padding: 6px 12px;
                    border-radius: 4px;
                    font-size: 12px;
                    cursor: pointer;
                    transition: all 0.2s;
                    display: flex;
                    align-items: center;
                    gap: 4px;
                }

                .btn:hover {
                    background-color: #40444b;
                    color: #ffffff;
                }

                .btn-primary {
                    background-color: #5865f2;
                    color: #ffffff;
                }

                .btn-primary:hover {
                    background-color: #4752c4;
                }

                .main-content {
                    flex: 1;
                    padding: 20px;
                    overflow-y: auto;
                }

                .search-bar {
                    display: flex;
                    gap: 12px;
                    margin-bottom: 20px;
                    flex-wrap: wrap;
                }

                .search-input {
                    flex: 1;
                    min-width: 200px;
                    background-color: #40444b;
                    border: 1px solid #202225;
                    border-radius: 4px;
                    padding: 8px 12px;
                    color: #dcddde;
                    font-size: 14px;
                    outline: none;
                }

                .search-input:focus {
                    border-color: #5865f2;
                }

                .search-input::placeholder {
                    color: #72767d;
                }

                .select {
                    background-color: #40444b;
                    border: 1px solid #202225;
                    border-radius: 4px;
                    padding: 8px 12px;
                    color: #dcddde;
                    font-size: 14px;
                    cursor: pointer;
                    outline: none;
                }

                .select:focus {
                    border-color: #5865f2;
                }

                .stats {
                    display: flex;
                    gap: 16px;
                    margin-bottom: 20px;
                    align-items: center;
                }

                .stat-item {
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    color: #72767d;
                    font-size: 12px;
                }

                .stat-number {
                    color: #ffffff;
                    font-weight: 600;
                }

                .file-container {
                    background-color: #2f3136;
                    border-radius: 8px;
                    border: 1px solid #202225;
                    overflow: hidden;
                }

                .file-table {
                    width: 100%;
                    border-collapse: collapse;
                }

                .file-table th {
                    background-color: #36393f;
                    padding: 12px 16px;
                    text-align: left;
                    font-size: 12px;
                    font-weight: 600;
                    color: #72767d;
                    text-transform: uppercase;
                    border-bottom: 1px solid #202225;
                }

                .file-table td {
                    padding: 12px 16px;
                    border-bottom: 1px solid #202225;
                    vertical-align: middle;
                }

                .file-table tbody tr:hover {
                    background-color: #36393f;
                }

                .file-table tbody tr:last-child td {
                    border-bottom: none;
                }

                .file-link {
                    color: #00aff4;
                    text-decoration: none;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    font-weight: 500;
                }

                .file-link:hover {
                    color: #ffffff;
                    text-decoration: underline;
                }

                .file-icon {
                    color: #72767d;
                    width: 16px;
                    text-align: center;
                }

                .file-description {
                    color: #b9bbbe;
                    max-width: 200px;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                }

                .file-size {
                    color: #72767d;
                    font-size: 12px;
                    font-family: 'Courier New', monospace;
                }

                .file-type {
                    background-color: #5865f2;
                    color: #ffffff;
                    padding: 2px 6px;
                    border-radius: 12px;
                    font-size: 10px;
                    font-weight: 600;
                    text-transform: uppercase;
                }

                .file-date {
                    color: #72767d;
                    font-size: 12px;
                }

                .empty-state {
                    text-align: center;
                    padding: 60px 20px;
                    color: #72767d;
                }

                .empty-state-icon {
                    font-size: 48px;
                    margin-bottom: 16px;
                    opacity: 0.5;
                }

                .empty-state h3 {
                    color: #b9bbbe;
                    margin-bottom: 8px;
                    font-size: 18px;
                    font-weight: 600;
                }

                .empty-state p {
                    font-size: 14px;
                    line-height: 1.5;
                }

                .htmx-indicator {
                    display: none;
                }

                .htmx-request .htmx-indicator {
                    display: inline;
                }

                .loading-spinner {
                    animation: spin 1s linear infinite;
                }

                @keyframes spin {
                    from { transform: rotate(0deg); }
                    to { transform: rotate(360deg); }
                }

                .filter-badge {
                    background-color: #5865f2;
                    color: #ffffff;
                    padding: 2px 6px;
                    border-radius: 12px;
                    font-size: 10px;
                    margin-left: 8px;
                }

                @media (max-width: 768px) {
                    .search-bar {
                        flex-direction: column;
                    }

                    .search-input {
                        min-width: 100%;
                    }

                    .file-table th:nth-child(3),
                    .file-table td:nth-child(3),
                    .file-table th:nth-child(4),
                    .file-table td:nth-child(4),
                    .file-table th:nth-child(5),
                    .file-table td:nth-child(5),
                    .file-table th:nth-child(6),
                    .file-table td:nth-child(6) {
                        display: none;
                    }
                }
            </style>
        </head>
        <body hx-boost="true">
            <div class="app-container">
                <header class="header">
                    <div class="header-left">
                        <div class="header-title">
                            <i class="fab fa-discord"></i>
                            nitro-fs
                        </div>
                        <div class="header-subtitle" id="current-directory"># loading...</div>
                    </div>
                    <div class="header-actions">
                        <button class="btn"
                                hx-get="/api/files?dir=$directoryId"
                                hx-target="#file-content"
                                hx-indicator="#loading-spinner">
                            <i class="fas fa-sync-alt"></i>
                        </button>
                        <button class="btn"
                                hx-get="/api/directories-html"
                                hx-target="#directory-list"
                                hx-trigger="click"
                                onclick="toggleDirectoryPanel()">
                            <i class="fas fa-folder"></i>
                        </button>
                    </div>
                </header>

                <main class="main-content">
                    <div class="directory-panel" id="directory-panel" style="display: none;">
                        <div class="panel-header">
                            <h3>directories</h3>
                            <button class="btn btn-sm" onclick="toggleDirectoryPanel()">
                                <i class="fas fa-times"></i>
                            </button>
                        </div>

                        <div class="create-directory-form">
                            <form onsubmit="createDirectory(event)">
                                <div class="form-row">
                                    <input type="text"
                                           id="new-directory-name"
                                           placeholder="directory name..."
                                           class="form-input"
                                           maxlength="100"
                                           pattern="[^<>:\"/\\|?*\x00-\x1f]+"
                                           title="Directory name cannot contain: < > : \" / \ | ? *"
                                           required>
                                    <button type="submit" class="btn btn-primary btn-sm" id="create-btn">
                                        <i class="fas fa-plus"></i>
                                    </button>
                                </div>
                            </form>
                            <div id="create-directory-message" class="form-message"></div>
                        </div>

                        <div id="directory-list">
                            <div class="empty-state">
                                <i class="fas fa-spinner loading-spinner"></i>
                            </div>
                        </div>
                    </div>

                    <div class="search-bar">
                        <input type="text"
                               class="search-input"
                               placeholder="search files..."
                               hx-get="/api/files?dir=$directoryId"
                               hx-trigger="keyup changed delay:300ms"
                               hx-target="#file-content"
                               hx-indicator="#loading-spinner"
                               name="search">

                        <select class="select"
                                hx-get="/api/files?dir=$directoryId"
                                hx-trigger="change"
                                hx-target="#file-content"
                                hx-indicator="#loading-spinner"
                                name="mimeType">
                            <option value="">all types</option>
                            <option value="image/">images</option>
                            <option value="video/">videos</option>
                            <option value="audio/">audio</option>
                            <option value="application/pdf">pdfs</option>
                            <option value="text/">text</option>
                            <option value="application/zip">archives</option>
                        </select>

                        <select class="select"
                                hx-get="/api/files?dir=$directoryId"
                                hx-trigger="change"
                                hx-target="#file-content"
                                hx-indicator="#loading-spinner"
                                name="sortBy">
                            <option value="created_at">newest</option>
                            <option value="file_name">name</option>
                            <option value="size">size</option>
                        </select>
                    </div>

                    <div class="stats">
                        <div class="stat-item">
                            <span id="file-count" class="stat-number">
                                <span class="htmx-indicator" id="loading-spinner">
                                    <i class="fas fa-spinner loading-spinner"></i>
                                </span>
                                <span id="count-value">loading</span>
                            </span>
                            files
                        </div>
                    </div>

                    <div class="file-container"
                         hx-get="/api/files?dir=$directoryId"
                         hx-trigger="load"
                         hx-target="#file-content"
                         hx-indicator="#loading-spinner"
                        <div id="file-content">
                            <div class="empty-state">
                                <div class="empty-state-icon">
                                    <i class="fas fa-spinner loading-spinner"></i>
                                </div>
                                <h3>loading files...</h3>
                                <p>fetching your files from discord storage</p>
                            </div>
                        </div>
                    </div>
                </main>
            </div>

            <script>
                let currentDirectoryId = $directoryId;

                function toggleDirectoryPanel() {
                    const panel = document.getElementById('directory-panel');
                    panel.style.display = panel.style.display === 'none' ? 'block' : 'none';
                }

                function switchDirectory(dirId, dirPath) {
                    console.log('Switching to directory:', dirId, dirPath);
                    currentDirectoryId = dirId;
                    const displayPath = dirPath === '' ? 'root' : dirPath;
                    document.getElementById('current-directory').textContent = '# ' + displayPath;

                    // Update all HTMX endpoints to use new directory
                    const elementsWithFiles = document.querySelectorAll('[hx-get*="/api/files"]');
                    console.log('Found', elementsWithFiles.length, 'elements to update');
                    elementsWithFiles.forEach(el => {
                        const currentUrl = el.getAttribute('hx-get');
                        const baseUrl = currentUrl.split('?')[0];
                        const params = new URLSearchParams(currentUrl.split('?')[1] || '');
                        params.set('dir', dirId);
                        const newUrl = baseUrl + '?' + params.toString();
                        el.setAttribute('hx-get', newUrl);
                        console.log('Updated element URL to:', newUrl);
                    });

                    // Clear any existing search/filters
                    const searchInput = document.querySelector('input[name="search"]');
                    const mimeSelect = document.querySelector('select[name="mimeType"]');
                    const sortSelect = document.querySelector('select[name="sortBy"]');

                    if (searchInput) searchInput.value = '';
                    if (mimeSelect) mimeSelect.value = '';
                    if (sortSelect) sortSelect.value = 'created_at';

                    // Refresh file list with new directory
                    console.log('Making HTMX request to: /api/files?dir=' + dirId);
                    htmx.ajax('GET', '/api/files?dir=' + dirId, {
                        target: '#file-content',
                        indicator: '#loading-spinner'
                    });

                    toggleDirectoryPanel();
                }

                // Clear filters function
                function clearFilters() {
                    document.querySelector('input[name="search"]').value = '';
                    document.querySelector('select[name="mimeType"]').value = '';
                    document.querySelector('select[name="sortBy"]').value = 'created_at';
                    // Refresh file list with current directory
                    htmx.ajax('GET', '/api/files?dir=' + currentDirectoryId, {
                        target: '#file-content',
                        indicator: '#loading-spinner'
                    });
                }

                function createDirectory(event) {
                    event.preventDefault();
                    const input = document.getElementById('new-directory-name');
                    const message = document.getElementById('create-directory-message');
                    const path = input.value.trim();

                    // Validation
                    if (!path) {
                        showMessage(message, 'Please enter a directory name', 'error');
                        return;
                    }

                    if (path.length < 1 || path.length > 100) {
                        showMessage(message, 'Directory name must be 1-100 characters', 'error');
                        return;
                    }

                    // Check for invalid characters
                    const invalidChars = /[<>:"/\\|?*\x00-\x1f]/;
                    if (invalidChars.test(path)) {
                        showMessage(message, 'Directory name contains invalid characters', 'error');
                        return;
                    }

                    // Check for reserved names
                    const reserved = ['CON', 'PRN', 'AUX', 'NUL', 'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9', 'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'];
                    if (reserved.includes(path.toUpperCase())) {
                        showMessage(message, 'Directory name is reserved', 'error');
                        return;
                    }

                    if (path === '.' || path === '..') {
                        showMessage(message, 'Invalid directory name', 'error');
                        return;
                    }

                    // Show loading
                    input.disabled = true;
                    showMessage(message, 'Creating directory...', 'loading');

                    fetch('/api/directories', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                        body: 'path=' + encodeURIComponent(path)
                    })
                    .then(response => response.json())
                    .then(data => {
                        input.disabled = false;
                        if (data.success) {
                            showMessage(message, 'Directory created!', 'success');
                            input.value = '';
                            // Refresh directory list
                            htmx.ajax('GET', '/api/directories-html', {
                                target: '#directory-list'
                            });
                        } else {
                            showMessage(message, data.message || 'Failed to create directory', 'error');
                        }
                    })
                    .catch(error => {
                        input.disabled = false;
                        showMessage(message, 'Network error: ' + error.message, 'error');
                    });
                }

                function showMessage(element, text, type) {
                    element.textContent = text;
                    element.className = 'form-message ' + type;
                    if (type === 'success') {
                        setTimeout(() => {
                            element.textContent = '';
                            element.className = 'form-message';
                        }, 3000);
                    }
                }

                // Update directory name on load
                console.log('Loading current directory info for ID:', currentDirectoryId);
                fetch('/api/directory/' + currentDirectoryId)
                    .then(r => {
                        console.log('Directory API response status:', r.status);
                        return r.json();
                    })
                    .then(dir => {
                        console.log('Directory info loaded:', dir);
                        const displayPath = dir.path === '' ? 'root' : dir.path;
                        document.getElementById('current-directory').textContent = '# ' + displayPath;
                    })
                    .catch(error => {
                        console.error('Failed to load directory info:', error);
                        document.getElementById('current-directory').textContent = '# root';
                    });

                function deleteDirectory(dirId, dirName) {
                    if (confirm('Are you sure you want to delete directory "' + dirName + '"?\\n\\nNote: Directory must be empty to delete.')) {
                        fetch('/api/directories/' + dirId, {
                            method: 'DELETE'
                        })
                        .then(response => response.json())
                        .then(data => {
                            if (data.success) {
                                // Refresh directory list
                                htmx.ajax('GET', '/api/directories-html', {
                                    target: '#directory-list'
                                });
                                // If we're currently in the deleted directory, switch to root
                                if (currentDirectoryId == dirId) {
                                    switchDirectory(1, '');
                                }
                            } else {
                                alert('Failed to delete directory: ' + data.message);
                            }
                        })
                        .catch(error => {
                            alert('Error deleting directory: ' + error.message);
                        });
                    }
                }
            </script>

            <style>
                .directory-panel {
                    position: fixed;
                    top: 53px;
                    right: 20px;
                    width: 300px;
                    background-color: #2f3136;
                    border: 1px solid #202225;
                    border-radius: 8px;
                    z-index: 200;
                    max-height: 400px;
                    overflow-y: auto;
                }

                .panel-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 12px 16px;
                    border-bottom: 1px solid #202225;
                }

                .panel-header h3 {
                    margin: 0;
                    font-size: 14px;
                    font-weight: 600;
                    color: #ffffff;
                }

                .directory-item {
                    padding: 12px 16px;
                    cursor: pointer;
                    border-bottom: 1px solid #202225;
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    transition: background-color 0.2s ease;
                }

                .directory-item:hover {
                    background-color: #36393f;
                }

                .directory-item:last-child {
                    border-bottom: none;
                }

                .directory-count {
                    color: #72767d;
                    font-size: 12px;
                }

                .directory-content {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    flex: 1;
                }

                .directory-icon {
                    color: #72767d;
                    font-size: 16px;
                    width: 20px;
                    text-align: center;
                }

                .directory-info {
                    flex: 1;
                }

                .directory-name {
                    color: #dcddde;
                    font-size: 14px;
                    font-weight: 500;
                    margin-bottom: 2px;
                }

                .directory-meta {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .file-count {
                    color: #72767d;
                    font-size: 11px;
                }

                .directory-arrow {
                    color: #72767d;
                    font-size: 10px;
                    transition: transform 0.2s ease;
                }

                .directory-item:hover .directory-arrow {
                    transform: translateX(2px);
                    color: #b9bbbe;
                }

                .directory-item:hover .directory-icon {
                    color: #b9bbbe;
                }

                .directory-item:hover .directory-name {
                    color: #ffffff;
                }

                .create-directory-form {
                    padding: 12px 16px;
                    border-bottom: 1px solid #202225;
                }

                .form-row {
                    display: flex;
                    gap: 8px;
                    align-items: center;
                }

                .form-input {
                    flex: 1;
                    background-color: #40444b;
                    border: 1px solid #202225;
                    border-radius: 4px;
                    padding: 6px 8px;
                    color: #dcddde;
                    font-size: 12px;
                    outline: none;
                }

                .form-input:focus {
                    border-color: #5865f2;
                }

                .form-input::placeholder {
                    color: #72767d;
                }

                .form-input:disabled {
                    opacity: 0.6;
                    cursor: not-allowed;
                }

                .btn-sm {
                    padding: 6px 8px;
                    font-size: 12px;
                }

                .form-message {
                    font-size: 11px;
                    margin-top: 8px;
                    padding: 4px 0;
                }

                .form-message.success {
                    color: #43b581;
                }

                .form-message.error {
                    color: #f04747;
                }

                .form-message.loading {
                    color: #72767d;
                }

                .file-actions {
                    text-align: center;
                }

                .btn-delete {
                    background: none;
                    border: none;
                    color: #f04747;
                    cursor: pointer;
                    padding: 4px 8px;
                    border-radius: 4px;
                    font-size: 12px;
                    transition: all 0.2s;
                }

                .btn-delete:hover {
                    background-color: #f04747;
                    color: #ffffff;
                }

                .directory-item {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                }

                .directory-content {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    flex: 1;
                    cursor: pointer;
                }

                .directory-actions {
                    opacity: 0;
                    transition: opacity 0.2s;
                }

                .directory-item:hover .directory-actions {
                    opacity: 1;
                }
            </style>
        </body>
        </html>
    """.trimIndent()
}

fun generateDirectoryListHtml(directories: List<Map<String, Any>>): String {
    if (directories.isEmpty()) {
        return """
        <div class="empty-state" style="padding: 20px;">
            <div class="empty-state-icon">
                <i class="fas fa-folder-open" style="opacity: 0.3;"></i>
            </div>
            <p style="font-size: 12px; color: #72767d; margin: 8px 0 0 0;">no directories found</p>
        </div>
        """.trimIndent()
    }

    val directoryItems = directories.joinToString("") { dir ->
        val path = dir["path"] as String
        val displayName = if (path.isEmpty()) "root" else path
        val fileCount = dir["fileCount"] as Int
        val countDisplay = if (fileCount == 0) "" else " (${fileCount})"

        """
        <div class="directory-item">
            <div class="directory-content" onclick="switchDirectory(${dir["id"]}, '${path.replace("'", "\\'")}')" title="Switch to $displayName directory">
                <div class="directory-icon">
                    <i class="fas ${if (path.isEmpty()) "fa-home" else "fa-folder"}"></i>
                </div>
                <div class="directory-info">
                    <div class="directory-name">$displayName</div>
                    <div class="directory-meta">
                        <span class="file-count">$fileCount files</span>
                    </div>
                </div>
                <div class="directory-arrow">
                    <i class="fas fa-chevron-right"></i>
                </div>
            </div>
            ${if (!path.isEmpty()) """
            <div class="directory-actions">
                <button class="btn-delete btn-sm" onclick="deleteDirectory(${dir["id"]}, '$displayName')" title="Delete directory">
                    <i class="fas fa-trash"></i>
                </button>
            </div>
            """ else ""}
        </div>
        """.trimIndent()
    }

    return directoryItems
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
    fun getFileIcon(mimeType: String?): String {
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

    val fileRows = files.joinToString("") { file ->
        """
        <tr>
            <td>
                <a href="/fetch?fileId=${file["id"]}" target="_blank" class="file-link">
                    <i class="${getFileIcon(file["mimeType"] as? String)} file-icon"></i>
                    ${file["name"]}
                </a>
            </td>
            <td class="file-description">${file["description"]}</td>
            <td class="file-size">${file["size"]}</td>
            <td><span class="file-type">${(file["mimeType"] as? String)?.split("/")?.get(0) ?: "file"}</span></td>
            <td class="file-date">${(file["created"] as String).split(" ")[0]}</td>
            <td class="file-actions">
                <button class="btn-delete" onclick="deleteFile(${file["id"]}, '${(file["name"] as String).replace("'", "\\'")}')" title="Delete file">
                    <i class="fas fa-trash"></i>
                </button>
            </td>
        </tr>
        """.trimIndent()
    }

    return if (files.isEmpty()) {
        val emptyMessage = if (search.isNotEmpty() || mimeTypeFilter.isNotEmpty()) {
            """
            <div class="empty-state">
                <div class="empty-state-icon">
                    <i class="fas fa-search"></i>
                </div>
                <h3>no matches found</h3>
                <p>try different search terms or clear your filters</p>
                <button class="btn btn-primary" onclick="clearFilters()">
                    <i class="fas fa-times"></i> clear filters
                </button>
            </div>
            """
        } else {
            """
            <div class="empty-state">
                <div class="empty-state-icon">
                    <i class="fas fa-folder-open"></i>
                </div>
                <h3>no files yet</h3>
                <p>upload some files through discord to see them here</p>
            </div>
            """
        }

        """
        $emptyMessage
        <script>
            document.getElementById('count-value').textContent = '0';
            function clearFilters() {
                document.querySelector('input[name="search"]').value = '';
                document.querySelector('select[name="mimeType"]').value = '';
                document.querySelector('select[name="sortBy"]').value = 'created_at';
                htmx.trigger('.file-container', 'refresh');
            }
        </script>
        """.trimIndent()
    } else {
        """
        <table class="file-table">
            <thead>
                <tr>
                    <th style="width: 40%;">name</th>
                    <th style="width: 25%;">description</th>
                    <th style="width: 10%;">size</th>
                    <th style="width: 10%;">type</th>
                    <th style="width: 10%;">date</th>
                    <th style="width: 5%;">actions</th>
                </tr>
            </thead>
            <tbody>
                $fileRows
            </tbody>
        </table>
        <script>
            document.getElementById('count-value').textContent = '${files.size}';
            function clearFilters() {
                document.querySelector('input[name="search"]').value = '';
                document.querySelector('select[name="mimeType"]').value = '';
                document.querySelector('select[name="sortBy"]').value = 'created_at';
                htmx.ajax('GET', '/api/files?dir=' + currentDirectoryId, {
                    target: '#file-content',
                    indicator: '#loading-spinner'
                });
            }
            function deleteFile(fileId, fileName) {
                if (confirm('Are you sure you want to delete "' + fileName + '"?')) {
                    fetch('/api/files/' + fileId, {
                        method: 'DELETE'
                    })
                    .then(response => response.json())
                    .then(data => {
                        if (data.success) {
                            // Refresh file list
                            htmx.ajax('GET', '/api/files?dir=' + currentDirectoryId, {
                                target: '#file-content',
                                indicator: '#loading-spinner'
                            });
                        } else {
                            alert('Failed to delete file: ' + data.message);
                        }
                    })
                    .catch(error => {
                        alert('Error deleting file: ' + error.message);
                    });
                }
            }
        </script>
        """.trimIndent()
    }
}
