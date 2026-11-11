package dev.dosyayoneticisi.plus

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

@androidx.annotation.Keep
class StorageUserService : IStorageService.Stub() {
    override fun destroy() { kotlin.system.exitProcess(0) }

    override fun children(path: String): Array<String> {
        val children = checkNotNull(File(path).listFiles()) { "Cannot read " + path }
        return children.map { (if (it.isDirectory) "d" else "f") + it.name }.toTypedArray()
    }

    override fun details(path: String): String {
        val stat = Os.lstat(path)
        return listOf(path, "UID: ${stat.st_uid}", "GID: ${stat.st_gid}",
            "Mode: " + (stat.st_mode and 0xFFF).toString(8), "Bytes: ${stat.st_size}").joinToString("\n")
    }

    override fun openRead(path: String, limit: Long): android.os.ParcelFileDescriptor {
        require(limit in 1..PRIVILEGED_LIMIT)
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            val stat = Os.fstat(fd)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size <= limit) { "File type or size is unsupported" }
            return android.os.ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
    }

    override fun chmod(path: String, mode: Int) {
        require(mode in 0..0xFFF)
        check(!OsConstants.S_ISLNK(Os.lstat(path).st_mode)) { "Symbolic link" }
        Os.chmod(path, mode)
    }
}

enum class ShizukuStatus { Offline, Ready, Connecting, Connected }

class ShizukuFiles(context: Context, private val error: (String) -> Unit) : ServiceConnection {
    val status = MutableStateFlow(ShizukuStatus.Offline)
    private val arguments = Shizuku.UserServiceArgs(ComponentName(context, StorageUserService::class.java))
        .tag("storage").processNameSuffix("storage_service").daemon(false).version(2)
    @Volatile private var service: IStorageService? = null
    private var requested = false
    private val received = Shizuku.OnBinderReceivedListener { status.value = ShizukuStatus.Ready }
    private val dead = Shizuku.OnBinderDeadListener {
        service = null
        requested = false
        status.value = ShizukuStatus.Offline
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 741 && requested) {
            if (result == PackageManager.PERMISSION_GRANTED) bind()
            else {
                requested = false
                status.value = ShizukuStatus.Ready
                error("Shizuku · " + if (result == PackageManager.PERMISSION_DENIED) "Permission denied" else "Permission unavailable")
            }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }

    fun connect() {
        if (status.value == ShizukuStatus.Connected || requested) return
        try {
            check(Shizuku.pingBinder() && !Shizuku.isPreV11()) { "Start Shizuku first" }
            requested = true
            status.value = ShizukuStatus.Connecting
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else if (Shizuku.shouldShowRequestPermissionRationale()) {
                error("Shizuku · Grant access from the Shizuku app")
                disconnect()
            } else Shizuku.requestPermission(741)
        } catch (e: Exception) {
            requested = false
            status.value = if (Shizuku.pingBinder()) ShizukuStatus.Ready else ShizukuStatus.Offline
            error("Shizuku · " + e.message)
        }
    }

    private fun bind() {
        try { Shizuku.bindUserService(arguments, this) }
        catch (e: Exception) { disconnect(); error("Shizuku · " + e.message) }
    }

    fun disconnect() {
        val wasRequested = requested
        requested = false
        service = null
        if (wasRequested && Shizuku.pingBinder()) runCatching { Shizuku.unbindUserService(arguments, this, true) }
        status.value = if (Shizuku.pingBinder()) ShizukuStatus.Ready else ShizukuStatus.Offline
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        if (!requested) return
        service = IStorageService.Stub.asInterface(binder)
        status.value = ShizukuStatus.Connected
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
        requested = false
        status.value = if (Shizuku.pingBinder()) ShizukuStatus.Ready else ShizukuStatus.Offline
    }

    private fun api(): IStorageService {
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku permission revoked" }
        return checkNotNull(service) { "Shizuku disconnected" }
    }
    fun list(path: String) = api().children(path).map {
        RootEntry(path.trimEnd('/') + "/" + it.drop(1), it.startsWith('d'))
    }.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))

    fun details(path: String): String = api().details(path)

    fun preview(path: String, dest: File, limit: Long) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(api().openRead(path, limit)).use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    check(bytes <= limit) { "File exceeds the preview limit" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    fun chmod(path: String, mode: String) {
        require(mode.matches(Regex("[0-7]{3,4}")))
        api().chmod(path, mode.toInt(8))
    }

    fun close() {
        disconnect()
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
    }
}


