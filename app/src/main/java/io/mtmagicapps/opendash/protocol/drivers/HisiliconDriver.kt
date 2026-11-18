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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HisiliconDriver : DashcamDriver {
    override val socType = SocType.HISILICON

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? {
        val wifiResp = client.get("http://$ip/cgi-bin/hisnet/getwifi.cgi?", 2500) ?: return null
        val wifiVars = parseHisnetVars(wifiResp.body)
        if (!wifiVars.containsKey("wifissid") && !wifiResp.body.contains("var ")) return null

        val attrVars = parseHisnetVars(client.get("http://$ip/cgi-bin/hisnet/getdeviceattr.cgi?", 2000)?.body.orEmpty())
        val chVars = parseHisnetVars(client.get("http://$ip/cgi-bin/hisnet/getcamchnl.cgi?&-camid=0", 2000)?.body.orEmpty())
        val numVars = parseHisnetVars(client.get("http://$ip/cgi-bin/hisnet/getcamnum.cgi?", 2000)?.body.orEmpty())
        val ch = chVars["camchnl"] ?: "0"

        return DashcamDevice(
            ip = ip,
            httpPort = 80,
            socType = SocType.HISILICON,
            ssid = wifiVars["wifissid"].orEmpty(),
            model = attrVars["model"] ?: "Hisilicon Dashcam",
            firmwareVersion = attrVars["softversion"] ?: "",
            cameraCount = numVars["camnum"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            liveStreamUrl = "rtsp://$ip:554/livestream/$ch"
        )
    }

    override suspend fun connect(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        client.get("http://${device.ip}/cgi-bin/hisnet/client.cgi?&-operation=register&-ip=${device.ip}")
        syncDateTime(device, client)
        return refreshStatus(device, client)
    }

    override suspend fun disconnect(device: DashcamDevice, client: WifiNetworkHttp) {
        client.get("http://${device.ip}/cgi-bin/hisnet/client.cgi?&-operation=unregister&-ip=${device.ip}")
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        return client.get("http://${device.ip}/cgi-bin/hisnet/setsystime.cgi?&-time=$ts")?.body?.contains("Success", true) == true
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val state = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getworkstate.cgi?", 2000)?.body.orEmpty())
        val sd = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getsdstatus.cgi?", 2000)?.body.orEmpty())
        val mic = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getcommparam.cgi?&-type=AUDIO", 2000)?.body.orEmpty())
        return device.copy(
            isRecording = state["running"] == "true",
            isEmergencyLocked = state["emrrecord"] == "true",
            isMicEnabled = mic["value"] != "0",
            sdCardPresent = sd["sdstatus"] != "0"
        )
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        val dirVars = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getdircapability.cgi?", 3000)?.body.orEmpty())
        val folders = dirVars["capability"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("norm", "emr", "photo")
        val out = mutableListOf<RemoteMediaFile>()
        for (folder in folders) {
            val cntVars = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getdirfilecount.cgi?&-dir=$folder", 3000)?.body.orEmpty())
            val count = cntVars["count"]?.toIntOrNull() ?: 0
            if (count <= 0) continue
            val listBody = client.get("http://${device.ip}/cgi-bin/hisnet/getdirfilelist.cgi?&-dir=$folder&-start=0&-end=${count.coerceAtMost(200)}", 5000)?.body ?: continue
            listBody.lineSequence().map { it.trim() }.filter { it.startsWith("/") || it.contains(".") }.forEach { path ->
                val norm = if (path.startsWith("/")) path else "/$path"
                val isPhoto = norm.endsWith(".jpg", true) || norm.endsWith(".png", true)
                val category = when {
                    isPhoto -> MediaCategory.PHOTO
                    folder.contains("emr", true) || folder.contains("event", true) -> MediaCategory.LOCKED
                    folder.contains("park", true) -> MediaCategory.PARKING
                    else -> MediaCategory.NORMAL
                }
                val thumb = if (isPhoto) "http://${device.ip}$norm" else "http://${device.ip}${norm.substringBeforeLast('.')}.THM"
                out.add(
                    RemoteMediaFile(
                        id = norm,
                        name = norm.substringAfterLast('/'),
                        remotePath = norm,
                        thumbnailUrl = thumb,
                        downloadUrl = "http://${device.ip}$norm",
                        streamUrl = "http://${device.ip}$norm",
                        category = category,
                        isPhoto = isPhoto,
                        isLocked = category == MediaCategory.LOCKED
                    )
                )
            }
        }
        return out
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        return client.get("http://${device.ip}/cgi-bin/hisnet/deletefile.cgi?&-name=${file.remotePath}")?.code == 200
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        val items = mutableListOf<CameraSettingItem>()
        suspend fun queryCam(type: String, title: String, section: SettingSection) {
            val cur = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getcamparam.cgi?&-workmode=NORM_REC&-type=$type")?.body.orEmpty())["value"] ?: return
            val cap = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getcamparamcapability.cgi?&-workmode=NORM_REC&-type=$type")?.body.orEmpty())["capability"].orEmpty()
            val opts = cap.split(",").filter { it.isNotBlank() }.map { SettingOption(it.trim(), it.trim()) }
            items.add(CameraSettingItem("cam:$type", title, section, cur, opts))
        }
        suspend fun queryComm(type: String, title: String, section: SettingSection) {
            val cur = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getcommparam.cgi?&-type=$type")?.body.orEmpty())["value"] ?: return
            val cap = parseHisnetVars(client.get("http://${device.ip}/cgi-bin/hisnet/getcommparamcapability.cgi?&-type=$type")?.body.orEmpty())["capability"].orEmpty()
            val opts = cap.split(",").filter { it.isNotBlank() }.map { SettingOption(it.trim(), it.trim()) }
            items.add(CameraSettingItem("comm:$type", title, section, cur, opts))
        }
        queryCam("MEDIAMODE", "Video Resolution", SettingSection.VIDEO_AUDIO)
        queryCam("Rec_Split_Time", "Loop Recording", SettingSection.VIDEO_AUDIO)
        queryCam("WDR", "WDR", SettingSection.VIDEO_AUDIO)
        queryCam("EV", "Exposure", SettingSection.VIDEO_AUDIO)
        queryComm("AUDIO", "Microphone", SettingSection.VIDEO_AUDIO)
        queryComm("GSR_SENSITIVITY", "G-Sensor", SettingSection.PARKING_SAFETY)
        queryComm("GSR_PARKING", "Parking G-Sensor", SettingSection.PARKING_SAFETY)
        queryCam("ANTIFLICKER", "Anti-Flicker", SettingSection.SYSTEM)
        return items
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        val url = if (key.startsWith("comm:")) {
            "http://${device.ip}/cgi-bin/hisnet/setcommparam.cgi?&-type=${key.removePrefix("comm:")}&-value=$value"
        } else {
            "http://${device.ip}/cgi-bin/hisnet/setcamparam.cgi?&-workmode=NORM_REC&-type=${key.removePrefix("cam:")}&-value=$value"
        }
        return client.get(url)?.code == 200
    }

    override suspend fun setWifiCredentials(device: DashcamDevice, client: WifiNetworkHttp, ssid: String, password: String): Boolean {
        return client.get("http://${device.ip}/cgi-bin/hisnet/setwifi.cgi?&-wifissid=$ssid&-wifikey=$password")?.code == 200
    }

    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return client.get("http://${device.ip}/cgi-bin/hisnet/sdcommand.cgi?&-format", 10000)?.code == 200
    }

    private fun parseHisnetVars(body: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("""var\s+([A-Za-z0-9_]+)\s*=\s*"([^"]*)"""").findAll(body).forEach { m ->
            map[m.groupValues[1].lowercase(Locale.US)] = m.groupValues[2]
        }
        return map
    }
}
