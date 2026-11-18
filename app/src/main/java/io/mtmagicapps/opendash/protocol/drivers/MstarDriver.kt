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

class MstarDriver : DashcamDriver {
    override val socType = SocType.MSTAR

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? {
        val resp = client.get("http://$ip/cgi-bin/Config.cgi?action=get&property=Net.WIFI_AP.SSID", 2500)
            ?: return null
        if (resp.code != 200 || (!resp.body.contains("OK") && !resp.body.contains("Net.WIFI_AP.SSID"))) {
            return null
        }
        val props = parseKeyValues(resp.body)
        val ssid = props["net.wifi_ap.ssid"].orEmpty()
        val infoBody = client.get("http://$ip/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*", 2500)?.body.orEmpty()
        val menuProps = parseKeyValues(infoBody)
        val avMode = menuProps["camera.preview.rtsp.av"]?.toIntOrNull() ?: 4
        val rtspSuffix = when (avMode) {
            1 -> "/liveRTSP/v1"
            3 -> "/liveRTSP/v3"
            else -> "/liveRTSP/av4"
        }

        return DashcamDevice(
            ip = ip,
            httpPort = 80,
            socType = SocType.MSTAR,
            ssid = ssid,
            model = menuProps["camera.menu.product"]?.substringBefore("@") ?: "MStar Dashcam",
            firmwareVersion = menuProps["camera.menu.fwversion"] ?: "",
            cameraCount = if (menuProps["camera.menu.rearstarus"] == "ON") 2 else 1,
            liveStreamUrl = "rtsp://$ip$rtspSuffix"
        )
    }

    override suspend fun connect(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        syncDateTime(device, client)
        return refreshStatus(device, client)
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        val formatted = SimpleDateFormat("yyyy\$MM\$dd\$HH\$mm\$ss", Locale.US).format(Date())
        val resp = client.get("http://${device.ip}/cgi-bin/Config.cgi?action=set&property=TimeSettings&value=$formatted")
        return resp?.body?.contains("OK") == true
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val body = client.get("http://${device.ip}/cgi-bin/Config.cgi?action=get&property=Camera.Preview.MJPEG.status.*", 2000)?.body.orEmpty()
        val props = parseKeyValues(body)
        val recState = props["camera.preview.mjpeg.status.record"].orEmpty()
        val sdBody = client.get("http://${device.ip}/cgi-bin/Config.cgi?action=get&property=Camera.Menu.SDInfo", 2000)?.body.orEmpty()
        val sdProps = parseKeyValues(sdBody)
        return device.copy(
            isRecording = recState.equals("Recording", true) || recState.equals("EmrRecording", true),
            isEmergencyLocked = recState.equals("EmrRecording", true),
            sdCardPresent = !sdProps["camera.menu.sdinfo"].equals("NONE", true)
        )
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        val folders = listOf(
            "Normal" to MediaCategory.NORMAL,
            "Event" to MediaCategory.LOCKED,
            "Photo" to MediaCategory.PHOTO,
            "Parking" to MediaCategory.PARKING
        )
        val result = mutableListOf<RemoteMediaFile>()
        for ((folder, category) in folders) {
            val xml = client.get(
                "http://${device.ip}/cgi-bin/Config.cgi?action=dir&property=$folder&format=all&backward=1&count=100&from=0",
                5000
            )?.body ?: continue
            result.addAll(parseFileXml(device.ip, xml, category))
        }
        if (result.isEmpty()) {
            val dcimXml = client.get("http://${device.ip}/cgi-bin/Config.cgi?action=dir&property=DCIM&format=all", 5000)?.body.orEmpty()
            result.addAll(parseFileXml(device.ip, dcimXml, MediaCategory.NORMAL))
        }
        return result.sortedByDescending { it.timestampMs }
    }

    private fun parseFileXml(ip: String, xml: String, category: MediaCategory): List<RemoteMediaFile> {
        val out = mutableListOf<RemoteMediaFile>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var name = ""
            var size = 0L
            var timeStr = ""
            var gpsPath: String? = null
            var tag = ""
            var inFile = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "file") { inFile = true; name = ""; size = 0L; timeStr = ""; gpsPath = null }
                        if (tag == "format" && inFile) {
                            timeStr = parser.getAttributeValue(null, "time").orEmpty()
                        }
                    }
                    XmlPullParser.TEXT -> if (inFile) {
                        val t = parser.text.orEmpty().trim()
                        when (tag) {
                            "name" -> name = t
                            "size" -> size = t.toLongOrNull() ?: 0L
                            "time" -> if (timeStr.isBlank()) timeStr = t
                            "GPSPATH" -> if (t.isNotBlank()) gpsPath = t
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "file" && inFile) {
                        inFile = false
                        val norm = if (name.startsWith("/")) name else "/$name"
                        val isPhoto = category == MediaCategory.PHOTO || norm.endsWith(".jpg", true)
                        val thumbPath = if (isPhoto) norm else norm.replace("/SD/", "/thumb/", true)
                        out.add(
                            RemoteMediaFile(
                                id = norm,
                                name = norm.substringAfterLast('/'),
                                remotePath = norm,
                                thumbnailUrl = "http://$ip$thumbPath",
                                downloadUrl = "http://$ip$norm",
                                streamUrl = "http://$ip$norm",
                                category = if (isPhoto) MediaCategory.PHOTO else category,
                                isPhoto = isPhoto,
                                sizeBytes = size,
                                isLocked = category == MediaCategory.LOCKED,
                                gpsPath = gpsPath
                            )
                        )
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {
        }
        return out
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        val escaped = file.remotePath.replace('/', '$')
        return client.get("http://${device.ip}/cgi-bin/Config.cgi?action=del&property=$escaped")?.body?.contains("OK") == true
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        val body = client.get("http://${device.ip}/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*", 3000)?.body.orEmpty()
        val props = parseKeyValues(body)
        val items = mutableListOf<CameraSettingItem>()

        fun addEnum(prop: String, title: String, section: SettingSection, opts: List<Pair<String, String>>) {
            val cur = props[prop.lowercase(Locale.US)] ?: return
            items.add(CameraSettingItem(prop.substringAfterLast('.'), title, section, cur, opts.map { SettingOption(it.first, it.second) }))
        }
        addEnum("Camera.Menu.Videores", "Video Resolution", SettingSection.VIDEO_AUDIO, listOf("1080P30" to "1080p 30fps", "1440P30" to "1440p 30fps", "2160P30" to "4K 30fps"))
        addEnum("Camera.Menu.LoopingVideo", "Loop Recording", SettingSection.VIDEO_AUDIO, listOf("1MIN" to "1 min", "3MIN" to "3 min", "5MIN" to "5 min"))
        addEnum("Camera.Menu.SoundRecord", "Microphone", SettingSection.VIDEO_AUDIO, listOf("ON" to "On", "OFF" to "Off"))
        addEnum("Camera.Menu.Hdr", "HDR / WDR", SettingSection.VIDEO_AUDIO, listOf("ON" to "On", "OFF" to "Off"))
        addEnum("Camera.Menu.GSensor", "G-Sensor", SettingSection.PARKING_SAFETY, listOf("OFF" to "Off", "LOW" to "Low", "MIDDLE" to "Medium", "HIGH" to "High"))
        addEnum("Camera.Menu.Flicker", "Anti-Flicker", SettingSection.SYSTEM, listOf("50HZ" to "50 Hz", "60HZ" to "60 Hz"))
        return items
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        return client.get("http://${device.ip}/cgi-bin/Config.cgi?action=set&property=$key&value=$value")?.body?.contains("OK") == true
    }

    override suspend fun setWifiCredentials(device: DashcamDevice, client: WifiNetworkHttp, ssid: String, password: String): Boolean {
        val r1 = client.get("http://${device.ip}/cgi-bin/Config.cgi?action=set&property=Net.WIFI_AP.SSID&value=$ssid")?.body?.contains("OK") == true
        val r2 = client.get("http://${device.ip}/cgi-bin/Config.cgi?action=set&property=Net.WIFI_AP.CryptoKey&value=$password")?.body?.contains("OK") == true
        client.get("http://${device.ip}/cgi-bin/Config.cgi?action=set&property=Net&value=reset")
        return r1 || r2
    }

    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return client.get("http://${device.ip}/cgi-bin/Config.cgi?action=set&property=SD0&value=format", 10000)?.body?.contains("OK") == true
    }

    override suspend fun factoryReset(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return client.get("http://${device.ip}/cgi-bin/Config.cgi?action=set&property=FactoryReset&value=FactoryReset")?.body?.contains("OK") == true
    }

    private fun parseKeyValues(body: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        body.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                map[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
            }
        }
        return map
    }
}
