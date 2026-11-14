package dev.crm.screentranslator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.crm.screentranslator.data.Engine

/**
 * Engine picker bottom sheet.
 *
 * V1 offers two real engines. Google Cloud needs an API key (TODO) and
 * AI engines (Gemini / ChatGPT) come later — both are shown disabled so
 * the user sees the roadmap, with no premium gating anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginePickerSheet(
    current: Engine,
    onSelect: (Engine) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Pilih Mesin", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            EngineRow(
                title = "Offline (ML Kit)",
                desc = "Di perangkat. Gratis, tanpa internet setelah model diunduh.",
                selected = current == Engine.OFFLINE,
                enabled = true,
                onClick = { onSelect(Engine.OFFLINE) },
            )
            EngineRow(
                title = "Online (LibreTranslate)",
                desc = "Gratis, butuh internet, instance bisa diganti di Pengaturan.",
                selected = current == Engine.ONLINE,
                enabled = true,
                onClick = { onSelect(Engine.ONLINE) },
            )
            EngineRow(
                title = "Google Cloud",
                desc = "Butuh API key. Belum tersedia di V1.",
                selected = false,
                enabled = false,
                onClick = {},
            )
            EngineRow(
                title = "AI (Gemini / ChatGPT)",
                desc = "Hadir nanti — tanpa premium.",
                selected = false,
                enabled = false,
                onClick = {},
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EngineRow(
    title: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                } else {
                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                desc,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (enabled) {
            TextButton(onClick = onClick) {
                Text(if (selected) "Dipakai" else "Pilih")
            }
        } else {
            TextButton(onClick = {}, enabled = false) { Text("Nanti") }
        }
    }
    Spacer(Modifier.height(12.dp))
}
