package app.wisp.keyboard.clipboard

import java.io.File

/** One copied thing. [uri] is set for images (the text is then a short description). */
class ClipItem(val id: Long, val text: String, val kind: Kind, val at: Long, val pinned: Boolean, val uri: String? = null)

enum class Kind { TEXT, LINK, CODE, IMAGE }

enum class ClipFilter { ALL, PINNED, LINKS, CODES, IMAGES }

/**
 * What was copied recently, kept on the phone for a short while. Never stores what the system flags as sensitive or what was copied
 * from a password field. Unpinned items clear after [TTL_MS]; one-time codes after [CODE_TTL_MS]. Pure logic with an injected clock;
 * the clipboard listener and the panel are in the IME.
 */
class ClipboardHistory(private val clock: () -> Long = System::currentTimeMillis, private val maxItems: Int = MAX_ITEMS) {

    private val list = ArrayList<ClipItem>()
    private var nextId = 1L

    /** False: nothing is stored and the panel says so. Turning it off forgets the unpinned items. */
    var enabled = true
        set(v) { field = v; if (!v) { list.removeAll { !it.pinned }; } }

    /**
     * Records a copy. Returns false when it was not stored: history off, empty, too long, sensitive, from a password field.
     * Copying the same text again moves it to the top (keeping its pin).
     */
    fun add(text: CharSequence?, sensitive: Boolean = false, fromPassword: Boolean = false, imageUri: String? = null): Boolean {
        if (!enabled || sensitive || fromPassword) return false
        val t = text?.toString()?.trim().orEmpty()
        if (imageUri == null && (t.isEmpty() || t.length > MAX_LENGTH)) return false
        val now = clock()
        purge(now)
        val kind = if (imageUri != null) Kind.IMAGE else classify(t)
        val label = if (imageUri != null) t.ifEmpty { "Image" } else t
        val old = list.firstOrNull { it.text == label && it.kind == kind && it.uri == imageUri }
        if (old != null) list.remove(old)
        list.add(0, ClipItem(old?.id ?: nextId++, label, kind, now, old?.pinned ?: false, imageUri))
        while (list.size > maxItems) { val i = list.indexOfLast { !it.pinned }; if (i < 0) break; list.removeAt(i) }
        return true
    }

    /** The items to show, newest first, pinned ones first within that. Expired items are dropped first. */
    fun items(filter: ClipFilter = ClipFilter.ALL): List<ClipItem> {
        purge(clock())
        val all = when (filter) {
            ClipFilter.ALL -> list
            ClipFilter.PINNED -> list.filter { it.pinned }
            ClipFilter.LINKS -> list.filter { it.kind == Kind.LINK }
            ClipFilter.CODES -> list.filter { it.kind == Kind.CODE }
            ClipFilter.IMAGES -> list.filter { it.kind == Kind.IMAGE }
        }
        return all.sortedByDescending { it.pinned }
    }

    fun pin(id: Long, on: Boolean) { val i = list.indexOfFirst { it.id == id }; if (i >= 0) { val o = list[i]; list[i] = ClipItem(o.id, o.text, o.kind, o.at, on, o.uri) } }
    fun remove(id: Long) { list.removeAll { it.id == id } }
    fun clear() { list.clear() }

    /** Milliseconds until [item] clears by itself, or null when it never does (pinned). */
    fun expiresInMs(item: ClipItem): Long? = if (item.pinned) null else item.at + ttlOf(item) - clock()

    private fun ttlOf(item: ClipItem) = if (item.kind == Kind.CODE) CODE_TTL_MS else TTL_MS

    private fun purge(now: Long) { list.removeAll { !it.pinned && now - it.at >= ttlOf(it) } }

    // ---- storage: one small text file, written atomically

    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for (c in list) w.write("${c.id}\t${c.kind.name}\t${c.at}\t${if (c.pinned) 1 else 0}\t${esc(c.uri ?: "")}\t${esc(c.text)}\n")
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun load(file: File) {
        list.clear()
        if (!file.isFile) return
        file.forEachLine { line ->
            val f = line.split('\t')
            if (f.size >= 6) runCatching {
                list.add(ClipItem(f[0].toLong(), unesc(f[5]), Kind.valueOf(f[1]), f[2].toLong(), f[3] == "1", unesc(f[4]).ifEmpty { null }))
            }
        }
        nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
        purge(clock())
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] }); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    companion object {
        const val TTL_MS = 60 * 60_000L
        const val CODE_TTL_MS = 3 * 60_000L
        const val MAX_ITEMS = 50
        const val MAX_LENGTH = 2_000

        private val CODE = Regex("^(?:[A-Za-z]-)?(?:\\d{3}[ -]?\\d{3}|\\d{4,8})$")
        private val LINK = Regex("^(?:https?://|www\\.)\\S+$|^[\\w-]+(?:\\.[\\w-]+)*\\.(?:com|org|net|io|dev|app|co|in|uk|de|me|ly|gg|xyz)(?:/\\S*)?$", RegexOption.IGNORE_CASE)

        /** One-time codes ("482 913", "G-482913", "4829") are shown under Codes and expire quickly; web addresses under Links. */
        fun classify(t: String): Kind = when {
            CODE.matches(t) -> Kind.CODE
            LINK.matches(t) -> Kind.LINK
            else -> Kind.TEXT
        }
    }
}
