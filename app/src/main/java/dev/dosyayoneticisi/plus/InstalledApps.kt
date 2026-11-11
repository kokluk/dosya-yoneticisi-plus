package dev.dosyayoneticisi.plus

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val info: ApplicationInfo, val label: String, val version: String,
    val parts: List<File>, val bytes: Long
) {
    val system get() = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
        info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
    val id get() = info.packageName
    val split get() = parts.size > 1
    val mime get() = if (split) "application/zip" else "application/vnd.android.package-archive"
    val filename get() = id + if (split) ".zip" else ".apk"
}

class InstalledApps(private val context: Context) {
    private val pm = context.packageManager

    @Suppress("DEPRECATION")
    fun list(): List<InstalledApp> = pm.getInstalledPackages(0).mapNotNull { item ->
        val info = item.applicationInfo ?: return@mapNotNull null
        val parts = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).map(::File)
        InstalledApp(info, info.loadLabel(pm).toString(), item.versionName.orEmpty(), parts, parts.sumOf { it.length() })
    }.sortedBy { it.label.lowercase() }

    private fun write(app: InstalledApp, output: OutputStream) {
        if (app.split) {
            ZipOutputStream(output).use { zip ->
                app.parts.forEachIndexed { index, part ->
                    zip.putNextEntry(ZipEntry(if (index == 0) "base.apk" else part.name))
                    part.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } else app.parts.single().inputStream().use { it.copyTo(output) }
    }

    fun backup(app: InstalledApp, uri: Uri) {
        val resolver = context.contentResolver
        try {
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { write(app, it) }
        } catch (error: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
            throw error
        }
    }

    fun share(app: InstalledApp): Intent {
        val cache = File(context.cacheDir, "apk-share").apply { mkdirs() }
        cache.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }
            .forEach { it.delete() }
        val file = File.createTempFile(app.id + "-", if (app.split) ".zip" else ".apk", cache)
        try { file.outputStream().use { write(app, it) } }
        catch (error: Exception) { file.delete(); throw error }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file, app.filename)
        val intent = Intent(Intent.ACTION_SEND).setType(app.mime).putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = ClipData.newRawUri(app.filename, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsPanel(vm: Manager, s: Screen, p: Preferences) {
    val context = LocalContext.current
    val pm = context.packageManager
    var all by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    var pendingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    fun launch(intent: Intent?) {
        try { context.startActivity(checkNotNull(intent)) }
        catch (_: Exception) { vm.notice(p.tr("Bu işlem için uygun uygulama yok.", "No app can handle this action.")) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val item = s.apps.firstOrNull { it.id == pendingId }
        if (uri != null && item != null) vm.backup(item, uri)
        pendingId = null
    }
    val uninstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshApps() }
    val apps = s.apps.filter { (all || !it.system) &&
        (s.query.isNullOrBlank() || it.label.contains(s.query, true) || it.id.contains(s.query, true)) }
    PrimaryTabRow(selectedTabIndex = if (all) 1 else 0) {
        Tab(!all, { all = false }, text = { Text(p.tr("İndirilenler", "Downloaded")) })
        Tab(all, { all = true }, text = { Text(p.tr("Tümü", "All")) })
    }
    if (s.query != null) OutlinedTextField(s.query, vm::query, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(p.tr("Uygulama ara", "Search apps")) })
    LazyColumn {
        items(apps, key = { it.id }) { item ->
            val bitmap by produceState<android.graphics.Bitmap?>(null, item) {
                value = withContext(Dispatchers.IO) { runCatching { item.info.loadIcon(pm).toBitmap(96, 96) }.getOrNull() }
            }
            ListItem(
                modifier = Modifier.combinedClickable(enabled = !s.busy,
                    onClick = { selected = item }, onLongClick = { selected = item }),
                leadingContent = {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(42.dp)) }
                        ?: Icon(Icons.Default.Android, null, Modifier.size(42.dp))
                },
                supportingContent = {
                    Column {
                        Text(p.fileSize(context, item.bytes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.version.isNotBlank()) Text(item.version, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium)
                    }
                },
                content = { Text(item.label, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) })
            HorizontalDivider()
        }
    }
    selected?.let { item ->
        ActionDialog(item.label, p.tr("İptal", "Cancel"), { selected = null }) {
            listOf(
                Triple(Icons.Default.Save, p.tr("APK yedekle", "Back up APK"), {
                    pendingId = item.id
                    save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(item.mime).putExtra(Intent.EXTRA_TITLE, item.filename))
                }),
                Triple(Icons.Default.DeleteOutline, p.tr("Kaldır", "Uninstall"), {
                    try {
                        uninstall.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + item.id))
                            .putExtra(Intent.EXTRA_RETURN_RESULT, true))
                    } catch (_: Exception) { vm.notice(p.tr("Kaldırma ekranı açılamadı.", "Could not open uninstall screen.")) }
                }),
                Triple(Icons.Default.Store, p.tr("Mağaza", "Store"), {
                    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + item.id))
                    try { context.startActivity(market) }
                    catch (_: android.content.ActivityNotFoundException) {
                        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + item.id)))
                    }
                }),
                Triple(Icons.Default.Share, p.tr("Paylaş", "Share"), { vm.shareApp(item, ::launch) }),
                Triple(Icons.AutoMirrored.Filled.OpenInNew, p.tr("Aç", "Open"), { launch(pm.getLaunchIntentForPackage(item.id)) })
            ).forEach { (icon, label, run) ->
                DialogAction(label, icon) { selected = null; run() }
            }
        }
    }
}



