package io.mtmagicapps.opendash.protocol.drivers

import io.mtmagicapps.opendash.protocol.DashcamDevice
import io.mtmagicapps.opendash.protocol.DashcamDriver
import io.mtmagicapps.opendash.protocol.MediaCategory
import io.mtmagicapps.opendash.protocol.RemoteMediaFile
import io.mtmagicapps.opendash.protocol.SocType
import io.mtmagicapps.opendash.protocol.WifiNetworkHttp
import org.json.JSONObject

/**
 * TUTK / W18 (Sunxi HTTP :8082 + RTSP :8554) protocol.
 */
class TutkW18Driver : DashcamDriver {
    override val socType = SocType.TUTK_W18

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? {
        val resp = client.get("http://$ip:8082/usr/share/app/sdv/build_info.lua", 2000) ?: return null
        if (resp.code != 200 || resp.body.isBlank()) return null
        val version = Regex("""version\s*=\s*"([^"]+)"""").find(resp.body)?.groupValues?.get(1).orEmpty()
        return DashcamDevice(
            ip = ip,
            httpPort = 8082,
            socType = SocType.TUTK_W18,
            model = "W18 / Sunxi Dashcam",
            firmwareVersion = version,
            liveStreamUrl = "rtsp://$ip:8554/ch01"
        )
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        val body = client.get("http://${device.ip}:8082/api/getdeviceinfo/?custom=1&cmd=3015", 5000)?.body
            ?: return emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("List") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<RemoteMediaFile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fpath = o.optString("fpath")
            val name = o.optString("name").ifBlank { fpath.substringAfterLast('/') }
            val norm = if (fpath.startsWith("/")) fpath else "/$fpath"
            val isPhoto = norm.endsWith(".jpg", true) || norm.endsWith(".png", true)
            val url = "http://${device.ip}:8082/file/media$norm"
            out.add(
                RemoteMediaFile(
                    id = norm,
                    name = name,
                    remotePath = norm,
                    thumbnailUrl = url,
                    downloadUrl = url,
                    streamUrl = url,
                    category = if (isPhoto) MediaCategory.PHOTO else MediaCategory.NORMAL,
                    isPhoto = isPhoto
                )
            )
        }
        return out
    }
}
