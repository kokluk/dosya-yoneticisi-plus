package dev.dosyayoneticisi.plus

import java.io.File
import java.util.concurrent.TimeUnit

data class RootEntry(val path: String, val folder: Boolean) {
    val name get() = path.substringAfterLast('/')
}

class RootFiles(private val cache: File) {
    fun detected() = System.getenv("PATH").orEmpty().split(':').any { File(it, "su").canExecute() }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun execute(script: String): String {
        val output = File.createTempFile("root", ".log", cache)
        val errors = File.createTempFile("root", ".err", cache)
        try {
            val process = ProcessBuilder("su", "-c", script)
                .redirectOutput(output).redirectError(errors).start()
            try {
                check(process.waitFor(45, TimeUnit.SECONDS)) { "su: timeout" }
                check(process.exitValue() == 0) { errors.readText().trim().ifBlank { "su: exit " + process.exitValue() } }
                return output.readText()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            output.delete()
            errors.delete()
        }
    }

    fun authorize() = check(execute("id -u").trim() == "0") { "su: UID != 0" }

    fun list(path: String): List<RootEntry> {
        val script = """
            cd ${quote(path)} || exit 1
            [ -r . ] && [ -x . ] || exit 1
            for item in ./* ./.[!.]* ./..?*; do
                [ -e "${'$'}item" ] || [ -L "${'$'}item" ] || continue
                if [ -d "${'$'}item" ]; then kind=d; else kind=f; fi
                printf '%s\000%s\000' "${'$'}kind" "${'$'}{item#./}"
            done
        """.trimIndent()
        return execute(script).split('\u0000').dropLast(1).chunked(2).map { (kind, name) ->
            RootEntry(path.trimEnd('/') + "/" + name, kind == "d")
        }.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
    }

    fun preview(path: String, dest: File, limit: Long) {
        val arg = quote(path)
        val script = "[ -f " + arg + " ] && [ ! -L " + arg + " ] || exit 1; head -c " + (limit + 1) + " -- " + arg
        val process = ProcessBuilder("su", "-c", script)
            .redirectOutput(dest).redirectError(File("/dev/null")).start()
        try {
            check(process.waitFor(45, TimeUnit.SECONDS) && process.exitValue() == 0) { "Cannot read root file" }
            check(dest.length() <= limit) { "File exceeds the preview limit" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun details(path: String) = execute("stat -- " + quote(path))

    fun chmod(path: String, mode: String) {
        require(mode.matches(Regex("[0-7]{3,4}")))
        execute("chmod -- " + mode + " " + quote(path))
    }
}



