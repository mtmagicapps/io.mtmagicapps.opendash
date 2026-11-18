package io.mtmagicapps.opendash.protocol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mtmagicapps.opendash.protocol.drivers.AllwinnerDriver
import io.mtmagicapps.opendash.protocol.drivers.EeasyDriver
import io.mtmagicapps.opendash.protocol.drivers.GeneralplusDriver
import io.mtmagicapps.opendash.protocol.drivers.HisiliconDriver
import io.mtmagicapps.opendash.protocol.drivers.HuiyingDriver
import io.mtmagicapps.opendash.protocol.drivers.IcatchDriver
import io.mtmagicapps.opendash.protocol.drivers.JieliDriver
import io.mtmagicapps.opendash.protocol.drivers.MstarDriver
import io.mtmagicapps.opendash.protocol.drivers.NelloDriver
import io.mtmagicapps.opendash.protocol.drivers.NovatekDriver
import io.mtmagicapps.opendash.protocol.drivers.TutkW18Driver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

data class DetectionResult(
    val device: DashcamDevice,
    val driver: DashcamDriver,
    val client: WifiNetworkHttp
)

class DashcamDetector(private val context: Context) {

    private val drivers: List<DashcamDriver> = listOf(
        NovatekDriver(),
        HisiliconDriver(),
        MstarDriver(),
        EeasyDriver(),
        AllwinnerDriver(),
        JieliDriver(),
        HuiyingDriver(),
        GeneralplusDriver(),
        IcatchDriver(),
        NelloDriver(),
        TutkW18Driver()
    )

    fun findWifiNetwork(): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    fun resolveGatewayIp(wifiNetwork: Network?): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = wifiNetwork ?: return null
        val linkProps = cm.getLinkProperties(net) ?: return null
        return linkProps.routes.firstOrNull { route ->
            route.isDefaultRoute && route.gateway is Inet4Address
        }?.gateway?.hostAddress
    }

    suspend fun detect(): DetectionResult? = coroutineScope {
        val wifiNet = findWifiNetwork()
        val client = WifiNetworkHttp(wifiNet)
        val gatewayIp = resolveGatewayIp(wifiNet)

        // Probe all 11 SoC protocols concurrently using the gateway IP (if known) and each SoC's default IPs
        val jobs = drivers.map { driver ->
            async {
                val ips = buildList {
                    if (!gatewayIp.isNullOrBlank()) add(gatewayIp)
                    driver.socType.defaultIps.forEach { if (it != gatewayIp) add(it) }
                }
                for (ip in ips) {
                    val dev = withTimeoutOrNull(3000L) {
                        driver.probe(ip, client)
                    }
                    if (dev != null) {
                        return@async DetectionResult(dev, driver, client)
                    }
                }
                null
            }
        }
        jobs.awaitAll().firstOrNull { it != null }
    }
}
