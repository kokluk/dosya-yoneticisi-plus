package dev.dosyayoneticisi.plus

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ViewerKind { Photo, Video, Text }
data class Viewer(val uri: Uri, val name: String, val kind: ViewerKind, val entry: Entry? = null,
    val temp: File? = null, val original: String = "", val draft: String = "", val writable: Boolean = false)

internal const val TEXT_LIMIT = 512 * 1024
internal const val PRIVILEGED_LIMIT = 256L * 1024 * 1024
internal fun viewerKind(name: String, mime: String): ViewerKind? = when {
    mime.startsWith("image/") -> ViewerKind.Photo
    mime.startsWith("video/") -> ViewerKind.Video
    extension(name) in setOf("txt", "json", "xml", "log", "md") -> ViewerKind.Text
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerScreen(vm: Manager, viewer: Viewer, p: Preferences, window: android.view.Window, busy: Boolean) {
    val context = LocalContext.current
    var fullscreen by remember(viewer.uri) { mutableStateOf(false) }
    var discard by remember(viewer.uri) { mutableStateOf(false) }
    var rotation by remember(viewer.uri) { mutableFloatStateOf(0f) }
    val dirty = viewer.draft != viewer.original
    fun back() {
        if (busy) return
        if (fullscreen) fullscreen = false
        else if (dirty) discard = true
        else vm.closeViewer()
    }
    BackHandler(onBack = ::back)
    DisposableEffect(fullscreen, window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (fullscreen) controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Scaffold(topBar = {
        if (!fullscreen) TopAppBar(title = { Text(viewer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, p.tr("Geri", "Back")) } },
            actions = {
                if (viewer.kind == ViewerKind.Photo) {
                    IconButton(onClick = { rotation = (rotation + 90f) % 360 }) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, p.tr("Döndür", "Rotate"))
                    }
                    if (viewer.entry != null) IconButton(onClick = { vm.launch(viewer.entry, true, callback = context::startActivity) }) {
                        Icon(Icons.Default.Share, p.tr("Paylaş", "Share"))
                    }
                }
                if (viewer.kind == ViewerKind.Text && viewer.writable) IconButton(enabled = dirty && !busy, onClick = vm::saveViewer) {
                    Icon(Icons.Default.Save, p.tr("Kaydet", "Save"))
                }
            })
    }, contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets) { inset ->
        Box(Modifier.fillMaxSize().padding(inset)) {
            when (viewer.kind) {
                ViewerKind.Photo -> PhotoContent(viewer.uri, rotation, p)
                ViewerKind.Video -> VideoContent(viewer.uri, fullscreen, { fullscreen = !fullscreen }, p)
                ViewerKind.Text -> {
                    val foreground = MaterialTheme.colorScheme.onSurface
                    BasicTextField(viewer.draft, vm::editViewer, readOnly = !viewer.writable || busy,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = foreground, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(p.tr("Kaydedilmemiş değişiklikler", "Unsaved changes")) },
        text = { Text(p.tr("Değişiklikler kaydedilmeden çıkılsın mı?", "Leave without saving changes?")) },
        confirmButton = { TextButton(onClick = { discard = false; vm.closeViewer() }) {
            Text(p.tr("Kaydetmeden çık", "Discard changes")) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(p.tr("Düzenlemeye dön", "Keep editing")) } })
}

@Composable
private fun PhotoContent(uri: Uri, rotation: Float, p: Preferences) {
    val resolver = LocalContext.current.contentResolver
    var failed by remember(uri) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val largest = maxOf(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(((largest.toLong() + 2047) / 2048).toInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
        failed = value == null
    }
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().transformable(transform), contentAlignment = Alignment.Center) {
        val boundX = constraints.maxWidth * scale / 2
        val boundY = constraints.maxHeight * scale / 2
        val image = bitmap
        when {
            image != null -> Image(image.asImageBitmap(), p.tr("Fotoğraf", "Photo"), Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; rotationZ = rotation
                translationX = offset.x.coerceIn(-boundX, boundX)
                translationY = offset.y.coerceIn(-boundY, boundY)
            })
            failed -> Text(p.tr("Fotoğraf açılamadı.", "Could not open the photo."))
            else -> CircularProgressIndicator()
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoContent(uri: Uri, fullscreen: Boolean, toggle: () -> Unit, p: Preferences) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(uri) { ExoPlayer.Builder(context).build() }
    var failed by remember(uri) { mutableStateOf(false) }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        onDispose { lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = {
            PlayerView(it).apply {
                useController = true
                setEnableComposeSurfaceSyncWorkaround(true)
                keepScreenOn = true
            }
        }, update = { it.player = player }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
        FilledTonalIconButton(onClick = toggle, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                if (fullscreen) p.tr("Tam ekrandan çık", "Exit fullscreen") else p.tr("Tam ekran", "Fullscreen"))
        }
        if (failed) Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium) {
            Text(p.tr("Video oynatılamıyor.", "Could not play the video."), Modifier.padding(16.dp))
        }
    }
}

