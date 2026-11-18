package io.mtmagicapps.opendash.protocol.drivers

import io.mtmagicapps.opendash.protocol.CameraChannel
import io.mtmagicapps.opendash.protocol.CameraSettingItem
import io.mtmagicapps.opendash.protocol.DashcamDevice
import io.mtmagicapps.opendash.protocol.DashcamDriver
import io.mtmagicapps.opendash.protocol.MediaCategory
import io.mtmagicapps.opendash.protocol.RemoteMediaFile
import io.mtmagicapps.opendash.protocol.SettingOption
import io.mtmagicapps.opendash.protocol.SettingSection
import io.mtmagicapps.opendash.protocol.SettingType
import io.mtmagicapps.opendash.protocol.SocType
import io.mtmagicapps.opendash.protocol.WifiNetworkHttp

/**
 * Interactive local demo driver for exploring the full UX without a physical dashcam connected.
 */
class DemoDashcamDriver : DashcamDriver {
    override val socType = SocType.DEMO

    private var recording = true
    private var mic = true
    private var channel = CameraChannel.FRONT
    private val settings = mutableMapOf(
        "res" to "2160p",
        "loop" to "3",
        "wdr" to "1",
        "ev" to "0.0",
        "gsensor" to "2",
        "parking" to "1",
        "flicker" to "50"
    )

    private val files = mutableListOf(
        RemoteMediaFile(
            id = "demo_1",
            name = "2026_1118_170512_F.MP4",
            remotePath = "/CARDV/MOVIE/2026_1118_170512_F.MP4",
            thumbnailUrl = "",
            downloadUrl = "",
            streamUrl = "",
            category = MediaCategory.NORMAL,
            channel = CameraChannel.FRONT,
            sizeBytes = 184_500_000L,
            durationSec = 180L,
            timestampMs = System.currentTimeMillis() - 900_000L,
            gpsPath = "DEMO_GPS"
        ),
        RemoteMediaFile(
            id = "demo_2",
            name = "2026_1118_164208_EMR.MP4",
            remotePath = "/CARDV/MOVIE/RO/2026_1118_164208_EMR.MP4",
            thumbnailUrl = "",
            downloadUrl = "",
            streamUrl = "",
            category = MediaCategory.LOCKED,
            channel = CameraChannel.FRONT,
            sizeBytes = 64_200_000L,
            durationSec = 60L,
            timestampMs = System.currentTimeMillis() - 2_300_000L,
            isLocked = true,
            gpsPath = "DEMO_GPS"
        ),
        RemoteMediaFile(
            id = "demo_3",
            name = "2026_1118_163000_P.JPG",
            remotePath = "/CARDV/PHOTO/2026_1118_163000_P.JPG",
            thumbnailUrl = "",
            downloadUrl = "",
            streamUrl = "",
            category = MediaCategory.PHOTO,
            channel = CameraChannel.FRONT,
            isPhoto = true,
            sizeBytes = 3_420_000L,
            timestampMs = System.currentTimeMillis() - 3_100_000L
        ),
        RemoteMediaFile(
            id = "demo_4",
            name = "2026_1118_121504_PARK.MP4",
            remotePath = "/CARDV/PARK/2026_1118_121504_PARK.MP4",
            thumbnailUrl = "",
            downloadUrl = "",
            streamUrl = "",
            category = MediaCategory.PARKING,
            channel = CameraChannel.REAR,
            sizeBytes = 42_100_000L,
            durationSec = 30L,
            timestampMs = System.currentTimeMillis() - 18_000_000L
        )
    )

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice {
        return DashcamDevice(
            ip = "192.168.1.254",
            httpPort = 80,
            socType = SocType.DEMO,
            ssid = "Dashcam_Demo_5G",
            model = "OpenDash Virtual Cam",
            firmwareVersion = "v2.4.1-demo",
            sdTotalBytes = 64_000_000_000L,
            sdFreeBytes = 21_900_000_000L,
            cameraCount = 2,
            activeChannel = channel,
            isRecording = recording,
            isMicEnabled = mic,
            resolutionLabel = "4K 30fps"
        )
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        return device.copy(
            isRecording = recording,
            isMicEnabled = mic,
            activeChannel = channel,
            resolutionLabel = settings["res"] ?: "4K 30fps"
        )
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> = files.toList()

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        return files.removeAll { it.id == file.id }
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        return listOf(
            CameraSettingItem("res", "Video Resolution", SettingSection.VIDEO_AUDIO, settings["res"]!!, listOf("2160p" to "4K 30fps", "1440p" to "2K 60fps", "1080p" to "1080p 60fps").map { SettingOption(it.first, it.second) }),
            CameraSettingItem("loop", "Loop Recording", SettingSection.VIDEO_AUDIO, settings["loop"]!!, listOf("1" to "1 min", "3" to "3 min", "5" to "5 min").map { SettingOption(it.first, it.second) }),
            CameraSettingItem("wdr", "WDR / HDR", SettingSection.VIDEO_AUDIO, settings["wdr"]!!, listOf("0" to "Off", "1" to "On").map { SettingOption(it.first, it.second) }, SettingType.TOGGLE),
            CameraSettingItem("ev", "Exposure", SettingSection.VIDEO_AUDIO, settings["ev"]!!, listOf("-1.0", "-0.5", "0.0", "+0.5", "+1.0").map { SettingOption(it, it) }),
            CameraSettingItem("gsensor", "G-Sensor Sensitivity", SettingSection.PARKING_SAFETY, settings["gsensor"]!!, listOf("0" to "Off", "1" to "Low", "2" to "Medium", "3" to "High").map { SettingOption(it.first, it.second) }),
            CameraSettingItem("parking", "Parking Guard", SettingSection.PARKING_SAFETY, settings["parking"]!!, listOf("0" to "Off", "1" to "On").map { SettingOption(it.first, it.second) }, SettingType.TOGGLE),
            CameraSettingItem("flicker", "Anti-Flicker", SettingSection.SYSTEM, settings["flicker"]!!, listOf("50" to "50 Hz", "60" to "60 Hz").map { SettingOption(it.first, it.second) })
        )
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        settings[key] = value
        return true
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean = true
    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean = true
    override suspend fun factoryReset(device: DashcamDevice, client: WifiNetworkHttp): Boolean = true
}
