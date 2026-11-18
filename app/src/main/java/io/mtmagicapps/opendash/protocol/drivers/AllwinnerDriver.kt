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

class AllwinnerDriver : DashcamDriver {
    override val socType = SocType.ALLWINNER

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? {
        val resp = client.get("http://$ip:8082/api/getdeviceinfo/?custom=1&cmd=2001", 2500) ?: return null
        val json = runCatching { JSONObject(resp.body) }.getOrNull() ?: return null
        val ssid = json.optString("wifi_ssid").ifBlank { json.optString("ssid") }
        val ver = json.optString("sw_version").ifBlank { json.optString("version") }
        if (ssid.isBlank() && ver.isBlank()) return null

        val camInfo = runCatching {
            JSONObject(client.get("http://$ip:8082/api/getdeviceinfo/?custom=1&cmd=2006", 2000)?.body.orEmpty())
        }.getOrNull()
        val camNum = camInfo?.optInt("camnum", 1)?.coerceAtLeast(1) ?: 1

        return DashcamDevice(
            ip = ip,
            httpPort = 8082,
            socType = SocType.ALLWINNER,
            ssid = ssid,
            model = json.optString("product").substringBefore("@").ifBlank { "Allwinner Dashcam" },
            firmwareVersion = ver,
            mac = json.optString("mac"),
            cameraCount = camNum,
            liveStreamUrl = "rtsp://$ip:8554/ch01"
        )
    }

    override suspend fun connect(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        client.post("http://${device.ip}:8082/api/setdeviceinfo/?custom=1&cmd=4900&par=1")
        syncDateTime(device, client)
        return refreshStatus(device, client)
    }

    override suspend fun disconnect(device: DashcamDevice, client: WifiNetworkHttp) {
        client.post("http://${device.ip}:8082/api/setdeviceinfo/?custom=1&cmd=4900&par=0")
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        val now = Date()
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
        val r1 = isOk(client.post("http://${device.ip}:8082/api/setdeviceinfo/?custom=1&cmd=3005&str=$d")?.body)
        val r2 = isOk(client.post("http://${device.ip}:8082/api/setdeviceinfo/?custom=1&cmd=3006&str=$t")?.body)
        return r1 && r2
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val rec = runCatching {
            JSONObject(client.get("http://${device.ip}:8082/api/getdeviceinfo/?custom=1&cmd=2005", 2000)?.body.orEmpty())
        }.getOrNull()
        return device.copy(isRecording = rec?.optInt("RecodStatus", 0) == 1)
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        val body = client.get("http://${device.ip}:8082/api/getdeviceinfo/?custom=1&cmd=3015", 6000)?.body ?: return emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("List") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<RemoteMediaFile>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val fpath = obj.optString("fpath")
            val name = obj.optString("name").ifBlank { fpath.substringAfterLast('/') }
            val norm = if (fpath.startsWith("/")) fpath else "/$fpath"
            val isPhoto = norm.endsWith(".jpg", true) || norm.endsWith(".png", true)
            val typeStr = obj.optString("type")
            val category = when {
                isPhoto -> MediaCategory.PHOTO
                typeStr.contains("lock", true) || norm.contains("event", true) || norm.contains("emr", true) -> MediaCategory.LOCKED
                norm.contains("park", true) -> MediaCategory.PARKING
                else -> MediaCategory.NORMAL
            }
            val thumb = if (isPhoto) "http://${device.ip}:8082$norm" else "http://${device.ip}:8082${norm.substringBeforeLast('.')}_ths.jpg"
            out.add(
                RemoteMediaFile(
                    id = norm,
                    name = name,
                    remotePath = norm,
                    thumbnailUrl = thumb,
                    downloadUrl = "http://${device.ip}:8082$norm",
                    streamUrl = "http://${device.ip}:8082$norm",
                    category = category,
                    isPhoto = isPhoto,
                    isLocked = category == MediaCategory.LOCKED
                )
            )
        }
        return out
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        return isOk(client.post("http://${device.ip}:8082/api/setdeviceinfo/?custom=1&cmd=4003&str=${file.remotePath}")?.body)
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        val capJson = runCatching {
            JSONObject(client.get("http://${device.ip}:8082/api/getdeviceinfo/?custom=1&cmd=3031", 3000)?.body.orEmpty())
        }.getOrNull() ?: return emptyList()
        val curJson = runCatching {
            JSONObject(client.get("http://${device.ip}:8082/api/getdeviceinfo/?custom=1&cmd=2002", 3000)?.body.orEmpty())
        }.getOrNull()

        val curMap = mutableMapOf<String, String>()
        val infoArr = curJson?.optJSONArray("info")
        if (infoArr != null) {
            for (i in 0 until infoArr.length()) {
                val o = infoArr.optJSONObject(i) ?: continue
                curMap[o.optString("cmd")] = o.optInt("status", 0).toString()
            }
        }

        val items = mutableListOf<CameraSettingItem>()
        val menuArr = capJson.optJSONArray("Menu") ?: return items
        for (i in 0 until menuArr.length()) {
            val m = menuArr.optJSONObject(i) ?: continue
            val cmd = m.optString("Cmd")
            val name = m.optString("Name").ifBlank { "Setting $cmd" }
            val opts = mutableListOf<SettingOption>()
            val optArr = m.optJSONArray("MenuList")
            if (optArr != null) {
                for (j in 0 until optArr.length()) {
                    val o = optArr.optJSONObject(j) ?: continue
                    opts.add(SettingOption(o.optInt("Index").toString(), o.optString("Id")))
                }
            }
            if (opts.isNotEmpty()) {
                items.add(CameraSettingItem(cmd, name, SettingSection.VIDEO_AUDIO, curMap[cmd] ?: opts.first().value, opts))
            }
        }
        return items
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        return isOk(client.post("http://${device.ip}:8082/api/setdeviceinfo/?custom=1&cmd=$key&par=$value")?.body)
    }

    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return isOk(client.post("http://${device.ip}:8082/api/setdeviceinfo/?custom=1&cmd=3010&par=1", timeoutMs = 10000)?.body)
    }

    private fun isOk(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return runCatching { JSONObject(body).optInt("Value", -1) == 0 }.getOrDefault(false)
    }
}
