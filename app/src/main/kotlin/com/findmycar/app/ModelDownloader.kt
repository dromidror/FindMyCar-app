package com.findmycar.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Downloads ML model files from Azure Blob Storage if not present locally.
 */
object ModelDownloader {

    private const val MODEL_FILE = "car_state_model.tflite"
    private const val CONFIG_FILE = "model_config.json"
    private const val RONIN_FILE = "ronin_model.tflite"
    private const val RONIN_CONFIG_FILE = "ronin_config.json"

    private val ALL_FILES = listOf(MODEL_FILE, CONFIG_FILE, RONIN_FILE, RONIN_CONFIG_FILE)

    /**
     * Check if all model files exist locally. Download missing ones from Azure.
     * Call from a background thread.
     */
    fun ensureModelsDownloaded(context: Context) {
        val filesDir = context.filesDir

        for (fileName in ALL_FILES) {
            val localFile = File(filesDir, fileName)
            if (!localFile.exists()) {
                try {
                    downloadFromAzure(fileName, localFile)
                } catch (e: Exception) {
                    android.util.Log.w("ModelDownloader", "Failed to download $fileName: ${e.message}")
                }
            }
        }
    }

    private fun downloadFromAzure(blobName: String, destFile: File) {
        val account = BuildConfig.AZURE_STORAGE_ACCOUNT
        val container = BuildConfig.AZURE_MODELS_CONTAINER
        val brokerUrl = BuildConfig.TOKEN_BROKER_BASE_URL
        val apiKey = BuildConfig.TOKEN_BROKER_API_KEY

        if (brokerUrl.isNotBlank()) {
            // Use token broker to get download URL
            val encodedName = URLEncoder.encode(blobName, "UTF-8")
            val endpoint = "${brokerUrl.trimEnd('/')}/api/storage/blob-sas?name=$encodedName&container=$container"
            val sasConn = URL(endpoint).openConnection() as HttpURLConnection
            sasConn.requestMethod = "GET"
            sasConn.connectTimeout = 15_000
            sasConn.readTimeout = 15_000
            sasConn.setRequestProperty("x-api-key", apiKey)

            val sasCode = sasConn.responseCode
            if (sasCode !in 200..299) {
                sasConn.disconnect()
                throw Exception("Broker returned $sasCode")
            }

            val sasBody = sasConn.inputStream.bufferedReader().use { it.readText() }
            sasConn.disconnect()

            val downloadUrl = org.json.JSONObject(sasBody).optString("downloadUrl", "")
            if (downloadUrl.isBlank()) throw Exception("No downloadUrl in response")

            downloadFile(downloadUrl, destFile)
        } else if (account.isNotBlank()) {
            // Direct download (no auth — public container or SAS in URL)
            val url = "https://$account.blob.core.windows.net/$container/$blobName"
            downloadFile(url, destFile)
        }
    }

    private fun downloadFile(url: String, destFile: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("x-ms-version", "2023-11-03")

        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw Exception("Download failed ($code)")
        }

        destFile.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()
        android.util.Log.i("ModelDownloader", "Downloaded: ${destFile.name} (${destFile.length() / 1024} KB)")
    }
}
