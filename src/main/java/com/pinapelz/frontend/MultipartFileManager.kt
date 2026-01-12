package com.pinapelz.frontend

import io.javalin.http.Context
import io.javalin.http.UploadedFile
import com.pinapelz.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min


sealed class SplitConfig {
    data class BySize(val sizeInBytes: Long) : SplitConfig()
    data class ByParts(val numParts: Int) : SplitConfig()
}

data class FilePartMeta(
    val id: String,
    val name: String,
    val size: Long,
    val path: Path
)

data class SplitMetadata(
    val originalFilename: String,
    val totalSize: Long,
    val partCount: Int,
    val parts: List<FilePartMeta>
)

data class SplitFileResult(
    val directory: Path,
    val metadata: SplitMetadata,
    val uploadResults: List<WebhookUploadResult>? = null
)

data class ApiSplitResponse(
    val success: Boolean,
    val message: String? = null,
    val parts: List<ApiPartInfo>? = null
)

data class ApiPartInfo(
    val id: String,
    val name: String,
    val size: Long,
    val uploaded: Boolean = false,
    val channelId: String? = null,
    val messageId: String? = null
)

class MultipartFileManager(
    private val fileSystem: FileSystem? = null,
    private val webhookManager: WebhookManager? = null
) {
    fun handleSplitRequest(ctx: Context): ApiSplitResponse {
        try {
            val uploadedFile = ctx.uploadedFile("file")
                ?: return ApiSplitResponse(false, "No file was uploaded")

            val splitMethod = ctx.formParam("split-method") ?: "size"
            val useWebhook = ctx.formParam("use-webhook")?.toBoolean() ?: false
            val directoryId = ctx.formParam("directory-id")?.toIntOrNull() ?: 1
            var filePrefix = ctx.formParam("file-prefix")?.takeIf { it.isNotBlank() }
                ?: uploadedFile.filename().substringBeforeLast(".")
            val fileDescription = ctx.formParam("file-description") ?: ""
            
            // Replace spaces with underscores in prefix
            filePrefix = filePrefix.replace(" ", "_")
            
            // Validate prefix doesn't contain spaces
            if (filePrefix.contains(" ")) {
                return ApiSplitResponse(false, "File prefix cannot contain spaces. Spaces have been replaced with underscores.")
            }

            val splitConfig = when {
                useWebhook -> {
                    SplitConfig.BySize(10 * 1024 * 1024L) // Discord file limit
                }
                splitMethod == "size" -> {
                    val partSize = ctx.formParam("part-size")?.toLongOrNull() ?: 25L
                    val sizeUnit = ctx.formParam("size-unit") ?: "MB"
                    val sizeInBytes = when (sizeUnit) {
                        "KB" -> partSize * 1024
                        "GB" -> partSize * 1024 * 1024 * 1024
                        else -> partSize * 1024 * 1024 // MB default
                    }
                    SplitConfig.BySize(sizeInBytes)
                }
                else -> {
                    val numParts = ctx.formParam("num-parts")?.toIntOrNull() ?: 5
                    SplitConfig.ByParts(numParts)
                }
            }

            val result = splitFile(uploadedFile, config=splitConfig, prefix=filePrefix)

            if (useWebhook && webhookManager != null && fileSystem != null) {
                return handleWebhookUpload(result, directoryId, uploadedFile.filename(), fileDescription)
            } else {
                val apiParts = result.metadata.parts.map { part ->
                    ApiPartInfo(
                        id = part.id,
                        name = part.name,
                        size = part.size,
                        uploaded = false
                    )
                }
                return ApiSplitResponse(true, "File split successfully", apiParts)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return ApiSplitResponse(false, "Failed to split file: ${e.message}")
        }
    }

    private fun handleWebhookUpload(splitResult: SplitFileResult, directoryId: Int, originalFilename: String, description: String): ApiSplitResponse {
            if (webhookManager == null || fileSystem == null) {
                return ApiSplitResponse(false, "Webhook manager or file system not configured")
            }

            try {
                for (part in splitResult.metadata.parts) {
                    if (fileSystem.checkPartialNameConstraint(part.name, directoryId)) {
                        return ApiSplitResponse(
                            false,
                            "File part '${part.name}' already exists in this directory. Please use a different prefix or delete the existing parts."
                        )
                    }
                }
            } catch (e: Exception) {
                println("Failed to check for existing parts: ${e.message}")
                return ApiSplitResponse(false, "Failed to validate file parts: ${e.message}")
            }

            val uploadResults = mutableListOf<ApiPartInfo>()
            var uploadedCount = 0

            try {
                for ((index, part) in splitResult.metadata.parts.withIndex()) {
                    println("Uploading part ${index + 1}/${splitResult.metadata.parts.size}: ${part.name}")

                    val uploadResult = webhookManager.uploadFile(part.path)

                    if (uploadResult.success && uploadResult.channelId != null && uploadResult.messageId != null) {
                        try {
                            val partialId = fileSystem.createFilePartial(
                                uploadResult.channelId,
                                uploadResult.messageId,
                                directoryId,
                                part.name,
                                index + 1,
                                part.size,
                                originalFilename,
                                description,
                                "application/octet-stream"
                            )

                            uploadResults.add(ApiPartInfo(
                                id = part.id,
                                name = part.name,
                                size = part.size,
                                uploaded = true,
                                channelId = uploadResult.channelId,
                                messageId = uploadResult.messageId
                            ))
                            uploadedCount++

                            println("Successfully uploaded and recorded part: ${part.name} (partial_id: $partialId)")
                        } catch (e: Exception) {
                            println("Failed to record part in database: ${e.message}")
                            uploadResults.add(ApiPartInfo(
                                id = part.id,
                                name = part.name,
                                size = part.size,
                                uploaded = false
                            ))
                        }
                    } else {
                        println("Failed to upload part ${part.name}: ${uploadResult.error}")
                        uploadResults.add(ApiPartInfo(
                            id = part.id,
                            name = part.name,
                            size = part.size,
                            uploaded = false
                        ))
                    }
                }
                try {
                    splitResult.directory.toFile().deleteRecursively()
                } catch (e: Exception) {
                    println("Failed to clean up temporary files: ${e.message}")
                }

                val message = if (uploadedCount == splitResult.metadata.parts.size) {
                    "All ${uploadedCount} parts uploaded successfully"
                } else {
                    "Uploaded ${uploadedCount}/${splitResult.metadata.parts.size} parts successfully"
                }

                return ApiSplitResponse(
                    success = uploadedCount > 0,
                    message = message,
                    parts = uploadResults
                )

            } catch (e: Exception) {
                return ApiSplitResponse(
                    false,
                    "Upload process failed: ${e.message}",
                    uploadResults
                )
            }
        }
    }

    private fun splitFile( uploadedFile: UploadedFile, config: SplitConfig, prefix: String,
                           workingDir: Path = Files.createTempDirectory("split-${prefix}-") ): SplitFileResult {
        val fileData = uploadedFile.content().readBytes()
        val fileSize = fileData.size.toLong()
        val partsMeta = mutableListOf<FilePartMeta>()

        when (config) {
            is SplitConfig.BySize -> {
                val partSize = config.sizeInBytes - (16 * 1024);
                val numParts = ceil(fileSize.toDouble() / partSize.toDouble()).toInt()
                println("Splitting file: ${fileSize} bytes into ${numParts} parts of max ${partSize} bytes each")

                for (partIndex in 0 until numParts) {
                    val startIndex = (partIndex * partSize).toInt()
                    val endIndex = min(startIndex + partSize.toInt(), fileData.size)
                    val partBytes = fileData.sliceArray(startIndex until endIndex)
                    println("Created part ${partIndex + 1}: ${partBytes.size} bytes")

                    val partId = UUID.randomUUID().toString()
                    val partName = "${prefix}.part${String.format("%03d", partIndex + 1)}.nitro"
                    val partPath = workingDir.resolve(partName)
                    Files.write(partPath, partBytes)
                    partsMeta += FilePartMeta(
                        id = partId,
                        name = partName,
                        size = partBytes.size.toLong(),
                        path = partPath
                    )
                }
            }

            is SplitConfig.ByParts -> { // TODO: stubbed not yet implemented
            }
        }
        val metadata = SplitMetadata(
            originalFilename = uploadedFile.filename(),
            totalSize = fileSize,
            partCount = partsMeta.size,
            parts = partsMeta
        )
        return SplitFileResult(
            directory = workingDir,
            metadata = metadata
        )
    }
