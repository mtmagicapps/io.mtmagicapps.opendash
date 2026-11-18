package io.mtmagicapps.opendash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.mtmagicapps.opendash.ui.screens.AlbumScreen
import io.mtmagicapps.opendash.ui.screens.ConnectionScreen
import io.mtmagicapps.opendash.ui.screens.SettingsScreen
import io.mtmagicapps.opendash.ui.theme.OpenDashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenDashTheme {
                OpenDashApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenDashApp(vm: DashcamViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearSnackbar()
    }

    val connectedDevice = (state.connectionState as? ConnectionState.Connected)?.device

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("OpenDash", style = MaterialTheme.typography.titleLarge)
                        if (connectedDevice != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = connectedDevice.socType.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (state.showLocalGallery && connectedDevice == null) {
                        IconButton(onClick = vm::closeLocalGallery) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (state.connectionState !is ConnectionState.Searching) {
                        IconButton(onClick = vm::scanForDashcam) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Rescan dashcam")
                        }
                    }
                    IconButton(onClick = { vm.togglePrivacyDialog(true) }) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = "Privacy & Protocol Info",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (connectedDevice != null) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        val icon = when (tab) {
                            AppTab.ALBUM -> Icons.Outlined.PhotoLibrary
                            AppTab.SETTINGS -> Icons.Outlined.Settings
                        }
                        NavigationBarItem(
                            selected = state.activeTab == tab,
                            onClick = { vm.selectTab(tab) },
                            icon = { Icon(icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val conn = state.connectionState) {
                ConnectionState.Searching -> {
                    ConnectionScreen(
                        isSearching = true,
                        onRetry = vm::scanForDashcam,
                        onStartDemo = vm::startDemoMode
                    )
                }
                ConnectionState.Disconnected -> {
                    if (state.showLocalGallery) {
                        AlbumScreen(
                            files = state.localFiles,
                            selectedCategory = state.selectedCategory,
                            isLoading = false,
                            wifiNetwork = null,
                            downloadProgress = emptyMap(),
                            downloadedIds = state.localFiles.map { it.id }.toSet(),
                            selectedFile = state.selectedFile,
                            gpsTrack = state.gpsTrack,
                            onSelectCategory = vm::selectCategory,
                            onRefresh = vm::loadLocalSavedFiles,
                            onOpenFile = vm::openFileViewer,
                            onCloseViewer = vm::closeFileViewer,
                            onDownloadFile = {},
                            onOpenSavedFile = vm::openSavedFile,
                            onDeleteFile = {}
                        )
                    } else {
                        ConnectionScreen(
                            isSearching = false,
                            onRetry = vm::scanForDashcam,
                            onStartDemo = vm::startDemoMode,
                            onOpenLocalGallery = vm::openLocalGallery
                        )
                    }
                }
                is ConnectionState.Connected -> {
                    when (state.activeTab) {
                        AppTab.ALBUM -> AlbumScreen(
                            files = state.files,
                            selectedCategory = state.selectedCategory,
                            isLoading = state.isLoadingFiles,
                            wifiNetwork = vm.wifiNetwork,
                            downloadProgress = state.downloadProgress,
                            downloadedIds = state.downloadedIds,
                            selectedFile = state.selectedFile,
                            gpsTrack = state.gpsTrack,
                            onSelectCategory = vm::selectCategory,
                            onRefresh = vm::loadFiles,
                            onOpenFile = vm::openFileViewer,
                            onCloseViewer = vm::closeFileViewer,
                            onDownloadFile = vm::downloadFile,
                            onOpenSavedFile = vm::openSavedFile,
                            onDeleteFile = vm::deleteFile
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            device = conn.device,
                            settings = state.settings,
                            isLoading = state.isLoadingSettings,
                            onUpdateSetting = vm::updateSetting,
                            onSyncTime = vm::syncTimeNow,
                            onUpdateWifi = vm::updateWifiCredentials,
                            onFormatSd = vm::formatSdCard,
                            onFactoryReset = vm::factoryReset,
                            onDisconnect = vm::disconnectSession
                        )
                    }
                }
            }
        }
    }

    if (state.showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { vm.togglePrivacyDialog(false) },
            title = { Text("OpenDash Privacy & Protocols") },
            text = {
                Text(
                    "OpenDash communicates exclusively with your dashcam over local Wi-Fi. " +
                        "It contains zero analytics, zero ad SDKs, zero cloud accounts, and zero third-party native binaries.\n\n" +
                        "Supported SoC protocols: Novatek, MStar/SigmaStar, Hisilicon, Jieli, Nello, Allwinner, Generalplus, iCatchTek, Huiying, Eeasy, and TUTK/W18."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.togglePrivacyDialog(false) }) {
                    Text("Close")
                }
            }
        )
    }
}
