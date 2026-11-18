package io.mtmagicapps.opendash.protocol.drivers

import io.mtmagicapps.opendash.protocol.CameraSettingItem
import io.mtmagicapps.opendash.protocol.DashcamDevice
import io.mtmagicapps.opendash.protocol.DashcamDriver
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
import java.util.Calendar

/**
 * Nello TCP socket (10080) + HTTP (/livesubstream.h264).
 * 12B BE header: 0xFFF0 (2B) + checksum (2B) + 0x0001 (2B) + cmdId (4B BE) + payloadLen (2B BE) + payload.
 */
class NelloDriver : DashcamDriver {
    override val socType = SocType.NELLO

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? = withContext(Dispatchers.IO) {
        try {
            client.openTcpSocket(ip, 10080, 2200).use { socket ->
                val input = socket.getInputStream()
                val header = input.readExact(12) ?: return@withContext null
                if (header[0] != 0xFF.toByte() || header[1] != 0xF0.toByte()) return@withContext null
                val len = ByteBuffer.wrap(header, 10, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                val payload = if (len > 0 && len < 65536) input.readExact(len) else ByteArray(0)
                val mac = if (payload != null && payload.size >= 14) {
                    payload.copyOfRange(8, 14).joinToString(":") { "%02X".format(it) }
                } else ""
                DashcamDevice(
                    ip = ip,
                    httpPort = 80,
                    socType = SocType.NELLO,
                    model = "Nello Dashcam",
                    mac = mac,
                    liveStreamUrl = "http://$ip/livesubstream.h264"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun syncDateTime(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val payload = byteArrayOf(
            (year shr 8).toByte(),
            (year and 0xFF).toByte(),
            cal.get(Calendar.WEEK_OF_YEAR).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte()
        )
        return sendNelloCmd(device.ip, client, 24579, payload)
    }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        return sendNelloCmd(device.ip, client, 32773, file.name.toByteArray(Charsets.UTF_8))
    }

    override suspend fun loadSettings(device: DashcamDevice, client: WifiNetworkHttp): List<CameraSettingItem> {
        return listOf(
            CameraSettingItem("28675", "Speaker Volume", SettingSection.VIDEO_AUDIO, "5", listOf("0", "5", "12", "20").map { SettingOption(it, it) }),
            CameraSettingItem("28679", "Microphone", SettingSection.VIDEO_AUDIO, "1", listOf("0" to "Off", "1" to "On").map { SettingOption(it.first, it.second) }),
            CameraSettingItem("28681", "G-Sensor", SettingSection.PARKING_SAFETY, "14", listOf("254" to "Off", "14" to "Low", "12" to "Medium", "8" to "High").map { SettingOption(it.first, it.second) })
        )
    }

    override suspend fun updateSetting(device: DashcamDevice, client: WifiNetworkHttp, key: String, value: String): Boolean {
        val cmdId = key.toIntOrNull() ?: return false
        return sendNelloCmd(device.ip, client, cmdId, value.toByteArray(Charsets.UTF_8))
    }

    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return sendNelloCmd(device.ip, client, 32783, ByteArray(0))
    }

    private suspend fun sendNelloCmd(ip: String, client: WifiNetworkHttp, cmdId: Int, payload: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                client.openTcpSocket(ip, 10080, 2500).use { socket ->
                    val body = ByteArrayOutputStream().apply {
                        write(byteArrayOf(0x00, 0x01))
                        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(cmdId).array())
                        write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(payload.size.toShort()).array())
                        write(payload)
                    }.toByteArray()
                    val checksum = body.sumOf { it.toInt() and 0xFF } and 0xFFFF
                    val packet = ByteArrayOutputStream().apply {
                        write(byteArrayOf(0xFF.toByte(), 0xF0.toByte()))
                        write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(checksum.toShort()).array())
                        write(body)
                    }.toByteArray()
                    socket.getOutputStream().apply {
                        write(packet)
                        flush()
                    }
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
}
