package com.pinapelz.frontend

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class WebhookUploadResult(
    val success: Boolean,
    val channelId: String? = null,
    val messageId: String? = null,
    val error: String? = null
)

class WebhookManager(webhooksFilePath: String) {
    private val webhooks: List<String>
    private val webhookCooldowns = mutableMapOf<String, Long>()
    private var currentWebhookIndex = 0
    private val cooldownPeriodMs = 1000L
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        val webhooksFile = File(webhooksFilePath)
        if (!webhooksFile.exists()) {
            throw IllegalArgumentException("Webhooks file not found: $webhooksFilePath")
        }

        webhooks = webhooksFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (webhooks.isEmpty()) {
            throw IllegalArgumentException("No valid webhooks found in file: $webhooksFilePath")
        }

        println("Loaded ${webhooks.size} webhooks from $webhooksFilePath")
    }

    private fun getNextAvailableWebhook(): String? {
        val currentTime = System.currentTimeMillis()
        val startIndex = currentWebhookIndex

        do {
            val webhook = webhooks[currentWebhookIndex]
            val lastUsed = webhookCooldowns[webhook] ?: 0
            val timeSinceLastUse = currentTime - lastUsed

            if (timeSinceLastUse >= cooldownPeriodMs) {
                return webhook
            }

            currentWebhookIndex = (currentWebhookIndex + 1) % webhooks.size
        } while (currentWebhookIndex != startIndex)
        val nextAvailableTime = webhookCooldowns.values.minOrNull() ?: 0
        val waitTime = max(0, (nextAvailableTime + cooldownPeriodMs) - currentTime)

        if (waitTime > 0) {
            Thread.sleep(waitTime)
            return getNextAvailableWebhook()
        }

        return null
    }

    fun uploadFile(filePath: Path): WebhookUploadResult {
        val webhook = getNextAvailableWebhook()
            ?: return WebhookUploadResult(false, error = "No available webhooks")

        val file = filePath.toFile()
        if (!file.exists()) {
            return WebhookUploadResult(false, error = "File does not exist: $filePath")
        }

        try {
            val mimeType = Files.probeContentType(filePath) ?: "application/octet-stream"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody(mimeType.toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url(webhook)
                .post(requestBody)
                .build()

            webhookCooldowns[webhook] = System.currentTimeMillis()
            currentWebhookIndex = (currentWebhookIndex + 1) % webhooks.size

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return WebhookUploadResult(
                        false,
                        error = "HTTP ${response.code}: ${response.message}"
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    return WebhookUploadResult(false, error = "Empty response from Discord")
                }

                try {
                    val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                    val channelId = jsonObject.get("channel_id")?.asString
                    val messageId = jsonObject.get("id")?.asString

                    println("Discord webhook response - Channel ID: $channelId, Message ID: $messageId")

                    if (channelId != null && messageId != null) {
                        return WebhookUploadResult(
                            success = true,
                            channelId = channelId,
                            messageId = messageId
                        )
                    } else {
                        println("Failed to extract IDs from response: $responseBody")
                        return WebhookUploadResult(
                            false,
                            error = "Could not extract channel/message IDs from response"
                        )
                    }
                } catch (e: Exception) {
                    println("Failed to parse JSON response: ${e.message}")
                    println("Response was: $responseBody")
                    return WebhookUploadResult(
                        false,
                        error = "Failed to parse Discord response: ${e.message}"
                    )
                }
            }
        } catch (e: IOException) {
            return WebhookUploadResult(false, error = "Network error: ${e.message}")
        } catch (e: Exception) {
            return WebhookUploadResult(false, error = "Unexpected error: ${e.message}")
        }
    }
}
