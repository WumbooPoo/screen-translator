package dev.crm.screentranslator.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * On-demand model downloads. No weights are ever bundled in the APK.
 *
 * Rules enforced here:
 * - The real size is fetched (HEAD Content-Length) BEFORE any download
 *   starts, and the UI shows it. Unknown size is reported as unknown —
 *   never guessed.
 * - Wi-Fi-only by default (user setting). Downloads refuse on mobile data
 *   unless the user turns the setting off.
 * - Everything lands under filesDir/models/<modelId>/ and can be deleted
 *   from the ModelManager screen. Deleting never touches app settings.
 * - Hayai is multi-file: its files are listed through the public Hugging
 *   Face tree API (no auth needed for public repos) and downloaded one by
 *   one into the same folder.
 */
class ModelDownloadManager(
    context: Context,
    private val wifiOnly: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder().build()

    /** Directory for one model's files. */
    fun modelDir(modelId: String): File =
        File(appContext.filesDir, "models/$modelId").apply { mkdirs() }

    fun isDownloaded(model: ModelRegistry.RemoteModel): Boolean {
        val dir = modelDir(model.id)
        return when {
            model.fileName != null -> File(dir, model.fileName).let { it.exists() && it.length() > 0 }
            model.id == ModelRegistry.HAYAI_LITERT.id ->
                dir.listFiles { f -> f.extension == "tflite" }.orEmpty().isNotEmpty()
            else -> true // Play-services models: managed by Play services
        }
    }

    /** Local file for a single-file model, or null when not downloaded. */
    fun localFile(model: ModelRegistry.RemoteModel): File? {
        if (model.fileName == null) return null
        val file = File(modelDir(model.id), model.fileName)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun delete(model: ModelRegistry.RemoteModel): Boolean {
        return modelDir(model.id).deleteRecursively()
    }

    /** True when the device is on Wi-Fi right now. */
    fun isOnWifi(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Fetches the real download size via HEAD. Returns null when the server
     * does not report a size — the UI must then say "unknown", not invent one.
     */
    suspend fun fetchSizeBytes(url: String): Long? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).head().build()
            http.newCall(request).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull()
                    ?.takeIf { it > 0 }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD failed for $url", e)
            null
        }
    }

    /**
     * Downloads [url] to [destFile], reporting progress 0f..1f.
     * @throws IllegalStateException when Wi-Fi-only is on and we are not on Wi-Fi.
     */
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (wifiOnly && !isOnWifi()) {
            return@withContext Result.failure(
                IllegalStateException("Wi-Fi only is ON — connect to Wi-Fi to download models."),
            )
        }
        try {
            destFile.parentFile?.mkdirs()
            val tmp = File(destFile.parentFile, destFile.name + ".part")
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Download failed: HTTP ${response.code}"),
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    IllegalStateException("Empty response body"),
                )
                val total = body.contentLength().takeIf { it > 0 }
                var written = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total != null) onProgress(written.toFloat() / total)
                        }
                    }
                }
                if (!tmp.renameTo(destFile)) {
                    tmp.copyTo(destFile, overwrite = true)
                    tmp.delete()
                }
            }
            onProgress(1f)
            Result.success(destFile)
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: $url", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the Hayai LiteRT export for [quant] (e.g. "wi4").
     * The file list comes from the public HF tree API, so exact filenames
     * never need to be hard-coded here.
     */
    suspend fun downloadHayaiQuant(
        quant: String,
        onProgress: (fileName: String, progress: Float) -> Unit,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        val apiUrl = "https://huggingface.co/api/models/" +
            "JustANormalTinkerer/hayai-ocr-v2-tflite/tree/main/litert_exports/$quant"
        try {
            val request = Request.Builder().url(apiUrl).build()
            val json = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HF tree API failed: HTTP ${response.code}"),
                    )
                }
                response.body?.string()
                    ?: return@withContext Result.failure(
                        IllegalStateException("Empty HF tree response"),
                    )
            }
            val files = JSONArray(json)
            val dir = modelDir(ModelRegistry.HAYAI_LITERT.id)
            val downloaded = mutableListOf<File>()
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                val path = entry.optString("path", "")
                val name = File(path).name
                if (!name.endsWith(".tflite")) continue
                val fileUrl = "https://huggingface.co/JustANormalTinkerer" +
                    "/hayai-ocr-v2-tflite/resolve/main/$path"
                val dest = File(dir, name)
                val result = download(fileUrl, dest) { p -> onProgress(name, p) }
                result.onFailure { return@withContext Result.failure(it) }
                result.onSuccess { downloaded.add(it) }
            }
            if (downloaded.isEmpty()) {
                Result.failure(IllegalStateException("No .tflite files found for quant '$quant'"))
            } else {
                Result.success(downloaded)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hayai download failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
    }
}
