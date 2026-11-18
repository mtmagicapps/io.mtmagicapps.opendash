package io.mtmagicapps.opendash.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.mtmagicapps.opendash.protocol.WifiNetworkHttp

private object ThumbnailCache {
    val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
}

@Composable
fun DashcamThumbnail(
    url: String,
    wifiNetwork: Network?,
    isPhoto: Boolean,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(url) { mutableStateOf(ThumbnailCache.cache.get(url)) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, wifiNetwork) {
        if (url.isBlank()) {
            failed = true
            return@LaunchedEffect
        }
        if (bitmap != null) return@LaunchedEffect
        val client = WifiNetworkHttp(wifiNetwork)
        val bytes = client.getBytes(url, timeoutMs = 4500)
        if (bytes != null && bytes.isNotEmpty()) {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (decoded != null) {
                ThumbnailCache.cache.put(url, decoded)
                bitmap = decoded
                failed = false
            } else {
                failed = true
            }
        } else {
            failed = true
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (failed && isPhoto) Icons.Outlined.BrokenImage else Icons.Outlined.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
