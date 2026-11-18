package io.mtmagicapps.opendash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import io.mtmagicapps.opendash.protocol.CameraSettingItem
import io.mtmagicapps.opendash.protocol.DashcamDevice
import io.mtmagicapps.opendash.protocol.SettingSection
import io.mtmagicapps.opendash.protocol.SettingType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    device: DashcamDevice,
    settings: List<CameraSettingItem>,
    isLoading: Boolean,
    onUpdateSetting: (key: String, value: String) -> Unit,
    onSyncTime: () -> Unit,
    onUpdateWifi: (ssid: String, password: String) -> Unit,
    onFormatSd: () -> Unit,
    onFactoryReset: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeEnumSetting by remember { mutableStateOf<CameraSettingItem?>(null) }
    var showWifiDialog by remember { mutableStateOf(false) }
    var confirmFormat by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        SettingSection.entries.forEach { section ->
            val sectionItems = settings.filter { it.section == section }
            if (sectionItems.isNotEmpty()) {
                SectionHeader(section.title)
                sectionItems.forEach { item ->
                    val currentLabel = item.options.firstOrNull { it.value == item.currentValue }?.label
                        ?: item.currentValue
                    val isToggle = item.type == SettingType.TOGGLE && item.options.size == 2
                    if (isToggle) {
                        val onOpt = item.options.last()
                        val offOpt = item.options.first()
                        val checked = item.currentValue == onOpt.value
                        ListItem(
                            headlineContent = { Text(item.title) },
                            trailingContent = {
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { isOn ->
                                        onUpdateSetting(item.key, if (isOn) onOpt.value else offOpt.value)
                                    }
                                )
                            }
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text(item.title) },
                            supportingContent = { Text(currentLabel) },
                            trailingContent = {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                            },
                            modifier = Modifier.clickable { activeEnumSetting = item }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        SectionHeader("Device & Wi-Fi")

        ListItem(
            leadingContent = { Icon(Icons.Outlined.AccessTime, contentDescription = null) },
            headlineContent = { Text("Sync Dashcam Clock") },
            supportingContent = { Text("Set dashcam date & timezone to match phone") },
            modifier = Modifier.clickable(onClick = onSyncTime)
        )

        ListItem(
            leadingContent = { Icon(Icons.Outlined.Wifi, contentDescription = null) },
            headlineContent = { Text("Wi-Fi Hotspot Credentials") },
            supportingContent = { Text(device.ssid.ifBlank { "Configure SSID & password" }) },
            modifier = Modifier.clickable { showWifiDialog = true }
        )

        ListItem(
            headlineContent = { Text("Hardware & Protocol") },
            supportingContent = {
                Text("${device.socType.displayName} · ${device.ip}:${device.httpPort}" +
                    if (device.firmwareVersion.isNotBlank()) " · ${device.firmwareVersion}" else "")
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        SectionHeader("Storage & Maintenance")

        ListItem(
            leadingContent = {
                Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            headlineContent = { Text("Format SD Card", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("Erases all normal, locked, and photo files") },
            modifier = Modifier.clickable { confirmFormat = true }
        )

        ListItem(
            leadingContent = {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            headlineContent = { Text("Factory Reset Dashcam", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("Restores all camera settings to defaults") },
            modifier = Modifier.clickable { confirmReset = true }
        )

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick = onDisconnect,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 24.dp)
        ) {
            Text("Disconnect Session")
        }
    }

    if (activeEnumSetting != null) {
        val item = activeEnumSetting!!
        ModalBottomSheet(onDismissRequest = { activeEnumSetting = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
                item.options.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUpdateSetting(item.key, opt.value)
                                activeEnumSetting = null
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = opt.value == item.currentValue,
                            onClick = null
                        )
                        Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    if (showWifiDialog) {
        var ssid by remember { mutableStateOf(device.ssid) }
        var pwd by remember { mutableStateOf("12345678") }
        AlertDialog(
            onDismissRequest = { showWifiDialog = false },
            title = { Text("Dashcam Wi-Fi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("Network Name (SSID)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        label = { Text("Password (min 8 chars)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWifiDialog = false
                        onUpdateWifi(ssid.trim(), pwd.trim())
                    },
                    enabled = ssid.isNotBlank() && pwd.length >= 8
                ) {
                    Text("Save & Reboot Wi-Fi")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWifiDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmFormat) {
        AlertDialog(
            onDismissRequest = { confirmFormat = false },
            title = { Text("Format SD card?") },
            text = { Text("Every video and photo on the dashcam SD card will be permanently erased.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmFormat = false
                        onFormatSd()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Format") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFormat = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Factory reset dashcam?") },
            text = { Text("All dashcam preferences and Wi-Fi settings will revert to factory defaults.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onFactoryReset()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
