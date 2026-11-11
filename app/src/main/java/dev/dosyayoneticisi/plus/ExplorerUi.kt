package dev.dosyayoneticisi.plus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

internal enum class FileLayout { List, Compact, Grid }
internal data class ExplorerOptions(val layout: FileLayout, val order: Order, val descending: Boolean, val hidden: Boolean) {
    fun encode() = listOf(layout.name, order.name, descending.toString(), hidden.toString()).joinToString("|")
}
internal fun Preferences.explorer(folder: String?): ExplorerOptions {
    val values = (folder?.let { setting("explorer.folder." + it).ifBlank { null } } ?: setting("explorer.global")).split('|')
    return ExplorerOptions(FileLayout.entries.firstOrNull { it.name == values.getOrNull(0) } ?: FileLayout.List,
        Order.entries.firstOrNull { it.name == values.getOrNull(1) } ?: order,
        values.getOrNull(2)?.toBooleanStrictOrNull() ?: (order != Order.Name),
        values.getOrNull(3)?.toBooleanStrictOrNull() ?: hidden)
}

@Composable
internal fun ExplorerSettings(p: Preferences, initial: ExplorerOptions, canScope: Boolean, dismiss: () -> Unit,
    apply: (ExplorerOptions, Boolean) -> Unit) {
    var options by remember { mutableStateOf(initial) }
    var all by remember { mutableStateOf(!canScope) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(p.tr("Görünüm ve sıralama", "View and sorting")) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(p.tr("Görünüm", "View"), color = MaterialTheme.colorScheme.primary)
                listOf(p.tr("Liste", "List"), p.tr("Kompakt liste", "Compact list"), p.tr("Izgara", "Grid")).forEachIndexed { i, label ->
                    RadioRow(label, options.layout.ordinal == i) { options = options.copy(layout = FileLayout.entries[i]) }
                }
                HorizontalDivider()
                Text(p.tr("Sıralama", "Sort by"), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary)
                Order.entries.forEach { order ->
                    RadioRow(p.label(order), options.order == order) { options = options.copy(order = order) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!options.descending, { options = options.copy(descending = false) }, { Text(p.tr("Artan", "Ascending")) },
                        modifier = Modifier.weight(1f))
                    FilterChip(options.descending, { options = options.copy(descending = true) }, { Text(p.tr("Azalan", "Descending")) },
                        modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                CheckRow(p.tr("Gizli dosyaları göster", "Show hidden files"), options.hidden, true) { options = options.copy(hidden = it) }
                CheckRow(p.tr("Tüm klasörlere uygula", "Apply to all folders"), all, canScope) { all = it }
            }
        },
        confirmButton = { TextButton(onClick = { apply(options, all) }) { Text(p.tr("Uygula", "Apply")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(p.tr("İptal", "Cancel")) } })
}

@Composable
private fun RadioRow(title: String, selected: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).selectable(selected, role = Role.RadioButton, onClick = action),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Text(title, Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, enabled: Boolean, action: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox,
        onValueChange = action), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(title, Modifier.padding(start = 14.dp))
    }
}

@Composable
internal fun ExplorerBreadcrumb(vm: Manager, s: Screen, p: Preferences) {
    val first = s.stack.firstOrNull()
    val volume = first?.let { vm.files.volumeFor(it.uri, s.volumes) }
    Row(Modifier.fillMaxWidth().heightIn(min = 51.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.page(Page.Home) }) { Icon(Icons.Default.Home, p.label(Page.Home)) }
        val pathScroll = rememberScrollState()
        LaunchedEffect(s.stack.size, s.rootPath, pathScroll.maxValue) { pathScroll.scrollTo(pathScroll.maxValue) }
        Row(Modifier.weight(1f).horizontalScroll(pathScroll), verticalAlignment = Alignment.CenterVertically) {
            if (s.rootPath != null) {
                Text("/", Modifier.clickable { vm.rootBrowse(throughShizuku = s.shizukuPath) }.padding(8.dp))
                var path = ""
                s.rootPath.split('/').filter(String::isNotBlank).forEach { part ->
                    path += "/" + part
                    val target = path
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(17.dp))
                    Text(part, Modifier.clickable { vm.rootBrowse(target, s.shizukuPath) }.padding(8.dp), maxLines = 1)
                }
            } else s.stack.forEachIndexed { index, folder ->
                Icon(Icons.Default.ChevronRight, null, Modifier.size(17.dp))
                val label = if (index == 0 && volume != null) {
                    if (volume.primary) p.tr("Ana bellek", "Internal storage") else p.tr("SD kart", "SD card")
                } else folder.uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
                Text(label, Modifier.widthIn(max = 180.dp).clickable { vm.goTo(index) }.padding(8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (volume != null && volume.total > 0) {
            val ratio = (1 - volume.free.toDouble() / volume.total).toFloat().coerceIn(0f, 1f)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CircularProgressIndicator(progress = { ratio }, Modifier.size(17.dp), strokeWidth = 2.dp)
                    Text(NumberFormat.getPercentInstance(java.util.Locale.forLanguageTag(if (p.english) "en" else "tr")).format(ratio),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
internal fun ActionDialog(title: String, cancel: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), content = content) },
        confirmButton = { TextButton(onClick = close) { Text(cancel) } })
}

@Composable
internal fun DialogAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = action).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
