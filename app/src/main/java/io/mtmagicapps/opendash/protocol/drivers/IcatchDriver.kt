package io.mtmagicapps.opendash.protocol.drivers

import io.mtmagicapps.opendash.protocol.DashcamDevice
import io.mtmagicapps.opendash.protocol.DashcamDriver
import io.mtmagicapps.opendash.protocol.MediaCategory
import io.mtmagicapps.opendash.protocol.RemoteMediaFile
import io.mtmagicapps.opendash.protocol.SocType
import io.mtmagicapps.opendash.protocol.WifiNetworkHttp
import io.mtmagicapps.opendash.protocol.readExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * iCatchTek PTP/IP (ISO 15740 over TCP port 15740) reverse-engineered from libcontrol.so.
 */
class IcatchDriver : DashcamDriver {
    override val socType = SocType.ICATCHTEK

    private var txId = 1

    override suspend fun probe(ip: String, client: WifiNetworkHttp): DashcamDevice? = withContext(Dispatchers.IO) {
        try {
            client.openTcpSocket(ip, 15740, 2200).use { socket ->
                if (!initPtpIp(socket.getOutputStream(), socket.getInputStream())) return@withContext null
                DashcamDevice(
                    ip = ip,
                    httpPort = 80,
                    socType = SocType.ICATCHTEK,
                    model = "iCatchTek Dashcam",
                    liveStreamUrl = "rtsp://$ip/H264?W=1280&H=720&BR=3000000&FPS=30"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun listFiles(device: DashcamDevice, client: WifiNetworkHttp): List<RemoteMediaFile> =
        withContext(Dispatchers.IO) {
            val handlesData = ptpSessionCmd(
                device.ip,
                client,
                opcode = 0x1007, // GetObjectHandles
                params = intArrayOf(0xFFFFFFFF.toInt(), 0, 0)
            ) ?: return@withContext emptyList()
            if (handlesData.size < 4) return@withContext emptyList()
            val buf = ByteBuffer.wrap(handlesData).order(ByteOrder.LITTLE_ENDIAN)
            val count = buf.int.coerceAtMost(200)
            val out = mutableListOf<RemoteMediaFile>()
            for (i in 0 until count) {
                if (buf.remaining() < 4) break
                val handle = buf.int
                val name = "FILE_${handle.toUInt().toString(16)}.MP4"
                out.add(
                    RemoteMediaFile(
                        id = handle.toString(),
                        name = name,
                        remotePath = "/$name",
                        thumbnailUrl = "",
                        downloadUrl = "http://${device.ip}/DCIM/$name",
                        streamUrl = "http://${device.ip}/DCIM/$name",
                        category = MediaCategory.NORMAL
                    )
                )
            }
            out
        }

    override suspend fun deleteFile(device: DashcamDevice, client: WifiNetworkHttp, file: RemoteMediaFile): Boolean {
        val handle = file.id.toIntOrNull() ?: return false
        return ptpSessionCmd(device.ip, client, opcode = 0x100B, params = intArrayOf(handle, 0)) != null // DeleteObject
    }

    override suspend fun formatSdCard(device: DashcamDevice, client: WifiNetworkHttp): Boolean {
        return ptpSessionCmd(device.ip, client, opcode = 0x100F, params = intArrayOf(0x00010001, 0)) != null // FormatStore
    }

    private fun initPtpIp(out: java.io.OutputStream, input: InputStream): Boolean {
        val guid = UUID.randomUUID()
        val nameUtf16 = "OpenDash\u0000".toByteArray(Charsets.UTF_16LE)
        val totalLen = 8 + 16 + nameUtf16.size + 4
        val packet = ByteArrayOutputStream().apply {
            writeIntLe(totalLen)
            writeIntLe(1) // PTPIP_INIT_COMMAND_REQUEST
            writeLongLe(guid.mostSignificantBits)
            writeLongLe(guid.leastSignificantBits)
            write(nameUtf16)
            writeIntLe(0x00010000)
        }.toByteArray()
        out.write(packet)
        out.flush()
        val hdr = input.readExact(8) ?: return false
        val respLen = ByteBuffer.wrap(hdr, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val respType = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (respType != 2 || respLen < 8) return false
        if (respLen > 8) input.readExact(respLen - 8)
        return true
    }

    private suspend fun ptpSessionCmd(
        ip: String,
        client: WifiNetworkHttp,
        opcode: Int,
        params: IntArray = intArrayOf()
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            client.openTcpSocket(ip, 15740, 3000).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                if (!initPtpIp(out, input)) return@withContext null
                // OpenSession (0x1002)
                sendCmdReq(out, 0x1002, intArrayOf(1))
                readCmdDataAndResp(input)
                // Target command
                sendCmdReq(out, opcode, params)
                readCmdDataAndResp(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendCmdReq(out: java.io.OutputStream, opcode: Int, params: IntArray) {
        val id = txId++
        val len = 18 + params.size * 4
        val pkt = ByteArrayOutputStream().apply {
            writeIntLe(len)
            writeIntLe(6) // PTPIP_CMD_REQUEST
            writeIntLe(1) // data phase info
            writeShortLe(opcode)
            writeIntLe(id)
            params.forEach { writeIntLe(it) }
        }.toByteArray()
        out.write(pkt)
        out.flush()
    }

    private fun readCmdDataAndResp(input: InputStream): ByteArray? {
        val dataOut = ByteArrayOutputStream()
        while (true) {
            val hdr = input.readExact(8) ?: return null
            val len = ByteBuffer.wrap(hdr, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val type = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val bodyLen = (len - 8).coerceAtLeast(0)
            val body = if (bodyLen > 0) input.readExact(bodyLen) ?: return null else ByteArray(0)
            when (type) {
                9 -> Unit // PTPIP_START_DATA_PACKET
                10, 12 -> if (body.size > 4) dataOut.write(body, 4, body.size - 4) // DATA / END_DATA
                7 -> { // PTPIP_CMD_RESPONSE
                    val code = if (body.size >= 2) ByteBuffer.wrap(body, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF else 0
                    return if (code == 0x2001) dataOut.toByteArray() else null
                }
            }
        }
    }

    private fun ByteArrayOutputStream.writeIntLe(v: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }

    private fun ByteArrayOutputStream.writeShortLe(v: Int) {
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
    }

    private fun ByteArrayOutputStream.writeLongLe(v: Long) {
        write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
    }
}
