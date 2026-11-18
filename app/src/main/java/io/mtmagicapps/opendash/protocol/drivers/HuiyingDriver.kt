package io.mtmagicapps.opendash.protocol.drivers

import io.mtmagicapps.opendash.protocol.CameraChannel
import io.mtmagicapps.opendash.protocol.CameraSettingItem
import io.mtmagicapps.opendash.protocol.DashcamDevice
import io.mtmagicapps.opendash.protocol.DashcamDriver
import io.mtmagicapps.opendash.protocol.MediaCategory
import io.mtmagicapps.opendash.protocol.RemoteMediaFile
import io.mtmagicapps.opendash.protocol.SettingOption
import io.mtmagicapps.opendash.protocol.SettingSection
import io.mtmagicapps.opendash.protocol.SocType
import io.mtmagicapps.opendash.protocol.WifiNetworkHttp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HuiyingDriver : DashcamDriver {
    override val socType = SocType.HUIYING

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? {
        val resp = client.get("http://$ip/?cmd=302&param=network_ap", 2500) ?: return null
        val json = runCatching { JSONObject(resp.body) }.getOrNull() ?: return null
        if (json.optInt("status", -1) != 0) return null
        val ap = json.optString("value").split(":")
        val ssid = ap.firstOrNull().orEmpty()
        val prod = runCatching { JSONObject(client.get("http://$ip/?cmd=9090", 2000)?.body.orEmpty()) }.getOrNull()
        val ver = runCatching { JSONObject(client.get("http://$ip/?cmd=305", 2000)?.body.orEmpty()) }.getOrNull()

        return DashcamDevice(
            ip = ip,
            httpPort = 80,
            socType = SocType.HUIYING,
            ssid = ssid,
            model = prod?.optString("product")?.substringBefore("@")?.ifBlank { null } ?: "Huiying Dashcam",
            firmwareVersion = ver?.optString("value").orEmpty(),
            liveStreamUrl = "rtsp://$ip/live/ch00_0"
        )
    }

    override suspend fun connect(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        syncDateTime(device, client)
        return refreshStatus(device, client)
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        val ts = SimpleDateFormat("yyyy-MM-dd\$HH:mm:ss", Locale.US).format(Date())
        return isOk(client.get("http://${device.ip}/?cmd=306&param=$ts")?.body)
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val rec = runCatching { JSONObject(client.get("http://${device.ip}/?cmd=103", 2000)?.body.orEmpty()) }.getOrNull()
        return device.copy(isRecording = rec?.optInt("value", -1) == 0)
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        val types = listOf(0 to MediaCategory.NORMAL, 1 to MediaCategory.LOCKED, 3 to MediaCategory.PHOTO, 4 to MediaCategory.PARKING)
        val out = mutableListOf<RemoteMediaFile>()
        for ((typeId, cat) in types) {
            val body = client.get("http://${device.ip}/?cmd=201&type=$typeId", 4000)?.body ?: continue
            val arr = runCatching { JSONObject(body).optJSONArray("list") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val path = o.optString("path")
                val norm = if (path.startsWith("/")) path else "/$path"
                val isPhoto = cat == MediaCategory.PHOTO || name.endsWith(".jpg", true)
                val thumb = if (isPhoto) "http://${device.ip}$norm" else "http://${device.ip}${norm.substringBeforeLast('.')}.jpg"
                out.add(
                    RemoteMediaFile(
                        id = "$typeId:$norm",
                        name = name,
                        remotePath = norm,
                        thumbnailUrl = thumb,
                        downloadUrl = "http://${device.ip}$norm",
                        streamUrl = "http://${device.ip}$norm",
                        category = if (name.startsWith("pk", true)) MediaCategory.PARKING else cat,
                        isPhoto = isPhoto,
                        sizeBytes = o.optLong("size", 0L),
                        durationSec = o.optLong("timecode", 0L),
                        isLocked = cat == MediaCategory.LOCKED
                    )
                )
            }
        }
        return out
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        val typeId = file.id.substringBefore(":").toIntOrNull() ?: 0
        return isOk(client.get("http://${device.ip}/?cmd=202&type=$typeId&param=${file.remotePath}")?.body)
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        val json = runCatching { JSONObject(client.get("http://${device.ip}/?cmd=303", 3000)?.body.orEmpty()) }.getOrNull()
            ?: return emptyList()
        val settings = json.optJSONObject("settings") ?: return emptyList()
        val out = mutableListOf<CameraSettingItem>()
        settings.keys().forEach { key ->
            val obj = settings.optJSONObject(key) ?: return@forEach
            val cur = obj.optString("current")
            val optArr = obj.optJSONArray("options") ?: return@forEach
            val opts = (0 until optArr.length()).map { idx ->
                val v = optArr.optString(idx)
                SettingOption(v, v)
            }
            val sec = if (key.contains("park", true) || key.contains("gsensor", true)) {
                SettingSection.PARKING_SAFETY
            } else {
                SettingSection.VIDEO_AUDIO
            }
            out.add(CameraSettingItem(key, key.replace('_', ' ').replaceFirstChar { it.uppercase() }, sec, cur, opts))
        }
        return out
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        return isOk(client.get("http://${device.ip}/?cmd=301&param=$key&value=$value")?.body)
    }

    override suspend fun setWifiCredentials(device: DashcamDevice, client: WifiNetworkHttp, ssid: String, password: String): Boolean {
        return isOk(client.get("http://${device.ip}/?cmd=301&param=network_ap&value=$ssid:$password")?.body)
    }

    private fun isOk(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return runCatching { JSONObject(body).optInt("status", -1) == 0 }.getOrDefault(false)
    }
}
