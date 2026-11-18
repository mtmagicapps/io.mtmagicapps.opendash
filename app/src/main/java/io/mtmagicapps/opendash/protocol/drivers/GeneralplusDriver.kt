package io.mtmagicapps.opendash.protocol.drivers

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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generalplus / GoPlus binary TCP protocol (port 8081) reverse-engineered from libviigoplus.so.
 * Packet framing:
 *   8B magic: "GPSOCKET"
 *   2B type (LE): 1=CMD, 2=ACK, 3=NAK
 *   1B mode: 0=General, 1=Record, 2=Capture, 3=Playback, 4=Menu
 *   1B cmd:  mode-specific command ID
 *   If ACK/CMD with payload: 2B payloadLen (LE) + payload
 *   If NAK: 2B errorCode (LE)
 * Live stream: rtsp://<ip>:8080/?action=stream (or MJPEG http://<ip>:8080/?action=stream)
 */
class GeneralplusDriver : DashcamDriver {
    override val socType = SocType.GENERALPLUS

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? = withContext(Dispatchers.IO) {
        val ack = sendGpCmd(ip, client, mode = 0, cmd = 1) ?: return@withContext null // GPSOCK_General_CMD_GetDeviceStatus
        val statusByte = ack.firstOrNull()?.toInt() ?: 0
        DashcamDevice(
            ip = ip,
            httpPort = 8080,
            socType = SocType.GENERALPLUS,
            model = "Generalplus Dashcam",
            isRecording = (statusByte and 1) != 0,
            liveStreamUrl = "rtsp://$ip:8080/?action=stream",
            mjpegFallbackUrl = "http://$ip:8080/?action=stream"
        )
    }

    override suspend fun refreshStatus(device: DashcamDevice, client: WifiNetworkHttp): DashcamDevice {
        val ack = sendGpCmd(device.ip, client, mode = 0, cmd = 1) ?: return device
        val statusByte = ack.firstOrNull()?.toInt() ?: 0
        return device.copy(isRecording = (statusByte and 1) != 0)
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> {
        sendGpCmd(device.ip, client, mode = 0, cmd = 0, payload = byteArrayOf(2)) // SetMode Playback
        val countBytes = sendGpCmd(device.ip, client, mode = 3, cmd = 2) ?: return emptyList()
        if (countBytes.size < 2) return emptyList()
        val count = ByteBuffer.wrap(countBytes, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        if (count <= 0) return emptyList()

        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(count.coerceAtMost(100).toShort())
            .array()
        val listData = sendGpCmd(device.ip, client, mode = 3, cmd = 3, payload = payload) ?: return emptyList()
        val rawNames = String(listData, Charsets.UTF_8).split('\u0000').filter { it.contains('.') }
        return rawNames.mapIndexed { idx, name ->
            val isPhoto = name.endsWith(".jpg", true)
            val cat = when {
                isPhoto -> MediaCategory.PHOTO
                name.startsWith("L", true) || name.startsWith("S", true) -> MediaCategory.LOCKED
                else -> MediaCategory.NORMAL
            }
            RemoteMediaFile(
                id = "$idx:$name",
                name = name,
                remotePath = name,
                thumbnailUrl = "",
                downloadUrl = "http://${device.ip}:8080/$name",
                streamUrl = "http://${device.ip}:8080/$name",
                category = cat,
                isPhoto = isPhoto,
                isLocked = cat == MediaCategory.LOCKED
            )
        }
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        return sendGpCmd(device.ip, client, mode = 3, cmd = 8, payload = file.remotePath.toByteArray(Charsets.UTF_8)) != null
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        return listOf(
            CameraSettingItem("0", "Video Resolution", SettingSection.VIDEO_AUDIO, "0", listOf("0" to "1080p", "1" to "720p").map { SettingOption(it.first, it.second) })
        )
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        val id = key.toIntOrNull() ?: return false
        val v = value.toIntOrNull() ?: return false
        return sendGpCmd(device.ip, client, mode = 4, cmd = 1, payload = byteArrayOf(id.toByte(), v.toByte())) != null
    }

    private suspend fun sendGpCmd(
        ip: String,
        client: WifiNetworkHttp,
        mode: Int,
        cmd: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            client.openTcpSocket(ip, 8081, 2200).use { socket ->
                val out = ByteArrayOutputStream().apply {
                    write("GPSOCKET".toByteArray(Charsets.US_ASCII))
                    write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array()) // GP_SOCK_TYPE_CMD
                    write(mode and 0xFF)
                    write(cmd and 0xFF)
                    if (payload.isNotEmpty()) {
                        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(payload.size.toShort()).array())
                        write(payload)
                    }
                }.toByteArray()
                socket.getOutputStream().apply {
                    write(out)
                    flush()
                }
                val input = socket.getInputStream()
                val magic = input.readExact(8) ?: return@withContext null
                if (String(magic, Charsets.US_ASCII) != "GPSOCKET") return@withContext null
                val typeBytes = input.readExact(2) ?: return@withContext null
                val type = ByteBuffer.wrap(typeBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                input.readExact(2) ?: return@withContext null // mode + cmd
                val sizeOrErrBytes = input.readExact(2) ?: return@withContext null
                val sizeOrErr = ByteBuffer.wrap(sizeOrErrBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                if (type == 3) return@withContext null // NAK
                if (sizeOrErr == 0) return@withContext ByteArray(0)
                input.readExact(sizeOrErr)
            }
        } catch (_: Exception) {
            null
        }
    }
}
