package dev.dosyayoneticisi.plus

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun AudioPreview(uri: Uri, name: String, p: Preferences, close: () -> Unit) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf(false) }
    DisposableEffect(uri) {
        val media = MediaPlayer()
        media.setOnPreparedListener { player = it }
        media.setOnCompletionListener { playing = false }
        media.setOnErrorListener { _, _, _ -> error = true; playing = false; player = null; true }
        try {
            media.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            media.setDataSource(context, uri)
            media.prepareAsync()
        } catch (_: Exception) { error = true }
        onDispose { player = null; media.release() }
    }
    AlertDialog(onDismissRequest = close, title = { Text(name) }, text = {
        if (error) Text(p.tr("Ses dosyası oynatılamıyor.", "Could not play the audio file."))
        else if (player == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else Text(p.tr("Yerleşik oynatıcı", "Built-in player"))
    }, confirmButton = {
        TextButton(enabled = player != null, onClick = {
            player?.let { if (playing) it.pause() else it.start(); playing = !playing }
        }) { Text(if (playing) p.tr("Duraklat", "Pause") else p.tr("Oynat", "Play")) }
    }, dismissButton = { TextButton(onClick = close) { Text(p.tr("Kapat", "Close")) } })
}
