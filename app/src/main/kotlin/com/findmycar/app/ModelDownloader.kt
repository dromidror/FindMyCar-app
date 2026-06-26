package com.findmycar.app

import android.content.Context
import java.io.File

/**
 * Copies bundled ML model files from assets to internal storage if not present.
 * Models are shipped with the APK — no network download needed.
 */
object ModelDownloader {

    private const val MODEL_FILE = "car_state_model.tflite"
    private const val CONFIG_FILE = "model_config.json"
    private const val RONIN_FILE = "ronin_model.tflite"
    private const val RONIN_CONFIG_FILE = "ronin_config.json"

    private val ALL_FILES = listOf(MODEL_FILE, CONFIG_FILE, RONIN_FILE, RONIN_CONFIG_FILE)

    /**
     * Ensure all model files exist in internal storage.
     * Copies from assets on first run.
     * Safe to call from any thread.
     */
    fun ensureModelsDownloaded(context: Context) {
        val filesDir = context.filesDir

        for (fileName in ALL_FILES) {
            val localFile = File(filesDir, fileName)
            if (!localFile.exists()) {
                try {
                    copyFromAssets(context, fileName, localFile)
                } catch (e: Exception) {
                    android.util.Log.w("ModelDownloader", "Failed to copy $fileName from assets: ${e.message}")
                }
            }
        }
    }

    private fun copyFromAssets(context: Context, assetName: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        android.util.Log.i("ModelDownloader", "Copied from assets: ${destFile.name} (${destFile.length() / 1024} KB)")
    }
}
