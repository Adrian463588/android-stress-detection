package com.stressdetector.mobile.ui.home

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private const val PERMISSION_SHEET_MAX_HEIGHT_FRACTION = 0.9f

internal enum class PermissionCategory {
    LOCATION,
    MICROPHONE,
    BLUETOOTH,
    NEARBY_DEVICES,
    NOTIFICATIONS,
    STORAGE
}

internal data class PermissionChecklistEntry(
    val category: PermissionCategory,
    val title: String,
    val description: String,
    val status: String
)

internal data class PermissionOnboardingState(
    val entries: List<PermissionChecklistEntry>,
    val runtimePermissionsGranted: Boolean,
    val showSettingsRecovery: Boolean
)

internal fun watchRuntimePermissions(apiLevel: Int): List<String> = if (apiLevel >= Build.VERSION_CODES.S) {
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}

internal fun onboardingRuntimePermissions(apiLevel: Int): List<String> = buildList {
    addAll(watchRuntimePermissions(apiLevel))
    add(Manifest.permission.RECORD_AUDIO)
    if (apiLevel >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

internal fun shouldOfferPermissionSettingsRecovery(requestAttempted: Boolean, shouldShowRationale: Boolean, allGranted: Boolean): Boolean =
    requestAttempted && !shouldShowRationale && !allGranted

internal fun permissionChecklistEntries(
    apiLevel: Int,
    watchPermissionsGranted: Boolean,
    microphoneGranted: Boolean,
    notificationsEnabled: Boolean
): List<PermissionChecklistEntry> {
    val watchStatus = if (watchPermissionsGranted) "Diizinkan" else "Diperlukan"
    val locationStatus = when {
        apiLevel >= Build.VERSION_CODES.S -> "Tidak diperlukan"
        watchPermissionsGranted -> "Diizinkan"
        else -> "Diperlukan"
    }

    return listOf(
        PermissionChecklistEntry(
            PermissionCategory.LOCATION,
            "Lokasi",
            if (apiLevel >= Build.VERSION_CODES.S) {
                "Tidak digunakan untuk koneksi Watch pada Android ini."
            } else {
                "Diperlukan Android 11 ke bawah untuk menemukan Watch melalui Bluetooth."
            },
            locationStatus
        ),
        PermissionChecklistEntry(
            PermissionCategory.MICROPHONE,
            "Mikrofon",
            "Dipakai untuk rekaman Verbatim+ dan input suara AI+.",
            if (microphoneGranted) "Diizinkan" else "Belum diizinkan"
        ),
        PermissionChecklistEntry(
            PermissionCategory.BLUETOOTH,
            "Bluetooth",
            "Dipakai untuk komunikasi dengan Watch yang dipasangkan.",
            if (apiLevel >= Build.VERSION_CODES.S) watchStatus else "Mengikuti izin Lokasi"
        ),
        PermissionChecklistEntry(
            PermissionCategory.NEARBY_DEVICES,
            "Perangkat sekitar",
            if (apiLevel >= Build.VERSION_CODES.S) {
                "Android mengelompokkan akses Bluetooth Watch di izin ini."
            } else {
                "Pada Android 11 ke bawah, mengikuti izin Lokasi di atas."
            },
            if (apiLevel >= Build.VERSION_CODES.S) "Diatur bersama Bluetooth" else "Mengikuti izin Lokasi"
        ),
        PermissionChecklistEntry(
            PermissionCategory.NOTIFICATIONS,
            "Notifikasi",
            "Dipakai untuk pengingat terapi dan notifikasi sesi yang relevan.",
            when {
                notificationsEnabled -> "Aktif"
                apiLevel >= Build.VERSION_CODES.TIRAMISU -> "Belum diizinkan"
                else -> "Belum aktif"
            }
        ),
        PermissionChecklistEntry(
            PermissionCategory.STORAGE,
            "Penyimpanan",
            "Pilih dokumen melalui pemilih Android; aplikasi tidak meminta akses penyimpanan luas.",
            "Pilih saat dibutuhkan"
        )
    )
}

@Composable
internal fun PermissionGateCard(onOpenPermissionSheet: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = "Izin koneksi Watch diperlukan" },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sambungkan Watch", style = MaterialTheme.typography.titleMedium)
            Text(
                "Izinkan akses koneksi yang diperlukan agar aplikasi dapat memeriksa Watch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onOpenPermissionSheet,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Izinkan aplikasi")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionOnboardingSheet(
    state: PermissionOnboardingState,
    onRequestAppPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(PERMISSION_SHEET_MAX_HEIGHT_FRACTION),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        PermissionOnboardingContent(state, onRequestAppPermissions, onOpenSettings, onDismiss)
    }
}

@Composable
private fun PermissionOnboardingContent(
    state: PermissionOnboardingState,
    onRequestAppPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PermissionSheetHeader()
        state.entries.forEach { entry -> PermissionChecklistRow(entry) }
        PermissionSheetActions(state, onRequestAppPermissions, onOpenSettings, onDismiss)
    }
}

@Composable
private fun PermissionSheetHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Izin aplikasi", style = MaterialTheme.typography.titleLarge)
        Text(
            "Setelah Anda melanjutkan, Android meminta akses runtime yang berlaku satu per satu atau dalam kelompok.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionSheetActions(
    state: PermissionOnboardingState,
    onRequestAppPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state.runtimePermissionsGranted) {
        Text(
            "Tidak ada izin runtime lain yang perlu diminta.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Tutup")
        }
        return
    }

    Button(onClick = onRequestAppPermissions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Izinkan akses yang diperlukan")
    }
    if (state.showSettingsRecovery) {
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Buka pengaturan aplikasi")
        }
    }
}

@Composable
private fun PermissionChecklistRow(entry: PermissionChecklistEntry) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(entry.title, style = MaterialTheme.typography.titleSmall)
        Text(
            entry.status,
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.status == "Diizinkan" || entry.status == "Aktif") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            entry.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
