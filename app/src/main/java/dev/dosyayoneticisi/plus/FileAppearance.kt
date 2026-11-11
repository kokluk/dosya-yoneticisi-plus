package dev.dosyayoneticisi.plus

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.text.format.Formatter
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Preferences.fileSize(context: Context, bytes: Long): String {
    val unit = setting("sizeUnit", "auto")
    val exponent = listOf("B", "KB", "MB", "GB").indexOf(unit)
    if (exponent < 0) return Formatter.formatShortFileSize(context, bytes)
    val locale = Locale.forLanguageTag(if (english) "en" else "tr")
    return java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
        .format(bytes / Math.pow(1000.0, exponent.toDouble())) + " " + unit
}

internal fun Preferences.fileDate(time: Long): String {
    if (time <= 0) return ""
    val locale = Locale.forLanguageTag(if (english) "en" else "tr")
    val formatter = when (setting("dateFormat", "medium")) {
        "iso" -> SimpleDateFormat("yyyy-MM-dd", locale)
        "numeric" -> SimpleDateFormat("dd.MM.yyyy", locale)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    }
    return formatter.format(Date(time))
}

private val thumbnailCache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

@Composable
internal fun EntryIcon(files: Files, item: Entry, p: Preferences, selected: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ext = item.name.substringAfterLast('.', "").lowercase()
    val image = item.mime.startsWith("image/")
    val video = item.mime.startsWith("video/")
    val previews = p.enabled("previews")
    val appIcons = p.enabled("associatedIcons")
    val assigned = p.defaultPackage(ext)
    val key = "${item.uri}|${item.modified}|${item.size}|$previews|$appIcons|$assigned"
    val bitmap by produceState<Bitmap?>(null, key) {
        value = null
        if (item.folder) return@produceState
        value = thumbnailCache.get(key) ?: withContext(Dispatchers.IO) {
            runCatching {
                val uri = files.shareUri(item)
                when {
                    (image || video) && previews -> {
                        val resolver = context.contentResolver
                        runCatching { resolver.loadThumbnail(uri, Size(128, 128), null) }.getOrNull() ?: if (image) {
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                                val sample = (maxOf(info.size.width, info.size.height) / 128).coerceAtLeast(1)
                                decoder.setTargetSampleSize(sample)
                            }
                        } else MediaMetadataRetriever().use {
                            it.setDataSource(context, uri)
                            it.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128)
                        }
                    }
                    !appIcons -> null
                    assigned != null -> context.packageManager.getApplicationIcon(assigned).toBitmap(96, 96)
                    ext in setOf("mcpack", "mcworld", "mcaddon", "mctemplate") ->
                        context.packageManager.getApplicationIcon("com.mojang.minecraftpe").toBitmap(96, 96)
                    else -> {
                        val pm = context.packageManager
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
                        val handlers = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                        val preferred = pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                        handlers.firstOrNull { it.activityInfo.packageName == preferred?.activityInfo?.packageName
                            && it.activityInfo.name == preferred?.activityInfo?.name }
                            ?.loadIcon(pm)?.toBitmap(96, 96)
                    }
                }
            }.getOrNull()?.also { thumbnailCache.put(key, it) }
        }
    }
    val icon = when {
        selected -> Icons.Default.CheckCircle
        item.folder -> Icons.Default.Folder
        image -> Icons.Default.Image
        video -> Icons.Default.Movie
        item.mime.startsWith("audio/") -> Icons.Default.MusicNote
        ext == "apk" -> Icons.Default.Android
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Default.FolderZip
        ext == "pdf" -> Icons.Default.PictureAsPdf
        ext in setOf("mcpack", "mcworld", "mcaddon", "mctemplate") -> Icons.Default.VideogameAsset
        else -> Icons.Default.Description
    }
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        val preview = bitmap
        if (preview != null && !selected) {
            Image(preview.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp)),
                contentScale = if (image || video) ContentScale.Crop else ContentScale.Fit)
        } else {
            Icon(icon, null, Modifier.fillMaxSize(), tint = if (item.folder && !selected) Color(0xFFF1A63F)
                else MaterialTheme.colorScheme.primary)
            if (item.folder && !selected) {
                val badge = when (item.name.lowercase()) {
                    "dcim", "camera" -> Icons.Default.PhotoCamera
                    "pictures", "screenshots" -> Icons.Default.Image
                    "documents" -> Icons.Default.Description
                    "download", "downloads" -> Icons.Default.Download
                    "movies", "videos" -> Icons.Default.Movie
                    "music", "audiobooks", "podcasts" -> Icons.Default.MusicNote
                    "android" -> Icons.Default.Android
                    else -> null
                }
                badge?.let { Icon(it, null, Modifier.align(Alignment.BottomEnd).padding(5.dp).size(16.dp),
                    tint = Color(0xFF603900)) }
            }
        }
    }
}


