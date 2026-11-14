package dev.crm.screentranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.sp
import dev.crm.screentranslator.data.Engine
import dev.crm.screentranslator.data.Languages

/**
 * Home screen — deliberately only 4 things (simple + smooth, per the brief):
 * a big ON button as the hero, the engine row, the language pair + swap,
 * and the floating-bubble card. No ads, no settings wall.
 */
@Composable
fun HomeScreen(
    isRunning: Boolean,
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    engine: Engine,
    sourceLang: String,
    targetLang: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPickEngine: () -> Unit,
    onSourceLangChange: (String) -> Unit,
    onTargetLangChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Screen Translator",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Terjemahkan apa pun di layar — offline, tanpa iklan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        // Big ON button (hero).
        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            shape = CircleShape,
            modifier = Modifier.size(160.dp),
        ) {
            Text(
                text = if (isRunning) "ON" else "OFF",
                fontSize = 32.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRunning) {
                "Aktif — ketuk gelembung untuk menerjemahkan."
            } else {
                "Ketuk untuk menyalakan."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        // Engine row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mesin", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onPickEngine) {
                Text(engineLabel(engine))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Language pair + swap.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanguageDropdown(
                label = if (sourceLang == "auto") "Otomatis" else langName(sourceLang),
                onSelect = onSourceLangChange,
                includeAuto = true,
            )
            TextButton(onClick = {
                if (sourceLang != "auto") {
                    onSourceLangChange(targetLang)
                    onTargetLangChange(sourceLang)
                }
            }) {
                Text("⇄", fontSize = 24.sp)
            }
            LanguageDropdown(
                label = langName(targetLang),
                onSelect = onTargetLangChange,
                includeAuto = false,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Floating-bubble card.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Gelembung mengambang", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tombol kecil yang nempel di tepi layar. Ketuk untuk " +
                        "menerjemahkan, geser untuk memindah.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Permission hints (only when missing).
        if (!hasOverlayPermission) {
            Text(
                "Izin tampil di atas aplikasi lain belum diberikan — " +
                    "ketuk tombol ON dan ikuti petunjuknya.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (!hasNotificationPermission) {
            Text(
                "Izin notifikasi belum diberikan — dibutuhkan untuk " +
                    "layanan perekaman layar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onOpenSettings) { Text("Pengaturan") }
            TextButton(onClick = onOpenModels) { Text("Kelola Model") }
        }
    }
}

private fun engineLabel(engine: Engine): String = when (engine) {
    Engine.OFFLINE -> "Offline (ML Kit)"
    Engine.ONLINE -> "Online (LibreTranslate)"
    Engine.GOOGLE -> "Google Cloud (nanti)"
}

private fun langName(code: String): String =
    Languages.byCode(code)?.displayName ?: code

@Composable
private fun LanguageDropdown(
    label: String,
    onSelect: (String) -> Unit,
    includeAuto: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) {
        Text(label)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (includeAuto) {
            DropdownMenuItem(
                text = { Text("Otomatis") },
                onClick = { expanded = false; onSelect("auto") },
            )
        }
        for (lang in Languages.SUPPORTED) {
            DropdownMenuItem(
                text = { Text(lang.displayName) },
                onClick = { expanded = false; onSelect(lang.code) },
            )
        }
    }
}
