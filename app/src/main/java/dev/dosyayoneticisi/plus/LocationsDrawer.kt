package dev.dosyayoneticisi.plus

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationsDrawer(vm: Manager, s: Screen, p: Preferences, width: Dp,
    close: () -> Unit, connect: () -> Unit, volume: (Volume) -> Unit, root: () -> Unit, network: (ExtraPage) -> Unit) {
    val context = LocalContext.current
    val shizuku by vm.shizuku.status.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    ModalDrawerSheet(Modifier.width(width).fillMaxHeight(), drawerShape = androidx.compose.ui.graphics.RectangleShape,
        drawerContainerColor = MaterialTheme.colorScheme.surface) {
        PrimaryTabRow(tab) {
            val labels = listOf(p.tr("Konumlar", "Locations"), p.tr("Yer imleri", "Bookmarks"), p.tr("Geçmiş", "History"))
            listOf(Icons.Default.Folder, Icons.Default.Star, Icons.Default.History).forEachIndexed { i, icon ->
                Tab(tab == i, { tab = i }, icon = { Icon(icon, labels[i]) })
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (tab == 0) {
                item { DrawerRow(p.tr("Ana ekran", "Home"), Icons.Default.Home) { close(); vm.page(Page.Home) } }
                items(s.volumes, key = { it.path.path }) { item ->
                    val ratio = if (item.total > 0) (1 - item.free.toDouble() / item.total).toFloat().coerceIn(0f, 1f) else 0f
                    DrawerRow(if (item.primary) p.tr("Ana bellek", "Internal storage") else p.tr("SD kart", "SD card"),
                        if (item.primary) Icons.Default.Storage else Icons.Default.SdCard,
                        "${(ratio * 100).toInt()}%", ratio) { close(); volume(item) }
                }
                item {
                    DrawerRow(p.label(Category.Trash), Icons.Default.DeleteOutline,
                        p.fileSize(context, s.deleted.sumOf { it.size })) { close(); vm.category(Category.Trash) }
                    DrawerRow(p.label(Category.Cloud), Icons.Default.Cloud) { close(); network(ExtraPage.Cloud) }
                    DrawerRow(p.tr("Uzak", "Remote"), Icons.Default.FolderOpen) { close(); network(ExtraPage.Remote) }
                    DrawerRow(p.tr("Klasör bağla", "Connect folder"), Icons.Default.CreateNewFolder) { close(); connect() }
                    if (p.rootRequested || s.rootEnabled) DrawerRow(p.tr("Root gezgini", "Root browser"),
                        Icons.Default.Security) { close(); root() }
                    if (shizuku == ShizukuStatus.Connected) DrawerRow(p.tr("Shizuku gezgini", "Shizuku browser"),
                        Icons.Default.FolderOpen) { close(); vm.rootBrowse(throughShizuku = true) }
                }
            } else {
                val locations = if (tab == 1) p.setting("bookmarks").lineSequence()
                    .filter(String::isNotBlank).map(Uri::parse).toList() else s.history
                if (locations.isEmpty()) item { Text(
                    if (tab == 1) p.tr("Henüz yer imi yok.", "No bookmarks yet.")
                    else p.tr("Henüz konum açılmadı.", "No locations opened yet."), Modifier.padding(16.dp)) }
                items(locations, key = { it.toString() }) { location ->
                    DrawerRow(location.lastPathSegment?.substringAfterLast('/') ?: location.toString(),
                        if (tab == 1) Icons.Default.Star else Icons.Default.History) { close(); vm.openLocation(location) }
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    detail: String? = null, ratio: Float? = null, click: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = click),
        leadingContent = { Icon(icon, null, Modifier.size(29.dp), tint = MaterialTheme.colorScheme.primary) },
        supportingContent = ratio?.let { progress -> { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) } },
        trailingContent = detail?.let { { Text(it) } },
        content = { Text(title, fontWeight = FontWeight.SemiBold) })
    HorizontalDivider()
}


