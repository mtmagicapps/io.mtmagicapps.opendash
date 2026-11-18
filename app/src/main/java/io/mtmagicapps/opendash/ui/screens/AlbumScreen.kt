package io.mtmagicapps.opendash.ui.screens

import android.net.Network
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.mtmagicapps.opendash.protocol.GpsTrackParser
import io.mtmagicapps.opendash.protocol.GpsTrackPoint
import io.mtmagicapps.opendash.protocol.MediaCategory
import io.mtmagicapps.opendash.protocol.RemoteMediaFile
import io.mtmagicapps.opendash.ui.components.DashcamThumbnail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlbumScreen(
    files: List<RemoteMediaFile>,
    selectedCategory: MediaCategory,
    isLoading: Boolean,
    wifiNetwork: Network?,
    downloadProgress: Map<String, Float>,
    downloadedIds: Set<String>,
    selectedFile: RemoteMediaFile?,
    gpsTrack: List<GpsTrackPoint>,
    onSelectCategory: (MediaCategory) -> Unit,
    onRefresh: () -> Unit,
    onOpenFile: (RemoteMediaFile) -> Unit,
    onCloseViewer: () -> Unit,
    onDownloadFile: (RemoteMediaFile) -> Unit,
    onOpenSavedFile: (RemoteMediaFile) -> Unit,
    onDeleteFile: (RemoteMediaFile) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<RemoteMediaFile?>(null) }

    val filtered = remember(files, selectedCategory) {
        if (selectedCategory == MediaCategory.ALL) files
        else files.filter { it.category == selectedCategory }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Filter Chips + Refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaCategory.entries.forEach { cat ->
                val count = if (cat == MediaCategory.ALL) files.size else files.count { it.category == cat }
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onSelectCategory(cat) },
                    label = {
                        Text(if (count > 0) "${cat.label} ($count)" else cat.label)
                    }
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh album")
            }
        }

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            filtered.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "No clips in ${selectedCategory.label}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(onClick = onRefresh) {
                            Text("Refresh SD Card")
                        }
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 164.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.id }) { file ->
                        val isSaved = file.id in downloadedIds
                        MediaGridCard(
                            file = file,
                            wifiNetwork = wifiNetwork,
                            progress = downloadProgress[file.id],
                            isDownloaded = isSaved,
                            onClick = { onOpenFile(file) },
                            onDownload = { if (isSaved) onOpenSavedFile(file) else onDownloadFile(file) },
                            onDelete = { pendingDelete = file }
                        )
                    }
                }
            }
        }
    }

    if (selectedFile != null) {
        val isSaved = selectedFile.id in downloadedIds
        MediaViewerDialog(
            file = selectedFile,
            wifiNetwork = wifiNetwork,
            gpsTrack = gpsTrack,
            progress = downloadProgress[selectedFile.id],
            isDownloaded = isSaved,
            onDismiss = onCloseViewer,
            onDownload = { if (isSaved) onOpenSavedFile(selectedFile) else onDownloadFile(selectedFile) },
            onDelete = { pendingDelete = selectedFile }
        )
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete from dashcam?") },
            text = { Text("${target.name} will be permanently removed from the dashcam SD card.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteFile(target)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MediaGridCard(
    file: RemoteMediaFile,
    wifiNetwork: Network?,
    progress: Float?,
    isDownloaded: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                DashcamThumbnail(
                    url = file.thumbnailUrl,
                    wifiNetwork = wifiNetwork,
                    isPhoto = file.isPhoto,
                    modifier = Modifier.fillMaxSize()
                )

                // Top-left Lock / Photo badge
                if (file.isLocked || file.isPhoto) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (file.isLocked) MaterialTheme.colorScheme.errorContainer else Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier
                            .padding(6.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Icon(
                            imageVector = if (file.isLocked) Icons.Outlined.Lock else Icons.Outlined.Photo,
                            contentDescription = null,
                            tint = if (file.isLocked) MaterialTheme.colorScheme.onErrorContainer else Color.White,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(14.dp)
                        )
                    }
                }

                // Bottom-right duration badge
                if (!file.isPhoto && file.durationSec > 0L) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(6.dp)
                            .align(Alignment.BottomEnd)
                    ) {
                        Text(
                            text = formatDuration(file.durationSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatMeta(file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row {
                    IconButton(
                        onClick = onDownload,
                        enabled = progress == null,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isDownloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                            contentDescription = "Download",
                            tint = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaViewerDialog(
    file: RemoteMediaFile,
    wifiNetwork: Network?,
    gpsTrack: List<GpsTrackPoint>,
    progress: Float?,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                ) {
                    DashcamThumbnail(
                        url = if (file.isPhoto) file.streamUrl else file.thumbnailUrl,
                        wifiNetwork = wifiNetwork,
                        isPhoto = file.isPhoto,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (gpsTrack.isNotEmpty()) {
                    GpsTrackCard(points = gpsTrack)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDownload,
                        enabled = progress == null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isDownloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            when {
                                progress != null -> "Downloading ${(progress * 100).toInt()}%"
                                isDownloaded -> "Saved to Phone"
                                else -> "Save to Phone"
                            }
                        )
                    }
                    FilledTonalButton(onClick = onDelete) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun GpsTrackCard(points: List<GpsTrackPoint>) {
    val distKm = remember(points) { GpsTrackParser.totalDistanceKm(points) }
    val maxSpeed = remember(points) { points.maxOfOrNull { it.speedKmh } ?: 0f }
    val pathColor = MaterialTheme.colorScheme.primary

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Route, contentDescription = null, tint = pathColor, modifier = Modifier.size(20.dp))
                    Text("GPS Route Telemetry", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = String.format("%.2f km · Max %.0f km/h", distKm, maxSpeed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                if (points.size < 2) return@Canvas
                val minLat = points.minOf { it.latitude }
                val maxLat = points.maxOf { it.latitude }
                val minLon = points.minOf { it.longitude }
                val maxLon = points.maxOf { it.longitude }
                val latSpan = (maxLat - minLat).coerceAtLeast(1e-5)
                val lonSpan = (maxLon - minLon).coerceAtLeast(1e-5)
                val pad = 16f
                val w = size.width - pad * 2
                val h = size.height - pad * 2

                val path = Path()
                points.forEachIndexed { idx, p ->
                    val x = pad + (((p.longitude - minLon) / lonSpan).toFloat() * w)
                    val y = pad + ((1f - ((p.latitude - minLat) / latSpan).toFloat()) * h)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = pathColor,
                    style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                val first = points.first()
                val last = points.last()
                drawCircle(
                    color = pathColor,
                    radius = 9f,
                    center = Offset(
                        pad + (((first.longitude - minLon) / lonSpan).toFloat() * w),
                        pad + ((1f - ((first.latitude - minLat) / latSpan).toFloat()) * h)
                    )
                )
                drawCircle(
                    color = Color(0xFFE53935),
                    radius = 9f,
                    center = Offset(
                        pad + (((last.longitude - minLon) / lonSpan).toFloat() * w),
                        pad + ((1f - ((last.latitude - minLat) / latSpan).toFloat()) * h)
                    )
                )
            }
        }
    }
}

private fun formatDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatMeta(file: RemoteMediaFile): String {
    val sizeMb = if (file.sizeBytes > 0L) String.format(Locale.US, "%.1f MB", file.sizeBytes / 1_000_000f) else ""
    val date = if (file.timestampMs > 0L) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(file.timestampMs))
    } else ""
    return listOf(sizeMb, date).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { file.category.label }
}
