package io.mtmagicapps.opendash.protocol

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class HttpResponse(
    val code: Int,
    val body: String
)

/**
 * Lightweight HTTP/TCP/UDP client bound to the active Wi-Fi [Network] when available,
 * preventing Android from routing 192.168.x.x dashcam requests over cellular data.
 */
class WifiNetworkHttp(
    val wifiNetwork: Network? = null
) {
    suspend fun get(
        url: String,
        timeoutMs: Int = 3500
    ): HttpResponse? = request("GET", url, null, timeoutMs)

    suspend fun post(
        url: String,
        body: String = "",
        timeoutMs: Int = 3500
    ): HttpResponse? = request("POST", url, body, timeoutMs)

    private suspend fun request(
        method: String,
        urlString: String,
        postBody: String?,
        timeoutMs: Int
    ): HttpResponse? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            conn = if (wifiNetwork != null) {
                wifiNetwork.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Connection", "close")
            if (postBody != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResponse(code, text)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun getBytes(urlString: String, timeoutMs: Int = 4000): ByteArray? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            conn = if (wifiNetwork != null) {
                wifiNetwork.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var read: Int
                while (input.read(chunk).also { read = it } != -1) {
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun downloadToStream(
        urlString: String,
        output: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val maxConsecutiveFailures = 8
        val maxTotalAttempts = 60
        var consecutiveFailures = 0
        var downloaded = 0L
        var total = -1L

        for (attempt in 1..maxTotalAttempts) {
            var conn: HttpURLConnection? = null
            var madeProgress = false
            try {
                val url = URL(urlString)
                conn = if (wifiNetwork != null) {
                    wifiNetwork.openConnection(url) as HttpURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }
                conn.connectTimeout = 10000
                conn.readTimeout = 12000
                conn.useCaches = false
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (downloaded > 0L) {
                    conn.setRequestProperty("Range", "bytes=$downloaded-")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w("WifiNetworkHttp", "downloadToStream HTTP $code on attempt $attempt for $urlString")
                    consecutiveFailures++
                    if (consecutiveFailures >= maxConsecutiveFailures) return@withContext false
                    delay(1000L * consecutiveFailures)
                    continue
                }
                val contentLen = conn.contentLengthLong
                if (total <= 0L && contentLen > 0L) {
                    total = if (code == HttpURLConnection.HTTP_PARTIAL) downloaded + contentLen else contentLen
                }
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536)
                    if (downloaded > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                        var toSkip = downloaded
                        while (toSkip > 0L) {
                            val skipped = input.read(buf, 0, minOf(buf.size.toLong(), toSkip).toInt())
                            if (skipped == -1) break
                            toSkip -= skipped
                        }
                    }
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        madeProgress = true
                        consecutiveFailures = 0
                        onProgress(downloaded, total)
                    }
                }
                if (total <= 0L || downloaded >= total) {
                    return@withContext true
                }
                Log.w("WifiNetworkHttp", "downloadToStream incomplete ($downloaded/$total) on attempt $attempt")
            } catch (e: Exception) {
                Log.w("WifiNetworkHttp", "downloadToStream error on attempt $attempt at $downloaded bytes: ${e.message}")
                if (!madeProgress) {
                    consecutiveFailures++
                    if (consecutiveFailures >= maxConsecutiveFailures) return@withContext false
                }
            } finally {
                conn?.disconnect()
            }
            delay(if (madeProgress) 300L else 1200L)
        }
        false
    }

    fun openTcpSocket(host: String, port: Int, timeoutMs: Int = 3500): Socket {
        val socket = Socket()
        wifiNetwork?.bindSocket(socket)
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        return socket
    }

    suspend fun udpBroadcast(
        broadcastIp: String,
        port: Int,
        payload: ByteArray,
        timeoutMs: Int = 2500
    ): String? = withContext(Dispatchers.IO) {
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = timeoutMs
            wifiNetwork?.bindSocket(sock)
            val addr = InetAddress.getByName(broadcastIp)
            sock.send(DatagramPacket(payload, payload.size, addr, port))
            val buf = ByteArray(2048)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            String(resp.data, 0, resp.length, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            sock?.close()
        }
    }
}

fun InputStream.readExact(count: Int): ByteArray? {
    val buf = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val r = read(buf, offset, count - offset)
        if (r <= 0) return null
        offset += r
    }
    return buf
}
