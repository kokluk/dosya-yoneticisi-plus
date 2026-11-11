package dev.dosyayoneticisi.plus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale

internal fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

internal fun Preferences.defaultPackage(ext: String): String? =
    ext.takeIf(String::isNotBlank)?.let { setting("handler." + it).ifBlank { null } }

internal fun viewIntents(uri: Uri, mime: String, ext: String): List<Intent> {
    val types = if (ext in setOf("mcpack", "mcaddon", "mcworld", "mctemplate"))
        listOf("application/x-minecraft-pack", mime, "application/octet-stream", "application/zip")
    else listOf(mime)
    return types.distinct().map {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun PackageManager.viewer(uri: Uri, mime: String, ext: String, pkg: String): Intent? =
    viewIntents(uri, mime, ext).map { it.setPackage(pkg) }
        .firstOrNull { resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }

@Composable
internal fun ChoiceDialog(
    title: String, values: List<Pair<String, String>>, selected: String?, cancel: String,
    dismiss: () -> Unit, choose: (String) -> Unit
) {
    AlertDialog(onDismissRequest = dismiss, shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        title = { Text(title) }, text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                values.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .selectable(selected == value, role = Role.RadioButton, onClick = { choose(value) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == value, null)
                        Text(label, Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text(cancel.uppercase()) } })
}

@Composable
internal fun DefaultsPanel(vm: Manager, p: Preferences) {
    val context = LocalContext.current
    var add by remember { mutableStateOf(false) }
    var ext by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val mappings = remember(p.options) { p.options.filter { it.key.startsWith("handler.") && it.value.isNotEmpty() }.toSortedMap() }
    LazyColumn {
        item {
            ListItem(modifier = Modifier.clickable { add = true }, leadingContent = { Icon(Icons.Default.Add, null) },
                content = { Text(p.tr("Dosya türü ekle", "Add file type")) })
            HorizontalDivider()
        }
        items(mappings.entries.toList(), key = { it.key }) { (key, pkg) ->
            val format = key.removePrefix("handler.")
            val pm = context.packageManager
            val name = remember(pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(MimeTypeMap.getSingleton().getMimeTypeFromExtension(format) ?: "extension/" + format,
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("." + format, style = MaterialTheme.typography.labelMedium)
            }
            ListItem(leadingContent = { HandlerIcon(context, pkg) },
                trailingContent = { IconButton(onClick = { vm.preference(key, "") }) {
                    Icon(Icons.Default.Close, p.tr("Eşleştirmeyi kaldır", "Remove association"), tint = MaterialTheme.colorScheme.error)
                } }, supportingContent = { Text(pkg) }, content = { Text(name) })
            HorizontalDivider()
        }
    }
    if (add) {
        val format = ext.trim().trimStart('.').lowercase(Locale.ROOT)
        AlertDialog(onDismissRequest = { add = false }, title = { Text(p.tr("Dosya türü ekle", "Add file type")) },
            text = { OutlinedTextField(ext, { ext = it }, singleLine = true, label = { Text(p.tr("Uzantı", "Extension")) },
                placeholder = { Text("mcpack") }) },
            confirmButton = { TextButton(enabled = format.matches(Regex("[a-z0-9_+@-]{1,64}")), onClick = {
                ext = format
                add = false
                candidates = sampleHandlers(context, format)
                if (candidates.isNullOrEmpty()) {
                    candidates = null
                    vm.notice(p.tr("Bu uzantı için uygun uygulama bulunamadı. Dosya menüsünden de seçim yapabilirsiniz.",
                        "No viewer was found for this extension. You can also choose from the file menu."))
                }
            }) { Text(p.tr("Devam", "Continue")) } },
            dismissButton = { TextButton(onClick = { add = false }) { Text(p.tr("İptal", "Cancel")) } })
    }
    candidates?.let {
        ChoiceDialog(p.tr("Varsayılan uygulama", "Default app"), it, p.defaultPackage(ext), p.tr("İptal", "Cancel"),
            { candidates = null }) { pkg -> vm.preference("handler." + ext, pkg); candidates = null }
    }
}

private fun sampleHandlers(context: Context, ext: String): List<Pair<String, String>> =
    fileHandlers(context, Uri.parse("content://" + context.packageName + ".files/sample." + ext),
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream", ext)

internal fun fileHandlers(context: Context, uri: Uri, mime: String, ext: String): List<Pair<String, String>> {
    val pm = context.packageManager
    return viewIntents(uri, mime, ext).flatMap { pm.queryIntentActivities(it, PackageManager.MATCH_DEFAULT_ONLY) }
        .distinctBy { it.activityInfo.packageName }.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .sortedBy { it.second.lowercase(Locale.ROOT) }
}

@Composable
private fun HandlerIcon(context: Context, pkg: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, pkg) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(pkg).let { drawable ->
                android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, 72, 72)
                    drawable.draw(android.graphics.Canvas(it))
                }
            } }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) androidx.compose.foundation.Image(image.asImageBitmap(), null, Modifier.size(36.dp))
    else Icon(Icons.Default.Android, null, Modifier.size(36.dp))
}

