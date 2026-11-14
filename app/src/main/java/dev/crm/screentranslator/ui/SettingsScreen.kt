package dev.crm.screentranslator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.crm.screentranslator.data.SettingsStore

/**
 * Settings screen — 3 groups, nothing else:
 * 1. Tampilan terjemahan (opacity).
 * 2. Ikon mengambang (size, snap-to-edge).
 * 3. Lainnya (manga mode, SFX toggle, Wi-Fi-only downloads, LibreTranslate URL).
 *
 * Font / icon colour / accessibility options live below the fold in the
 * full app; V1 keeps the three groups above the fold.
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onOpenModels: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var opacity by remember { mutableStateOf(settings.overlayOpacity) }
    var bubbleSize by remember { mutableStateOf(settings.bubbleSizeDp) }
    var snapToEdge by remember { mutableStateOf(settings.snapToEdge) }
    var mangaMode by remember { mutableStateOf(settings.mangaMode) }
    var sfxEnabled by remember { mutableStateOf(settings.sfxEnabled) }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnlyDownloads) }
    var libreUrl by remember { mutableStateOf(settings.libreTranslateUrl) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Kembali") }
            Text("Pengaturan", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(16.dp))
        Text("Tampilan terjemahan", style = MaterialTheme.typography.titleMedium)
        Text("Transparansi latar: $opacity%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = opacity.toFloat(),
            onValueChange = { opacity = it.toInt() },
            onValueChangeFinished = { settings.overlayOpacity = opacity },
            valueRange = 10f..100f,
        )

        Spacer(Modifier.height(16.dp))
        Text("Ikon mengambang", style = MaterialTheme.typography.titleMedium)
        Text("Ukuran gelembung: ${bubbleSize}dp", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = bubbleSize.toFloat(),
            onValueChange = { bubbleSize = it.toInt() },
            onValueChangeFinished = { settings.bubbleSizeDp = bubbleSize },
            valueRange = 32f..72f,
        )
        SettingSwitch(
            label = "Tempel otomatis ke tepi layar",
            checked = snapToEdge,
            onChange = { snapToEdge = it; settings.snapToEdge = it },
        )

        Spacer(Modifier.height(16.dp))
        Text("Lainnya", style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            label = "Mode manga",
            desc = "Deteksi balon, urutan baca kanan-ke-kiri, dan OCR manga.",
            checked = mangaMode,
            onChange = { mangaMode = it; settings.mangaMode = it },
        )
        SettingSwitch(
            label = "Terjemahkan SFX",
            desc = "Suara efek (mis. ドキドキ). Mati secara default.",
            checked = sfxEnabled,
            onChange = { sfxEnabled = it; settings.sfxEnabled = it },
        )
        SettingSwitch(
            label = "Unduh model hanya via Wi-Fi",
            checked = wifiOnly,
            onChange = { wifiOnly = it; settings.wifiOnlyDownloads = it },
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = libreUrl,
            onValueChange = { libreUrl = it },
            label = { Text("URL instance LibreTranslate") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TextButton(onClick = { settings.libreTranslateUrl = libreUrl }) {
            Text("Simpan URL")
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenModels) { Text("Kelola Model →") }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    desc: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
    Spacer(Modifier.height(8.dp))
}
