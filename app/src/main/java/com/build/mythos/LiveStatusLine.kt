package com.build.mythos

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

// Baris status AI "live": satu baris berisi ikon yang berganti sesuai pekerjaan AI, teks berkilau
// yang memudar saat berganti, dan panah. Diketuk -> terbuka terminal gelap yang menampilkan perintah + keluarannya
// secara live (diwarnai, bisa dipilih per langkah, bisa disalin). Data berasal dari AgentStepStore (renderSteps).
// Logika murni pewarnaan ada di TerminalLex.kt (teruji); berkas ini hanya tampilan.

internal object Live {
    // Baris status
    val ORANGE = 0xFFD67658.toInt()      // aksen hangat (titik "berpikir" dan ikon kerja)
    val TEXT_BASE: Int get() = if (Palette.dark) 0xFF7E7C77.toInt() else 0xFFA7A59F.toInt()   // teks abu saat berkilau
    val TEXT_HI: Int get() = if (Palette.dark) 0xFFECEAE5.toInt() else 0xFF141413.toInt()     // sorot kilau / teks akhir
    val CHEVRON = 0xFF8D8C86.toInt()

    // Terminal
    val BG = 0xFF0F172A.toInt()
    val BAR = 0xFF162033.toInt()
    val LINE = 0xFF1E293B.toInt()
    val FG = 0xFFE2E8F0.toInt()
    val DIM = 0xFF64748B.toInt()
    val DIM_HI = 0xFF94A3B8.toInt()
    val GREEN = 0xFF4ADE80.toInt()
    val RED = 0xFFF87171.toInt()
    val AMBER = 0xFFFBBF24.toInt()
    val CYAN = 0xFF67E8F9.toInt()
    val VIOLET = 0xFFC4B5FD.toInt()
    val ORANGE_SOFT = 0xFFFDBA74.toInt()
    val ROSE = 0xFFFDA4AF.toInt()
    val ADD_BG = 0x2216A34A
    val DEL_BG = 0x22EF4444

    fun colorOf(k: TermKind): Int = when (k) {
        TermKind.PLAIN -> FG
        TermKind.DIM -> DIM
        TermKind.CMD -> CYAN
        TermKind.FLAG -> ORANGE_SOFT
        TermKind.STR -> GREEN
        TermKind.OP -> VIOLET
        TermKind.VAR -> AMBER
        TermKind.KEYWORD -> VIOLET
        TermKind.NUM -> ORANGE_SOFT
        TermKind.PATH -> VIOLET
        TermKind.TAG -> CYAN
        TermKind.OK -> GREEN
        TermKind.ERR -> RED
        TermKind.WARN -> AMBER
        TermKind.TASK -> DIM_HI
        TermKind.ADD -> GREEN
        TermKind.DEL -> ROSE
        TermKind.PROMPT -> GREEN
    }
}

private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
private const val NARRATION_HOLD_MS = 2400L   // lama narasi tampil sebelum kembali ke status kerja (rekaman acuan: ~2 dtk)
private const val MIN_DWELL_MS = 1200L      // status kerja tampil minimal ini sebelum diganti (rekaman acuan: ~1,2 dtk)
private const val NARRATION_GAP_MS = 350L    // jeda status kerja di antara dua narasi
private const val GRID_BURST_MS = 7000L       // ikon 4 titik di awal tiap rangkaian alat (video: 5,5-8 dtk), lalu kembali 3 titik
private const val STILL_AFTER_MS = 6000L      // tanpa aktivitas baru selama ini -> muncul "Masih diproses..."

// ------------------------------------------------------------------ Ikon "berpikir": tiga titik mengorbit

/** Tiga titik hangat yang berputar sambil "bernapas" (mengembang-mengerut). Berhenti sendiri saat lepas dari layar. */
internal class OrbitDotsView(ctx: Context, private val dotColor: Int = Live.ORANGE) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    private fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            startUnlessReduced()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        p.color = dotColor
        val cx = width / 2f
        val cy = height / 2f
        val s = minOf(width, height).toFloat()
        val twoPi = (2.0 * Math.PI).toFloat()
        val breathe = 0.5f - 0.5f * cos(phase * twoPi * 2f) // 0..1, dua kali per putaran
        val orbit = s * (0.09f + 0.21f * breathe)
        for (i in 0..2) {
            val a = phase * twoPi + i * twoPi / 3f
            val r = s * (0.065f + 0.05f * (0.5f + 0.5f * sin(phase * twoPi * 3f + i * 2.1f)))
            c.drawCircle(cx + orbit * cos(a), cy + orbit * sin(a), r, p)
        }
    }
}

/** Kotak kursor yang berkedip. */
private class BlinkBlock(ctx: Context, color: Int) : View(ctx) {
    private var anim: ObjectAnimator? = null

    init {
        setBackgroundColor(color)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim?.cancel()
        if (Motion.reduced) return
        anim = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.08f).apply {
            duration = 520
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        alpha = 1f
        super.onDetachedFromWindow()
    }
}

/** ScrollView dengan tinggi maksimum; di dalam ScrollView lain ia merebut gestur geser selama masih bisa digulir. */
private class CappedScroll(ctx: Context) : ScrollView(ctx) {
    var maxHeightPx: Int = 0
        set(v) {
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && (canScrollVertically(1) || canScrollVertically(-1))) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onInterceptTouchEvent(ev)
    }
}

// ------------------------------------------------------------------ Terminal live

internal class LiveTerminal(private val act: MainActivity) : LinearLayout(act) {
    var onApprove: (() -> Unit)? = null
    var onReject: (() -> Unit)? = null
    var onOpenFull: ((AgentStep) -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private var steps: List<AgentStep> = emptyList()
    private var pinnedId: String? = null
    private var shownId = ""
    private var shownLines: List<String> = emptyList()
    private var chipSig = ""
    private var big = false
    private var renderedLines = 0
    private var lastH = 0
    private val queue = ArrayList<TermLine>()

    private fun dotView(color: Int): View = View(act).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun pill(label: String, fg: Int, bg: Int, onClick: () -> Unit): TextView = TextView(act).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(fg)
        gravity = Gravity.CENTER
        background = pressable(act.rounded(bg, 12))
        setPadding(act.dp(11), act.dp(4), act.dp(11), act.dp(4))
        setOnClickListener { onClick() }
    }

    private val titleTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11.5f
        setTextColor(Live.DIM_HI)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val copyTv: TextView = pill("Salin", Live.FG, Live.LINE) { copyCurrent() }
    private val sizeTv: TextView = pill("Besar", Live.FG, Live.LINE) { toggleBig() }

    private val topBar = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(12), act.dp(8), act.dp(10), act.dp(8))
        background = GradientDrawable().apply {
            setColor(Live.BAR)
            val r = act.dp(16).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        addView(dotView(0xFFF87171.toInt()), LinearLayout.LayoutParams(act.dp(8), act.dp(8)).apply { marginEnd = act.dp(5) })
        addView(dotView(0xFFFBBF24.toInt()), LinearLayout.LayoutParams(act.dp(8), act.dp(8)).apply { marginEnd = act.dp(5) })
        addView(dotView(0xFF4ADE80.toInt()), LinearLayout.LayoutParams(act.dp(8), act.dp(8)).apply { marginEnd = act.dp(10) })
        addView(titleTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(sizeTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(8) })
        addView(copyTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(6) })
    }

    private val chipsRow = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(2))
    }
    private val chipsScroll = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        visibility = View.GONE
        addView(chipsRow, FrameLayout.LayoutParams(WRAP, WRAP))
    }

    private val codeTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Live.FG)
        setLineSpacing(0f, 1.2f)
    }
    private val cursorRow = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, act.dp(2), 0, 0)
        addView(TextView(act).apply {
            text = "❯"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Live.GREEN)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(BlinkBlock(act, Live.GREEN), LinearLayout.LayoutParams(act.dp(7), act.dp(14)).apply { marginStart = act.dp(6) })
    }
    private val bodyBox = LinearLayout(act).apply {
        orientation = VERTICAL
        setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
        addView(codeTv, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(cursorRow, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    private val scroll = CappedScroll(act).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        maxHeightPx = act.dp(250)
        addView(bodyBox, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private val stateTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11.5f
        setTextColor(Live.DIM_HI)
        setSingleLine(true)
    }
    private val rejectTv: TextView = pill("Tolak", Live.FG, Live.LINE) { onReject?.invoke() }
    private val approveTv: TextView = pill("Setuju", 0xFF0F172A.toInt(), Live.GREEN) { onApprove?.invoke() }
    private val footer = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(14), act.dp(7), act.dp(10), act.dp(8))
        addView(stateTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(rejectTv, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(approveTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(6) })
    }

    private val drip = object : Runnable {
        override fun run() {
            if (queue.isEmpty()) return
            val follow = nearBottom()
            val n = minOf(queue.size, maxOf(1, (queue.size + 5) / 6))
            for (i in 0 until n) appendLine(queue.removeAt(0))
            trimOld()
            if (follow) scrollToBottom()
            notifyGrowth()
            if (queue.isNotEmpty()) ui.postDelayed(this, 45)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val st = current()
            if (st != null) updateState(st)
            ui.postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        background = act.rounded(Live.BG, 16)
        addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(chipsScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(View(act).apply { setBackgroundColor(Live.LINE) }, LinearLayout.LayoutParams(MATCH, act.dp(1)))
        addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        titleTv.setOnLongClickListener {
            current()?.let { s -> onOpenFull?.invoke(s) }
            true
        }
        showIdle()
    }

    // ---------------------------------------------------------- API

    /** Terima daftar langkah terbaru. Murah bila terminal sedang tertutup. */
    fun render(groups: List<AgentStepGroup>) {
        steps = groups.flatMap { it.steps }.sortedBy { it.startedAt ?: Long.MAX_VALUE }
        if (visibility == View.VISIBLE) refresh(animate = true)
    }

    fun onOpened() {
        shownId = ""
        refresh(animate = false)
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 1000)
    }

    fun onClosed() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        queue.clear()
        shownId = ""
    }

    fun reset() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        queue.clear()
        steps = emptyList()
        pinnedId = null
        shownId = ""
        shownLines = emptyList()
        chipSig = ""
        big = false
        sizeTv.text = "Besar"
        scroll.maxHeightPx = act.dp(250)
        chipsRow.removeAllViews()
        chipsScroll.visibility = View.GONE
        showIdle()
    }

    // ---------------------------------------------------------- Isi

    private fun current(): AgentStep? {
        val id = pinnedId
        if (id != null) {
            val s = steps.firstOrNull { it.id == id }
            if (s != null) return s
        }
        return steps.lastOrNull()
    }

    private fun refresh(animate: Boolean) {
        val st = current()
        updateChips(st)
        if (st == null) {
            showIdle()
            return
        }
        titleTv.text = st.title
        updateState(st)
        val lines = termLinesFor(st)
        val plain = lines.map { it.text }
        val add = if (animate && st.id == shownId) newLinesAfter(shownLines, plain) else null
        if (add != null) {
            if (add.isNotEmpty()) {
                queue.addAll(lines.subList(lines.size - add.size, lines.size))
                ui.removeCallbacks(drip)
                ui.post(drip)
            }
        } else {
            replaceAll(lines)
        }
        shownId = st.id
        shownLines = plain
    }

    private fun showIdle() {
        titleTv.text = "terminal"
        stateTv.text = ""
        rejectTv.visibility = View.GONE
        approveTv.visibility = View.GONE
        cursorRow.visibility = View.VISIBLE
        shownId = ""
        shownLines = emptyList()
        replaceAll(listOf(TermLine("Menunggu perintah dari AI…", emptyList(), TermKind.DIM)))
    }

    private fun updateState(st: AgentStep) {
        val now = System.currentTimeMillis()
        val running = st.status == AgentStepStatus.RUNNING
        val waiting = st.status == AgentStepStatus.WAITING
        cursorRow.visibility = if (running || waiting) View.VISIBLE else View.GONE
        val needApproval = st.isApprovalWaiting
        rejectTv.visibility = if (needApproval) View.VISIBLE else View.GONE
        approveTv.visibility = if (needApproval) View.VISIBLE else View.GONE
        when (st.status) {
            AgentStepStatus.RUNNING -> {
                stateTv.text = "● berjalan  " + formatElapsed(now - (st.startedAt ?: now))
                stateTv.setTextColor(Live.ORANGE)
            }
            AgentStepStatus.WAITING -> {
                stateTv.text = "○ menunggu izin"
                stateTv.setTextColor(Live.AMBER)
            }
            AgentStepStatus.SUCCESS -> {
                stateTv.text = "✓ selesai  " + formatElapsed(st.durationMs)
                stateTv.setTextColor(Live.GREEN)
            }
            AgentStepStatus.FAILED -> {
                stateTv.text = "✕ gagal  " + formatElapsed(st.durationMs)
                stateTv.setTextColor(Live.RED)
            }
        }
    }

    private fun updateChips(sel: AgentStep?) {
        val last = steps.takeLast(24)
        chipsScroll.visibility = if (steps.size >= 2) View.VISIBLE else View.GONE
        val sig = last.joinToString("|") { it.id + it.status.ordinal } + "#" + (sel?.id ?: "") + "#" + (pinnedId ?: "")
        if (sig == chipSig) return
        chipSig = sig
        val keepX = chipsScroll.scrollX
        chipsRow.removeAllViews()
        val base = steps.size - last.size
        for ((i, s) in last.withIndex()) {
            val on = s.id == sel?.id
            val glyph: String
            val gc: Int
            when (s.status) {
                AgentStepStatus.WAITING -> { glyph = "○"; gc = Live.AMBER }
                AgentStepStatus.RUNNING -> { glyph = "●"; gc = Live.ORANGE }
                AgentStepStatus.SUCCESS -> { glyph = "✓"; gc = Live.GREEN }
                AgentStepStatus.FAILED -> { glyph = "✕"; gc = Live.RED }
            }
            val label = SpannableStringBuilder(glyph + " " + chipLabel(base + i, s.title, 18))
            label.setSpan(ForegroundColorSpan(gc), 0, glyph.length, SPAN_FLAGS)
            val chip = TextView(act).apply {
                text = label
                textSize = 11.5f
                setSingleLine(true)
                setTextColor(if (on) Live.FG else Live.DIM_HI)
                background = pressable(act.rounded(if (on) Live.LINE else Color.TRANSPARENT, 12))
                setPadding(act.dp(10), act.dp(4), act.dp(10), act.dp(4))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    pinnedId = if (s.id == steps.lastOrNull()?.id) null else s.id
                    refresh(animate = false)
                }
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = act.dp(4) })
        }
        if (pinnedId == null) chipsScroll.post { chipsScroll.fullScroll(View.FOCUS_RIGHT) }
        else chipsScroll.post { chipsScroll.scrollTo(keepX, 0) }
    }

    // ---------------------------------------------------------- Menggambar baris

    private fun styled(line: TermLine): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        sb.append(line.text).append('\n')
        val end = line.text.length
        if (end > 0) {
            sb.setSpan(ForegroundColorSpan(Live.colorOf(line.whole)), 0, end, SPAN_FLAGS)
            for (s in line.spans) {
                if (s.start >= 0 && s.end <= end && s.start < s.end) {
                    sb.setSpan(ForegroundColorSpan(Live.colorOf(s.kind)), s.start, s.end, SPAN_FLAGS)
                }
            }
        }
        when (line.whole) {
            TermKind.ADD -> sb.setSpan(DiffBg(Live.ADD_BG), 0, sb.length, SPAN_FLAGS)
            TermKind.DEL -> sb.setSpan(DiffBg(Live.DEL_BG), 0, sb.length, SPAN_FLAGS)
            else -> Unit
        }
        return sb
    }

    private fun appendLine(line: TermLine) {
        codeTv.append(styled(line))
        renderedLines++
    }

    private fun replaceAll(lines: List<TermLine>) {
        ui.removeCallbacks(drip)
        queue.clear()
        val all = SpannableStringBuilder()
        for (l in lines) all.append(styled(l))
        codeTv.setText(all, TextView.BufferType.EDITABLE)
        renderedLines = lines.size
        scrollToBottom()
        notifyGrowth()
    }

    /** Buang baris tertua agar tampilan tetap ringan saat keluaran sangat panjang. */
    private fun trimOld() {
        if (renderedLines <= 420) return
        val e = codeTv.editableText ?: return
        var pos = 0
        var removed = 0
        while (removed < 120) {
            val nl = e.indexOf('\n', pos)
            if (nl < 0) break
            pos = nl + 1
            removed++
        }
        if (pos > 0) {
            e.delete(0, pos)
            renderedLines -= removed
        }
    }

    private fun nearBottom(): Boolean {
        val c = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= c.height - act.dp(28)
    }

    private fun scrollToBottom() {
        scroll.post {
            val c = scroll.getChildAt(0)
            if (c != null) scroll.smoothScrollTo(0, (c.height - scroll.height).coerceAtLeast(0))
        }
    }

    /** Beri tahu chat agar ikut menggulir saat terminal bertambah tinggi. */
    private fun notifyGrowth() {
        scroll.post {
            if (scroll.height != lastH) {
                lastH = scroll.height
                act.scrollDown()
            }
        }
    }

    // ---------------------------------------------------------- Aksi

    private fun copyCurrent() {
        val text = shownLines.joinToString("\n")
        if (text.isBlank()) return
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        act.copyText("terminal", text)
        copyTv.text = "Tersalin ✓"
        ui.postDelayed({ copyTv.text = "Salin" }, 1400)
    }

    private fun toggleBig() {
        big = !big
        sizeTv.text = if (big) "Kecil" else "Besar"
        scroll.maxHeightPx = act.dp(if (big) 460 else 250)
        scrollToBottom()
        notifyGrowth()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) {
            ui.removeCallbacks(ticker)
            ui.postDelayed(ticker, 1000)
        }
    }
}

// ------------------------------------------------------------------ Baris status

internal class LiveStatusLine(private val act: MainActivity) : LinearLayout(act) {
    val terminal = LiveTerminal(act)

    private val ui = Handler(Looper.getMainLooper())
    private fun newGate() = TextGate(700L) { holdFor(it, 700L) }
    private var gate = newGate()
    private val kindByText = HashMap<String, Int>()
    private var iconKind = Int.MIN_VALUE
    private var iconTint = 0
    private var finished = false
    private var expanded = false
    private var breathing = false
    // Narasi hanya dipicu event App Server nyata. Ikon asli tidak dibuat ulang; alpha saja yang disembunyikan.
    private var narrationGeneration = 0
    private var narrationRunnable: Runnable? = null
    private var typingNarration = false
    private var currentNarrationText = ""
    private var narrationIdle = "Sedang dikerjakan…"
    private val pendingNarrations = java.util.ArrayDeque<String>()
    private var pendingStatusLine: Pair<String, Int>? = null
    private var lastChangeMs = 0L
    private var deferredIdle: String? = null
    private val applyDeferred = Runnable {
        val t = deferredIdle ?: return@Runnable
        deferredIdle = null
        if (!finished && !typingNarration && label.text.toString() != t) {
            pendingStatusLine = null
            swapText(t, KIND_DOTS, Live.ORANGE, working = true)
        }
    }
    private val startDeferredNarration = Runnable {
        if (finished || typingNarration) return@Runnable
        val n = pendingNarrations.pollFirst() ?: return@Runnable
        startNarration(n, clearPendingStatus = true)
    }
    private var breatheAnim: ObjectAnimator? = null
    private var heightAnim: ValueAnimator? = null

    private val iconSlot = FrameLayout(act)
    private fun newLabel() = ShimmerText(act).apply {
        textSize = 17f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        shimmerBase = Live.TEXT_BASE
        shimmerHi = Live.TEXT_HI
        setTextColor(Live.TEXT_HI)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0f
        setShimmer(false)
    }
    private val labelA = newLabel()
    private val labelB = newLabel()
    private var label = labelA
    // Dua teks bertumpuk di wadah yang lebarnya beranimasi: teks lama terpotong dari kanan sambil memudar,
    // teks baru memudar masuk di tempat yang sama, dan panah ikut bergeser (seperti di Claude).
    private val textHost = FrameLayout(act).apply {
        clipChildren = true
        addView(labelA, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
        addView(labelB, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
    }
    private var hostAnim: ValueAnimator? = null
    private val chevron = IconView(act, IconView.CHEVRON, Live.CHEVRON)

    private val head = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = act.dp(40)
        setPadding(0, act.dp(4), act.dp(10), act.dp(4))
        background = pressable(act.rounded(Color.TRANSPARENT, 14))
        addView(iconSlot, LinearLayout.LayoutParams(act.dp(40), act.dp(32)))
        addView(textHost, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(chevron, LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        setOnClickListener { toggle() }
    }

    // Ikon: dasar 3 titik mengorbit; 4 titik (grid) hanya dalam "ledakan" singkat di awal tiap rangkaian alat, sesuai rekaman acuan.
    private var burstGrid = false
    private val endBurst = Runnable {
        burstGrid = false
        (iconSlot.getChildAt(0) as? ClaudeDotsView)?.setMode(if (iconKind == KIND_DOTS) ClaudeDotsView.ORBIT else ClaudeDotsView.GRID)
    }

    /** Dipanggil saat rangkaian alat baru dimulai: ikon berganti ke 4 titik sebentar. Bentuk gerak ikon tidak diubah. */
    fun gridBurst() {
        if (finished) return
        ui.removeCallbacks(endBurst)
        burstGrid = true
        (iconSlot.getChildAt(0) as? ClaudeDotsView)?.setMode(ClaudeDotsView.GRID)
        ui.postDelayed(endBurst, GRID_BURST_MS)
    }

    private fun stopBurst() {
        ui.removeCallbacks(endBurst)
        burstGrid = false
    }

    // Sub-baris kecil di bawah baris status saat pekerjaan berjalan lama tanpa kabar baru (seperti di rekaman acuan).
    private val stillTv = TextView(act).apply {
        text = "Masih diproses..."
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(Live.TEXT_BASE)
        setPadding(act.dp(40), act.dp(6), 0, act.dp(2))
        visibility = View.GONE
    }
    private val showStill = Runnable { if (!finished) stillTv.visibility = View.VISIBLE }

    private fun armStill() {
        ui.removeCallbacks(showStill)
        stillTv.visibility = View.GONE
        ui.postDelayed(showStill, STILL_AFTER_MS)
    }

    private fun disarmStill() {
        ui.removeCallbacks(showStill)
        stillTv.visibility = View.GONE
    }

    private val release = Runnable {
        gate.release(SystemClock.uptimeMillis())?.let { applyLine(it) }
        scheduleRelease()
    }

    init {
        orientation = VERTICAL
        addView(head, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(stillTv, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(terminal, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(6); bottomMargin = act.dp(4) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Teks panjang dipotong dengan "…" sebelum panah agar tetap ringkas.
        post {
            val mw = (w - paddingLeft - paddingRight - act.dp(40 + 20 + 12)).coerceAtLeast(act.dp(80))
            labelA.maxWidth = mw
            labelB.maxWidth = mw
        }
    }

    // ---------------------------------------------------------- Teks dan ikon

    /** Satu baris penjelasan aktivitas. Teks sama diabaikan; perubahan beruntun ditahan agar tidak berkedip. */
    fun setLine(raw: String, kind: Int = KIND_DOTS) {
        if (finished) return
        val t = tidyLine(raw, 90)
        if (t.isEmpty()) return
        if (typingNarration) {
            // Jangan memotong kalimat di tengah ketikan; tampilkan status terbaru setelah narasi selesai.
            pendingStatusLine = t to kind
            return
        }
        kindByText[t] = kind
        gate.submit(t, SystemClock.uptimeMillis())?.let { applyLine(it) }
        scheduleRelease()
    }

    /**
     * Tampilkan narasi singkat yang diturunkan dari item tool nyata.
     * Narasi berikutnya diantrikan, bukan memotong teks yang sedang diketik.
     */
    /**
     * Perbarui teks status kerja agar sesuai keadaan nyata (mis. alat selesai -> "Proses berpikir").
     * Bila narasi sedang tampil, teks ini dipakai begitu narasi selesai; bila tidak, langsung diganti.
     */
    fun setIdle(idle: String) {
        if (finished) return
        val t = tidyLine(idle, 60)
        if (t.isBlank()) return
        narrationIdle = t
        if (typingNarration || label.text.toString() == t) return
        // Beri waktu baca: status sebelumnya tampil minimal MIN_DWELL_MS sebelum diganti.
        val wait = MIN_DWELL_MS - (SystemClock.uptimeMillis() - lastChangeMs)
        if (wait > 60L) {
            deferredIdle = t
            ui.removeCallbacks(applyDeferred)
            ui.postDelayed(applyDeferred, wait)
        } else {
            pendingStatusLine = null
            swapText(t, KIND_DOTS, Live.ORANGE, working = true)
        }
    }

    fun showNarration(raw: String, idle: String = "Sedang dikerjakan…") {
        if (finished) return
        narrationIdle = tidyLine(idle, 60).ifBlank { "Sedang dikerjakan…" }
        val text = tidyLine(raw, 90)
        if (text.isBlank()) return
        if (typingNarration) {
            if (text != currentNarrationText && pendingNarrations.peekLast() != text) pendingNarrations.addLast(text)
            while (pendingNarrations.size > 2) pendingNarrations.removeFirst()
            return
        }
        if (text == currentNarrationText && label.text.toString() == text) return
        // Narasi juga menunggu status kerja yang sedang tampil selesai dibaca; yang terlalu lama mengantri dibuang
        // (simpan 2 terbaru) supaya teks tidak tertinggal dari pekerjaan sebenarnya.
        val wait = MIN_DWELL_MS - (SystemClock.uptimeMillis() - lastChangeMs)
        if (wait > 60L) {
            if (pendingNarrations.peekLast() != text) pendingNarrations.addLast(text)
            while (pendingNarrations.size > 2) pendingNarrations.removeFirst()
            ui.removeCallbacks(startDeferredNarration)
            ui.postDelayed(startDeferredNarration, wait)
            return
        }
        startNarration(text, clearPendingStatus = true)
    }

    private fun startNarration(text: String, clearPendingStatus: Boolean) {
        if (finished) return
        ui.removeCallbacks(release)
        ui.removeCallbacks(applyDeferred)
        deferredIdle = null
        narrationRunnable?.let { ui.removeCallbacks(it) }
        val generation = ++narrationGeneration
        // Narasi muncul utuh lewat pergantian teks seperti status biasa (teks lama memudar, teks baru memudar masuk,
        // lebar wadah dan panah ikut bergeser, teks berkilau). Tidak diketik per karakter, sesuai rekaman acuan.
        typingNarration = true
        currentNarrationText = text
        if (clearPendingStatus) pendingStatusLine = null
        val kind = if (iconKind == Int.MIN_VALUE) KIND_DOTS else iconKind
        swapText(text, kind, Live.ORANGE, working = true)
        armStill()
        val run = Runnable {
            if (generation != narrationGeneration || !typingNarration) return@Runnable
            narrationRunnable = null
            typingNarration = false
            // Setelah narasi tertahan cukup lama, kembali ke status kerja; ikon asli tetap hidup tanpa dibuat ulang.
            val pending = pendingStatusLine
            pendingStatusLine = null
            val next = if (pendingNarrations.isNotEmpty()) pendingNarrations.removeFirst() else null
            // Seperti rekaman acuan: bila tidak ada narasi berikutnya, kembali ke status kerja ("Sedang menjalankan perintah").
            if (pending != null && pending.first != text) {
                swapText(pending.first, pending.second, Live.ORANGE, working = true)
            } else if (next == null) {
                swapText(narrationIdle, KIND_DOTS, Live.ORANGE, working = true)
            }
            if (next != null) {
                ui.postDelayed({
                    if (!finished && !typingNarration) startNarration(next, clearPendingStatus = false)
                }, NARRATION_GAP_MS)
            }
        }
        narrationRunnable = run
        ui.postDelayed(run, NARRATION_HOLD_MS)
    }

    private fun cancelNarration() {
        stopBurst()
        disarmStill()
        narrationGeneration++
        narrationRunnable?.let { ui.removeCallbacks(it) }
        narrationRunnable = null
        ui.removeCallbacks(applyDeferred)
        ui.removeCallbacks(startDeferredNarration)
        deferredIdle = null
        typingNarration = false
        currentNarrationText = ""
        pendingNarrations.clear()
        pendingStatusLine = null
        iconSlot.animate().cancel()
        iconSlot.alpha = 1f
    }

    private fun scheduleRelease() {
        ui.removeCallbacks(release)
        gate.nextDelay(SystemClock.uptimeMillis())?.let { ui.postDelayed(release, it) }
    }

    private fun applyLine(t: String) {
        val kind = kindByText[t] ?: KIND_DOTS
        swapText(t, kind, Live.ORANGE, working = true)
        armStill()
    }

    /** Ganti teks + ikon: teks lama terpotong dan memudar, teks baru memudar masuk bersamaan (tanpa geser), lebar wadah beranimasi. */
    private fun swapText(t: String, kind: Int, tint: Int, working: Boolean) {
        lastChangeMs = SystemClock.uptimeMillis()
        val old = label
        val nw = if (old === labelA) labelB else labelA
        old.animate().cancel()
        nw.animate().cancel()
        hostAnim?.cancel()
        nw.text = t
        nw.setShimmer(working)
        if (!working) nw.setTextColor(Live.TEXT_HI)
        nw.translationY = 0f
        setIcon(kind, tint, working)
        label = nw
        nw.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val toW = nw.measuredWidth
        val first = old.alpha < 0.05f || old.text.isEmpty() || textHost.width <= 0
        val lp = textHost.layoutParams
        if (first) {
            old.alpha = 0f
            lp.width = toW
            textHost.layoutParams = lp
            nw.alpha = 0f
            nw.animate().alpha(1f).setDuration(200).start()
            return
        }
        val fromW = textHost.width
        old.animate().alpha(0f).setDuration(200).withEndAction { if (old !== label) old.setShimmer(false) }.start()
        nw.alpha = 0f
        nw.animate().alpha(1f).setDuration(200).start()
        val a = ValueAnimator.ofInt(fromW, toW)
        hostAnim = a
        a.duration = 250
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            lp.width = it.animatedValue as Int
            textHost.layoutParams = lp
        }
        a.start()
    }

    private fun setIcon(kind: Int, tint: Int, breathe: Boolean) {
        if (kind == iconKind && tint == iconTint) return
        iconKind = kind
        iconTint = tint
        stopBreathe()
        if (breathe && tint == Live.ORANGE) {
            // Keadaan bekerja: satu ikon titik yang berganti bentuk (orbit 3 titik / kotak 4 titik) dengan susut-tahan-kembang.
            val mode = if (kind == KIND_DOTS && !burstGrid) ClaudeDotsView.ORBIT else ClaudeDotsView.GRID
            val cur = iconSlot.getChildAt(0) as? ClaudeDotsView
            if (cur != null) { cur.setMode(mode); return }
            iconSlot.removeAllViews()
            val dv = ClaudeDotsView(act)
            iconSlot.addView(dv, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
            dv.setMode(mode)
            breathing = false
            return
        }
        iconSlot.removeAllViews()
        val v: View = if (kind == KIND_DOTS) {
            OrbitDotsView(act, tint)
        } else {
            IconView(act, kind, tint).apply { working = kind == IconView.GEAR && breathe }
        }
        val size = if (kind == KIND_DOTS) act.dp(30) else act.dp(24)
        iconSlot.addView(v, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        v.scaleX = 0.4f
        v.scaleY = 0.4f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
            .setInterpolator(OvershootInterpolator(2f)).start()
        breathing = breathe && kind != KIND_DOTS && kind != IconView.GEAR
        if (breathing && isAttachedToWindow) startBreathe()
    }

    private fun startBreathe() {
        breatheAnim?.cancel()
        breatheAnim = ObjectAnimator.ofPropertyValuesHolder(
            iconSlot,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.08f)
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            if (!Motion.reduced) start()
        }
    }

    private fun stopBreathe() {
        breatheAnim?.cancel()
        breatheAnim = null
        iconSlot.scaleX = 1f
        iconSlot.scaleY = 1f
    }

    // ---------------------------------------------------------- Siklus

    /** Putaran baru: kosongkan semuanya dan mulai dari "berpikir". */
    fun begin(firstText: String) {
        ui.removeCallbacks(release)
        cancelNarration()
        gate = newGate()
        kindByText.clear()
        finished = false
        iconKind = Int.MIN_VALUE
        clearLabels()
        iconSlot.removeAllViews()
        setExpanded(open = false, animate = false)
        terminal.reset()
        setLine(firstText, KIND_DOTS)
    }

    /** Hasil akhir: ikon berhenti, teks diam, panah tetap bisa dibuka untuk meninjau langkah. */
    fun finish(text: String, kind: Int, tint: Int) {
        ui.removeCallbacks(release)
        cancelNarration()
        finished = true
        swapText(tidyLine(text, 90), kind, tint, working = false)
    }

    fun reset() {
        ui.removeCallbacks(release)
        cancelNarration()
        gate = newGate()
        kindByText.clear()
        finished = false
        iconKind = Int.MIN_VALUE
        clearLabels()
        stopBreathe()
        iconSlot.removeAllViews()
        setExpanded(open = false, animate = false)
        terminal.reset()
    }

    private fun clearLabels() {
        hostAnim?.cancel()
        for (l in arrayOf(labelA, labelB)) {
            l.animate().cancel()
            l.setShimmer(false)
            l.alpha = 0f
            l.text = ""
        }
        label = labelA
        textHost.layoutParams = textHost.layoutParams.also { it.width = WRAP }
    }

    fun render(groups: List<AgentStepGroup>) = terminal.render(groups)

    // ---------------------------------------------------------- Buka / tutup terminal

    private fun toggle() {
        head.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setExpanded(open = !expanded, animate = true)
    }

    private fun setExpanded(open: Boolean, animate: Boolean) {
        if (open == expanded && !(open && terminal.visibility != View.VISIBLE)) {
            if (!animate && !open) terminal.visibility = View.GONE
            return
        }
        expanded = open
        chevron.animate().cancel()
        if (animate) chevron.animate().rotation(if (open) 90f else 0f).setDuration(220).start()
        else chevron.rotation = if (open) 90f else 0f
        heightAnim?.cancel()
        heightAnim = null
        terminal.animate().cancel()
        if (open) terminal.onOpened() else terminal.onClosed()
        if (!animate) {
            terminal.alpha = 1f
            terminal.layoutParams.height = WRAP
            terminal.visibility = if (open) View.VISIBLE else View.GONE
            terminal.requestLayout()
            return
        }
        val availW = (width - paddingLeft - paddingRight).coerceAtLeast(act.dp(200))
        terminal.visibility = View.VISIBLE
        terminal.measure(
            View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val full = terminal.measuredHeight
        val from = if (open) 0 else terminal.height
        val to = if (open) full else 0
        terminal.alpha = if (open) 0f else 1f
        terminal.layoutParams.height = from
        terminal.requestLayout()
        terminal.animate().alpha(if (open) 1f else 0f).setDuration(240).start()
        val a = ValueAnimator.ofInt(from, to)
        heightAnim = a
        a.duration = 280
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            terminal.layoutParams.height = it.animatedValue as Int
            terminal.requestLayout()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (heightAnim !== a) return
                heightAnim = null
                terminal.layoutParams.height = WRAP
                if (open) {
                    terminal.alpha = 1f
                    terminal.requestLayout()
                    act.scrollDown()
                } else {
                    terminal.visibility = View.GONE
                    terminal.requestLayout()
                }
            }
        })
        a.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (breathing) startBreathe()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(release)
        cancelNarration()
        breatheAnim?.cancel()
        breatheAnim = null
        super.onDetachedFromWindow()
    }
}
