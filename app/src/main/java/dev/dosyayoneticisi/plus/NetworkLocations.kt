package dev.dosyayoneticisi.plus

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class ExtraPage { Defaults, Cloud, Remote }
private data class Provider(val label: String, val color: Long, val packages: Set<String>)
private val providers = listOf(
    Provider("Dropbox", 0xFF0061FF, setOf("com.dropbox.android")),
    Provider("Google Drive", 0xFF168F54, setOf("com.google.android.apps.docs")),
    Provider("OneDrive", 0xFF1267BC, setOf("com.microsoft.skydrive")),
    Provider("Box", 0xFF0061D5, setOf("com.box.android")),
    Provider("Nextcloud", 0xFF0082C9, setOf("com.nextcloud.client")),
    Provider("pCloud", 0xFF128A94, setOf("com.pcloud.pcloud")),
    Provider("Yandex", 0xFF436BD7, setOf("ru.yandex.disk"))
)
private val protocols = listOf("LAN", "SMB", "FTP", "SFTP", "WebDAV")

@Composable
internal fun NetworkLocations(vm: Manager, p: Preferences, cloud: Boolean, add: Boolean,
    closeAdd: () -> Unit, openAdd: () -> Unit, openDocument: (Uri) -> Unit) {
    val context = LocalContext.current
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var protocol by rememberSaveable { mutableStateOf<String?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    val prefix = if (cloud) "cloud." else "remote."
    val saved = remember(p.options, prefix) {
        p.options.filter { it.key.startsWith(prefix) && it.value.isNotBlank() }.entries.sortedBy { it.key }
    }
    fun save(uri: Uri) {
        val selected = providers.firstOrNull { it.label == provider }
        val authority = uri.authority
        val owner = authority?.let { context.packageManager.resolveContentProvider(it, 0)?.packageName }
        if (cloud && (selected == null || (owner !in selected.packages && selected.packages.none {
                authority?.startsWith(it + ".") == true
            }))) {
            vm.notice(p.tr("Android seçicisinde seçtiğiniz bulut sağlayıcısının kendi konumunu açın.",
                "Open the selected cloud provider in the Android picker."))
            return
        }
        if (!cloud && authority in setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents",
                "com.android.providers.media.documents")) {
            vm.notice(p.tr("Android seçicisinden bir ağ sağlayıcısı seçin.", "Choose a network provider in the Android picker."))
            return
        }
        val label = selected?.label ?: protocol ?: "LAN"
        vm.saveNetwork(prefix, label, uri)
        provider = null
        protocol = null
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::save) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::save) }

    LazyColumn {
        item {
            ListItem(modifier = Modifier.clickable(onClick = openAdd),
                leadingContent = { Icon(Icons.Default.Add, null) },
                content = { Text(if (cloud) p.tr("Bir bulut konumu ekle", "Add a cloud location")
                    else p.tr("Uzak bir konum ekle", "Add a remote location"), fontWeight = FontWeight.Bold) })
            HorizontalDivider()
        }
        items(saved, key = { it.key }) { item ->
            val uri = Uri.parse(item.value)
            val title = item.key.removePrefix(prefix).substringBefore('|')
            ListItem(modifier = Modifier.clickable {
                if (uri.scheme == "content") openDocument(uri) else openRemote(context, vm, p, uri)
            }, leadingContent = { Icon(if (cloud) Icons.Default.Cloud else Icons.Default.FolderOpen, null) },
                supportingContent = { Text(uri.host ?: uri.authority.orEmpty(), maxLines = 1) },
                trailingContent = { IconButton(onClick = { vm.preference(item.key, "") }) {
                    Icon(Icons.Default.Close, p.tr("Konumu kaldır", "Remove location"))
                } }, content = { Text(title) })
            HorizontalDivider()
        }
    }
    if (add) {
        AlertDialog(onDismissRequest = closeAdd, shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
            title = { Text(if (cloud) p.tr("Bulut Ekle", "Add Cloud") else p.tr("Uzak Konum Ekle", "Add Remote Location")) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (cloud) providers.forEach { item ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 49.dp).clickable {
                            closeAdd(); provider = item.label
                        }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.Cloud, null, Modifier.size(26.dp), tint = Color(item.color))
                            Text(item.label, fontWeight = FontWeight.SemiBold)
                        }
                    } else protocols.forEach { item ->
                        ListItem(modifier = Modifier.clickable { closeAdd(); protocol = item; address = "" },
                            leadingContent = { Icon(Icons.Default.FolderOpen, null, tint = Color(0xFFDF9F48)) },
                            content = { Text(if (item == "LAN") p.tr("Yerel Ağ", "Local Network") else item,
                                fontWeight = FontWeight.SemiBold) })
                    }
                }
            }, confirmButton = {})
    }
    provider?.let { name ->
        AlertDialog(onDismissRequest = { provider = null }, title = { Text(name) },
            text = { Text(p.tr("Android seçicisinde bu sağlayıcıyı seçin. Sağlayıcı klasör seçimine izin vermiyorsa dosya bağlayabilirsiniz.",
                "Choose this provider in the Android picker. If it does not support folder selection, connect a file.")) },
            confirmButton = { TextButton(onClick = { tree.launch(null) }) { Text(p.tr("Klasör", "Folder")) } },
            dismissButton = { TextButton(onClick = { document.launch(arrayOf("*/*")) }) { Text(p.tr("Dosya", "File")) } })
    }
    protocol?.let { kind ->
        val uri = parseEndpoint(address, kind)
        AlertDialog(onDismissRequest = { protocol = null },
            title = { Text(if (kind == "LAN") p.tr("Yerel Ağ", "Local Network") else kind) },
            text = {
                Column {
                    Text(p.tr("Ağ konumunu bir Android belge sağlayıcısından bağlayın veya adresi uyumlu uygulamada açın.",
                        "Connect a location through an Android document provider or open the address in a compatible app."))
                    if (kind != "LAN") OutlinedTextField(address, { address = it }, singleLine = true,
                        label = { Text(p.tr("Sunucu adresi", "Server address")) },
                        placeholder = { Text(if (kind == "WebDAV") "https://server.example/dav/" else kind.lowercase() + "://server.example/") })
                }
            },
            confirmButton = {
                if (kind != "LAN") TextButton(enabled = uri != null, onClick = {
                    uri?.let {
                        if (openRemote(context, vm, p, it)) {
                            vm.preference(prefix + kind + "|" + it, it.toString())
                            protocol = null
                        }
                    }
                }) { Text(p.tr("Aç ve kaydet", "Open and save")) }
            },
            dismissButton = { TextButton(onClick = { tree.launch(null) }) { Text(p.tr("Sağlayıcı seç", "Choose provider")) } })
    }
}

private fun parseEndpoint(value: String, kind: String): Uri? {
    val address = value.trim()
    val parsed = runCatching { java.net.URI(address) }.getOrNull() ?: return null
    val schemes = if (kind == "WebDAV") setOf("https", "webdav", "webdavs") else setOf(kind.lowercase(java.util.Locale.ROOT))
    if (parsed.scheme?.lowercase(java.util.Locale.ROOT) !in schemes || parsed.host.isNullOrBlank() ||
        parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) return null
    return Uri.parse(address).let { if (kind == "WebDAV" && parsed.scheme.equals("https", true)) it.buildUpon().scheme("webdavs").build() else it }
}

private fun openRemote(context: Context, vm: Manager, p: Preferences, uri: Uri): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    true
} catch (_: android.content.ActivityNotFoundException) {
    vm.notice(p.tr("Bu protokol için uyumlu bir Android uygulaması bulunamadı.",
        "No compatible Android app was found for this protocol."))
    false
}


