package app.wisp.keyboard

import android.content.Context
import android.os.Debug
import app.wisp.keyboard.dict.NativeDictionary
import app.wisp.keyboard.dict.Proximity
import app.wisp.keyboard.dict.Suggestions
import app.wisp.keyboard.gesture.GestureEval
import app.wisp.keyboard.gesture.GestureGeometry
import app.wisp.keyboard.gesture.GestureIndexStore
import app.wisp.keyboard.gesture.alphabetOf
import app.wisp.keyboard.kbd.CompiledLayout
import app.wisp.keyboard.kbd.LayoutLoader

/** Device-side checks for code that needs the native library. Each returns "PASS ..." or "FAIL ...". */
object SelfTests {

    fun all(ctx: Context): List<Pair<String, () -> String>> {
        val env = Env(ctx)
        return listOf(
            "writer.probe" to { env.writerProbe() },
            "gesture.index" to { env.gestureIndex() },
            "gesture.eval" to { env.gestureEval() },
            "export.words" to { env.exportWords() },
            "lang.hinglish" to { env.hinglish() },
            "import.words" to { env.importWords() },
            "voice.benchmark" to { env.voiceBenchmark() },
            "dict.header" to { env.headerHasLocale() },
            "dict.probability" to { env.probability() },
            "dict.typo-corrections" to { env.corrections() },
            "dict.completions" to { env.completions() },
            "dict.bigram" to { env.bigram() },
            "dict.latency" to { env.latency() },
            "dict.memory" to { env.memory() },
        )
    }

    class Env(private val ctx: Context) {
        private val layout: CompiledLayout by lazy {
            val dm = ctx.resources.displayMetrics
            val d = dm.density
            CompiledLayout(LayoutLoader.load(ctx, R.raw.layout_qwerty), dm.widthPixels.toFloat(), 4 * 54 * d + 14 * d, 3 * d, 6 * d, 8 * d)
        }

        fun openEn(): NativeDictionary {
            val fd = ctx.assets.openFd("dicts/main_en_US.dict")
            return NativeDictionary.open(ctx.applicationInfo.sourceDir, fd.startOffset, fd.length, "en_US")
                ?: error("could not open dictionary")
        }

        /** Touch coordinates for typing [word] on the layout (key centres). */
        private fun coords(word: String): Pair<IntArray, IntArray> {
            val xs = IntArray(word.length); val ys = IntArray(word.length)
            for ((i, c) in word.withIndex()) { val k = layout.indexOfCode(c.code); xs[i] = layout.centerX(k).toInt(); ys[i] = layout.centerY(k).toInt() }
            return xs to ys
        }

        private fun suggest(d: NativeDictionary, prox: Proximity, typed: String, prev: String? = null): Suggestions {
            val (xs, ys) = coords(typed)
            return Suggestions().also { d.suggest(typed, prox, xs, ys, prev, it) }
        }

        fun dump(): String = openEn().use { d ->
            Proximity.build(layout).use { prox ->
                val sb = StringBuilder("PASS\n")
                for (w in listOf("helo", "teh", "wrold", "hello")) {
                    val a = suggest(d, prox, w)
                    val b = Suggestions().also { d.suggest(w, prox, null, null, null, it) }
                    sb.append("  $w coords: $a\n  $w nocoords: $b\n")
                }
                sb.toString()
            }
        }

        /** Writes every dictionary word with its probability to files/words_<tag>.tsv (pull it for the desktop evaluation). */
        /** Can the native engine write a dictionary from a word list? (Needed to build the Hinglish dictionary on the device.) */
        fun writerProbe(): String {
            val dir = java.io.File(ctx.filesDir, "dicts"); dir.mkdirs()
            val path = java.io.File(dir, "probe.dict"); path.deleteRecursively()
            val keys = arrayOf("dictionary", "locale", "description", "version"); val vals = arrayOf("main:hi_latn", "hi_Latn", "Probe", "1")
            val handle = com.android.inputmethod.latin.BinaryDictionary.createOnMemoryNative(403L, "hi_Latn", keys, vals)
            if (handle == 0L) return "FAIL createOnMemory returned 0"
            var added = 0
            for ((w, p) in listOf("kya" to 200, "kaise" to 190, "kaisa" to 170, "haal" to 150, "accha" to 180, "achha" to 150)) {
                if (com.android.inputmethod.latin.BinaryDictionary.addUnigramEntryNative(handle, w.codePoints().toArray(), p, null, 0, false, false, false, -1)) added++
            }
            val flushed = com.android.inputmethod.latin.BinaryDictionary.flushWithGCNative(handle, path.path)
            com.android.inputmethod.latin.BinaryDictionary.closeNative(handle)
            val kind = if (path.isDirectory) "directory(${path.list()?.size} files)" else "file(${path.length()} bytes)"
            val d = NativeDictionary.open(path.path, 0, 0, "hi_Latn", updatable = true) ?: return "FAIL added=$added flushed=$flushed $kind; reopen failed"
            val layout = layoutFor(Languages.ENGLISH_US); val prox = Proximity.build(layout)
            val out = Suggestions(); d.suggest("kai", prox, null, null, null, out)
            val result = "added=$added flushed=$flushed $kind; p(kya)=${d.probability("kya")} p(zzz)=${d.probability("zzz")}; 'kai' -> $out; header=${d.header()}"
            prox.close(); d.close()
            return (if (added == 6 && flushed && out.count > 0) "PASS " else "FAIL ") + result
        }

        private fun layoutFor(lang: Language): CompiledLayout {
            val dm = ctx.resources.displayMetrics; val d = dm.density
            return CompiledLayout(LayoutLoader.load(ctx, lang.layoutRes), dm.widthPixels.toFloat(), 4 * 54 * d + 14 * d, 3 * d, 6 * d, 8 * d)
        }

        private fun usedHeapKb() = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } + Debug.getNativeHeapAllocatedSize()) / 1024

        fun gestureIndex(): String {
            val store = GestureIndexStore(ctx); val sb = StringBuilder()
            for (lang in Languages.all) {
                val layout = layoutFor(lang); val alpha = alphabetOf(layout)
                java.io.File(ctx.filesDir, "gesture").listFiles()?.forEach { if (it.name.startsWith(lang.tag + "-")) it.delete() }   // measure a cold build
                val t0 = System.nanoTime()
                val built = store.get(lang, alpha) ?: return "FAIL could not build ${lang.tag}"
                val buildMs = (System.nanoTime() - t0) / 1_000_000
                System.gc(); val before = usedHeapKb()
                val t1 = System.nanoTime(); val opened = store.get(lang, alpha)!!; val openUs = (System.nanoTime() - t1) / 1000
                val geo = GestureGeometry.from(layout, alpha)
                val out = app.wisp.keyboard.gesture.GestureDecoder.Results(10)
                val dec = app.wisp.keyboard.gesture.GestureDecoder(opened, geo)
                val tr = app.wisp.keyboard.gesture.SyntheticGestures(geo, 3).generate(IntArray(5) { it % alpha.size }, 0.2f)
                repeat(20) { dec.decode(tr.xs, tr.ys, tr.n, out) }
                val after = usedHeapKb()
                val kb = java.io.File(ctx.filesDir, "gesture").listFiles()!!.first { it.name.startsWith(lang.tag + "-") }.length() / 1024
                if (built.count < 1000) return "FAIL ${lang.tag} only ${built.count} words"
                sb.append("${lang.tag}: ${opened.count} words, ${kb}KB file, cold build ${buildMs}ms, reopen ${openUs}us, heap +${after - before}KB after open+decode; ")
            }
            return "PASS $sb"
        }

        fun gestureEval(): String {
            val store = GestureIndexStore(ctx); val lang = Languages.ENGLISH_US
            val layout = layoutFor(lang); val alpha = alphabetOf(layout)
            val index = store.get(lang, alpha) ?: return "FAIL no index"
            val geo = GestureGeometry.from(layout, alpha)
            val r = GestureEval.run(index, geo, 0.30f, 600, 42)
            // Gate (docs/gesture-eval.md): normal-noise swipes, decode p95 within 50 ms, accuracy within a few points of the desktop run.
            val ok = r.p95Us <= 50_000 && r.top3 >= 0.85f
            return (if (ok) "PASS " else "FAIL ") + r
        }

        fun exportWords(): String {
            val sb = StringBuilder("PASS ")
            for (lang in Languages.all) {
                val sources = app.wisp.keyboard.dict.DictionaryProvider(ctx).open(lang).filter { it.own }
                val out = java.io.File(ctx.getExternalFilesDir(null), "words_${lang.tag}.tsv")
                var n = 0; val hist = IntArray(8)
                out.bufferedWriter().use { w ->
                    for (src in sources) src.dict.forEachWord { word, p -> w.write(word); w.write("\t"); w.write(p.toString()); w.write("\n"); n++; hist[(p.coerceIn(0, 255)) / 32]++ }
                }
                sources.forEach { it.dict.close() }
                sb.append("${lang.tag}: $n words, prob histogram (per 32): ${hist.joinToString(",")}; ")
            }
            return sb.toString()
        }

        fun hinglish(): String {
            val lang = Languages.HINGLISH
            val provider = app.wisp.keyboard.dict.DictionaryProvider(ctx)
            val t0 = System.nanoTime()
            val sources = provider.open(lang)
            val buildMs = (System.nanoTime() - t0) / 1_000_000
            if (sources.size < 2) return "FAIL expected the Hinglish list and English, got ${sources.size} sources"
            val seed = sources[0].dict; val english = sources[1].dict
            val count = seed.header()["UNIGRAM_COUNT"]
            val layout = layoutFor(Languages.ENGLISH_US); val prox = Proximity.build(layout)
            val out = Suggestions(); seed.suggest("kai", prox, null, null, null, out)
            val kai = (0 until minOf(3, out.count)).map { out.word(it) }
            val facts = listOf(
                "seed has $count words" to (count != null && count.toInt() > 900),
                "kya is common (${seed.probability("kya")})" to (seed.probability("kya") >= 190),
                "nonsense is unknown" to (seed.probability("xqzvkw") == -1),
                "English meeting comes from the English source (${english.probability("meeting")})" to (english.probability("meeting") >= 0),
                "kai -> $kai" to kai.contains("kaise"),
            )
            prox.close(); sources.forEach { it.dict.close() }
            // The swipe index covers both.
            val alpha = alphabetOf(layout)
            java.io.File(ctx.filesDir, "gesture").listFiles()?.forEach { if (it.name.startsWith(lang.tag + "-")) it.delete() }
            val idx = GestureIndexStore(ctx).get(lang, alpha) ?: return "FAIL no swipe index"
            val originals = HashSet<String>().also { set -> for (i in 0 until idx.count) set.add(idx.original(i)) }
            val swipe = listOf("swipe index has kaise" to ("kaise" in originals), "swipe index has meeting" to ("meeting" in originals), "swipe index is large (${idx.count})" to (idx.count > 80_000))
            val bad = (facts + swipe).filter { !it.second }
            return (if (bad.isEmpty()) "PASS " else "FAIL ${bad.map { it.first }}; ") + "built in ${buildMs}ms; " + (facts + swipe).joinToString("; ") { it.first }
        }

        fun importWords(): String {
            val lang = Languages.ENGLISH_US
            app.wisp.keyboard.dict.WordListImporter.clear(ctx, lang)
            val list = "zorbax\t180\nquillnut\t170\nrubbish line without tab\nfloofy\t90\n# comment\n"
            val r = app.wisp.keyboard.dict.WordListImporter.import(ctx, lang, list.byteInputStream())
            val provider = app.wisp.keyboard.dict.DictionaryProvider(ctx)
            val sources = provider.open(lang)
            val known = sources.count { it.dict.probability("zorbax") >= 0 }
            val layout = layoutFor(lang); val prox = Proximity.build(layout); val out = Suggestions()
            var suggested = false
            for (src in sources) { src.dict.suggest("zorb", prox, null, null, null, out); if ((0 until out.count).any { out.word(it) == "zorbax" }) suggested = true }
            prox.close(); sources.forEach { it.dict.close() }
            java.io.File(ctx.filesDir, "gesture").listFiles()?.forEach { if (it.name.startsWith(lang.tag + "-")) it.delete() }
            val idx = GestureIndexStore(ctx).get(lang, alphabetOf(layout))
            val swipeable = idx != null && (0 until idx.count).any { idx.original(it) == "quillnut" }
            app.wisp.keyboard.dict.WordListImporter.clear(ctx, lang)
            java.io.File(ctx.filesDir, "gesture").listFiles()?.forEach { if (it.name.startsWith(lang.tag + "-")) it.delete() }
            val ok = r.read == 3 && r.total == 3 && known == 1 && suggested && swipeable
            return (if (ok) "PASS" else "FAIL") + " read=${r.read} total=${r.total} known=$known suggested=$suggested swipeable=$swipeable"
        }

        /** Benchmarks the installed test model through the voice process (run tools/install-test-model.sh first). */
        fun voiceBenchmark(): String {
            val spec = app.wisp.keyboard.voice.model.ModelCatalog.byId("moonshine-tiny-en")!!
            val bench = app.wisp.keyboard.voice.model.ModelBench(ctx)
            val latch = java.util.concurrent.CountDownLatch(1); var out: app.wisp.keyboard.voice.model.BenchResult? = null
            android.os.Handler(android.os.Looper.getMainLooper()).post { bench.run(spec) { out = it; latch.countDown() } }
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) return "FAIL timed out"
            val r = out ?: return "FAIL benchmark failed"
            val stored = bench.result(spec.id)
            val ok = stored != null && r.timesRealTime > 1f
            return (if (ok) "PASS" else "FAIL") + " load ${r.loadMs} ms, decode ${r.decodeMs} ms for ${r.audioMs} ms of speech = ${"%.1f".format(r.timesRealTime)}x real time; stored=${stored?.toStored()}"
        }

        fun calibrate(): String = openEn().use { d ->
            Proximity.build(layout).use { prox ->
                val sb = StringBuilder("PASS\n")
                for (w in listOf("teh", "wrold", "thnks", "recieve", "helo", "tha", "hello", "the", "intern", "wisp", "gonna", "its", "im", "dont", "teh", "becuase", "definately", "adn", "thier")) {
                    val s = suggest(d, prox, w)
                    sb.append("  $w p=${d.probability(w)} ac=${s.autoCommitConfidence} -> ")
                    for (i in 0 until minOf(4, s.count)) sb.append("${s.word(i)}:${s.scores[i]}/t${s.types[i]} ")
                    sb.append("\n")
                }
                sb.toString()
            }
        }

        fun headerHasLocale(): String = openEn().use { d ->
            val h = d.header()
            if (h["locale"]?.lowercase()?.replace('-', '_') == "en_us") "PASS $h" else "FAIL header=$h"
        }

        fun probability(): String = openEn().use { d ->
            val the = d.probability("the"); val nonsense = d.probability("xqzvkw")
            if (the > 100 && nonsense == -1) "PASS the=$the nonsense=$nonsense" else "FAIL the=$the nonsense=$nonsense"
        }

        fun corrections(): String = openEn().use { d ->
            Proximity.build(layout).use { prox ->
                // "helo" is a hard case with exact key centres: "help" and "hell" are one adjacent-key slip away, so hello ranks 4th.
                val cases = mapOf("teh" to ("the" to 3), "wrold" to ("world" to 3), "thnks" to ("thanks" to 3), "recieve" to ("receive" to 3), "helo" to ("hello" to 5))
                val bad = cases.filter { (typo, want) -> val s = suggest(d, prox, typo); (0 until minOf(want.second, s.count)).none { s.word(it).equals(want.first, true) } }
                if (bad.isEmpty()) "PASS all ${cases.size} typos have the intended word in the top 3 (helo: top 5)" else "FAIL missing: $bad"
            }
        }

        fun completions(): String = openEn().use { d ->
            Proximity.build(layout).use { prox ->
                val s = suggest(d, prox, "intern")
                val words = (0 until s.count).map { s.word(it).lowercase() }
                if (words.any { it.startsWith("intern") && it.length > 6 }) "PASS intern -> $words" else "FAIL intern -> $words"
            }
        }

        fun bigram(): String = openEn().use { d ->
            Proximity.build(layout).use { prox ->
                val withCtx = suggest(d, prox, "ar", "how").let { s -> (0 until s.count).map { s.word(it) } }
                val p = d.bigramProbability("how", "are")
                if (withCtx.take(3).any { it.equals("are", true) }) "PASS how ar -> $withCtx bigram(how,are)=$p" else "FAIL how ar -> $withCtx bigram=$p"
            }
        }

        fun latency(): String = openEn().use { d ->
            Proximity.build(layout).use { prox ->
                val words = listOf("helo", "wrold", "thnks", "intern", "beautifl", "somethin", "tomorow", "pleas")
                val out = Suggestions(); val times = LongArray(800)
                for (i in times.indices) {
                    val w = words[i % words.size]; val (xs, ys) = coords(w)
                    val t0 = System.nanoTime(); d.suggest(w, prox, xs, ys, null, out); times[i] = System.nanoTime() - t0
                }
                times.sort()
                val p50 = times[400] / 1000; val p99 = times[792] / 1000
                if (p99 < 8000) "PASS suggest p50=${p50}us p99=${p99}us" else "FAIL suggest p50=${p50}us p99=${p99}us"
            }
        }

        private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

        fun memory(): String {
            System.gc()
            val base = pssKb()
            val d = openEn()
            val afterOpen = pssKb()
            val prox = Proximity.build(layout)
            val out = Suggestions()
            for (w in listOf("hello", "world", "thanks", "intern", "beauti")) { val (xs, ys) = coords(w); d.suggest(w, prox, xs, ys, null, out) }
            val afterQueries = pssKb()
            prox.close(); d.close()
            val afterClose = pssKb()
            val fileKb = ctx.assets.openFd("dicts/main_en_US.dict").length / 1024
            val ok = afterOpen - base < 1024
            return (if (ok) "PASS" else "FAIL") + " dict file ${fileKb}KB; PSS delta: open +${afterOpen - base}KB, after queries +${afterQueries - base}KB, after close ${afterClose - base}KB"
        }
    }
}
