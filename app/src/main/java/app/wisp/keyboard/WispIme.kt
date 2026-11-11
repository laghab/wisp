package app.wisp.keyboard

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.wisp.keyboard.dict.SuggestionEngine
import app.wisp.keyboard.gesture.GestureEngine
import app.wisp.keyboard.emoji.EmojiData
import app.wisp.keyboard.emoji.EmojiPanelView
import app.wisp.keyboard.emoji.RecentEmoji
import app.wisp.keyboard.kbd.FieldInfo
import app.wisp.keyboard.kbd.FieldKind
import app.wisp.keyboard.kbd.InputController
import app.wisp.keyboard.kbd.KeyCodes
import app.wisp.keyboard.kbd.KeyboardView
import app.wisp.keyboard.kbd.LayoutLoader
import app.wisp.keyboard.kbd.LayoutSpec
import app.wisp.keyboard.kbd.Shift
import app.wisp.keyboard.kbd.SuggestionBarView
import app.wisp.keyboard.personal.LearningPolicy
import app.wisp.keyboard.settings.WispSettings
import app.wisp.keyboard.kbd.Palette
import app.wisp.keyboard.clipboard.ClipItem
import app.wisp.keyboard.clipboard.ClipboardHistory
import app.wisp.keyboard.clipboard.ClipboardPanelView
import app.wisp.keyboard.clipboard.Kind
import app.wisp.keyboard.kbd.FloatHandle
import app.wisp.keyboard.kbd.LetterLayouts
import app.wisp.keyboard.kbd.Placement
import app.wisp.keyboard.kbd.PlacementEditView
import app.wisp.keyboard.kbd.PlacementRail
import android.inputmethodservice.InputMethodService.Insets
import app.wisp.keyboard.kbd.LayoutGuard
import app.wisp.keyboard.style.KeyboardStyle
import app.wisp.keyboard.voice.MicPermissionActivity
import app.wisp.keyboard.voice.VoiceController
import app.wisp.keyboard.voice.llm.RewriteController
import app.wisp.keyboard.voice.llm.RewritePanelView
import app.wisp.keyboard.voice.llm.Tone
import app.wisp.keyboard.voice.text.TextProcessor
import android.content.Intent

/** Entry point of the keyboard. */
class WispIme : InputMethodService() {

    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS_MORE, NUMPAD, EMOJI, CLIPBOARD, REWRITE }

    private val editor = ImeEditor(this)
    private val controller = InputController(editor)
    private var engine: SuggestionEngine? = null
    private var gestureEngine: GestureEngine? = null

    private var keys: KeyboardView? = null
    private var bar: SuggestionBarView? = null
    private var emojiPanel: View? = null
    private var emojiBar: KeyboardView? = null
    private var voice: VoiceController? = null
    private var mode = Mode.LETTERS
    private var incognito = false
    private var packageName: String? = null
    private var personalWords: List<String> = emptyList()

    private val enabledLanguages = Languages.all
    private var language = Languages.ENGLISH_US

    /** The focused field; password fields are never learned from or suggested for. */
    var field: FieldInfo = FieldInfo.from(0, 0)
        private set

    override fun onCreate() {
        Probe.start()
        super.onCreate()
        incognito = WispSettings.get(this).getBoolean("incognito", false)
        language = Languages.byTag(WispSettings.get(this).getString("language")) ?: Languages.ENGLISH_US
        getSystemService(android.content.ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener)
        Probe.mark("ime_created")
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onDestroy() {
        getSystemService(android.content.ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener)
        main.removeCallbacks(clipSave); clipHistory.save(clipFile)
        engine?.close(); engine = null
        gestureEngine?.close(); gestureEngine = null
        voice?.release(); voice = null
        super.onDestroy()
    }

    /** Show the on-screen keyboard next to a hardware one when the user's system setting asks for it. */
    override fun onEvaluateInputViewShown(): Boolean =
        super.onEvaluateInputViewShown() ||
            Settings.Secure.getInt(contentResolver, "show_ime_with_hard_keyboard", 0) == 1

    override fun onCreateInputView(): View {
        val listener = object : KeyboardView.Listener {
            override fun onKey(code: Int, x: Int, y: Int) = handleKey(code, x, y)
            override fun onRepeat(word: Boolean) { if (emojiSearching) { emojiView?.backspaceQuery() } else controller.onRepeat(word) }
            override fun onWordSwipe(chars: Int) { if (!emojiSearching) controller.onWordSwipe(chars) }
            override fun onWordSwipeEnd(chars: Int) { if (!emojiSearching) controller.onWordSwipeEnd(chars) }
            override fun onShiftLetter(code: Int, x: Int, y: Int) = controller.onShiftLetter(code, x, y)
            override fun onCursorSteps(steps: Int) { if (!emojiSearching) controller.onCursorSteps(steps) }
            override fun onLongPress(code: Int) { if (code == KeyCodes.SWITCH) showPicker() }
            override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) { if (!emojiSearching) gestureEngine?.decode(xs, ys, count, null) }
        }
        Probe.mark("p_start")
        val spec0 = lettersSpec()
        Probe.mark("p_spec")
        val k = KeyboardView(this, spec0, palette)
        k.listener = listener
        Probe.mark("p_keys")

        // The dictionary runs on its own thread; its answers come back to the controller on the main thread.
        lateinit var gest: GestureEngine
        val eng = SuggestionEngine(this, onResult = { controller.onSuggestions(it) }, onPersonalChanged = { gest.setPersonal(it); personalWords = it.words.map { w -> w.first } })
        engine = eng
        eng.setLanguage(language)
        controller.suggester = eng
        controller.learner = eng
        gest = GestureEngine(this) { controller.onGestureOutcome(it) }
        gestureEngine = gest
        controller.onGestureWord = { word, strong -> gest.learnFromChoice(word, strong) }
        controller.onGestureRejected = { gest.rememberFailedSwipe() }
        controller.onGestureCorrected = { word -> gest.learnFromCorrection(word) }
        gest.setLanguage(language)
        Probe.mark("p_engines")
        k.onLayoutBuilt = { eng.setLayout(it); gest.setLayout(it) }

        val b = SuggestionBarView(this, palette)
        Probe.mark("p_bar")
        b.listener = object : SuggestionBarView.Listener {
            override fun onMic() {
                val vc = voice
                val problem = vc?.failure?.problem
                if (problem != null) fixVoiceProblem(problem) else if (vc?.isActive == true) vc.stop() else openVoice()
            }
            override fun onSettings() = openSettings(null)
            override fun onSuggestion(index: Int) = controller.onSuggestionTapped(index)
            override fun onTool(tool: SuggestionBarView.Tool) = when (tool) {
                SuggestionBarView.Tool.SELECT_ALL -> controller.selectAll()
                SuggestionBarView.Tool.CUT -> controller.cut()
                SuggestionBarView.Tool.COPY -> controller.copy()
                SuggestionBarView.Tool.PASTE -> controller.paste()
                SuggestionBarView.Tool.CLIPBOARD -> setMode(Mode.CLIPBOARD)
                SuggestionBarView.Tool.REWRITE -> setMode(Mode.REWRITE)
                SuggestionBarView.Tool.EMOJI -> setMode(Mode.EMOJI)
                SuggestionBarView.Tool.RESIZE -> startPlacementEdit()
                SuggestionBarView.Tool.THEMES -> openSettings("look")
                SuggestionBarView.Tool.SETTINGS -> openSettings(null)
                SuggestionBarView.Tool.INCOGNITO -> { incognito = !incognito; b.incognito = incognito; WispSettings.get(this@WispIme).putBoolean("incognito", incognito); updateLearning() }
            }
        }
        controller.onBarChanged = { b.setState(it) }
        controller.onGestureInserted = { app.wisp.keyboard.gesture.GlideStats.record(this) }

        // Emoji panel: the panel view (made when it opens, dropped when it closes) over a one-row bar (ABC, globe, space, backspace).
        Probe.mark("p_pre_emoji")
        val eb = KeyboardView(this, LayoutLoader.load(this, R.raw.layout_emoji_bar), palette)
        eb.listener = listener
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(eb, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }

        // Voice typing lives in the toolbar strip (SuggestionBarView.setVoice), so it has no panel and the keys never change size.
        Probe.mark("p_panels")
        voice = VoiceController(this, onChange = ::updateVoiceBar, onResult = { text -> controller.onDictation(text); updateVoiceBar() })
        Probe.mark("p_voice")
        val stack = app.wisp.keyboard.kbd.PanelStack(this, k)
        panelStack = stack
        stack.addView(k, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        stack.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val grip = FloatHandle(this, palette).also { it.visibility = View.GONE; it.onMoved = ::onFloatMoved; it.onGrab = { grabbed = frame } }
        root.addView(grip, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // The toolbar strip, with room over it for the password manager's login chips (inline autofill).
        val inline = android.widget.HorizontalScrollView(this).apply { visibility = View.GONE; isHorizontalScrollBarEnabled = false }
        inlineScroll = inline
        val stripBox = FrameLayout(this)
        stripBox.addView(b, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        stripBox.addView(inline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stripBox, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(stack, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        keys = k; bar = b; emojiPanel = panel; emojiBar = eb
        applyStyleIfChanged(force = true)
        b.incognito = incognito

        controller.onShiftChanged = { s -> k.shifted = s != Shift.OFF && mode == Mode.LETTERS; k.capsLock = s == Shift.CAPS && mode == Mode.LETTERS }
        Probe.mark("view_created")
        val outer = FrameLayout(this)
        outer.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        val railView = PlacementRail(this, style.themeFor(this)).also { it.visibility = View.GONE; it.onAction = ::onRail }
        outer.addView(railView, FrameLayout.LayoutParams(0, 0, android.view.Gravity.BOTTOM))
        host = outer; floatRoot = root; floatGrip = grip; rail = railView
        applyStyleIfChanged(force = true)
        return outer
    }

    // ---- emoji, kaomoji and symbols: opened on demand, released on close; search types through the keyboard's own keys -----------

    private var emojiView: EmojiPanelView? = null
    private var emojiSearching = false
    private val emojiData by lazy {
        fun raw(id: Int) = resources.openRawResource(id).bufferedReader().use { it.readText() }
        val probe = android.graphics.Paint()
        EmojiData.parse(raw(R.raw.emoji), raw(R.raw.kaomoji), raw(R.raw.symbols)) { probe.hasGlyph(it) }       // only emoji this phone can draw
    }
    private val recentEmoji by lazy { RecentEmoji({ WispSettings.get(this).getString("emoji_recent", "").orEmpty() }, { WispSettings.get(this).putString("emoji_recent", it) }) }

    private fun showEmojiPanel() {
        val panel = emojiPanel as? LinearLayout ?: return
        if (emojiView != null) return
        val d = resources.displayMetrics.density
        val v = EmojiPanelView(this, emojiData, recentEmoji, style.themeFor(this))
        v.setTheme(style.themeFor(this))
        v.listener = object : EmojiPanelView.Listener {
            override fun onInsert(text: String, isEmoji: Boolean) { controller.finishWord(); editor.commit(text); if (isEmoji) recentEmoji.add(text) }
            override fun onSearchTapped() = setEmojiSearching(true)
        }
        emojiView = v
        panel.addView(v, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun hideEmojiPanel() {
        setEmojiSearching(false, leaving = true)
        val v = emojiView ?: return
        emojiView = null
        (emojiPanel as? LinearLayout)?.removeView(v)
    }

    /** Search mode: the panel shrinks to the box and a couple of result rows, the keys show below it and type into the box. */
    private fun setEmojiSearching(on: Boolean, leaving: Boolean = false) {
        val v = emojiView; val k = keys; val panel = emojiPanel ?: return
        if (on == emojiSearching && !(on && v == null)) return
        emojiSearching = on
        val d = resources.displayMetrics.density
        val compactH = (150 * d).toInt()
        v?.compact = on
        v?.layoutParams = (v?.layoutParams as? LinearLayout.LayoutParams)?.also { it.height = if (on) compactH else (300 * d).toInt() }
        emojiBar?.visibility = if (on) View.GONE else View.VISIBLE
        // The keys sit under the panel while searching.
        if (k != null) {
            (k.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = if (on) compactH else 0; k.layoutParams = it }
            if (on) { k.setSpec(lettersSpec()); k.visibility = View.VISIBLE; panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).also { it.height = compactH; it.gravity = android.view.Gravity.TOP } }
            else { if (!leaving) k.visibility = View.INVISIBLE; panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).also { it.height = ViewGroup.LayoutParams.WRAP_CONTENT } }
        }
        panel.requestLayout()
    }

    /** While searching, the keys edit the search box and nothing else. Returns true when the key was handled here. */
    private fun searchKey(code: Int): Boolean {
        if (!emojiSearching) return false
        val v = emojiView ?: return false
        when {
            code == KeyCodes.BACKSPACE -> if (!v.backspaceQuery()) setEmojiSearching(false)
            code == KeyCodes.ENTER -> setEmojiSearching(false)
            code == KeyCodes.SPACE -> v.appendQuery(" ")
            code > 0 -> v.appendQuery(String(Character.toChars(if (controller.shift != Shift.OFF) Character.toUpperCase(code) else code)).lowercase())
            code == KeyCodes.SHIFT -> return false
        }
        return true
    }

    private fun handleKey(code: Int, x: Int, y: Int) {
        if (voice?.isActive == true) { voice?.stop(); return }      // touching the keys while listening ends the dictation (its text arrives a moment later)
        if (searchKey(code)) return
        when (code) {
            KeyCodes.SWITCH -> switchKeyboard()
            KeyCodes.SYMBOLS -> setMode(Mode.SYMBOLS)
            KeyCodes.ABC -> setMode(Mode.LETTERS)
            KeyCodes.SYMBOLS_MORE -> setMode(if (mode == Mode.SYMBOLS) Mode.SYMBOLS_MORE else Mode.SYMBOLS)
            KeyCodes.NUMPAD -> setMode(Mode.NUMPAD)
            KeyCodes.EMOJI -> setMode(Mode.EMOJI)
            KeyCodes.LANGUAGE -> cycleLanguage()
            else -> controller.onKey(code, x, y)
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        val k = keys ?: return
        k.heightRows = lettersSpec().rows.size       // every page and panel is as tall as the letters
        syncGap()
        when (m) {
            Mode.LETTERS -> k.setSpec(lettersSpec())
            Mode.SYMBOLS -> k.setSpec(LayoutLoader.load(this, R.raw.layout_symbols))
            Mode.SYMBOLS_MORE -> k.setSpec(LayoutLoader.load(this, R.raw.layout_symbols_more))
            Mode.NUMPAD -> k.setSpec(LayoutLoader.load(this, R.raw.layout_numpad))
            Mode.EMOJI, Mode.CLIPBOARD, Mode.REWRITE -> {}
        }
        k.visibility = if (m == Mode.EMOJI || m == Mode.CLIPBOARD || m == Mode.REWRITE) View.INVISIBLE else View.VISIBLE
        if (m == Mode.REWRITE) showRewritePanel() else hideRewritePanel()
        if (m == Mode.EMOJI) showEmojiPanel() else hideEmojiPanel()
        if (m == Mode.CLIPBOARD) showClipboardPanel() else hideClipboardPanel()
        fadeTo(emojiPanel, m == Mode.EMOJI)
        if (m != Mode.EMOJI && m != Mode.CLIPBOARD && m != Mode.REWRITE) {
            k.setLabel(KeyCodes.ENTER, field.enter.label)
            k.languageName = language.name; k.languageCode = enabledLanguages[(enabledLanguages.indexOf(language) + 1) % enabledLanguages.size].code   // the space bar names this language; its menu offers the next
            k.shifted = m == Mode.LETTERS && controller.shift != Shift.OFF; k.capsLock = m == Mode.LETTERS && controller.shift == Shift.CAPS
        }
        emojiBar?.setLabel(KeyCodes.ENTER, field.enter.label)
    }

    /** Shows or hides a panel; a panel that appears fades in over a moment (nothing runs when it is not changing, or when the phone's animations are off). */
    private fun fadeTo(panel: View?, show: Boolean) {
        panel ?: return
        if (show == (panel.visibility == View.VISIBLE)) return
        panel.animate().cancel()
        if (!show) { panel.alpha = 1f; panel.visibility = View.GONE; return }
        panel.visibility = View.VISIBLE
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { panel.alpha = 1f; return }
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(PANEL_FADE_MS).start()
    }

    /** Ordinary text fields use the current language's layout (with the language key when there are several); other fields keep their special layouts. */
    private fun lettersSpec(): LayoutSpec {
        val letterRes = if (language.layoutRes == R.raw.layout_qwerty) LetterLayouts.res(style.layout) else language.layoutRes
        val base = if (field.kind == FieldKind.TEXT) LayoutLoader.load(this, letterRes)
        else LayoutLoader.load(this, layoutFor(field.kind))
        val letters = field.kind == FieldKind.TEXT || field.kind == FieldKind.EMAIL || field.kind == FieldKind.URL
        return if (style.numberRow && letters) LayoutGuard.withNumberRow(base) else base
    }

    // ---- look and feel (task group 8) --------------------------------------------------------------------------

    private val backups by lazy { app.wisp.keyboard.backup.BackupManager(this) }
    // ---- where the keyboard sits: docked, raised with a gap, narrower, one-handed, floating or split (see Placement) -----------------

    private var host: FrameLayout? = null
    private var floatRoot: View? = null
    private var floatGrip: FloatHandle? = null
    private var rail: PlacementRail? = null
    private var placementEditor: PlacementEditView? = null
    private var placement = Placement()
    private var frame: Placement.Frame? = null

    private fun landscape() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private fun screenDp() = resources.displayMetrics.let { it.widthPixels / it.density to it.heightPixels / it.density }

    /** Shows the saved placement for the current orientation. */
    private fun loadPlacement() { applyPlacement(Placement.load(this, landscape()), save = false) }

    /** Solves [p] for this screen and applies it live: the keyboard's width, position and key height, the gap, and the rail. */
    private fun applyPlacement(p: Placement, save: Boolean) {
        val h = host ?: return; val r = floatRoot ?: return
        val (sw, sh) = screenDp()
        val f = p.solve(sw, sh)
        placement = p; frame = f
        val d = resources.displayMetrics.density
        val lp = r.layoutParams as FrameLayout.LayoutParams
        val gapPx = (f.gap * d).toInt(); val floating = p.mode == Placement.Mode.FLOATING
        lp.width = (f.width * d).toInt(); lp.leftMargin = (f.left * d).toInt()
        // A floating keyboard leaves the app showing below it; a raised one is filled down to the screen's bottom (see syncGap).
        lp.bottomMargin = if (floating) gapPx else 0
        r.layoutParams = lp
        syncGap()
        keys?.rowScale = f.rowScale
        keys?.splitGapPx = f.splitGap * d
        floatGrip?.visibility = if (p.mode == Placement.Mode.FLOATING && placementEditor == null) View.VISIBLE else View.GONE
        val editing = placementEditor
        // The window must not change size while a finger is dragging the keyboard, or the touch coordinates shift under it: floating keeps
        // the window at its full height (only the keyboard takes touches), and so does edit mode, which needs room above for the raised keyboard.
        h.minimumHeight = when {
            editing != null || p.mode == Placement.Mode.FLOATING -> ((sh - 96f) * d).toInt()
            else -> 0
        }
        editing?.show(p, f)
        updateRail(p, f)
        h.requestLayout()
        if (save) Placement.save(this, landscape(), p)
    }

    /**
     * The gap below a raised keyboard is filled with the keyboard's own background. While typing it stays empty, because the keys are held
     * higher for the thumbs' sake; while a panel (emoji, clipboard, rewrite) is open the panel grows into it, since browsing wants room.
     */
    private fun syncGap() {
        val r = floatRoot ?: return
        val gapPx = ((frame?.gap ?: 0f) * resources.displayMetrics.density).toInt()
        val floating = placement.mode == Placement.Mode.FLOATING
        val panel = mode == Mode.EMOJI || mode == Mode.CLIPBOARD || mode == Mode.REWRITE
        r.setPadding(r.paddingLeft, r.paddingTop, r.paddingRight, if (floating || panel) 0 else gapPx)
        (panelStack as? app.wisp.keyboard.kbd.PanelStack)?.extraBottom = if (!floating && panel) gapPx else 0
    }

    private fun updateRail(p: Placement, f: Placement.Frame) {
        val rl = rail ?: return; val d = resources.displayMetrics.density
        val oneHanded = (p.mode == Placement.Mode.ONE_HANDED_LEFT || p.mode == Placement.Mode.ONE_HANDED_RIGHT) && placementEditor == null
        rl.visibility = if (oneHanded) View.VISIBLE else View.GONE
        if (!oneHanded) return
        val (sw, _) = screenDp()
        val lp = rl.layoutParams as FrameLayout.LayoutParams
        lp.width = ((sw - f.width) * d).toInt().coerceAtLeast(0)
        lp.height = (f.height * d).toInt()
        lp.gravity = android.view.Gravity.BOTTOM or if (p.mode == Placement.Mode.ONE_HANDED_LEFT) android.view.Gravity.END else android.view.Gravity.START
        lp.bottomMargin = (f.gap * d).toInt()
        rl.layoutParams = lp
    }

    private fun onRail(action: String) {
        val p = placement
        applyPlacement(when (action) {
            "swap" -> p.copy(mode = if (p.mode == Placement.Mode.ONE_HANDED_LEFT) Placement.Mode.ONE_HANDED_RIGHT else Placement.Mode.ONE_HANDED_LEFT)
            else -> p.copy(mode = Placement.Mode.FULL, width = 1f)
        }, save = true)
    }

    private var grabbed: Placement.Frame? = null

    /** Floating: the grip along the top drags the keyboard; positions are measured from where the finger went down. */
    private fun onFloatMoved(dx: Float, dy: Float, done: Boolean) {
        val f = grabbed ?: return
        val d = resources.displayMetrics.density
        val (sw, _) = screenDp()
        val left = f.left + dx / d
        val x = if (sw - f.width > 1f) (left / (sw - f.width)).coerceIn(0f, 1f) else 0f
        applyPlacement(placement.copy(x = x, gap = f.gap - dy / d), save = done)
    }

    /** Edit mode, from the keyboard: resize, move and mode changes are applied live; Done keeps them, Reset starts again. */
    private fun startPlacementEdit() {
        val h = host ?: return
        if (placementEditor != null) return
        val view = PlacementEditView(this, style.themeFor(this), palette.accent)
        view.listener = object : PlacementEditView.Listener {
            override fun onChange(p: Placement) = applyPlacement(p, save = false)
            override fun onDone() = endPlacementEdit()
            override fun onReset() = applyPlacement(Placement(height = if (landscape()) 0.8f else 1f), save = false)
            override fun onNumberRow() { style.copy(numberRow = !style.numberRow).save(this@WispIme); applyStyleIfChanged(); view.numberRow = style.numberRow }
        }
        view.numberRow = style.numberRow
        placementEditor = view
        h.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyPlacement(placement, save = false)
    }

    private fun endPlacementEdit() {
        val view = placementEditor ?: return
        placementEditor = null
        host?.removeView(view)
        applyPlacement(placement, save = true)
    }

    // ---- password managers: the autofill service offers logins as chips, which replace the toolbar while they are available ---------------

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): android.view.inputmethod.InlineSuggestionsRequest? {
        val d = resources.displayMetrics.density
        val style = androidx.autofill.inline.UiVersions.newStylesBuilder()
            .addStyle(androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder().build()).build()
        val spec = android.widget.inline.InlinePresentationSpec.Builder(android.util.Size((60 * d).toInt(), (36 * d).toInt()), android.util.Size((resources.displayMetrics.widthPixels * 0.7f).toInt(), (40 * d).toInt()))
            .setStyle(style).build()
        return android.view.inputmethod.InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(5).build()
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        val scroll = inlineScroll ?: return false
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) { scroll.removeAllViews(); scroll.visibility = View.GONE; return false }
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0) }
        val views = arrayOfNulls<View>(suggestions.size)
        var pending = suggestions.size
        for ((i, s) in suggestions.withIndex()) {
            s.inflate(this, android.util.Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), mainExecutor) { v ->
                views[i] = v
                if (--pending == 0) {
                    row.removeAllViews()
                    for (x in views) if (x != null) row.addView(x, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = (8 * d).toInt() })
                    scroll.removeAllViews(); scroll.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    scroll.setBackgroundColor(palette.background)
                    scroll.visibility = if (row.childCount > 0) View.VISIBLE else View.GONE
                }
            }
        }
        return true
    }

    /**
     * When the keyboard does not fill the bottom of the window (a gap below it, narrower, floating), only the keyboard takes touches and the
     * app behind is told the keyboard is where it is, or, for floating, that nothing is covered.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val r = floatRoot; val h = host; val f = frame
        if (r == null || h == null || f == null || !isInputViewShown || placementEditor != null) return
        val (sw, _) = screenDp()
        if (f.gap == 0f && f.width >= sw - 0.5f && placement.mode != Placement.Mode.FLOATING) return
        val d = resources.displayMetrics.density
        val loc = IntArray(2); r.getLocationInWindow(loc)
        // Where the keyboard really starts, measured, not worked out from the placement: a number row or a taller panel makes it taller than the
        // plan, and an app told the wrong top leaves its text box hidden behind the keys.
        val top = if (placement.mode == Placement.Mode.FLOATING) h.height else loc[1]
        outInsets.contentTopInsets = top; outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + r.width, loc[1] + r.height)
        rail?.takeIf { it.visibility == View.VISIBLE }?.let { rl -> val l2 = IntArray(2); rl.getLocationInWindow(l2); outInsets.touchableRegion.union(android.graphics.Rect(l2[0], l2[1], l2[0] + rl.width, l2[1] + rl.height)) }
    }

    override fun onEvaluateFullscreenMode(): Boolean = if (placement.mode == Placement.Mode.FLOATING) false else super.onEvaluateFullscreenMode()

    private val palette = Palette()
    private var style = KeyboardStyle()
    private var styleKey = ""

    /** Re-reads the style settings and, only if something changed (or the phone switched between light and dark), restyles every view in place. */
    private fun applyStyleIfChanged(force: Boolean = false) {
        val next = KeyboardStyle.load(this)
        val key = next.signature + next.isDark(this) + resources.configuration.orientation
        if (!force && key == styleKey) return
        val numberRowChanged = next.numberRow != style.numberRow
        styleKey = key; style = next
        palette.set(next.palette(this))
        bar?.setTheme(next.themeFor(this))
        keys?.applyStyle(next); emojiBar?.applyStyle(next)
        bar?.invalidate()
        if (numberRowChanged && !force) setMode(mode)
        floatRoot?.setBackgroundColor(palette.background)
        rail?.setTheme(next.themeFor(this))
        loadPlacement()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStyleIfChanged()
    }

    /** The language key: next enabled language, with its own dictionary and layout. */
    private fun cycleLanguage() {
        controller.finishWord()
        language = enabledLanguages[(enabledLanguages.indexOf(language) + 1) % enabledLanguages.size]
        WispSettings.get(this).putString("language", language.tag)
        engine?.setLanguage(language)
        gestureEngine?.setLanguage(language)
        controller.autoCorrect = language.autoCorrect && style.autoCorrect
        setMode(Mode.LETTERS)
    }

    private fun openVoice() {
        if (!field.suggest || field.kind == FieldKind.PASSWORD) return        // never record into a password field
        voice?.start()
        updateVoiceBar()
    }

    /** The toolbar strip shows the live transcript while the microphone is open (or why it could not start). */
    private fun updateVoiceBar() {
        val vc = voice ?: return
        val warming = vc.phase == VoiceController.Phase.WARMING
        val message = vc.failure?.message ?: if (vc.phase == VoiceController.Phase.FINISHING) "Finishing…" else if (warming && vc.partial.isBlank()) "Warming up… you can start talking" else null
        bar?.setVoice(vc.isActive, vc.partial, message)
    }

    private fun fixVoiceProblem(problem: VoiceController.Problem) {
        when (problem) {
            VoiceController.Problem.NO_PERMISSION -> startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            VoiceController.Problem.NO_MODEL -> startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCREEN, SettingsActivity.SCREEN_VOICE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> { voice?.cancel(); return }
        }
        voice?.cancel(); updateVoiceBar(); requestHideSelf(0)
    }

    private fun layoutFor(kind: FieldKind): Int = when (kind) {
        FieldKind.NUMBER, FieldKind.PHONE, FieldKind.DATETIME -> R.raw.layout_number
        FieldKind.EMAIL -> R.raw.layout_qwerty_email
        FieldKind.URL -> R.raw.layout_qwerty_url
        else -> R.raw.layout_qwerty
    }

    /** Back to the previous keyboard; if there is none, offer the system picker. */
    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToPreviousInputMethod()) return
        showPicker()
    }

    /** Whether what is typed here may be learned: not in passwords, no-learning fields, incognito or excluded apps. */
    private fun updateLearning() {
        val excluded = WispSettings.get(this).getStringSet("excluded_apps")
        controller.learningEnabled = LearningPolicy.allowed(field, incognito, packageName, excluded)
    }

    /** "omw=On my way" per line. */
    private fun parseShortcuts(text: String?): Map<String, String> =
        text.orEmpty().lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }
            .filter { it.second.isNotEmpty() }.toMap()

    /** Opens Wisp's settings (the home screen, or [screen]) and hides the keyboard. */
    private fun openSettings(screen: String?) {
        val intent = Intent(this, SettingsActivity::class.java).apply { if (screen != null) putExtra(SettingsActivity.EXTRA_SCREEN, screen) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(intent) } catch (e: RuntimeException) { android.util.Log.w("Wisp", "could not open settings", e); return }
        // Hide after the other window is on its way, not while this one is still handling the tap that asked for it.
        window?.window?.decorView?.post { requestHideSelf(0) } ?: requestHideSelf(0)
    }

    // ---- rewrite: a small on-device model rewrites the selected text or the current sentence; nothing changes until the user chooses ----

    private var rewritePanel: RewritePanelView? = null
    private var rewriteControl: RewriteController? = null
    private var rewriteDraft = ""
    private var rewriteTrailing = ""
    private var rewriteSelectedByUs = false

    private fun showRewritePanel() {
        val stack = panelStack ?: return
        if (rewritePanel != null) return
        val d = resources.displayMetrics.density
        val ctl = RewriteController(this) { rewritePanel?.invalidate() }
        rewriteControl = ctl
        val panel = RewritePanelView(this, ctl, style.themeFor(this))
        panel.setTheme(style.themeFor(this))
        // What to rewrite: the selection, or else the sentence before the cursor (selected so it shows in the app).
        controller.finishWord()
        val chosen = editor.selectedText()?.toString()
        rewriteTrailing = ""; rewriteSelectedByUs = false
        if (chosen != null) rewriteDraft = chosen
        else {
            val before = editor.textBefore(600)?.toString().orEmpty()
            var e = before.length; while (e > 0 && before[e - 1].isWhitespace()) e--
            var st = e; while (st > 0 && before[st - 1] !in ".!?\n") st--
            while (st < e && before[st].isWhitespace()) st++
            rewriteDraft = before.substring(st, e)
            rewriteTrailing = before.substring(e)
            if (rewriteDraft.isNotBlank()) { editor.selectBefore(before.length - st); rewriteSelectedByUs = true }
        }
        panel.hasText = rewriteDraft.isNotBlank()
        panel.listener = object : RewritePanelView.Listener {
            override fun onTone(tone: Tone) { if (!ctl.working) ctl.start(tone, rewriteDraft) else { ctl.cancel(); ctl.start(tone, rewriteDraft) } }
            override fun onReplace(text: String) { editor.commit(text + rewriteTrailing); rewriteSelectedByUs = false; setMode(Mode.LETTERS) }
            override fun onInsertBelow(text: String) { editor.selectBefore(0); editor.commit("\n" + text); rewriteSelectedByUs = false; setMode(Mode.LETTERS) }
            override fun onRegenerate() = ctl.start(ctl.tone, rewriteDraft)
            override fun onClose() = setMode(Mode.LETTERS)
            override fun onOpenModels() { openSettings("voice") }
        }
        stack.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rewritePanel = panel
        if (ctl.model != null && rewriteDraft.isNotBlank()) ctl.start(Tone.POLISH, rewriteDraft)
    }

    /** Closing the panel leaves the field as it was: the sentence we selected is deselected, and a rewrite still running is stopped. */
    private fun hideRewritePanel() {
        val panel = rewritePanel ?: return
        rewritePanel = null
        rewriteControl?.cancel(); rewriteControl?.release(); rewriteControl = null
        if (rewriteSelectedByUs) { editor.selectBefore(0); rewriteSelectedByUs = false }
        panelStack?.removeView(panel)
    }

    // ---- clipboard history: recent copies kept for an hour, never from password fields or flagged-sensitive copies --------------------

    private var panelStack: FrameLayout? = null
    private var inlineScroll: android.widget.HorizontalScrollView? = null
    private var clipPanel: ClipboardPanelView? = null
    private val clipFile get() = java.io.File(filesDir, "clipboard.tsv")
    private val clipHistory by lazy { ClipboardHistory().also { it.load(clipFile); it.enabled = WispSettings.get(this).getBoolean("clipboard_history", true) } }
    private val clipSave = Runnable { clipHistory.save(clipFile) }
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    private fun onClipChanged() {
        val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip ?: return
        if (clip.itemCount == 0) return
        val sensitive = clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        val noStore = field.kind == FieldKind.PASSWORD || incognito
        val item = clip.getItemAt(0)
        val stored = if (item.uri != null && clip.description.hasMimeType("image/*")) clipHistory.add("Image", sensitive, noStore, imageUri = item.uri.toString())
        else clipHistory.add(item.coerceToText(this), sensitive, noStore)
        if (stored) { saveClipSoon(); clipPanel?.refresh() }
    }

    private fun saveClipSoon() { main.removeCallbacks(clipSave); main.postDelayed(clipSave, 2_000) }

    private fun showClipboardPanel() {
        val stack = panelStack ?: return
        if (clipPanel != null) return
        val d = resources.displayMetrics.density
        val panel = ClipboardPanelView(this, clipHistory, style.themeFor(this))
        panel.setTheme(style.themeFor(this))
        panel.listener = object : ClipboardPanelView.Listener {
            override fun onInsert(item: ClipItem) { insertClip(item); setMode(Mode.LETTERS) }
            override fun onBack() = setMode(Mode.LETTERS)
            override fun onHistoryToggled(on: Boolean) { WispSettings.get(this@WispIme).putBoolean("clipboard_history", on); saveClipSoon() }
            override fun onChanged() = saveClipSoon()
        }
        stack.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        panel.refresh()
        clipPanel = panel
    }

    /** The panel's views are removed when it closes, so a closed panel costs nothing. */
    private fun hideClipboardPanel() {
        val panel = clipPanel ?: return
        clipPanel = null
        panelStack?.removeView(panel)
    }

    private fun insertClip(item: ClipItem) {
        controller.finishWord()
        if (item.kind != Kind.IMAGE || item.uri == null) { editor.commit(item.text); return }
        val info = currentInputEditorInfo; val ic = currentInputConnection
        val mime = contentResolver.getType(android.net.Uri.parse(item.uri))
        val accepted = info != null && androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info).any { it == mime || (mime != null && it.endsWith("/*") && mime.startsWith(it.dropLast(1))) }
        if (ic == null || info == null || mime == null || !accepted) { android.widget.Toast.makeText(this, "This field cannot take images", android.widget.Toast.LENGTH_SHORT).show(); return }
        val content = androidx.core.view.inputmethod.InputContentInfoCompat(android.net.Uri.parse(item.uri), android.content.ClipDescription("image", arrayOf(mime)), null)
        androidx.core.view.inputmethod.InputConnectionCompat.commitContent(ic, info, content, androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
    }

    private fun showPicker() { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }

    override fun onFinishInputView(finishingInput: Boolean) {
        endPlacementEdit()          // keep whatever was changed if the keyboard goes away mid-edit
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyStyleIfChanged()
        backups.runIfDue()
        bar?.setTools(app.wisp.keyboard.kbd.Toolbar.load(WispSettings.get(this)))
        val f = FieldInfo.from(info.inputType, info.imeOptions)
        field = f
        editor.inputType = info.inputType
        editor.enterAction = f.enter
        // Suggestions and auto-correct only make sense for ordinary text; not for passwords, e-mail, URLs or numbers.
        controller.suggestionsEnabled = f.suggest && f.kind == FieldKind.TEXT
        controller.autoCorrect = language.autoCorrect && style.autoCorrect
        controller.autoCapitalize = style.autoCaps
        controller.doubleSpacePeriod = style.doubleSpacePeriod
        controller.shortcuts = parseShortcuts(style.shortcuts)
        packageName = info.packageName
        bar?.micEnabled = f.kind != FieldKind.PASSWORD && f.suggest
        engine?.refreshSources(); gestureEngine?.refreshSources()
        updateLearning()
        keys?.gesturesEnabled = controller.suggestionsEnabled && style.glide
        if (!restarting) mode = Mode.LETTERS
        setMode(mode)
        controller.onFieldStarted(restarting)
        keys?.shifted = mode == Mode.LETTERS && controller.shift != Shift.OFF; keys?.capsLock = mode == Mode.LETTERS && controller.shift == Shift.CAPS
    }

    override fun onFinishInput() {
        inlineScroll?.let { it.removeAllViews(); it.visibility = View.GONE }
        voice?.cancel()                              // the field this was meant for is gone
        controller.onExternalCursorMove()
        engine?.flush()
        gestureEngine?.saveStyle()
        super.onFinishInput()
    }

    /** The app moved the cursor (or committed text) behind our back: stop treating the old word as being typed. */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (controller.isComposing && (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            controller.onExternalCursorMove()
        }
    }

    private companion object { const val PANEL_FADE_MS = 120L }
}
