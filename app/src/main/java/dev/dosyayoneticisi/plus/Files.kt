package dev.dosyayoneticisi.plus

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class Entry(
    val doc: DocumentFile,
    val parent: DocumentFile?,
    val name: String = doc.name ?: doc.uri.toString(),
    val folder: Boolean = doc.isDirectory,
    val size: Long = if (folder) 0L else doc.length(),
    val modified: Long = doc.lastModified(),
    val mime: String = doc.type ?: MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension(name)) ?: "application/octet-stream"
) {
    val uri = doc.uri
}
data class Volume(val primary: Boolean, val path: File, val total: Long, val free: Long)
data class Deleted(val id: File, val name: String, val origin: Uri, val payload: DocumentFile, val size: Long)
enum class Category { Images, Videos, Audio, Documents, Apps, Downloads, Recent, Cloud, Trash }
enum class Order { Name, Date, Size }

class Files(private val context: Context) {
    private val resolver = context.contentResolver
    private val trash = File(context.filesDir, "trash")
    val broadAccess get() = android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun volumes(): List<Volume> {
        val manager = context.getSystemService(StorageManager::class.java)
        val primary = Environment.getExternalStorageDirectory()
        return buildList {
            fun addVolume(file: File, internal: Boolean) {
                runCatching { StatFs(file.path) }.getOrNull()?.let {
                    add(Volume(internal, file, it.totalBytes, it.availableBytes))
                }
            }
            addVolume(primary, true)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                manager.storageVolumes.filter { it.isRemovable && !it.isPrimary }
                    .mapNotNull { it.directory }.distinct().forEach { addVolume(it, false) }
            } else {
                context.getExternalFilesDirs(null).filterNotNull().drop(1)
                    .filter { Environment.isExternalStorageRemovable(it) }
                    .forEach { addVolume(it, false) }
            }
        }
    }

    fun roots(): List<DocumentFile> = buildList {
        if (broadAccess) volumes().forEach { add(DocumentFile.fromFile(it.path)) }
        resolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .mapNotNull { DocumentFile.fromTreeUri(context, it.uri) }.forEach { add(it) }
    }.distinctBy { it.uri }

    fun resolveLocation(uri: Uri): DocumentFile? {
        if (uri.scheme == "file") {
            if (!broadAccess) return null
            val file = raw(uri).canonicalFile
            if (volumes().none { file.toPath().startsWith(it.path.canonicalFile.toPath()) }) return null
            return DocumentFile.fromFile(file).takeIf { it.exists() }
        }
        val grants = resolver.persistedUriPermissions.filter { it.isReadPermission && it.uri.authority == uri.authority }
        if (!DocumentsContract.isTreeUri(uri)) {
            return DocumentFile.fromSingleUri(context, uri)?.takeIf { grants.any { it.uri == uri } && it.exists() }
        }
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        if (grants.none { DocumentsContract.isTreeUri(it.uri) && DocumentsContract.getTreeDocumentId(it.uri) == treeId }) return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() }
    }

    fun persist(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, 3)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(uri, 1)
        }
    }

    fun grant(uri: Uri): DocumentFile {
        persist(uri)
        return requireNotNull(DocumentFile.fromTreeUri(context, uri))
    }

    fun children(dir: DocumentFile, hidden: Boolean): List<Entry> {
        val batched: List<Entry>? = if (DocumentsContract.isTreeUri(dir.uri)) runCatching {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri, DocumentsContract.getDocumentId(dir.uri))
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
            resolver.query(uri, columns, null, null, null)?.use { cursor ->
                val indices = columns.map(cursor::getColumnIndex)
                if (indices.any { it < 0 }) return@use null
                buildList<Entry> {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(indices[0]) ?: continue
                        val label = cursor.getString(indices[1]) ?: id
                        if (!hidden && label.startsWith('.')) continue
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(dir.uri, id)
                        val child = DocumentFile.fromTreeUri(context, childUri) ?: continue
                        val type = cursor.getString(indices[2]) ?: MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(extension(label)) ?: "application/octet-stream"
                        val folder = type == Document.MIME_TYPE_DIR
                        add(Entry(child, dir, label, folder, if (folder) 0L else cursor.getLong(indices[3]),
                            cursor.getLong(indices[4]), type))
                    }
                }
            }
        }.getOrNull() else null
        return batched ?: dir.listFiles().map { Entry(it, dir) }.filter { hidden || !it.name.startsWith('.') }
    }

    suspend fun scan(start: List<DocumentFile>, hidden: Boolean): List<Entry> {
        val pending = ArrayDeque(start)
        val visited = mutableSetOf<Uri>()
        val result = mutableListOf<Entry>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = pending.removeFirst()
            if (!visited.add(dir.uri) || isLink(dir)) continue
            children(dir, hidden).forEach {
                result.add(it)
                if (it.folder) pending.add(it.doc)
            }
        }
        return result.distinctBy { it.uri }
    }

    fun matches(entry: Entry, category: Category): Boolean {
        if (entry.folder) return false
        return when (category) {
            Category.Images -> entry.mime.startsWith("image/")
            Category.Videos -> entry.mime.startsWith("video/")
            Category.Audio -> entry.mime.startsWith("audio/")
            Category.Apps -> false
            Category.Documents -> entry.mime.startsWith("text/") ||
                entry.name.substringAfterLast('.').lowercase() in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "csv", "epub", "rtf")
            Category.Downloads -> entry.uri.toString().let(Uri::decode).split('/', ':')
                .any { it.equals("Download", true) || it.equals("Downloads", true) }
            Category.Recent -> entry.modified >= System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            else -> false
        }
    }

    fun name(value: String): String {
        require(value.isNotBlank() && value !in listOf(".", "..") && value.none { it in "/\\\u0000" })
        return value
    }

    private fun isLink(doc: DocumentFile): Boolean =
        doc.uri.scheme == "file" && java.nio.file.Files.isSymbolicLink(File(requireNotNull(doc.uri.path)).toPath())

    private fun raw(uri: Uri) = File(requireNotNull(uri.path))
    private fun input(doc: DocumentFile) = if (doc.uri.scheme == "file") raw(doc.uri).inputStream()
        else requireNotNull(resolver.openInputStream(doc.uri))
    private fun output(doc: DocumentFile) = if (doc.uri.scheme == "file") raw(doc.uri).outputStream()
        else requireNotNull(resolver.openOutputStream(doc.uri, "wt"))

    private fun available(dir: DocumentFile, name: String) {
        check(dir.findFile(name) == null) { "File already exists: " + name }
    }

    fun folder(dir: DocumentFile, value: String): DocumentFile {
        val label = name(value)
        available(dir, label)
        return checkNotNull(dir.createDirectory(label))
    }

    fun rename(entry: Entry, value: String) {
        val label = name(value)
        if (entry.name == label) return
        available(checkNotNull(entry.parent), label)
        check(entry.doc.renameTo(label))
    }

    private fun create(dir: DocumentFile, mime: String, label: String): DocumentFile {
        available(dir, name(label))
        if (dir.uri.scheme == "file") {
            val file = File(raw(dir.uri), label)
            check(file.createNewFile())
            return DocumentFile.fromFile(file)
        }
        return checkNotNull(dir.createFile(mime, label))
    }

    private fun delete(doc: DocumentFile) {
        if (doc.uri.scheme == "file") {
            val path = raw(doc.uri).toPath()
            java.nio.file.Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) }
            }
        } else check(doc.delete())
    }

    private data class CopyItem(val entry: Entry, val children: List<CopyItem>)

    private fun copyPlan(entry: Entry, ancestors: Set<Uri> = emptySet()): CopyItem {
        check(!isLink(entry.doc) && entry.uri !in ancestors && ancestors.size < 128) {
            "Unsupported link or directory depth: " + entry.name
        }
        val children = if (entry.folder) children(entry.doc, true).map { copyPlan(it, ancestors + entry.uri) }
            else emptyList()
        return CopyItem(entry, children)
    }

    private fun volumePath(uri: Uri): File? {
        if (uri.scheme == "file") return raw(uri).canonicalFile
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = android.provider.DocumentsContract.getDocumentId(uri)
        val volume = id.substringBefore(':')
        val path = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory()
            else File("/storage", volume)
        return File(path, id.substringAfter(':', "")).canonicalFile
    }

    fun copy(entry: Entry, dest: DocumentFile, move: Boolean = false, label: String = entry.name) {
        if (entry.folder) {
            val sourcePath = volumePath(entry.uri)
            val destPath = volumePath(dest.uri)
            if (sourcePath != null && destPath != null) {
                check(!destPath.toPath().startsWith(sourcePath.toPath())) { "Destination is inside the source" }
            }
            if (entry.uri.scheme == "content" && dest.uri.scheme == "content" && entry.uri.authority == dest.uri.authority) {
                val sourceId = android.provider.DocumentsContract.getDocumentId(entry.uri)
                val destId = android.provider.DocumentsContract.getDocumentId(dest.uri)
                check(destId != sourceId && !destId.startsWith(sourceId.trimEnd('/') + "/")) {
                    "Destination is inside the source"
                }
            }
        }
        val plan = copyPlan(entry)
        fun write(item: CopyItem, directory: DocumentFile, targetName: String) {
            val source = item.entry
            val target = if (source.folder) folder(directory, targetName) else create(directory, source.mime, targetName)
            try {
                if (source.folder) item.children.forEach { write(it, target, it.entry.name) }
                else input(source.doc).use { bytes -> output(target).use { bytes.copyTo(it) } }
            } catch (error: Exception) {
                runCatching { delete(target) }
                throw error
            }
        }
        write(plan, dest, label)
        if (move) delete(entry.doc)
    }

    fun remove(entry: Entry, recycle: Boolean) {
        if (!recycle) {
            delete(entry.doc)
            return
        }
        val record = File(trash, UUID.randomUUID().toString())
        check(record.mkdirs())
        try {
            val data = File(record, "data")
            check(data.mkdir())
            copy(entry, DocumentFile.fromFile(data))
            File(record, "origin").writeText(checkNotNull(entry.parent).uri.toString())
        } catch (error: Exception) {
            record.deleteRecursively()
            throw error
        }
        delete(entry.doc)
    }

    fun deleted(): List<Deleted> = trash.listFiles().orEmpty().mapNotNull { record ->
        val origin = File(record, "origin")
        val file = File(record, "data").listFiles()?.singleOrNull()
        if (file == null || !origin.isFile) null else Deleted(
            record, file.name, Uri.parse(origin.readText()), DocumentFile.fromFile(file),
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        )
    }

    fun restore(item: Deleted) {
        val dest = if (item.origin.scheme == "file") {
            check(broadAccess)
            DocumentFile.fromFile(raw(item.origin))
        } else DocumentFile.fromTreeUri(context, item.origin)?.let { root ->
            val tree = android.provider.DocumentsContract.getTreeDocumentId(item.origin)
            val doc = android.provider.DocumentsContract.getDocumentId(item.origin)
            if (tree == doc) root else scanFor(root, item.origin)
        }
        copy(Entry(item.payload, null), checkNotNull(dest))
        purge(item)
    }

    private fun scanFor(root: DocumentFile, uri: Uri): DocumentFile? {
        val pending = ArrayDeque(listOf(root))
        val visited = mutableSetOf<Uri>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current.uri == uri) return current
            if (visited.add(current.uri)) current.listFiles().forEach {
                if (it.uri == uri) return it
                if (it.isDirectory) pending.add(it)
            }
        }
        return null
    }

    fun purge(item: Deleted) = delete(DocumentFile.fromFile(item.id))

    fun archive(dir: DocumentFile, label: String, entries: List<Entry>) {
        val target = create(dir, "application/zip", label)
        try {
            ZipOutputStream(output(target)).use { zip ->
                fun append(entry: Entry, prefix: String) {
                    check(!isLink(entry.doc) && entry.uri != target.uri)
                    val path = prefix + name(entry.name)
                    zip.putNextEntry(ZipEntry(if (entry.folder) path + "/" else path))
                    if (!entry.folder) input(entry.doc).use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (entry.folder) children(entry.doc, true).forEach { append(it, path + "/") }
                }
                entries.forEach { append(it, "") }
            }
        } catch (error: Exception) {
            runCatching { delete(target) }
            throw error
        }
    }

    fun extract(entry: Entry, dir: DocumentFile) {
        val target = folder(dir, entry.name.substringBeforeLast('.').ifBlank { "ZIP" })
        try {
            ZipInputStream(input(entry.doc)).use { zip ->
                var count = 0
                var bytes = 0L
                val buffer = ByteArray(65536)
                while (true) {
                    val member = zip.nextEntry ?: break
                    check(++count <= 100_000)
                    val parts = member.name.trimEnd('/').split('/').map(::name)
                    var parent = target
                    for (part in parts.dropLast(if (member.isDirectory) 0 else 1)) {
                        parent = (parent.findFile(part) ?: folder(parent, part)).also { check(it.isDirectory) }
                    }
                    if (!member.isDirectory) {
                        val file = create(parent, "application/octet-stream", parts.last())
                        output(file).use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                bytes += read
                                check(bytes <= 16L * 1024 * 1024 * 1024) { "ZIP exceeds 16 GiB" }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
                check(count > 0) { "ZIP is empty or invalid" }
            }
        } catch (error: Exception) {
            runCatching { delete(target) }
            throw error
        }
    }

    fun volumeFor(uri: Uri, volumes: List<Volume>): Volume? {
        val path = volumePath(uri) ?: return null
        return volumes.firstOrNull { path.toPath().startsWith(it.path.canonicalFile.toPath()) }
    }

    fun readText(uri: Uri): String {
        val bytes = checkNotNull(resolver.openInputStream(uri)).use { source ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                require(out.size() + read <= TEXT_LIMIT) { "Text exceeds 512 KiB" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    fun saveText(entry: Entry, original: String, draft: String) {
        val bytes = draft.toByteArray(Charsets.UTF_8)
        require(bytes.size <= TEXT_LIMIT) { "Text exceeds 512 KiB" }
        check(entry.doc.canWrite()) { "Document is read-only" }
        check(readText(shareUri(entry)) == original) { "The file changed after it was opened" }
        try {
            output(entry.doc).use { it.write(bytes) }
        } catch (error: Exception) {
            runCatching { output(entry.doc).use { it.write(original.toByteArray(Charsets.UTF_8)) } }
                .exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun shareUri(entry: Entry): Uri {
        check(!entry.folder)
        return if (entry.uri.scheme == "content") entry.uri
        else FileProvider.getUriForFile(context, context.packageName + ".files", raw(entry.uri))
    }
}





