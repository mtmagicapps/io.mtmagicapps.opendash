package io.mtmagicapps.opendash

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mtmagicapps.opendash.protocol.CameraSettingItem
import io.mtmagicapps.opendash.protocol.DashcamDetector
import io.mtmagicapps.opendash.protocol.DashcamDevice
import io.mtmagicapps.opendash.protocol.DashcamDriver
import io.mtmagicapps.opendash.protocol.GpsTrackParser
import io.mtmagicapps.opendash.protocol.GpsTrackPoint
import io.mtmagicapps.opendash.protocol.MediaCategory
import io.mtmagicapps.opendash.protocol.RemoteMediaFile
import io.mtmagicapps.opendash.protocol.SocType
import io.mtmagicapps.opendash.protocol.TsToMp4Remuxer
import io.mtmagicapps.opendash.protocol.WifiNetworkHttp
import io.mtmagicapps.opendash.protocol.drivers.DemoDashcamDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

enum class AppTab(val label: String) {
    ALBUM("Album"),
    SETTINGS("Settings")
}

sealed interface ConnectionState {
    data object Searching : ConnectionState
    data class Connected(val device: DashcamDevice) : ConnectionState
    data object Disconnected : ConnectionState
}

data class DashcamUiState(
    val connectionState: ConnectionState = ConnectionState.Searching,
    val activeTab: AppTab = AppTab.ALBUM,
    val files: List<RemoteMediaFile> = emptyList(),
    val selectedCategory: MediaCategory = MediaCategory.ALL,
    val isLoadingFiles: Boolean = false,
    val downloadProgress: Map<String, Float> = emptyMap(),
    val downloadedIds: Set<String> = emptySet(),
    val downloadedUris: Map<String, Uri> = emptyMap(),
    val settings: List<CameraSettingItem> = emptyList(),
    val isLoadingSettings: Boolean = false,
    val selectedFile: RemoteMediaFile? = null,
    val gpsTrack: List<GpsTrackPoint> = emptyList(),
    val snackbarMessage: String? = null,
    val showPrivacyDialog: Boolean = false,
    val showLocalGallery: Boolean = false,
    val localFiles: List<RemoteMediaFile> = emptyList()
)

class DashcamViewModel(app: Application) : AndroidViewModel(app) {
    private val detector = DashcamDetector(app)
    private var activeDriver: DashcamDriver? = null
    private var activeClient: WifiNetworkHttp = WifiNetworkHttp()
    private var statusPollJob: Job? = null
    private var detectJob: Job? = null

    private val _uiState = MutableStateFlow(DashcamUiState())
    val uiState: StateFlow<DashcamUiState> = _uiState.asStateFlow()

    val wifiNetwork: Network?
        get() = activeClient.wifiNetwork

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (_uiState.value.connectionState is ConnectionState.Disconnected) {
                scanForDashcam()
            }
        }
    }

    init {
        getApplication<Application>().cacheDir.listFiles { _, name -> name.startsWith("dl_") }?.forEach { it.delete() }
        registerWifiCallback()
        scanForDashcam()
    }

    private fun registerWifiCallback() {
        try {
            val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        statusPollJob?.cancel()
        detectJob?.cancel()
    }

    fun scanForDashcam() {
        detectJob?.cancel()
        statusPollJob?.cancel()
        _uiState.update { it.copy(connectionState = ConnectionState.Searching) }
        detectJob = viewModelScope.launch {
            val result = detector.detect()
            if (result != null) {
                activeDriver = result.driver
                activeClient = result.client
                val connectedDev = result.driver.connect(result.device, result.client)
                _uiState.update { it.copy(connectionState = ConnectionState.Connected(connectedDev)) }
                startStatusPolling()
                loadFiles()
                loadSettings()
            } else {
                _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
            }
        }
    }

    fun startDemoMode() {
        detectJob?.cancel()
        statusPollJob?.cancel()
        viewModelScope.launch {
            val demo = DemoDashcamDriver()
            val client = WifiNetworkHttp(null)
            val dev = demo.probe("127.0.0.1", client)
            activeDriver = demo
            activeClient = client
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Connected(dev),
                    snackbarMessage = "Demo mode active — simulated 4K dual-channel dashcam"
                )
            }
            loadFiles()
            loadSettings()
        }
    }

    fun disconnectSession() {
        statusPollJob?.cancel()
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device
        val driver = activeDriver
        viewModelScope.launch {
            if (dev != null && driver != null) {
                driver.disconnect(dev, activeClient)
            }
            activeDriver = null
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Disconnected,
                    files = emptyList(),
                    settings = emptyList()
                )
            }
        }
    }

    private fun startStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = viewModelScope.launch {
            while (isActive) {
                delay(4000L)
                if (_uiState.value.downloadProgress.isNotEmpty()) continue
                val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: break
                val driver = activeDriver ?: break
                if (driver.socType == SocType.DEMO) continue
                val updated = driver.refreshStatus(dev, activeClient)
                _uiState.update { state ->
                    if (state.connectionState is ConnectionState.Connected) {
                        state.copy(connectionState = ConnectionState.Connected(updated))
                    } else state
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(activeTab = tab) }
        val state = _uiState.value
        if (state.connectionState is ConnectionState.Connected) {
            if (tab == AppTab.ALBUM && state.files.isEmpty()) loadFiles()
            if (tab == AppTab.SETTINGS && state.settings.isEmpty()) loadSettings()
        }
    }

    fun selectCategory(category: MediaCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun loadFiles() {
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFiles = true) }
            val list = driver.listFiles(dev, activeClient)
            _uiState.update { it.copy(files = list, isLoadingFiles = false) }
        }
    }

    fun openFileViewer(file: RemoteMediaFile) {
        _uiState.update { it.copy(selectedFile = file, gpsTrack = emptyList()) }
        if (file.gpsPath != null) {
            viewModelScope.launch {
                val points = if (file.gpsPath == "DEMO_GPS") {
                    demoGpsTrack()
                } else {
                    val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return@launch
                    val path = if (file.gpsPath.startsWith("/")) file.gpsPath else "/${file.gpsPath}"
                    val raw = activeClient.get("http://${dev.ip}:${dev.httpPort}$path", 3000)?.body.orEmpty()
                    GpsTrackParser.parse(raw)
                }
                _uiState.update { it.copy(gpsTrack = points) }
            }
        }
    }

    fun closeFileViewer() {
        _uiState.update { it.copy(selectedFile = null, gpsTrack = emptyList()) }
    }

    fun downloadFile(file: RemoteMediaFile) {
        if (file.id in _uiState.value.downloadProgress) return
        if (file.downloadUrl.isBlank()) {
            _uiState.update {
                it.copy(
                    downloadedIds = it.downloadedIds + file.id,
                    snackbarMessage = "Demo clip marked as saved"
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(downloadProgress = it.downloadProgress + (file.id to 0f)) }
            val app = getApplication<Application>()
            val wakeLock = runCatching {
                app.getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenDash:Download")
                    ?.apply { acquire(30 * 60 * 1000L) }
            }.getOrNull()
            @Suppress("DEPRECATION")
            val wifiLock = runCatching {
                app.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenDash:Download")
                    ?.apply { acquire() }
            }.getOrNull()

            var savedUri: Uri? = null
            try {
                var lastUiUpdateMs = 0L
                savedUri = saveToMediaStore(file) { pct ->
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMs >= 200L || pct >= 0.99f) {
                        lastUiUpdateMs = now
                        _uiState.update { it.copy(downloadProgress = it.downloadProgress + (file.id to pct)) }
                    }
                }
            } finally {
                runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
            _uiState.update { state ->
                val savedName = savedFileName(file)
                state.copy(
                    downloadProgress = state.downloadProgress - file.id,
                    downloadedIds = if (savedUri != null) state.downloadedIds + file.id else state.downloadedIds,
                    downloadedUris = if (savedUri != null) state.downloadedUris + (file.id to savedUri) else state.downloadedUris,
                    snackbarMessage = if (savedUri != null) "Saved $savedName to ${if (file.isPhoto) "Pictures" else "Movies"}/OpenDash" else "Download failed"
                )
            }
        }
    }

    private fun savedFileName(file: RemoteMediaFile): String {
        return if (!file.isPhoto && file.name.lowercase().endsWith(".ts")) {
            file.name.dropLast(3) + ".mp4"
        } else {
            file.name
        }
    }

    fun openSavedFile(file: RemoteMediaFile) {
        val context = getApplication<Application>()
        val uri = _uiState.value.downloadedUris[file.id]
            ?: file.downloadUrl.takeIf { it.startsWith("content://") }?.let { Uri.parse(it) }
            ?: findMediaStoreUri(file)
        if (uri == null) {
            showMessage("Saved file not found on device")
            return
        }
        val targetName = if (file.downloadUrl.startsWith("content://")) file.name else savedFileName(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeFor(targetName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { showMessage("No app available to open $targetName") }
    }

    private fun findMediaStoreUri(file: RemoteMediaFile): Uri? {
        val context = getApplication<Application>()
        val collection = if (file.isPhoto) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val candidates = listOf(savedFileName(file), file.name).distinct()
        for (name in candidates) {
            val found = runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(name),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        ContentUris.withAppendedId(collection, id)
                    } else null
                }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun mimeTypeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".ts") -> "video/mp2ts"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    private suspend fun saveToMediaStore(file: RemoteMediaFile, onProgress: (Float) -> Unit): Uri? {
        val context = getApplication<Application>()
        val needsRemux = !file.isPhoto && file.name.lowercase().endsWith(".ts")
        if (!needsRemux) {
            return writeToMediaStore(file.name, file.isPhoto) { out ->
                activeClient.downloadToStream(file.downloadUrl, out) { done, total ->
                    val effectiveTotal = if (total > 0L) total else file.sizeBytes
                    if (effectiveTotal > 0L) onProgress((done.toFloat() / effectiveTotal.toFloat()).coerceIn(0f, 1f))
                }
            }
        }

        val stamp = System.currentTimeMillis()
        val tempTs = File(context.cacheDir, "dl_${stamp}_${file.name}")
        val mp4Name = savedFileName(file)
        val tempMp4 = File(context.cacheDir, "dl_${stamp}_${mp4Name}")
        return try {
            val downloaded = withContext(Dispatchers.IO) {
                FileOutputStream(tempTs).use { out ->
                    activeClient.downloadToStream(file.downloadUrl, out) { done, total ->
                        val effectiveTotal = if (total > 0L) total else file.sizeBytes
                        if (effectiveTotal > 0L) onProgress((done.toFloat() / effectiveTotal.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            if (!downloaded) return null

            val remuxed = withContext(Dispatchers.IO) { TsToMp4Remuxer.remux(tempTs, tempMp4) }
            val sourceFile = if (remuxed) tempMp4 else tempTs
            val finalName = if (remuxed) mp4Name else file.name

            writeToMediaStore(finalName, isPhoto = false) { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
                true
            }
        } finally {
            tempTs.delete()
            tempMp4.delete()
        }
    }

    private suspend fun writeToMediaStore(
        displayName: String,
        isPhoto: Boolean,
        writer: suspend (java.io.OutputStream) -> Boolean
    ): Uri? {
        val context = getApplication<Application>()
        val mime = mimeTypeFor(displayName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isPhoto) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relPath = if (isPhoto) "${Environment.DIRECTORY_PICTURES}/OpenDash" else "${Environment.DIRECTORY_MOVIES}/OpenDash"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                writer(out)
            } ?: false
            if (!ok) {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (isPhoto) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
                ),
                "OpenDash"
            )
            dir.mkdirs()
            val dest = File(dir, displayName)
            val ok = FileOutputStream(dest).use { out -> writer(out) }
            if (ok) Uri.fromFile(dest) else null
        }
    }

    fun deleteFile(file: RemoteMediaFile) {
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            if (driver.deleteFile(dev, activeClient, file)) {
                _uiState.update { state ->
                    state.copy(
                        files = state.files.filterNot { it.id == file.id },
                        selectedFile = if (state.selectedFile?.id == file.id) null else state.selectedFile,
                        snackbarMessage = "Deleted ${file.name}"
                    )
                }
            } else {
                showMessage("Failed to delete ${file.name}")
            }
        }
    }

    fun loadSettings() {
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSettings = true) }
            val items = driver.loadSettings(dev, activeClient)
            _uiState.update { it.copy(settings = items, isLoadingSettings = false) }
        }
    }

    fun updateSetting(key: String, value: String) {
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            if (driver.updateSetting(dev, activeClient, key, value)) {
                _uiState.update { state ->
                    state.copy(
                        settings = state.settings.map { if (it.key == key) it.copy(currentValue = value) else it },
                        snackbarMessage = "Setting updated"
                    )
                }
            } else {
                showMessage("Camera rejected setting change")
            }
        }
    }

    fun syncTimeNow() {
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            val ok = driver.syncDateTime(dev, activeClient)
            showMessage(if (ok) "Dashcam clock synced to phone" else "Clock sync not supported")
        }
    }

    fun updateWifiCredentials(ssid: String, password: String) {
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            val ok = driver.setWifiCredentials(dev, activeClient, ssid, password)
            showMessage(if (ok) "Wi-Fi updated — reconnect to $ssid" else "Wi-Fi update failed")
        }
    }

    fun formatSdCard() {
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            val ok = driver.formatSdCard(dev, activeClient)
            if (ok) loadFiles()
            showMessage(if (ok) "SD card formatted" else "Format failed")
        }
    }

    fun factoryReset() {
        val dev = (_uiState.value.connectionState as? ConnectionState.Connected)?.device ?: return
        val driver = activeDriver ?: return
        viewModelScope.launch {
            val ok = driver.factoryReset(dev, activeClient)
            showMessage(if (ok) "Factory reset sent" else "Factory reset failed")
        }
    }

    fun togglePrivacyDialog(show: Boolean) {
        _uiState.update { it.copy(showPrivacyDialog = show) }
    }

    fun openLocalGallery() {
        _uiState.update { it.copy(showLocalGallery = true) }
        loadLocalSavedFiles()
    }

    fun closeLocalGallery() {
        _uiState.update { it.copy(showLocalGallery = false) }
    }

    fun loadLocalSavedFiles() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val out = mutableListOf<RemoteMediaFile>()
            val queryDirs = listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to false,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to true
            )
            for ((uri, isPhoto) in queryDirs) {
                val proj = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED
                )
                val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                } else {
                    "${MediaStore.MediaColumns.DATA} LIKE ?"
                }
                val args = arrayOf("%OpenDash%")
                runCatching {
                    context.contentResolver.query(uri, proj, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                        val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                        while (c.moveToNext()) {
                            val id = c.getLong(idIdx)
                            val contentUri = android.content.ContentUris.withAppendedId(uri, id).toString()
                            out.add(
                                RemoteMediaFile(
                                    id = contentUri,
                                    name = c.getString(nameIdx) ?: "Clip",
                                    remotePath = contentUri,
                                    thumbnailUrl = contentUri,
                                    downloadUrl = contentUri,
                                    streamUrl = contentUri,
                                    category = if (isPhoto) MediaCategory.PHOTO else MediaCategory.NORMAL,
                                    isPhoto = isPhoto,
                                    sizeBytes = c.getLong(sizeIdx),
                                    timestampMs = c.getLong(dateIdx) * 1000L
                                )
                            )
                        }
                    }
                }
            }
            _uiState.update { it.copy(localFiles = out.sortedByDescending { f -> f.timestampMs }) }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun showMessage(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    private fun demoGpsTrack(): List<GpsTrackPoint> = listOf(
        GpsTrackPoint(48.8566, 2.3522, 42f),
        GpsTrackPoint(48.8574, 2.3541, 48f),
        GpsTrackPoint(48.8589, 2.3558, 51f),
        GpsTrackPoint(48.8602, 2.3549, 46f),
        GpsTrackPoint(48.8615, 2.3572, 54f),
        GpsTrackPoint(48.8624, 2.3595, 49f)
    )
}
