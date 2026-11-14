package dev.crm.screentranslator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.crm.screentranslator.models.ModelDownloadManager
import dev.crm.screentranslator.models.ModelRegistry
import kotlinx.coroutines.launch

/**
 * Model manager: download / delete per model, with sizes shown BEFORE
 * downloading. Nothing here is bundled in the APK.
 *
 * - ML Kit models: managed by Play services (no buttons; they download
 *   automatically on first use).
 * - YOLO bubble: single file, size fetched via HEAD first.
 * - Hayai LiteRT: multi-file (encoder + prefill + decode), fetched via
 *   the Hugging Face tree API. Marked BLOCKED until its decode loop is
 *   implemented — the download works, the engine does not run yet.
 */
@Composable
fun ModelManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ModelDownloadManager(context) }

    // Per-model UI state, keyed by model id.
    val downloaded = remember { mutableStateMapOf<String, Boolean>() }
    val downloading = remember { mutableStateMapOf<String, Boolean>() }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val sizeText = remember { mutableStateMapOf<String, String>() }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshDownloaded() {
        for (model in ModelRegistry.ALL) {
            downloaded[model.id] = manager.isDownloaded(model)
        }
    }

    LaunchedEffect(Unit) {
        refreshDownloaded()
        // Fetch the real size for the YOLO model before offering download.
        val yolo = ModelRegistry.YOLO_BUBBLE
        yolo.downloadUrl?.let { url ->
            val bytes = manager.fetchSizeBytes(url)
            sizeText[yolo.id] = bytes?.let { "Ukuran: ${formatBytes(it)}" }
                ?: "Ukuran: tidak diketahui (dicek saat unduh)"
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row {
            TextButton(onClick = onBack) { Text("← Kembali") }
            Text("Model", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "Model diunduh sesuai kebutuhan, tidak dibundel di APK. " +
                "Default hanya via Wi-Fi (ubah di Pengaturan).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn {
            items(ModelRegistry.ALL) { model ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(model.description, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Lisensi: ${model.licence}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sizeText[model.id]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        model.sizeBytes?.let {
                            if (sizeText[model.id] == null) {
                                Text(
                                    "Ukuran: ~${formatBytes(it)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (model.kind == ModelRegistry.Kind.BLOCKED_TODO) {
                            Text(
                                "Status: unduhan tersedia, tapi mesin OCR-nya " +
                                    "belum jalan (decode loop masih TODO).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (downloading[model.id] == true) {
                            LinearProgressIndicator(
                                progress = { progress[model.id] ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (model.kind == ModelRegistry.Kind.WORKING) {
                            Text(
                                "Dikelola Play services — terunduh otomatis saat dipakai.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Row {
                                if (downloaded[model.id] == true) {
                                    OutlinedButton(onClick = {
                                        manager.delete(model)
                                        refreshDownloaded()
                                    }) { Text("Hapus") }
                                } else {
                                    Button(onClick = {
                                        error = null
                                        downloading[model.id] = true
                                        scope.launch {
                                            when (model.id) {
                                                ModelRegistry.YOLO_BUBBLE.id -> {
                                                    val url = model.downloadUrl!!
                                                    val dest = java.io.File(
                                                        manager.modelDir(model.id),
                                                        model.fileName!!,
                                                    )
                                                    manager.download(url, dest) { p ->
                                                        progress[model.id] = p
                                                    }.onFailure { error = it.message }
                                                }
                                                ModelRegistry.HAYAI_LITERT.id -> {
                                                    manager.downloadHayaiQuant("wi4") { _, p ->
                                                        progress[model.id] = p
                                                    }.onFailure { error = it.message }
                                                }
                                            }
                                            downloading[model.id] = false
                                            refreshDownloaded()
                                        }
                                    }) { Text("Unduh") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
