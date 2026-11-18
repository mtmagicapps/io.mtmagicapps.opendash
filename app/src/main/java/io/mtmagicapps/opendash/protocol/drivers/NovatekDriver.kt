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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class NovatekDriver : DashcamDriver {
    override val socType = SocType.NOVATEK

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? {
        var resp = client.get("http://$ip/?custom=1&cmd=3029", 2500) ?: return null
        if (resp.body.contains("-256")) {
            resp = client.get("http://$ip/?custom=1&cmd=3504", 2500) ?: return null
        }
        val status = extractTag(resp.body, "Status")?.toIntOrNull()
        if (status == -22) {
            client.get("http://$ip/?custom=1&cmd=3035", 2000)
            resp = client.get("http://$ip/?custom=1&cmd=3029", 2500) ?: return null
        }
        val ssid = extractTag(resp.body, "SSID").orEmpty()
        if (!resp.body.contains("<Function>") && ssid.isBlank()) return null

        val modelXml = client.get("http://$ip/?custom=1&cmd=9090", 2000)?.body.orEmpty()
        val fwXml = client.get("http://$ip/?custom=1&cmd=3012", 2000)?.body.orEmpty()
        val macXml = client.get("http://$ip/?custom=1&cmd=9091", 2000)?.body.orEmpty()
        val camNumXml = client.get("http://$ip/?custom=1&cmd=9095", 2000)?.body.orEmpty()
        val liveXml = client.get("http://$ip/?custom=1&cmd=2019", 2000)?.body.orEmpty()

        val rtsp = extractTag(liveXml, "MovieLiveViewLink")
            ?.takeIf { it.isNotBlank() }
            ?: "rtsp://$ip/xxxx.mov"

        return DashcamDevice(
            ip = ip,
            httpPort = 80,
            socType = SocType.NOVATEK,
            ssid = ssid,
            model = extractTag(modelXml, "String")?.substringBefore("@") ?: "Novatek Dashcam",
            firmwareVersion = extractTag(fwXml, "String") ?: "",
            mac = extractTag(macXml, "String") ?: "",
            cameraCount = extractTag(camNumXml, "Value")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            liveStreamUrl = rtsp,
            mjpegFallbackUrl = "http://$ip:8192"
        )
    }

    override suspend fun connect(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        client.get("http://${device.ip}/?custom=1&cmd=3023", 2000)
        syncDateTime(device, client)
        return refreshStatus(device, client)
    }

    override suspend fun disconnect(device: DashcamDevice, client: WifiNetworkHttp) {
        client.get("http://${device.ip}/?custom=1&cmd=3035", 1500)
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        val now = Date()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
        val tzHours = (TimeZone.getDefault().getOffset(now.time) / 3600000) + 12
        val r1 = client.get("http://${device.ip}/?custom=1&cmd=3005&str=$dateStr")
        val r2 = client.get("http://${device.ip}/?custom=1&cmd=3006&str=$timeStr")
        client.get("http://${device.ip}/?custom=1&cmd=9146&par=$tzHours")
        return isOk(r1?.body) && isOk(r2?.body)
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val recBody = client.get("http://${device.ip}/?custom=1&cmd=2016", 2000)?.body.orEmpty()
        val sdBody = client.get("http://${device.ip}/?custom=1&cmd=3024", 2000)?.body.orEmpty()
        val statusBody = client.get("http://${device.ip}/?custom=1&cmd=3014", 2500)?.body.orEmpty()

        val recVal = extractTag(recBody, "Value")?.toIntOrNull()
        val sdState = extractTag(sdBody, "Value")?.toIntOrNull()
        val micStatus = extractCmdStatus(statusBody, "2007")

        return device.copy(
            isRecording = recVal == 1 || recVal == 2,
            sdCardPresent = sdState != 0,
            isMicEnabled = micStatus != "0"
        )
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        val xml = client.get("http://${device.ip}/?custom=1&cmd=3015", 8000)?.body ?: return emptyList()
        val files = mutableListOf<RemoteMediaFile>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var name = ""
            var fpath = ""
            var thumb = ""
            var size = 0L
            var attr = 0
            var timeStr = ""
            var gpsPath: String? = null
            var currentTag = ""
            var inFile = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "File") {
                            inFile = true
                            name = ""; fpath = ""; thumb = ""; size = 0L; attr = 0; timeStr = ""; gpsPath = null
                        }
                    }
                    XmlPullParser.TEXT -> if (inFile) {
                        val txt = parser.text.orEmpty().trim()
                        when (currentTag) {
                            "NAME" -> name = txt
                            "FPATH" -> fpath = txt
                            "THUMBNAIL" -> thumb = txt
                            "SIZE" -> size = txt.toLongOrNull() ?: 0L
                            "ATTR" -> attr = txt.toIntOrNull(16) ?: txt.toIntOrNull() ?: 0
                            "TIME" -> timeStr = txt
                            "GPSPATH" -> if (txt.isNotBlank()) gpsPath = txt
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "File" && inFile) {
                        inFile = false
                        val cleanPath = fpath.removePrefix("A:").replace('\\', '/')
                        val normalizedPath = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
                        val lower = name.lowercase(Locale.US)
                        val isPhoto = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                        val isLocked = (attr and 1) != 0 || normalizedPath.contains("RO", ignoreCase = true) ||
                            normalizedPath.contains("EMR", ignoreCase = true) || normalizedPath.contains("EVENT", ignoreCase = true)
                        val isParking = normalizedPath.contains("PARK", ignoreCase = true)
                        val category = when {
                            isPhoto -> MediaCategory.PHOTO
                            isLocked -> MediaCategory.LOCKED
                            isParking -> MediaCategory.PARKING
                            else -> MediaCategory.NORMAL
                        }
                        val channel = if (normalizedPath.contains("REAR", ignoreCase = true) || name.contains("_R.")) {
                            CameraChannel.REAR
                        } else {
                            CameraChannel.FRONT
                        }
                        val thumbUrl = when {
                            isPhoto -> "http://${device.ip}$normalizedPath"
                            thumb.isNotBlank() -> "http://${device.ip}" + thumb.removePrefix("A:").replace('\\', '/')
                            else -> "http://${device.ip}$normalizedPath?custom=1&cmd=4001"
                        }
                        val dlUrl = "http://${device.ip}$normalizedPath"
                        files.add(
                            RemoteMediaFile(
                                id = normalizedPath,
                                name = name.ifBlank { normalizedPath.substringAfterLast('/') },
                                remotePath = fpath.ifBlank { normalizedPath },
                                thumbnailUrl = thumbUrl,
                                downloadUrl = dlUrl,
                                streamUrl = dlUrl,
                                category = category,
                                channel = channel,
                                isPhoto = isPhoto,
                                sizeBytes = size,
                                timestampMs = parseTime(timeStr),
                                isLocked = isLocked,
                                gpsPath = gpsPath
                            )
                        )
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return files.sortedByDescending { it.timestampMs }
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        return isOk(client.get("http://${device.ip}/?custom=1&cmd=4003&str=${file.remotePath}")?.body)
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        val statusXml = client.get("http://${device.ip}/?custom=1&cmd=3014", 3500)?.body.orEmpty()
        val capXml = client.get("http://${device.ip}/?custom=1&cmd=3031&str=all", 3500)?.body.orEmpty()

        val currentValues = parseCurrentValues(statusXml)
        val caps = parseCapabilities(capXml)

        val definitions = listOf(
            SettingDef("2002", "Video Resolution", SettingSection.VIDEO_AUDIO, listOf("0" to "4K / 2160p", "1" to "1440p QHD", "2" to "1080p FHD")),
            SettingDef("2003", "Loop Recording", SettingSection.VIDEO_AUDIO, listOf("0" to "Off", "1" to "1 min", "2" to "3 min", "3" to "5 min")),
            SettingDef("2004", "WDR / HDR", SettingSection.VIDEO_AUDIO, listOf("0" to "Off", "1" to "On"), SettingType.TOGGLE),
            SettingDef("2005", "Exposure (EV)", SettingSection.VIDEO_AUDIO, listOf("0" to "-2.0", "3" to "-1.0", "6" to "0.0", "9" to "+1.0", "12" to "+2.0")),
            SettingDef("2007", "Microphone", SettingSection.VIDEO_AUDIO, listOf("0" to "Off", "1" to "On"), SettingType.TOGGLE),
            SettingDef("2008", "Date/Time Watermark", SettingSection.VIDEO_AUDIO, listOf("0" to "Off", "1" to "On"), SettingType.TOGGLE),
            SettingDef("2011", "G-Sensor Sensitivity", SettingSection.PARKING_SAFETY, listOf("0" to "Off", "1" to "Low", "2" to "Medium", "3" to "High")),
            SettingDef("3101", "Parking Guard", SettingSection.PARKING_SAFETY, listOf("0" to "Off", "1" to "On"), SettingType.TOGGLE),
            SettingDef("9138", "Motion Detection", SettingSection.PARKING_SAFETY, listOf("0" to "Off", "1" to "On"), SettingType.TOGGLE),
            SettingDef("9132", "Anti-Flicker", SettingSection.SYSTEM, listOf("0" to "50 Hz", "1" to "60 Hz")),
            SettingDef("9135", "Key Tone", SettingSection.SYSTEM, listOf("0" to "Off", "1" to "On"), SettingType.TOGGLE),
            SettingDef("2023", "Vertical Flip", SettingSection.SYSTEM, listOf("0" to "Off", "1" to "On"), SettingType.TOGGLE)
        )

        return definitions.mapNotNull { def ->
            val current = currentValues[def.cmd] ?: return@mapNotNull null
            val options = caps[def.cmd]?.takeIf { it.isNotEmpty() }
                ?: def.fallbackOptions.map { SettingOption(it.first, it.second) }
            CameraSettingItem(
                key = def.cmd,
                title = def.title,
                section = def.section,
                currentValue = current,
                options = options,
                type = def.type
            )
        }
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        val ok = isOk(client.get("http://${device.ip}/?custom=1&cmd=$key&par=$value")?.body)
        if (ok) client.get("http://${device.ip}/?custom=1&cmd=3021")
        return ok
    }

    override suspend fun setWifiCredentials(device: DashcamDevice, client: WifiNetworkHttp, ssid: String, password: String): Boolean {
        val r1 = isOk(client.get("http://${device.ip}/?custom=1&cmd=3003&str=$ssid")?.body)
        val r2 = isOk(client.get("http://${device.ip}/?custom=1&cmd=3004&str=$password")?.body)
        client.get("http://${device.ip}/?custom=1&cmd=3018")
        return r1 || r2
    }

    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return isOk(client.get("http://${device.ip}/?custom=1&cmd=3010&par=1", 10000)?.body)
    }

    override suspend fun factoryReset(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return isOk(client.get("http://${device.ip}/?custom=1&cmd=3011")?.body)
    }

    private fun isOk(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val status = extractTag(body, "Status")?.toIntOrNull()
        return status == 0
    }

    private fun extractTag(xml: String, tag: String): String? {
        val start = xml.indexOf("<$tag>")
        val end = xml.indexOf("</$tag>")
        if (start == -1 || end <= start) return null
        return xml.substring(start + tag.length + 2, end).trim()
    }

    private fun extractCmdStatus(xml: String, cmd: String): String? = parseCurrentValues(xml)[cmd]

    private fun parseCurrentValues(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("<Cmd>(\\d+)</Cmd>\\s*<Status>([^<]+)</Status>")
        regex.findAll(xml).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[2].trim()
        }
        return map
    }

    private fun parseCapabilities(xml: String): Map<String, List<SettingOption>> {
        val map = mutableMapOf<String, List<SettingOption>>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var curCmd = ""
            var curIdx = ""
            var curId = ""
            var curOptions = mutableListOf<SettingOption>()
            var tag = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "Cmd") curOptions = mutableListOf()
                        if (tag == "Option") { curIdx = ""; curId = "" }
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text.orEmpty().trim()
                        when (tag) {
                            "Cmd" -> if (t.all { it.isDigit() }) curCmd = t
                            "Index" -> curIdx = t
                            "Id" -> curId = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "Option" && curIdx.isNotBlank()) {
                            curOptions.add(SettingOption(curIdx, curId.ifBlank { curIdx }))
                        } else if (parser.name == "MenuList" && curCmd.isNotBlank() && curOptions.isNotEmpty()) {
                            map[curCmd] = curOptions.toList()
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun parseTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).parse(raw)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private data class SettingDef(
        val cmd: String,
        val title: String,
        val section: SettingSection,
        val fallbackOptions: List<Pair<String, String>>,
        val type: SettingType = SettingType.ENUM
    )
}
