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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EeasyDriver : DashcamDriver {
    override val socType = SocType.EEASY

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? {
        val port = probePort(ip, client) ?: return null
        val base = "http://$ip:$port"
        val attrBody = client.get("$base/app/getdeviceattr", 2500)?.body ?: return null
        val json = unwrapInfoObject(attrBody) ?: return null
        val uuid = json.optString("uuid")
        val ssid = json.optString("ssid")
        if (uuid.isBlank() && ssid.isBlank()) return null

        val prod = unwrapInfoObject(client.get("$base/app/getproductinfo", 2000)?.body)
        val media = unwrapInfoObject(client.get("$base/app/getmediainfo", 2000)?.body)
        val rtsp = media?.optJSONArray("rtsps")?.optString(0)?.takeIf { it.isNotBlank() }
            ?: media?.optString("rtsp")?.takeIf { it.isNotBlank() }
            ?: "rtsp://$ip:554"

        return DashcamDevice(
            ip = ip,
            httpPort = port,
            socType = SocType.EEASY,
            ssid = ssid,
            model = prod?.optString("model")?.ifBlank { null } ?: "Eeasy Dashcam",
            brand = prod?.optString("company").orEmpty(),
            firmwareVersion = json.optString("softver"),
            mac = json.optString("bssid"),
            cameraCount = json.optInt("camnum", 1).coerceAtLeast(1),
            liveStreamUrl = rtsp
        )
    }

    private suspend fun probePort(ip: String, client: WifiNetworkHttp): Int? {
        if (client.get("http://$ip:80/app/getdeviceattr", 2000)?.code == 200) return 80
        if (client.get("http://$ip:8083/app/getdeviceattr", 2000)?.code == 200) return 8083
        return null
    }

    override suspend fun connect(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val base = "http://${device.ip}:${device.httpPort}"
        client.get("$base/app/enterrecorder")
        syncDateTime(device, client)
        return refreshStatus(device, client)
    }

    override suspend fun disconnect(device: DashcamDevice, client: WifiNetworkHttp) {
        client.get("http://${device.ip}:${device.httpPort}/app/exitrecorder")
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        val base = "http://${device.ip}:${device.httpPort}"
        val now = Date()
        val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now)
        val tzHours = TimeZone.getDefault().getOffset(now.time) / 3600000
        client.get("$base/app/settimezone?timezone=$tzHours")
        return client.get("$base/app/setsystime?date=$ts")?.code == 200
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val base = "http://${device.ip}:${device.httpPort}"
        val rec = unwrapInfoObject(client.get("$base/app/getparamvalue?param=rec", 2000)?.body)
        val mic = unwrapInfoObject(client.get("$base/app/getparamvalue?param=mic", 2000)?.body)
        val sd = unwrapInfoObject(client.get("$base/app/getsdinfo", 2000)?.body)
        val freeMb = sd?.optLong("free", 0L) ?: 0L
        val totalMb = sd?.optLong("total", 0L) ?: 0L
        return device.copy(
            isRecording = rec?.optInt("value", 0) == 1,
            isMicEnabled = mic?.optInt("value", 1) != 0,
            sdCardPresent = sd?.optInt("status", 0) == 0,
            sdFreeBytes = freeMb * 1024L * 1024L,
            sdTotalBytes = totalMb * 1024L * 1024L
        )
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        val base = "http://${device.ip}:${device.httpPort}"
        val out = mutableListOf<RemoteMediaFile>()
        val seenIds = mutableSetOf<String>()
        val folderNames = listOf("loop", "emr", "event", "park", "photo")
        for (folderQuery in folderNames) {
            val body = client.get("$base/app/getfilelist?folder=$folderQuery&start=0&end=99", 6000)?.body ?: continue
            val folders = unwrapInfoArray(body) ?: continue
            for (i in 0 until folders.length()) {
                val folderObj = folders.optJSONObject(i) ?: continue
                val folderName = folderObj.optString("folder").ifBlank { folderQuery }
                val files = folderObj.optJSONArray("files") ?: continue
                for (j in 0 until files.length()) {
                    val f = files.optJSONObject(j) ?: continue
                    val name = f.optString("name")
                    if (name.isBlank()) continue
                    val norm = if (name.startsWith("/")) name else "/$name"
                    if (!seenIds.add(norm)) continue
                    val isPhoto = norm.endsWith(".jpg", true) || norm.endsWith(".jpeg", true) || norm.endsWith(".png", true)
                    val cat = when {
                        isPhoto -> MediaCategory.PHOTO
                        folderName.contains("emr", true) || folderName.contains("lock", true) -> MediaCategory.LOCKED
                        folderName.contains("park", true) -> MediaCategory.PARKING
                        else -> MediaCategory.NORMAL
                    }
                    val thumb = if (isPhoto) "$base$norm" else "$base/app/getthumbnail?file=$norm"
                    val rawDur = f.optLong("duration", 0L)
                    out.add(
                        RemoteMediaFile(
                            id = norm,
                            name = norm.substringAfterLast('/'),
                            remotePath = norm,
                            thumbnailUrl = thumb,
                            downloadUrl = "$base$norm",
                            streamUrl = "$base$norm",
                            category = cat,
                            isPhoto = isPhoto,
                            sizeBytes = f.optLong("size", 0L) * 1024L,
                            durationSec = if (rawDur > 0L) rawDur else 0L,
                            timestampMs = f.optLong("createtime", 0L) * 1000L,
                            isLocked = cat == MediaCategory.LOCKED,
                            gpsPath = f.optString("GPSPATH").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
        return out.sortedByDescending { it.timestampMs }
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        return client.get("http://${device.ip}:${device.httpPort}/app/deletefile?file=${file.remotePath}")?.code == 200
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        val base = "http://${device.ip}:${device.httpPort}"
        val itemsArr = unwrapInfoArray(client.get("$base/app/getparamitems?param=all", 3000)?.body)
            ?: return emptyList()
        val valArr = unwrapInfoArray(client.get("$base/app/getparamvalue?param=all", 3000)?.body)
        val curMap = mutableMapOf<String, String>()
        if (valArr != null) {
            for (i in 0 until valArr.length()) {
                val o = valArr.optJSONObject(i) ?: continue
                val n = o.optString("name")
                if (n.isNotBlank()) curMap[n] = o.opt("value")?.toString().orEmpty()
            }
        }

        val out = mutableListOf<CameraSettingItem>()
        for (i in 0 until itemsArr.length()) {
            val o = itemsArr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank() || name == "rec" || name == "switchcam") continue
            val optNames = o.optJSONArray("items") ?: continue
            if (optNames.length() <= 1) continue
            val idxArr = o.optJSONArray("index")
            val opts = (0 until optNames.length()).map { j ->
                val valStr = idxArr?.opt(j)?.toString() ?: j.toString()
                SettingOption(valStr, optNames.optString(j))
            }
            val sec = when {
                name.contains("park", true) || name.contains("gsr", true) || name.contains("power", true) ->
                    SettingSection.PARKING_SAFETY
                name.contains("flip", true) || name.contains("filp", true) || name.contains("mirror", true) ->
                    SettingSection.SYSTEM
                else -> SettingSection.VIDEO_AUDIO
            }
            val isToggle = opts.size == 2 &&
                opts[0].label.equals("off", true) &&
                opts[1].label.equals("on", true)
            out.add(
                CameraSettingItem(
                    key = name,
                    title = formatSettingTitle(name),
                    section = sec,
                    currentValue = curMap[name] ?: opts.first().value,
                    options = opts,
                    type = if (isToggle) SettingType.TOGGLE else SettingType.ENUM
                )
            )
        }
        return out
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        return client.get("http://${device.ip}:${device.httpPort}/app/setparamvalue?param=$key&value=$value")?.code == 200
    }

    override suspend fun setWifiCredentials(device: DashcamDevice, client: WifiNetworkHttp, ssid: String, password: String): Boolean {
        val base = "http://${device.ip}:${device.httpPort}"
        val r1 = client.get("$base/app/setwifi?wifissid=$ssid")?.code == 200
        val r2 = client.get("$base/app/setwifi?wifipwd=$password")?.code == 200
        client.get("$base/app/wifireboot")
        return r1 || r2
    }

    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return client.get("http://${device.ip}:${device.httpPort}/app/sdformat?index=0", 10000)?.code == 200
    }

    private fun unwrapInfoObject(body: String?): JSONObject? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(body)
            root.optJSONObject("info") ?: root
        }.getOrNull()
    }

    private fun unwrapInfoArray(body: String?): JSONArray? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("info")
        }.getOrNull()
    }

    private fun formatSettingTitle(key: String): String = when (key) {
        "rec_resolution" -> "Video Resolution"
        "rec_split_duration" -> "Loop Recording"
        "osd" -> "Date/Time Watermark"
        "mic" -> "Microphone"
        "speaker" -> "Speaker Volume"
        "gsr_sensitivity" -> "G-Sensor Sensitivity"
        "parking_monitor" -> "Parking Monitor"
        "park_record_time" -> "Parking Duration"
        "low_power_protect" -> "Low-Voltage Protection"
        "parking_mode" -> "Parking Mode"
        "video_filp", "video_flip" -> "Vertical Flip"
        "video_mirror" -> "Horizontal Mirror"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
