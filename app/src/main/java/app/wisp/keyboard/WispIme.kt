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
import app.wisp.keyboard.kbd.EmojiGridView
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
import app.wisp.keyboard.kbd.FloatHandle
import android.inputmethodservice.InputMethodService.Insets
import app.wisp.keyboard.kbd.LayoutGuard
import app.wisp.keyboard.style.KeyboardStyle
import app.wisp.keyboard.voice.MicPermissionActivity
import app.wisp.keyboard.voice.VoiceController
import app.wisp.keyboard.voice.VoicePanelView
import app.wisp.keyboard.voice.text.TextProcessor
import android.content.Intent

/** Entry point of the keyboard. */
class WispIme : InputMethodService() {

    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS_MORE, EMOJI, VOICE }

    private val editor = ImeEditor(this)
    private val controller = InputController(editor)
    private var engine: SuggestionEngine? = null
    private var gestureEngine: GestureEngine? = null

    private var keys: KeyboardView? = null
    private var bar: SuggestionBarView? = null
    private var emojiPanel: View? = null
    private var emojiBar: KeyboardView? = null
    private var voice: VoiceController? = null
    private var voicePanel: View? = null
    private var voiceBar: View? = null
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
        Probe.mark("ime_created")
    }

    override fun onDestroy() {
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
            override fun onRepeat(code: Int, count: Int) = controller.onRepeat(code, count)
            override fun onCursorSteps(steps: Int) = controller.onCursorSteps(steps)
            override fun onLongPress(code: Int) { if (code == KeyCodes.SWITCH) showPicker() }
            override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) { gestureEngine?.decode(xs, ys, count, null) }
            override fun onOneHanded(action: String) {
                val side = when (action) { "swap" -> if (style.oneHanded == "left") "right" else "left"; else -> "off" }
                WispSettings.get(this@WispIme).putString("style_one_handed", side)
                applyStyleIfChanged()
            }
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
            override fun onMicDown() = openVoice(handsFree = false)
            override fun onSettings() {
                startActivity(Intent(this@WispIme, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCREEN, "look").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                requestHideSelf(0)
            }
            override fun onMicUp(heldMs: Long) { if (heldMs >= HOLD_TO_TALK_MS) voice?.stop() else voice?.makeHandsFree() }
            override fun onSuggestion(index: Int) = controller.onSuggestionTapped(index)
            override fun onTool(tool: SuggestionBarView.Tool) = when (tool) {
                SuggestionBarView.Tool.SELECT_ALL -> controller.selectAll()
                SuggestionBarView.Tool.CUT -> controller.cut()
                SuggestionBarView.Tool.COPY -> controller.copy()
                SuggestionBarView.Tool.PASTE -> controller.paste()
                SuggestionBarView.Tool.INCOGNITO -> { incognito = !incognito; b.incognito = incognito; WispSettings.get(this@WispIme).putBoolean("incognito", incognito); updateLearning() }
            }
        }
        controller.onBarChanged = { b.setState(it) }

        // Emoji panel: scrolling grid on top, a one-row bar (ABC, globe, space, backspace) below.
        Probe.mark("p_pre_emoji")
        val grid = EmojiGridView(this, loadEmoji(), palette)
        grid.listener = EmojiGridView.Listener { editor.commit(it) }
        val eb = KeyboardView(this, LayoutLoader.load(this, R.raw.layout_emoji_bar), palette)
        eb.listener = listener
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(eb, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }

        // Voice panel: the words as you speak, the ribbon, the mic. It shares the one-row bar (ABC, globe, ...) with the emoji panel's layout.
        Probe.mark("p_panels")
        val vc = VoiceController(this, onChange = { voicePanel?.invalidate() }, onResult = { text -> controller.onDictation(text); setMode(Mode.LETTERS) })
        voice = vc
        val vp = VoicePanelView(this, vc, palette)
        vp.listener = object : VoicePanelView.Listener {
            override fun onMicDown() { vc.start(handsFree = false) }
            override fun onHandsFree() = vc.makeHandsFree()
            override fun onFinish() = vc.stop()
            override fun onCancel() { vc.cancel(); setMode(Mode.LETTERS) }
            override fun onFix(problem: VoiceController.Problem) = fixVoiceProblem(problem)
        }
        voicePanel = vp
        val vbar = KeyboardView(this, LayoutLoader.load(this, R.raw.layout_emoji_bar), palette).also { it.listener = listener }
        val voiceBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(vp, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(vbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        voiceBar = voiceBox
        // The names and words the user has taught Wisp also correct what the speech model mishears.
        controller.dictationProcessor = { TextProcessor.process(it, null, removeFillers = true, customWords = personalWords, threshold = 0.18) }

        Probe.mark("p_voice")
        val stack = FrameLayout(this)
        stack.addView(k, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        stack.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        stack.addView(voiceBox, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val grip = FloatHandle(this, palette).also { it.visibility = View.GONE; it.onMoved = ::onFloatMoved }
        root.addView(grip, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(b, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(stack, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        keys = k; bar = b; emojiPanel = panel; emojiBar = eb; voiceKeys = vbar
        applyStyleIfChanged(force = true)
        b.incognito = incognito

        controller.onShiftChanged = { s -> k.shifted = s != Shift.OFF && mode == Mode.LETTERS }
        Probe.mark("view_created")
        val outer = FrameLayout(this)
        outer.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        host = outer; floatRoot = root; floatGrip = grip
        applyStyleIfChanged(force = true)
        return outer
    }

    private fun loadEmoji(): Array<String> =
        resources.openRawResource(R.raw.emoji).bufferedReader().use { it.readText() }
            .split(' ', '\n').filter { it.isNotBlank() }.toTypedArray()

    private fun handleKey(code: Int, x: Int, y: Int) {
        when (code) {
            KeyCodes.SWITCH -> switchKeyboard()
            KeyCodes.SYMBOLS -> setMode(Mode.SYMBOLS)
            KeyCodes.ABC -> setMode(Mode.LETTERS)
            KeyCodes.SYMBOLS_MORE -> setMode(if (mode == Mode.SYMBOLS) Mode.SYMBOLS_MORE else Mode.SYMBOLS)
            KeyCodes.EMOJI -> setMode(Mode.EMOJI)
            KeyCodes.LANGUAGE -> cycleLanguage()
            else -> controller.onKey(code, x, y)
        }
    }

    private fun setMode(m: Mode) {
        if (mode == Mode.VOICE && m != Mode.VOICE) voice?.cancel()      // leaving the voice panel throws a recording away
        mode = m
        val k = keys ?: return
        when (m) {
            Mode.LETTERS -> k.setSpec(lettersSpec())
            Mode.SYMBOLS -> k.setSpec(LayoutLoader.load(this, R.raw.layout_symbols))
            Mode.SYMBOLS_MORE -> k.setSpec(LayoutLoader.load(this, R.raw.layout_symbols_more))
            Mode.EMOJI, Mode.VOICE -> {}
        }
        k.visibility = if (m == Mode.EMOJI || m == Mode.VOICE) View.INVISIBLE else View.VISIBLE
        fadeTo(emojiPanel, m == Mode.EMOJI)
        fadeTo(voiceBar, m == Mode.VOICE)
        if (m != Mode.EMOJI && m != Mode.VOICE) {
            k.setLabel(KeyCodes.ENTER, field.enter.label)
            k.setLabel(KeyCodes.LANGUAGE, language.code)
            k.shifted = m == Mode.LETTERS && controller.shift != Shift.OFF
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
        val base = if (field.kind == FieldKind.TEXT) LayoutLoader.load(this, language.layoutRes, withLanguageKey = enabledLanguages.size > 1)
        else LayoutLoader.load(this, layoutFor(field.kind))
        val letters = field.kind == FieldKind.TEXT || field.kind == FieldKind.EMAIL || field.kind == FieldKind.URL
        return if (style.numberRow && letters) LayoutGuard.withNumberRow(base) else base
    }

    // ---- look and feel (task group 8) --------------------------------------------------------------------------

    private val backups by lazy { app.wisp.keyboard.backup.BackupManager(this) }
    // ---- floating mode: a smaller keyboard the user drags, with the app behind it left alone ----------------------------

    private var host: FrameLayout? = null
    private var floatRoot: View? = null
    private var floatGrip: FloatHandle? = null

    private fun screenSize() = resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** Places the keyboard: full width at the bottom normally; in floating mode 80% wide at the remembered spot. */
    private fun applyFloating() {
        val h = host ?: return; val r = floatRoot ?: return
        val lp = r.layoutParams as FrameLayout.LayoutParams
        if (!style.floating) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.leftMargin = 0; lp.bottomMargin = 0
            h.minimumHeight = 0; floatGrip?.visibility = View.GONE
        } else {
            val (w, sh) = screenSize()
            val settings = WispSettings.get(this)
            lp.width = (w * 0.8f).toInt()
            h.minimumHeight = (sh * 0.7f).toInt()
            lp.leftMargin = ((w - lp.width) * settings.getInt("float_x", 50) / 100f).toInt()
            lp.bottomMargin = ((h.minimumHeight - 1) * settings.getInt("float_y", 0) / 100f * 0.6f).toInt()
            floatGrip?.visibility = View.VISIBLE
        }
        r.layoutParams = lp
        h.requestLayout()
    }

    private fun onFloatMoved(dx: Float, dy: Float, done: Boolean) {
        val r = floatRoot ?: return; val h = host ?: return
        val lp = r.layoutParams as FrameLayout.LayoutParams
        val (w, _) = screenSize()
        lp.leftMargin = (lp.leftMargin + dx).toInt().coerceIn(0, maxOf(0, w - lp.width))
        lp.bottomMargin = (lp.bottomMargin - dy).toInt().coerceIn(0, maxOf(0, h.height - r.height))
        r.layoutParams = lp
        if (done) {
            val settings = WispSettings.get(this)
            val rangeX = maxOf(1, w - lp.width); val rangeY = maxOf(1f, (h.minimumHeight - 1) * 0.6f)
            settings.putInt("float_x", (lp.leftMargin * 100f / rangeX).toInt().coerceIn(0, 100))
            settings.putInt("float_y", (lp.bottomMargin * 100f / rangeY).toInt().coerceIn(0, 100))
        }
    }

    /** In floating mode the window is tall and mostly empty: tell the system the app behind is not covered, and that only the keyboard takes touches. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val r = floatRoot; val h = host
        if (!style.floating || r == null || h == null || !isInputViewShown) return
        val top = h.height
        outInsets.contentTopInsets = top; outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        val loc = IntArray(2); r.getLocationInWindow(loc)
        outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + r.width, loc[1] + r.height)
    }

    override fun onEvaluateFullscreenMode(): Boolean = if (style.floating) false else super.onEvaluateFullscreenMode()

    private val palette = Palette()
    private var style = KeyboardStyle()
    private var styleKey = ""
    private var voiceKeys: KeyboardView? = null

    /** Re-reads the style settings and, only if something changed (or the phone switched between light and dark), restyles every view in place. */
    private fun applyStyleIfChanged(force: Boolean = false) {
        val next = KeyboardStyle.load(this)
        val key = next.signature + next.isDark(this) + resources.configuration.orientation
        if (!force && key == styleKey) return
        val numberRowChanged = next.numberRow != style.numberRow
        styleKey = key; style = next
        palette.set(next.palette(this))
        val barStyle = next.copy(oneHanded = "off", split = false)      // the one-row bars under the emoji and voice panels stay full width
        keys?.applyStyle(next); emojiBar?.applyStyle(barStyle); voiceKeys?.applyStyle(barStyle)
        bar?.invalidate(); voicePanel?.invalidate()
        if (numberRowChanged && !force) setMode(mode)
        floatRoot?.let { it.setPadding(0, 0, 0, (next.bottomGap * resources.displayMetrics.density).toInt()); it.setBackgroundColor(palette.background) }
        applyFloating()
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
        controller.autoCorrect = language.autoCorrect
        setMode(Mode.LETTERS)
    }

    private fun openVoice(handsFree: Boolean) {
        if (!field.suggest || field.kind == FieldKind.PASSWORD) return        // never record into a password field
        setMode(Mode.VOICE)
        voice?.start(handsFree)
    }

    private fun fixVoiceProblem(problem: VoiceController.Problem) {
        when (problem) {
            VoiceController.Problem.NO_PERMISSION -> startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            VoiceController.Problem.NO_MODEL -> startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCREEN, SettingsActivity.SCREEN_VOICE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> { voice?.cancel(); return }
        }
        voice?.cancel(); setMode(Mode.LETTERS); requestHideSelf(0)
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

    private fun showPicker() { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }

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
        controller.autoCorrect = language.autoCorrect
        packageName = info.packageName
        bar?.micEnabled = f.kind != FieldKind.PASSWORD && f.suggest
        engine?.refreshSources(); gestureEngine?.refreshSources()
        updateLearning()
        keys?.gesturesEnabled = controller.suggestionsEnabled
        if (!restarting) mode = Mode.LETTERS
        setMode(mode)
        controller.onFieldStarted(restarting)
        keys?.shifted = mode == Mode.LETTERS && controller.shift != Shift.OFF
    }

    override fun onFinishInput() {
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

    private companion object { const val HOLD_TO_TALK_MS = 350L; const val PANEL_FADE_MS = 120L }
}
