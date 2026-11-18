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
import io.mtmagicapps.opendash.protocol.readExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jieli (JL AC52xx / AC54xx / AC57xx) CTP binary-over-TCP (port 3333) + HTTP (port 8080) + RTSP (554).
 * Frame: "CTP:" (4B) + topicLen (2B LE) + topic + payloadLen (4B LE) + JSON payload.
 */
class JieliDriver : DashcamDriver {
    override val socType = SocType.JIELI

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? = withContext(Dispatchers.IO) {
        val accessResp = sendCtp(ip, client, "APP_ACCESS", "PUT", mapOf("type" to "0", "ver" to "1.0.0"))
            ?: return@withContext null
        if (accessResp.optInt("errno", -1) != 0) return@withContext null

        val ssidResp = sendCtp(ip, client, "AP_SSID_INFO", "GET")
        val ssid = ssidResp?.optJSONObject("param")?.optString("ssid").orEmpty()
        val descBody = client.get("http://$ip:8080/mnt/spiflash/res/dev_desc.txt", 2000)?.body.orEmpty()
        val descJson = runCatching { JSONObject(descBody) }.getOrNull()
        val hasRear = descJson?.optBoolean("behind_support", false) == true

        DashcamDevice(
            ip = ip,
            httpPort = 8080,
            socType = SocType.JIELI,
            ssid = ssid,
            model = descJson?.optString("product_type")?.ifBlank { null } ?: "Jieli Dashcam",
            firmwareVersion = descJson?.optString("firmware_version").orEmpty(),
            cameraCount = if (hasRear) 2 else 1,
            liveStreamUrl = "rtsp://$ip:554/264_pcm_rt/XXX.fhd"
        )
    }

    override suspend fun connect(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        sendCtp(device.ip, client, "CTP_CMD_OPENAPP", "PUT")
        syncDateTime(device, client)
        return refreshStatus(device, client)
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        return sendCtp(device.ip, client, "DATE_TIME", "PUT", mapOf("date" to ts))?.optInt("errno", -1) == 0
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val rec = sendCtp(device.ip, client, "VIDEO_CTRL", "GET")
        val mic = sendCtp(device.ip, client, "VIDEO_MIC", "GET")
        val isRec = rec?.optJSONObject("param")?.optString("status") == "1"
        val isMic = mic?.optJSONObject("param")?.optString("mic") != "0"
        return device.copy(isRecording = isRec, isMicEnabled = isMic)
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        val out = mutableListOf<RemoteMediaFile>()
        val topics = listOf("FORWARD_MEDIA_FILES_LIST" to CameraChannel.FRONT, "BEHIND_MEDIA_FILES_LIST" to CameraChannel.REAR)
        for ((topic, channel) in topics) {
            val resp = sendCtp(device.ip, client, topic, "PUT", mapOf("type" to "1")) ?: continue
            val listPath = resp.optJSONObject("param")?.optString("path").orEmpty()
            if (listPath.isBlank()) continue
            val normPath = if (listPath.startsWith("/")) listPath else "/$listPath"
            val jsonStr = client.get("http://${device.ip}:8080$normPath", 5000)?.body ?: continue
            val arr = runCatching { JSONObject(jsonStr).optJSONArray("file_list") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val f = obj.optString("f")
                val y = obj.optString("y")
                val size = obj.optLong("s", 0L)
                val dur = obj.optLong("d", 0L)
                val epoch = obj.optLong("e", 0L) * 1000L
                val isPhoto = f.endsWith(".jpg", true) || f.endsWith(".png", true)
                val isLocked = y == "2"
                val category = when {
                    isPhoto -> MediaCategory.PHOTO
                    isLocked -> MediaCategory.LOCKED
                    else -> MediaCategory.NORMAL
                }
                val fileUrlPath = if (f.startsWith("/")) f else "/$f"
                val fullUrl = "http://${device.ip}:8080$fileUrlPath"
                out.add(
                    RemoteMediaFile(
                        id = fileUrlPath,
                        name = fileUrlPath.substringAfterLast('/'),
                        remotePath = f,
                        thumbnailUrl = fullUrl,
                        downloadUrl = fullUrl,
                        streamUrl = fullUrl,
                        category = category,
                        channel = channel,
                        isPhoto = isPhoto,
                        sizeBytes = size,
                        durationSec = dur,
                        timestampMs = epoch,
                        isLocked = isLocked
                    )
                )
            }
        }
        return out.sortedByDescending { it.timestampMs }
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        return sendCtp(device.ip, client, "FILES_DELETE", "PUT", mapOf("file0" to file.remotePath, "path_0" to file.remotePath))
            ?.optInt("errno", -1) == 0
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        val items = mutableListOf<CameraSettingItem>()
        suspend fun q(topic: String, key: String, title: String, section: SettingSection, opts: List<Pair<String, String>>) {
            val v = sendCtp(device.ip, client, topic, "GET")?.optJSONObject("param")?.optString(key)
            if (!v.isNullOrBlank()) {
                items.add(CameraSettingItem("$topic:$key", title, section, v, opts.map { SettingOption(it.first, it.second) }))
            }
        }
        q("VIDEO_LOOP", "cyc", "Loop Recording", SettingSection.VIDEO_AUDIO, listOf("1" to "1 min", "3" to "3 min", "5" to "5 min"))
        q("VIDEO_WDR", "wdr", "WDR", SettingSection.VIDEO_AUDIO, listOf("0" to "Off", "1" to "On"))
        q("VIDEO_MIC", "mic", "Microphone", SettingSection.VIDEO_AUDIO, listOf("0" to "Off", "1" to "On"))
        q("GRA_SEN", "gra", "G-Sensor", SettingSection.PARKING_SAFETY, listOf("0" to "Off", "1" to "Low", "2" to "Medium", "3" to "High"))
        q("VIDEO_PAR_CAR", "par", "Parking Guard", SettingSection.PARKING_SAFETY, listOf("0" to "Off", "1" to "On"))
        q("KEY_VOICE", "kvo", "Key Tone", SettingSection.SYSTEM, listOf("0" to "Off", "1" to "On"))
        q("SCREEN_PRO", "pro", "Screen Standby", SettingSection.SYSTEM, listOf("0" to "Off", "30" to "30s", "60" to "1 min"))
        return items
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        val parts = key.split(":")
        if (parts.size != 2) return false
        return sendCtp(device.ip, client, parts[0], "PUT", mapOf(parts[1] to value))?.optInt("errno", -1) == 0
    }

    override suspend fun setWifiCredentials(device: DashcamDevice, client: WifiNetworkHttp, ssid: String, password: String): Boolean {
        return sendCtp(device.ip, client, "AP_SSID_INFO", "PUT", mapOf("ssid" to ssid, "pwd" to password))?.optInt("errno", -1) == 0
    }

    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return sendCtp(device.ip, client, "FORMAT", "PUT")?.optInt("errno", -1) == 0
    }

    override suspend fun factoryReset(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return sendCtp(device.ip, client, "SYSTEM_DEFAULT", "PUT")?.optInt("errno", -1) == 0
    }

    private suspend fun sendCtp(
        ip: String,
        client: WifiNetworkHttp,
        topic: String,
        op: String,
        params: Map<String, String>? = null
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            client.openTcpSocket(ip, 3333, 2500).use { socket ->
                val topicBytes = topic.toByteArray(Charsets.UTF_8)
                val json = JSONObject().apply {
                    put("op", op)
                    if (params != null) {
                        put("param", JSONObject(params))
                    }
                }.toString().toByteArray(Charsets.UTF_8)

                val frame = ByteArrayOutputStream()
                frame.write("CTP:".toByteArray(Charsets.US_ASCII))
                frame.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(topicBytes.size.toShort()).array())
                frame.write(topicBytes)
                frame.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(json.size).array())
                frame.write(json)

                socket.getOutputStream().apply {
                    write(frame.toByteArray())
                    flush()
                }

                val input = socket.getInputStream()
                val magic = input.readExact(4) ?: return@withContext null
                if (String(magic, Charsets.US_ASCII) != "CTP:") return@withContext null
                val tLenBytes = input.readExact(2) ?: return@withContext null
                val tLen = ByteBuffer.wrap(tLenBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                if (tLen > 0) input.readExact(tLen) ?: return@withContext null
                val pLenBytes = input.readExact(4) ?: return@withContext null
                val pLen = ByteBuffer.wrap(pLenBytes).order(ByteOrder.LITTLE_ENDIAN).int
                if (pLen <= 0 || pLen > 1_048_576) return@withContext JSONObject()
                val payload = input.readExact(pLen) ?: return@withContext null
                JSONObject(String(payload, Charsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
    }
}
