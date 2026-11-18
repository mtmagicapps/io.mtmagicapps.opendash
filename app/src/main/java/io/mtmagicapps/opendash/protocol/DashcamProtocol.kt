package io.mtmagicapps.opendash.protocol

enum class SocType(
    val displayName: String,
    val defaultIps: List<String>,
    val defaultHttpPort: Int = 80
) {
    NOVATEK("Novatek", listOf("192.168.1.254")),
    MSTAR("MStar / SigmaStar", listOf("192.72.1.1", "192.168.1.1")),
    HISILICON("Hisilicon", listOf("192.168.0.1")),
    JIELI("Jieli", listOf("192.168.1.1"), 8080),
    NELLO("Nello", listOf("192.168.1.1")),
    ALLWINNER("Allwinner", listOf("192.168.10.1"), 8082),
    GENERALPLUS("Generalplus", listOf("192.168.25.1", "192.168.100.1"), 8080),
    ICATCHTEK("iCatchTek", listOf("192.168.1.1")),
    HUIYING("Huiying", listOf("192.168.201.1")),
    EEASY("Eeasy", listOf("192.168.169.1"), 80),
    TUTK_W18("TUTK / W18", listOf("192.168.10.1"), 8082),
    DEMO("Demo Dashcam", listOf("127.0.0.1"))
}

enum class MediaCategory(val label: String) {
    ALL("All"),
    NORMAL("Normal"),
    LOCKED("Locked"),
    PHOTO("Photo"),
    PARKING("Parking")
}

enum class CameraChannel(val label: String) {
    FRONT("Front"),
    REAR("Rear"),
    INTERIOR("Interior"),
    FOURTH("Aux")
}

enum class SettingType {
    ENUM,
    TOGGLE,
    INFO
}

data class SettingOption(
    val value: String,
    val label: String
)

data class CameraSettingItem(
    val key: String,
    val title: String,
    val section: SettingSection,
    val currentValue: String,
    val options: List<SettingOption> = emptyList(),
    val type: SettingType = SettingType.ENUM
)

enum class SettingSection(val title: String) {
    VIDEO_AUDIO("Video & Audio"),
    PARKING_SAFETY("Parking & G-Sensor"),
    SYSTEM("System & Display")
}

data class DashcamDevice(
    val ip: String,
    val httpPort: Int,
    val socType: SocType,
    val ssid: String = "",
    val model: String = "",
    val brand: String = "",
    val firmwareVersion: String = "",
    val mac: String = "",
    val sdTotalBytes: Long = 0L,
    val sdFreeBytes: Long = 0L,
    val sdCardPresent: Boolean = true,
    val cameraCount: Int = 1,
    val activeChannel: CameraChannel = CameraChannel.FRONT,
    val isRecording: Boolean = false,
    val isEmergencyLocked: Boolean = false,
    val isMicEnabled: Boolean = true,
    val recordDurationSec: Long = 0L,
    val liveStreamUrl: String = "",
    val mjpegFallbackUrl: String = "",
    val resolutionLabel: String = ""
)

data class RemoteMediaFile(
    val id: String,
    val name: String,
    val remotePath: String,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val streamUrl: String,
    val category: MediaCategory,
    val channel: CameraChannel = CameraChannel.FRONT,
    val isPhoto: Boolean = false,
    val sizeBytes: Long = 0L,
    val durationSec: Long = 0L,
    val timestampMs: Long = 0L,
    val isLocked: Boolean = false,
    val gpsPath: String? = null
)

data class GpsTrackPoint(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float = 0f,
    val timestampMs: Long = 0L
)

interface DashcamDriver {
    val socType: SocType
    suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice?
    suspend fun connect(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice = device
    suspend fun disconnect(device: DashcamDevice, client: WifiNetworkHttp) {}
    suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean = false
    suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice = device
    suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> = emptyList()
    suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean = false
    suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> = emptyList()
    suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean = false
    suspend fun setWifiCredentials(device: DashcamDevice, client: WifiNetworkHttp, ssid: String, password: String): Boolean = false
    suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean = false
    suspend fun factoryReset(device: DashcamDevice, client: WifiNetworkHttp): Boolean = false
}
