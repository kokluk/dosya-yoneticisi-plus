package dev.dosyayoneticisi.plus

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Application.store by preferencesDataStore("settings")
enum class Page { Home, Files, Settings }
data class Preferences(
    val theme: String = "system", val english: Boolean = false, val dynamic: Boolean = false,
    val hidden: Boolean = false, val order: Order = Order.Name,
    val recycle: Boolean = true, val rootRequested: Boolean = false,
    val options: Map<String, String> = emptyMap()
) {
    fun setting(key: String, default: String = "") = options[key] ?: default
    fun enabled(key: String, default: Boolean = true) = setting(key, default.toString()) == "true"
}
data class Screen(
    val page: Page = Page.Home,
    val viewer: Viewer? = null,
    val apps: List<InstalledApp> = emptyList(),
    val history: List<Uri> = emptyList(),
    val shizukuPath: Boolean = false,
    val volumes: List<Volume> = emptyList(),
    val roots: List<DocumentFile> = emptyList(),
    val stack: List<DocumentFile> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val indexed: List<Entry>? = null,
    val category: Category? = null,
    val deleted: List<Deleted> = emptyList(),
    val selected: Set<Uri> = emptySet(),
    val clipboard: List<Entry> = emptyList(),
    val cut: Boolean = false,
    val query: String? = null,
    val searchResults: List<Entry>? = null,
    val rootEnabled: Boolean = false,
    val rootPath: String? = null,
    val rootEntries: List<RootEntry> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null
)

class Manager(private val app: Application) : AndroidViewModel(app) {
    val files = Files(app)
    private val root = RootFiles(app.cacheDir)
    val rootDetected get() = root.detected()
    val state = MutableStateFlow(Screen())
    val preferences = app.store.data.map { stored ->
        fun get(key: String) = stored[stringPreferencesKey(key)]
        Preferences(get("theme") ?: "system", get("english") == "true", get("dynamic") == "true",
            get("hidden") == "true",
            Order.entries.firstOrNull { it.name == get("order") } ?: Order.Name, get("recycle") != "false", get("root") == "true", stored.asMap().mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }.toMap())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())

    private val installed = InstalledApps(app)
    val shizuku = ShizukuFiles(app, ::notice)
    init {
        viewModelScope.launch(Dispatchers.IO) {
            java.io.File(app.cacheDir, "privileged-view").listFiles().orEmpty().forEach { it.delete() }
        }
        reload()
    }

    override fun onCleared() { shizuku.close(); super.onCleared() }

    fun saveNetwork(prefix: String, label: String, uri: Uri) = task {
        files.persist(uri)
        app.store.edit { it[stringPreferencesKey(prefix + label + "|" + uri)] = uri.toString() }
    }

    internal fun saveExplorer(folder: String?, options: ExplorerOptions, all: Boolean) {
        viewModelScope.launch {
            app.store.edit { prefs ->
                if (all || folder == null) {
                    prefs.asMap().keys.filter { it.name.startsWith("explorer.folder.") }.forEach { prefs.remove(it) }
                    prefs[stringPreferencesKey("explorer.global")] = options.encode()
                    prefs[stringPreferencesKey("order")] = options.order.name
                    prefs[stringPreferencesKey("hidden")] = options.hidden.toString()
                } else prefs[stringPreferencesKey("explorer.folder." + folder)] = options.encode()
            }
        }
    }

    fun goTo(index: Int) = task {
        val stack = state.value.stack
        require(index in stack.indices)
        mutate { it.copy(stack = stack.take(index + 1), query = null, selected = emptySet(), searchResults = null) }
        refresh()
    }

    fun openLocation(uri: Uri) = task {
        val doc = checkNotNull(files.resolveLocation(uri)) { preferences.value.tr("Konuma erişilemiyor.", "Location unavailable.") }
        if (doc.isDirectory) enter(doc, true)
        else {
            val entry = Entry(doc, null)
            val exposed = files.shareUri(entry)
            if (openInternal(entry, exposed)) return@task
            val intent = fileView(entry, exposed, useDefault = true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            withContext(Dispatchers.Main) { app.startActivity(intent) }
        }
    }

    fun pinLocation(uri: Uri, label: String) {
        viewModelScope.launch {
            val token = java.util.UUID.randomUUID().toString()
            val key = stringPreferencesKey("shortcut." + token)
            app.store.edit { it[key] = uri.toString() }
            val shortcuts = app.getSystemService(android.content.pm.ShortcutManager::class.java)
            val added = runCatching {
                shortcuts.isRequestPinShortcutSupported && shortcuts.requestPinShortcut(
                    android.content.pm.ShortcutInfo.Builder(app, token)
                        .setShortLabel(label)
                        .setIcon(android.graphics.drawable.Icon.createWithResource(app, R.mipmap.ic_launcher))
                        .setIntent(Intent(app, MainActivity::class.java).setAction(MainActivity.OPEN_LOCATION)
                            .putExtra("shortcutToken", token)).build(), null)
            }.getOrDefault(false)
            if (!added) {
                app.store.edit { it.remove(key) }
                notice(preferences.value.tr("Başlatıcı kısayolu desteklemiyor.", "The launcher does not support shortcuts."))
            }
        }
    }

    fun openPinnedLocation(token: String) {
        if (token.length != 36) return
        viewModelScope.launch {
            val key = stringPreferencesKey("shortcut." + token)
            val uri = app.store.data.first()[key] ?: return@launch
            state.first { !it.busy }
            openLocation(Uri.parse(uri))
        }
    }

    fun bookmark(uri: Uri) {
        viewModelScope.launch {
            app.store.edit {
                val key = stringPreferencesKey("bookmarks")
                val values = it[key].orEmpty().lineSequence().filter(String::isNotBlank).toMutableList()
                if (!values.remove(uri.toString())) values.add(0, uri.toString())
                it[key] = values.joinToString("\n")
            }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { installed.list() }
                mutate { it.copy(apps = apps) }
            } catch (_: SecurityException) { notice(preferences.value.tr("Uygulama listesi okunamadı.", "Cannot read installed apps.")) }
        }
    }

    fun backup(item: InstalledApp, uri: Uri) = task {
        installed.backup(item, uri)
        notice(preferences.value.tr("Yedek kaydedildi", "Backup saved") + " · " + item.filename)
    }

    fun shareApp(item: InstalledApp, callback: (Intent) -> Unit) = task {
        val intent = installed.share(item)
        withContext(Dispatchers.Main) { callback(intent) }
    }

    fun clearTemporary() = task {
        val cache = java.io.File(app.cacheDir, "apk-share")
        check(cache.deleteRecursively())
        notice(preferences.value.tr("Geçici yedekler temizlendi.", "Temporary backups cleared."))
    }

    fun preference(key: String, value: String) {
        viewModelScope.launch {
            app.store.edit {
                it[stringPreferencesKey(key)] = value
                val global = stringPreferencesKey("explorer.global")
                if (it[global] != null && (key == "order" || key == "hidden")) {
                    val current = preferences.value.explorer(null)
                    it[global] = if (key == "order") current.copy(order = Order.valueOf(value), descending = value != Order.Name.name).encode()
                        else current.copy(hidden = value == "true").encode()
                }
            }
        }
    }

    fun mutate(transform: (Screen) -> Screen) = state.update(transform)
    fun notice(text: String?) = mutate { it.copy(notice = text) }

    private fun task(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutate { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val prefix = if (preferences.value.english) "Operation failed" else "İşlem başarısız"
                notice(prefix + (error.message?.let { ": " + it } ?: ""))
            } finally {
                mutate { it.copy(busy = false) }
            }
        }
    }

    private suspend fun refresh(invalidate: Boolean = false) {
        val current = state.value
        val entries = current.stack.lastOrNull()?.let { files.children(it, true) }.orEmpty()
        val volumes = files.volumes()
        val roots = files.roots()
        val deleted = files.deleted()
        val refreshIndex = invalidate && (current.indexed != null || current.category?.let { it !in listOf(Category.Trash, Category.Apps) } == true)
        val index = if (refreshIndex) files.scan(roots, true) else current.indexed
        val results = if (current.searchResults != null && current.query?.isNotBlank() == true) {
            val scope = current.stack.lastOrNull()?.let(::listOf) ?: roots
            files.scan(scope, preferences.value.explorer(current.stack.lastOrNull()?.uri?.toString()).hidden).filter { it.name.contains(current.query.trim(), true) }
        } else null
        mutate {
            it.copy(entries = entries, volumes = volumes, roots = roots, deleted = deleted,
                indexed = index, selected = emptySet(), searchResults = results)
        }
    }

    fun reload() = task {
        val current = state.value
        if (current.page == Page.Files && current.rootPath != null) displayRoot(current.rootPath, current.shizukuPath)
        else {
            refresh(true)
            val apps = installed.list()
            mutate { it.copy(apps = apps) }
        }
    }
    fun analyze() = task {
        refresh()
        val entries = files.scan(files.roots(), true)
        mutate { it.copy(indexed = entries) }
        val english = preferences.value.english
        val summary = Category.entries.filter { it !in listOf(Category.Cloud, Category.Trash, Category.Recent, Category.Apps) }
            .joinToString("\n") { group ->
                val matching = entries.filter { files.matches(it, group) }
                val size = android.text.format.Formatter.formatFileSize(app, matching.sumOf { it.size })
                val count = matching.size
                preferences.value.label(group) + " · " + size + " (" + count + ")"
            }
        notice(if (files.roots().isEmpty()) {
            if (english) "Connect a folder from Files to analyze its contents."
            else "İçerik analizi için Dosyalar sekmesinden bir klasör bağlayın."
        } else summary)
    }

    fun page(page: Page) {
        if (state.value.busy) return
        mutate { it.copy(page = page, query = null, searchResults = null, selected = emptySet()) }
    }

    fun category(category: Category) = task {
        mutate { it.copy(page = Page.Files, category = category, stack = emptyList(),
            rootPath = null, query = null, searchResults = null, selected = emptySet()) }
        refresh()
        if (category == Category.Apps) {
            val apps = installed.list()
            mutate { it.copy(apps = apps) }
        } else if (category != Category.Trash) {
            val index = files.scan(files.roots(), true)
            mutate { it.copy(indexed = index) }
        }
    }

    fun grant(uri: Uri) = task {
        val dir = files.grant(uri)
        enter(dir, true)

    }

    private suspend fun enter(dir: DocumentFile, fromRoot: Boolean) {
        mutate { it.copy(page = Page.Files, stack = if (fromRoot) listOf(dir) else it.stack + dir,
            category = null, rootPath = null, query = null, searchResults = null, selected = emptySet(),
            history = (listOf(dir.uri) + it.history.filterNot { uri -> uri == dir.uri }).take(30)) }
        refresh()
    }

    fun open(dir: DocumentFile, fromRoot: Boolean = false) = task { enter(dir, fromRoot) }

    fun locations() = task {
        mutate { it.copy(page = Page.Files, stack = emptyList(), category = null, rootPath = null, query = null) }
        refresh()
    }

    fun back() {
        val current = state.value
        when {
            current.busy -> Unit
            current.selected.isNotEmpty() -> mutate { it.copy(selected = emptySet()) }
            current.query != null -> mutate { it.copy(query = null, searchResults = null) }
            current.rootPath != null && current.rootPath != "/" -> rootBrowse(current.rootPath.substringBeforeLast('/').ifBlank { "/" }, current.shizukuPath)
            current.rootPath != null || current.category != null || current.stack.size == 1 -> locations()
            current.stack.size > 1 -> task {
                mutate { it.copy(stack = it.stack.dropLast(1)) }
                refresh()
            }
            else -> page(Page.Home)
        }
    }

    fun query(value: String?) = mutate {
        val current = if (it.page == Page.Files) it
            else it.copy(stack = emptyList(), category = null, rootPath = null, entries = emptyList())
        current.copy(page = Page.Files, query = value, searchResults = null, selected = emptySet())
    }

    fun search() = task {
        val current = state.value
        val term = current.query.orEmpty().trim()
        if (term.isBlank()) return@task
        val scope = current.stack.lastOrNull()?.let(::listOf) ?: files.roots()
        val hits = files.scan(scope, preferences.value.explorer(current.stack.lastOrNull()?.uri?.toString()).hidden).filter { it.name.contains(term, true) }
        mutate { if (it.query == current.query) it.copy(searchResults = hits) else it }
    }

    fun select(entry: Entry) = mutate {
        it.copy(selected = if (entry.uri in it.selected) it.selected - entry.uri else it.selected + entry.uri)
    }

    fun stage(entries: List<Entry>, cut: Boolean) = mutate {
        it.copy(clipboard = entries, cut = cut, selected = emptySet(), query = null, searchResults = null)
    }

    fun paste(dir: DocumentFile) = task {
        try {
            while (state.value.clipboard.isNotEmpty()) {
                val current = state.value
                files.copy(current.clipboard.first(), dir, current.cut)
                mutate { it.copy(clipboard = it.clipboard.drop(1)) }
            }
        } finally { refresh(true) }
    }

    fun create(dir: DocumentFile, label: String) = task { files.folder(dir, label); refresh(true) }
    fun rename(entry: Entry, label: String) = task { files.rename(entry, label); refresh(true) }
    fun delete(entries: List<Entry>) = task {
        try { entries.forEach { files.remove(it, preferences.value.recycle) } }
        finally { refresh(true) }
    }
    fun zip(dir: DocumentFile, label: String, entries: List<Entry>) = task {
        files.archive(dir, if (label.endsWith(".zip", true)) label else label + ".zip", entries)
        refresh(true)
    }
    fun unzip(entry: Entry, dir: DocumentFile) = task { files.extract(entry, dir); refresh(true) }
    fun restore(item: Deleted) = task { files.restore(item); refresh(true) }
    fun purge(item: Deleted) = task { files.purge(item); refresh(true) }
    fun emptyTrash() = task {
        try { files.deleted().forEach(files::purge) } finally { refresh(true) }
    }

    fun launch(entry: Entry, share: Boolean, chooser: Boolean = false, callback: (Intent) -> Unit) = task {
        val uri = files.shareUri(entry)
        if (!share && !chooser && openInternal(entry, uri)) return@task
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(entry.mime).putExtra(Intent.EXTRA_STREAM, uri)
            else fileView(entry, uri, useDefault = !chooser)
        intent.clipData = android.content.ClipData.newRawUri(entry.name, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        withContext(Dispatchers.Main) { callback(if (share || chooser) Intent.createChooser(intent, null) else intent) }
    }

    private fun openInternal(entry: Entry, uri: Uri): Boolean {
        val kind = viewerKind(entry.name, entry.mime) ?: return false
        val ext = extension(entry.name)
        val assigned = preferences.value.defaultPackage(ext)
        if (assigned != null && app.packageManager.viewer(uri, entry.mime, ext, assigned) != null) return false
        val text = if (kind == ViewerKind.Text) files.readText(uri) else ""
        val writable = kind == ViewerKind.Text && entry.doc.canWrite()
        mutate { it.copy(viewer = Viewer(uri, entry.name, kind, entry = entry, original = text, draft = text, writable = writable)) }
        return true
    }

    fun editViewer(text: String) {
        if (text.length <= TEXT_LIMIT) mutate { state -> state.copy(viewer = state.viewer?.copy(draft = text)) }
    }

    fun saveViewer() = task {
        val viewer = checkNotNull(state.value.viewer)
        check(viewer.writable)
        files.saveText(checkNotNull(viewer.entry), viewer.original, viewer.draft)
        mutate { it.copy(viewer = viewer.copy(original = viewer.draft)) }
    }

    fun closeViewer() {
        val viewer = state.value.viewer
        mutate { it.copy(viewer = null) }
        viewer?.temp?.let { file -> viewModelScope.launch(Dispatchers.IO) { file.delete() } }
    }

    fun openPrivileged(item: RootEntry) = task {
        val current = state.value
        check(current.rootPath != null && (current.rootEnabled || current.shizukuPath) && !item.folder)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(item.name)).orEmpty()
        val kind = viewerKind(item.name, mime)
        if (kind == null) {
            notice(if (current.shizukuPath) shizuku.details(item.path) else root.details(item.path))
            return@task
        }
        val directory = java.io.File(app.cacheDir, "privileged-view").apply { mkdirs() }
        val file = java.io.File.createTempFile("view-", ".tmp", directory)
        try {
            val limit = if (kind == ViewerKind.Text) TEXT_LIMIT.toLong() else PRIVILEGED_LIMIT
            if (current.shizukuPath) shizuku.preview(item.path, file, limit) else root.preview(item.path, file, limit)
            val uri = Uri.fromFile(file)
            val text = if (kind == ViewerKind.Text) files.readText(uri) else ""
            mutate { it.copy(viewer = Viewer(uri, item.name, kind, temp = file, original = text, draft = text)) }
        } catch (error: Exception) { file.delete(); throw error }
    }

    private fun fileView(entry: Entry, uri: Uri, useDefault: Boolean): Intent {
        val ext = extension(entry.name)
        val fallback = viewIntents(uri, entry.mime, ext)
        val pm = app.packageManager
        if (useDefault) {
            val chosen = preferences.value.defaultPackage(ext) ?: if (ext in setOf("mcpack", "mcworld", "mcaddon", "mctemplate")) {
                "com.mojang.minecraftpe"
            } else null
            if (chosen != null) pm.viewer(uri, entry.mime, ext, chosen)?.let { return it }
        }
        return fallback.firstOrNull { it.resolveActivity(pm) != null } ?: fallback.last()
    }

    fun rootEnable(browse: Boolean = false) = task {
        root.authorize()
        app.store.edit { it[stringPreferencesKey("root")] = "true" }
        mutate { it.copy(rootEnabled = true) }
        if (browse) displayRoot("/")
    }

    fun rootDisable() {
        mutate { it.copy(rootEnabled = false, rootPath = null, rootEntries = emptyList()) }
        preference("root", "false")
    }

    fun rootBrowse(path: String = "/", throughShizuku: Boolean = false) = task { displayRoot(path, throughShizuku) }

    private fun displayRoot(path: String, throughShizuku: Boolean = false) {
        check(throughShizuku || state.value.rootEnabled)
        val entries = if (throughShizuku) shizuku.list(path) else root.list(path)
        mutate { it.copy(page = Page.Files, rootPath = path, shizukuPath = throughShizuku, rootEntries = entries,
            stack = emptyList(), category = null, query = null, searchResults = null, selected = emptySet()) }
    }

    fun rootDetails(entry: RootEntry) = task {
        if (state.value.shizukuPath) notice(shizuku.details(entry.path))
        else { check(state.value.rootEnabled); notice(root.details(entry.path)) }
    }
    fun chmod(entry: RootEntry, mode: String) = task {
        if (state.value.shizukuPath) shizuku.chmod(entry.path, mode)
        else { check(state.value.rootEnabled); root.chmod(entry.path, mode) }
    }
}











