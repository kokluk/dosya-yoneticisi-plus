package dev.dosyayoneticisi.plus

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun Browser(vm: Manager, s: Screen, p: Preferences,
    connect: () -> Unit, broad: () -> Unit, openRoot: () -> Unit, confirm: (Prompt) -> Unit) {
    val ctx = LocalContext.current
    val current = s.stack.lastOrNull()
    val directory = if (s.rootPath == null && s.category == null) current else null
    val options = p.explorer(current?.uri?.toString())
    var actions by remember { mutableStateOf(false) }
    var assign by remember { mutableStateOf<Pair<Entry, List<Pair<String, String>>>?>(null) }
    var audio by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }
    val entries = remember(s.entries, s.category, s.indexed, s.searchResults, options) {
        val source = s.searchResults ?: when (val group = s.category) {
            null -> s.entries
            else -> s.indexed.orEmpty().filter { vm.files.matches(it, group) }
        }
        val criterion = when (options.order) {
            Order.Name -> compareBy<Entry> { it.name.lowercase() }
            Order.Date -> compareBy<Entry> { it.modified }
            Order.Size -> compareBy<Entry> { it.size }
        }
        source.filter { options.hidden || !it.name.startsWith('.') }.sortedWith(compareBy<Entry> { !it.folder }
            .then(if (options.descending) criterion.reversed() else criterion))
    }
    val selection = entries.filter { it.uri in s.selected }
    fun deletion(items: List<Entry>) {
        if (p.recycle && !p.enabled("recycleConfirm")) { vm.delete(items); return }
        confirm(Prompt(
        if (p.recycle) p.tr("Geri dönüşüm kutusuna taşı", "Move to recycle bin") else p.tr("Kalıcı olarak sil", "Delete permanently"),
        items.joinToString("\n", limit = 8) { it.name }) { vm.delete(items) })
    }
    fun properties(item: Entry) {
        vm.notice(listOf(item.name, item.mime, item.uri.toString(),
            if (item.folder) p.tr("Klasör", "Folder") else p.fileSize(ctx, item.size),
            if (item.modified > 0) DateFormat.getDateTimeInstance().format(Date(item.modified)) else "").filter { it.isNotBlank() }.joinToString("\n"))
    }
    ExplorerBreadcrumb(vm, s, p)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (s.query != null || s.category != null || current != null || s.rootPath != null || selection.isNotEmpty()) {
            IconButton(enabled = !s.busy, onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, p.tr("Geri", "Back")) }
        }
        Text(if (selection.isNotEmpty()) "${selection.size} " + p.tr("seçili", "selected")
            else s.rootPath ?: current?.name ?: s.category?.let(p::label) ?: p.tr("Konumlar", "Locations"),
            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (directory != null || selection.isNotEmpty()) {
            Box {
                IconButton(enabled = !s.busy, onClick = { actions = true }) { Icon(Icons.Default.MoreHoriz, p.tr("Dosya işlemleri", "File actions")) }
                if (actions) ActionDialog(p.tr("Dosya işlemleri", "File actions"), p.tr("İptal", "Cancel"), { actions = false }) {
                    @Composable
                    fun action(tr: String, en: String, run: () -> Unit) {
                        DialogAction(p.tr(tr, en)) { actions = false; run() }
                    }
                    if (selection.isEmpty() && directory != null) action("Yeni klasör", "New folder") {
                        confirm(Prompt(p.tr("Yeni klasör", "New folder"), initial = "") { vm.create(directory, it) })
                    }
                    if (selection.isNotEmpty()) {
                        action("Kopyala", "Copy") { vm.stage(selection, false) }
                        action("Taşı", "Move") { vm.stage(selection, true) }
                        action("Sil", "Delete") { deletion(selection) }
                        if (directory != null) action("ZIP oluştur", "Create ZIP") {
                            confirm(Prompt(p.tr("ZIP dosyasının adı", "ZIP file name"), initial = "Archive.zip") {
                                vm.zip(directory, it, selection)
                            })
                        }
                        selection.singleOrNull()?.let { item ->
                            action("Özellikler", "Properties") { properties(item) }
                            if (!item.folder) action("Paylaş", "Share") { vm.launch(item, true, callback = ctx::startActivity) }
                            if (p.enabled("bookmarkMenu")) {
                                val saved = item.uri.toString() in p.setting("bookmarks").lineSequence()
                                action(if (saved) "Yer imini kaldır" else "Yer imi ekle",
                                    if (saved) "Remove bookmark" else "Add bookmark") { vm.bookmark(item.uri) }
                            }
                            if (!item.folder && extension(item.name).isNotBlank()) action("Varsayılan uygulama", "Default app") {
                                runCatching { fileHandlers(ctx, vm.files.shareUri(item), item.mime, extension(item.name)) }
                                    .onSuccess { matches ->
                                        if (matches.isEmpty()) vm.notice(p.tr("Uygun uygulama bulunamadı.", "No compatible app found."))
                                        else assign = item to matches
                                    }.onFailure { vm.notice(it.message) }
                            }
                            if (!item.folder && p.enabled("openWithMenu")) action("Farklı aç", "Open with") {
                                vm.launch(item, false, chooser = true, callback = ctx::startActivity)
                            }
                            if (p.enabled("hiddenMenu", false)) {
                                val hidden = item.name.startsWith('.')
                                action(if (hidden) "Gizlemeyi kaldır" else "Gizle", if (hidden) "Unhide" else "Hide") {
                                    vm.rename(item, if (hidden) item.name.trimStart('.') else "." + item.name)
                                }
                            }
                            if (p.enabled("shortcutMenu", false)) action("Ana ekrana ekle", "Add to home screen") {
                                vm.pinLocation(item.uri, item.name)
                            }
                            action("Yeniden adlandır", "Rename") {
                                confirm(Prompt(p.tr("Yeniden adlandır", "Rename"), initial = item.name) { vm.rename(item, it) })
                            }
                            if (item.name.endsWith(".zip", true) && item.parent != null) action("ZIP çıkar", "Extract ZIP") {
                                vm.unzip(item, item.parent)
                            }
                        }
                    }
                }
            }
        }
    }
    if (s.clipboard.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(p.tr("Panoda", "Clipboard") + " · " + s.clipboard.size, Modifier.weight(1f))
            TextButton(enabled = !s.busy, onClick = { vm.stage(emptyList(), false) }) { Text(p.tr("Temizle", "Clear")) }
            Button(enabled = directory != null && !s.busy, onClick = { directory?.let(vm::paste) }) {
                Text(p.tr("Yapıştır", "Paste"))
            }
        }
    }
    if (s.query != null) {
        OutlinedTextField(s.query, vm::query, Modifier.fillMaxWidth(), enabled = !s.busy, singleLine = true,
            label = { Text(p.tr("Dosya veya klasör adı", "File or folder name")) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            trailingIcon = { IconButton(enabled = !s.busy && s.query.isNotBlank(), onClick = vm::search) {
                Icon(Icons.Default.Search, p.tr("Ara", "Search"))
            } })
    }

    when {
        s.rootPath != null && s.query == null -> LazyColumn(Modifier.fillMaxWidth()) {
            items(s.rootEntries.filter { options.hidden || !it.name.startsWith('.') }, key = { it.path }) { item ->
                var options by remember { mutableStateOf(false) }
                ListItem(content = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(if (item.folder) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                    modifier = Modifier.combinedClickable(enabled = !s.busy,
                        onClick = { if (item.folder) vm.rootBrowse(item.path, s.shizukuPath) else vm.openPrivileged(item) }, onLongClick = { options = true }),
                    trailingContent = {
                        Box {
                            IconButton(enabled = !s.busy, onClick = { options = true }) { Icon(Icons.Default.MoreVert, p.tr("İşlemler", "Actions")) }
                            if (options) ActionDialog(item.name, p.tr("İptal", "Cancel"), { options = false }) {
                                DialogAction(p.tr("Özellikler", "Properties")) {
                                    options = false
                                    vm.rootDetails(item)
                                }
                                DialogAction(p.tr("İzinleri değiştir", "Change permissions")) {
                                    options = false
                                    confirm(Prompt(p.tr("İzin değişikliğini onayla", "Confirm permission change"),
                                        p.tr("Sistem çalışmasını etkileyebilir. Sekizlik mod girin.", "This can affect the system. Enter an octal mode.") + "\n" + item.path,
                                        initial = "") { vm.chmod(item, it) })
                                }
                            }
                        }
                    })
            }
        }
        s.category == Category.Trash && s.query == null -> LazyColumn(Modifier.fillMaxWidth()) {
            if (s.deleted.isEmpty()) item { Hint(p.tr("Geri dönüşüm kutusu boş.", "The recycle bin is empty.")) }
            items(s.deleted, key = { it.id.path }) { item ->
                ListItem(content = { Text(item.name) }, supportingContent = { Text(p.fileSize(ctx, item.size)) },
                    trailingContent = {
                        Row {
                            IconButton(enabled = !s.busy, onClick = { vm.restore(item) }) {
                                Icon(Icons.Default.Restore, p.tr("Geri yükle", "Restore"))
                            }
                            IconButton(enabled = !s.busy, onClick = {
                                confirm(Prompt(p.tr("Kalıcı olarak sil", "Delete permanently"), item.name) { vm.purge(item) })
                            }) { Icon(Icons.Default.Delete, p.tr("Kalıcı olarak sil", "Delete permanently")) }
                        }
                    })
            }
        }
        current == null && s.category == null && s.query == null -> LazyColumn(Modifier.fillMaxWidth()) {
            item {
                ListItem(content = { Text(p.tr("Klasör bağla", "Connect folder")) }, leadingContent = { Icon(Icons.Default.CreateNewFolder, null) },
                    modifier = Modifier.combinedClickable(enabled = !s.busy, onClick = connect))
                if (android.os.Build.VERSION.SDK_INT >= 30 && !vm.files.broadAccess) ListItem(
                    content = { Text(p.tr("Paylaşılan depolamayı gez", "Browse shared storage")) },
                    leadingContent = { Icon(Icons.Default.Storage, null) },
                    modifier = Modifier.combinedClickable(enabled = !s.busy, onClick = broad))
                if (p.rootRequested || s.rootEnabled) ListItem(content = { Text(p.tr("Root gezgini", "Root browser")) },
                    leadingContent = { Icon(Icons.Default.Security, null) },
                    modifier = Modifier.combinedClickable(enabled = !s.busy, onClick = openRoot))
            }
            items(s.roots, key = { it.uri.toString() }) { root ->
                ListItem(content = { Text(root.name ?: root.uri.toString()) }, leadingContent = { Icon(Icons.Default.Folder, null) },
                    modifier = Modifier.combinedClickable(enabled = !s.busy, onClick = { vm.open(root, true) }))
            }
        }
        entries.isEmpty() && !s.busy -> {
            Hint(when {
                s.query != null && s.searchResults == null -> p.tr("Aramayı başlatmak için bir ad girin.", "Enter a name to search.")
                s.roots.isEmpty() -> p.tr("Dosyaları listelemek için klasör bağlayın.", "Connect a folder to list files.")
                else -> p.tr("Dosya bulunamadı.", "No files found.")
            })
            if (s.roots.isEmpty()) TextButton(onClick = connect) { Text(p.tr("Klasör bağla", "Connect folder")) }
        }
        else -> {
            fun open(item: Entry) {
                if (selection.isNotEmpty()) vm.select(item)
                else if (item.folder) vm.open(item.doc, s.query != null)
                else if (item.mime.startsWith("audio/") && p.setting("musicPlayer") == "internal") {
                    try { audio = vm.files.shareUri(item) to item.name }
                    catch (_: Exception) { vm.notice(p.tr("Ses dosyası açılamadı.", "Could not open the audio file.")) }
                } else vm.launch(item, false, callback = ctx::startActivity)
            }
            if (options.layout == FileLayout.Grid) LazyVerticalGrid(GridCells.Adaptive(100.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.uri.toString() }) { item ->
                    val selected = item.uri in s.selected
                    Surface(shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 92.dp).combinedClickable(enabled = !s.busy,
                            onClick = { open(item) }, onLongClick = { vm.select(item) }).padding(9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            EntryIcon(vm.files, item, p, selected)
                            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else LazyColumn {
                items(entries, key = { it.uri.toString() }) { item ->
                    val selected = item.uri in s.selected
                    if (options.layout == FileLayout.Compact) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp)
                            .combinedClickable(enabled = !s.busy, onClick = { open(item) }, onLongClick = { vm.select(item) })
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(29.dp)) { EntryIcon(vm.files, item, p, selected) }
                            Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!item.folder) Text(p.fileSize(ctx, item.size), style = MaterialTheme.typography.labelSmall)
                        }
                    } else ListItem(content = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { EntryIcon(vm.files, item, p, selected) },
                        supportingContent = {
                            if (!item.folder) Text(p.fileSize(ctx, item.size))
                            else {
                                val count by produceState<Int?>(null, item.uri, item.modified) {
                                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching { item.doc.listFiles().size }.getOrNull()
                                    }
                                }
                                Text(count?.let { it.toString() + " " + p.tr("öğe", "items") } ?: p.tr("Klasör", "Folder"))
                            }
                        },
                        trailingContent = { Text(p.fileDate(item.modified), style = MaterialTheme.typography.labelSmall) },
                        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                        modifier = Modifier.combinedClickable(enabled = !s.busy, onClick = { open(item) }, onLongClick = { vm.select(item) }))
                    HorizontalDivider()
                }
            }
        }
    }
    assign?.let { (item, options) ->
        val ext = extension(item.name)
        ChoiceDialog(p.tr("Varsayılan uygulama", "Default app") + " · ." + ext,
            options, p.defaultPackage(ext), p.tr("İptal", "Cancel"), { assign = null }) {
            vm.preference("handler." + ext, it)
            assign = null
        }
    }
    audio?.let { (uri, name) -> AudioPreview(uri, name, p) { audio = null } }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun PreferencesPanel(vm: Manager, s: Screen, p: Preferences, confirm: (Prompt) -> Unit, openDefaults: () -> Unit) {
    val context = LocalContext.current
    val shizuku by vm.shizuku.status.collectAsState()
    var pendingThreshold by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("off") }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.preference("storageAlert", if (granted) pendingThreshold else "off")
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        @Composable
        fun toggle(title: String, checked: Boolean, action: (Boolean) -> Unit) {
            ListItem(content = { Text(title) },
                trailingContent = { Switch(checked, action, enabled = !s.busy) })
        }
        @Composable
        fun choices(title: String, values: List<Pair<String, String>>, selected: String, action: (String) -> Unit) {
            var expanded by remember { mutableStateOf(false) }
            ListItem(content = { Text(title) },
                supportingContent = { Text(values.firstOrNull { it.first == selected }?.second.orEmpty()) },
                trailingContent = { Icon(Icons.Default.ArrowDropDown, null) },
                modifier = Modifier.combinedClickable(enabled = !s.busy, onClick = { expanded = true }))
            if (expanded) ChoiceDialog(title, values, selected, p.tr("İptal", "Cancel"), { expanded = false }) {
                expanded = false
                action(it)
            }
        }
        @Composable
        fun section(tr: String, en: String) {
            HorizontalDivider()
            Text(p.tr(tr, en), Modifier.padding(start = 16.dp, top = 8.dp),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        }
        @Composable
        fun row(title: String, subtitle: String? = null, onClick: () -> Unit) {
            ListItem(modifier = Modifier.combinedClickable(enabled = !s.busy, onClick = onClick),
                supportingContent = subtitle?.let { value -> { Text(value) } }, content = { Text(title) })
        }
        fun option(key: String, value: Boolean) = vm.preference(key, value.toString())
        section("Genel ayarlar", "General settings")
        row(p.tr("Varsayılan uygulamalar", "Default apps"), onClick = openDefaults)
        row(p.tr("Yüklü uygulamalar", "Installed apps")) { vm.category(Category.Apps) }
        choices(p.tr("Tema", "Theme"), listOf("system" to p.tr("Sistem", "System"), "light" to p.tr("Açık", "Light"),
            "dark" to p.tr("Koyu", "Dark")), p.theme) { vm.preference("theme", it) }
        if (android.os.Build.VERSION.SDK_INT >= 31) toggle(p.tr("Material You dinamik renkler", "Material You dynamic colors"), p.dynamic) {
            vm.preference("dynamic", it.toString())
        }
        choices(p.tr("Dil", "Language"), listOf("false" to "Türkçe", "true" to "English"), p.english.toString()) { vm.preference("english", it) }
        toggle(p.tr("Gizli dosyaları göster", "Show hidden files"), p.hidden) { vm.preference("hidden", it.toString()) }
        choices(p.tr("Varsayılan sıralama", "Default sort"), Order.entries.map { it.name to p.label(it) }, p.order.name) {
            vm.preference("order", it)
        }
        section("Geçici dosyalar", "Temporary files")
        row(p.tr("Geçici APK yedeklerini temizle", "Clear temporary APK backups")) {
            confirm(Prompt(p.tr("Geçici dosyaları temizle", "Clear temporary files")) { vm.clearTemporary() })
        }
        section("Geri dönüşüm kutusu ayarları", "Recycle bin settings")
        toggle(p.tr("Geri dönüşüm kutusu onayı", "Confirm recycling"), p.enabled("recycleConfirm")) {
            option("recycleConfirm", it)
        }
        toggle(p.tr("Geri dönüşüm kutusunu kullan", "Use recycle bin"), p.recycle) { vm.preference("recycle", it.toString()) }
        if (s.deleted.isNotEmpty()) TextButton(enabled = !s.busy, onClick = {
            confirm(Prompt(p.tr("Geri dönüşüm kutusunu boşalt", "Empty recycle bin"),
                p.tr("İçerik kalıcı olarak silinecek.", "Contents will be permanently deleted.")) { vm.emptyTrash() })
        }) { Text(p.tr("Kutuyu boşalt", "Empty recycle bin")) }
        section("Bildirim ayarları", "Notifications")
        choices(p.tr("Bellek dolu uyarısı", "Storage full alert"),
            listOf("off" to p.tr("Kapalı", "Off"), "90" to "90%", "95" to "95%", "98" to "98%"),
            p.setting("storageAlert", "off")) {
            if (it != "off" && android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingThreshold = it
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else vm.preference("storageAlert", it)
        }
        section("Gelişmiş ayarlar", "Advanced settings")
        toggle(p.tr("Menü simgesini her zaman göster", "Always show menu icon"), p.enabled("alwaysNav")) { option("alwaysNav", it) }
        toggle(p.tr("USB bağlantısını algıla", "Detect USB storage"), p.enabled("usbDetect")) { option("usbDetect", it) }
        toggle(p.tr("Sistem depolamasını göster", "Show system storage"), p.enabled("systemVolume", false)) { option("systemVolume", it) }
        choices(p.tr("Dosya boyutu birimi", "File size unit"),
            listOf("auto" to p.tr("Otomatik", "Auto")) + listOf("B", "KB", "MB", "GB").map { it to it },
            p.setting("sizeUnit", "auto")) { vm.preference("sizeUnit", it) }
        choices(p.tr("Tarih biçimi", "Date format"),
            listOf("medium", "numeric", "iso").map { format ->
                format to p.copy(options = p.options + ("dateFormat" to format)).fileDate(System.currentTimeMillis())
            },
            p.setting("dateFormat", "medium")) { vm.preference("dateFormat", it) }
        choices(p.tr("Müzik çalar", "Music player"),
            listOf("external" to p.tr("Sistem uygulaması", "System app"), "internal" to p.tr("Yerleşik oynatıcı", "Built-in player")),
            p.setting("musicPlayer", "external")) { vm.preference("musicPlayer", it) }
        toggle(p.tr("Resim ve video önizlemeleri", "Image and video previews"), p.enabled("previews")) { option("previews", it) }
        toggle(p.tr("İlişkili uygulama simgeleri", "Associated app icons"), p.enabled("associatedIcons")) { option("associatedIcons", it) }
        section("Gelişmiş menüler", "Advanced menus")
        toggle(p.tr("Yer imi ekle", "Add bookmark"), p.enabled("bookmarkMenu")) { option("bookmarkMenu", it) }
        toggle(p.tr("Ana ekrana ekle", "Add to home screen"), p.enabled("shortcutMenu", false)) { option("shortcutMenu", it) }
        toggle(p.tr("Gizle / Gizlemeyi kaldır", "Hide / Unhide"), p.enabled("hiddenMenu", false)) { option("hiddenMenu", it) }
        toggle(p.tr("Farklı aç", "Open with"), p.enabled("openWithMenu")) { option("openWithMenu", it) }
        section("Ayrıcalıklı erişim", "Privileged access")
        row(p.tr("Shizuku", "Shizuku"), when (shizuku) {
            ShizukuStatus.Offline -> p.tr("Shizuku çalışmıyor", "Shizuku is stopped")
            ShizukuStatus.Ready -> p.tr("Bağlanmak için dokunun", "Tap to connect")
            ShizukuStatus.Connecting -> p.tr("Bağlanıyor", "Connecting")
            ShizukuStatus.Connected -> p.tr("Bağlı · Bağlantıyı kes", "Connected · Disconnect")
        }) {
            if (shizuku == ShizukuStatus.Connected || shizuku == ShizukuStatus.Connecting) vm.shizuku.disconnect()
            else vm.shizuku.connect()
        }
        if (shizuku == ShizukuStatus.Connected) row(p.tr("Shizuku gezginini aç", "Open Shizuku browser")) {
            vm.rootBrowse(throughShizuku = true)
        }
        toggle(p.tr("Root erişimi", "Root access"), (p.rootRequested || s.rootEnabled)) { enabled ->
            if (!enabled) vm.rootDisable()
            else confirm(Prompt(p.tr("Root erişimi iste", "Request root access"),
                p.tr("Root yöneticinizde bu uygulama için yetki verin.", "Grant access for this app in your root manager.")) { vm.rootEnable() })
        }
        Hint(if (vm.rootDetected) p.tr("su yürütülebilir dosyası algılandı.", "su executable detected.")
            else p.tr("PATH üzerinde su algılanmadı.", "su was not detected on PATH."))
        section("Hakkında", "About")
        TextButton(onClick = {
            vm.notice(p.tr("Dosya Yöneticisi + · 1.3\nAndroid 10 ve sonrası\nKotlin · Jetpack Compose",
                "File Manager + · 1.3\nAndroid 10 and later\nKotlin · Jetpack Compose"))
        }) { Text(p.tr("Uygulama hakkında", "About")) }
    }
}












