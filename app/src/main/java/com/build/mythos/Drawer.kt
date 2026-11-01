package com.build.mythos

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Drawer samping, mode Chat samaran, dan lembar "Tambahkan ke chat" (tampilan mengikuti rekaman layar).
// Fungsi-fungsi di sini adalah ekstensi MainActivity.

internal const val DRAWER_FRACTION = 0.82f
internal const val DRAWER_ANIM_MS = 260L

// ------------------------------------------------------------------ Bar atas

/** Tombol bulat bergaris tipis di bar atas (menu dan hantu). */
internal fun MainActivity.circleButton(desc: String, icon: View, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
    contentDescription = desc
    background = pressable(GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Palette.NAV)
        setStroke(dp(1), Palette.BORDER)
    })
    addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    setOnClickListener { onClick() }
}

// ------------------------------------------------------------------ Drawer

private fun MainActivity.drawerRow(
    glyph: Int, label: String, sizeSp: Float, heightDp: Int,
    selected: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit
): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(if (selected) rounded(Palette.TILE, 16) else null)
        setPadding(dp(12), 0, dp(12), 0)
        addView(GlyphView(context, glyph, Palette.INK).apply { strokeDp = 1.5f }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(context).apply {
            text = label
            textSize = sizeSp
            setTextColor(Palette.INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        minimumHeight = dp(heightDp)
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }

/** Jumlah chat yang ditampilkan di bagian "Terbaru"; sisanya lewat "Semua chat". */
private const val RECENT_COUNT = 6

/** Panel drawer: judul, menu utama, daftar terbaru, avatar, dan tombol Chat baru. */
internal fun MainActivity.buildDrawer(): View {
    val nama = (prefs.getString("nama_tampil", NAMA_TAMPIL_BAWAAN) ?: NAMA_TAMPIL_BAWAAN).trim().ifBlank { NAMA_TAMPIL_BAWAAN }
    val initials = nama.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
    }
    fun rebuild() {
        list.removeAllViews()
        list.addView(drawerRow(GlyphView.CHATS, "Chat", 18f, 54) { closeDrawer() })
        list.addView(drawerRow(GlyphView.PROJECT, "Proyek", 18f, 54) { closeDrawer(); showProjectsPage() })
        list.addView(drawerRow(GlyphView.CODE, "Code", 18f, 54) {
            closeDrawer(); startActivity(Intent(this@buildDrawer, CommandLogActivity::class.java))
        })
        list.addView(drawerRow(GlyphView.ARTIFACT, "Artefak", 18f, 54) { closeDrawer(); showArtifactsPage() })
        list.addView(TextView(this).apply {
            text = "Terbaru"
            textSize = 15f
            setTextColor(Palette.INK_SOFT)
            setPadding(dp(12), dp(22), 0, dp(6))
        })
        val chats = listChats()
        val cur = currentChatId()
        if (chats.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Belum ada chat"
                textSize = 16f
                setTextColor(Palette.INK_SOFT)
                setPadding(dp(12), dp(12), 0, dp(12))
            })
        } else {
            for (c in chats.take(RECENT_COUNT)) {
                list.addView(drawerRow(GlyphView.BUBBLE, c.title, 17f, 50, selected = c.id == cur && !incognito,
                    onLong = { chatActions(c) }) { closeDrawer(); openChat(c.id) })
            }
        }
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(null)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addView(TextView(context).apply {
                text = "Semua chat"
                textSize = 15f
                setTextColor(Palette.INK_SOFT)
            })
            addView(GlyphView(context, GlyphView.CHEVRON_R, Palette.INK_SOFT).apply { strokeDp = 1.4f },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
            setOnClickListener { closeDrawer(); showAllChats() }
        })
    }
    drawerRefresh = { runOnUiThread { rebuild() } }
    rebuild()

    val avatar = FrameLayout(this).apply {
        contentDescription = "Pengaturan"
        background = pressable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.NAV)
            setStroke(dp(1), Palette.BORDER)
        })
        addView(TextView(context).apply {
            text = initials
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        setOnClickListener { closeDrawer(); showSettings() }
    }
    val newChatBtn = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = pressable(rounded(Palette.STRONG, 28))
        setPadding(dp(20), 0, dp(22), 0)
        addView(GlyphView(context, GlyphView.PLUS, onColor(Palette.STRONG)).apply { strokeDp = 1.7f }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(TextView(context).apply {
            text = "Chat baru"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(onColor(Palette.STRONG))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        setOnClickListener {
            closeDrawer()
            if (incognito) setIncognito(false) else newChat()
        }
    }

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.DRAWER_BG)
        setPadding(dp(12), 0, dp(12), dp(16))
        isClickable = true // jangan teruskan sentuhan ke lapisan di bawahnya
        addView(TextView(context).apply {
            text = "Mythos"
            textSize = 27f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = -0.02f
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(MATCH, dp(60)))
        addView(android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(newChatBtn, LinearLayout.LayoutParams(WRAP, dp(52)))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }
}

/** Lebar drawer: 82% layar, tetapi dibatasi 360dp (tablet, lanskap). */
internal fun MainActivity.drawerWidthPx(): Int =
    DrawerGeometry.widthPx(resources.displayMetrics.widthPixels, resources.displayMetrics.density)

/** Terapkan ulang lebar drawer setelah layar diputar atau ukurannya berubah (activity tidak dibuat ulang). */
internal fun MainActivity.refreshDrawerWidth() {
    val v = drawerView ?: return
    val lp = v.layoutParams ?: return
    val w = drawerWidthPx()
    if (lp.width != w) {
        lp.width = w
        v.layoutParams = lp
    }
    applyDrawerProgress(drawerProgress)
}

/** Keadaan satu gestur geser drawer. */
internal class DrawerDrag {
    var active = false
    var needsCancel = false      // true sampai ACTION_CANCEL dikirim ke view anak di awal geseran
    var startProgress = 0f
    var startDx = 0f
    var velocity: VelocityTracker? = null

    fun reset() {
        active = false; needsCancel = false; startProgress = 0f; startDx = 0f
    }
}

/** Terapkan kemajuan animasi [p] (0 = tertutup, 1 = terbuka): geser lapisan utama, bulatkan sudut, ubah warna status bar. */
internal fun MainActivity.applyDrawerProgress(p: Float) {
    drawerProgress = p
    mainLayer.translationX = drawerWidthPx() * p
    mainLayer.elevation = dp(10) * p
    mainLayer.invalidateOutline()
    drawerScrim.visibility = if (p > 0.02f) View.VISIBLE else View.GONE
    window.statusBarColor = mixArgb(Palette.BG, Palette.DRAWER_BG, p)
}

/** Siapkan lapisan utama agar sudutnya membulat mengikuti kemajuan drawer. Dipanggil sekali setelah mainLayer dibuat. */
internal fun MainActivity.initDrawerLayer() {
    mainLayer.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(38) * drawerProgress)
        }
    }
    mainLayer.clipToOutline = true
    drawerScrim.setOnClickListener { closeDrawer() }
}

private fun MainActivity.animateDrawer(open: Boolean, refresh: Boolean = true) {
    drawerAnim?.cancel()
    drawerOpen = open
    if (open && refresh) {
        val prog = drawerRefresh
        prog?.invoke()
        hideKeyboard()
    }
    val target = if (open) 1f else 0f
    drawerAnim = ValueAnimator.ofFloat(drawerProgress, target).apply {
        duration = DrawerGeometry.animMs(drawerProgress, target, DRAWER_ANIM_MS)
        interpolator = DecelerateInterpolator(1.6f)
        addUpdateListener { applyDrawerProgress(it.animatedValue as Float) }
        start()
    }
}

internal fun MainActivity.openDrawer() = animateDrawer(true)

internal fun MainActivity.closeDrawer() { if (drawerOpen || drawerProgress > 0f) animateDrawer(false) }

internal fun MainActivity.toggleDrawer() { if (drawerOpen) closeDrawer() else openDrawer() }

/**
 * Gestur geser yang mengikuti jari: dari tepi kiri ke kanan menarik drawer terbuka, geser ke kiri menariknya tertutup.
 * Saat jari diangkat, drawer menyelesaikan geraknya: lemparan cepat menentukan arah, selain itu setengah jalan.
 * Mengembalikan true bila sentuhan sudah dipakai (tidak diteruskan ke view lain).
 */
internal fun MainActivity.handleDrawerSwipe(ev: MotionEvent): Boolean {
    val drag = drawerDrag
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            swipeDownX = ev.rawX; swipeDownY = ev.rawY
            drag.reset()
            drag.velocity?.recycle()
            drag.velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
        }
        MotionEvent.ACTION_MOVE -> {
            drag.velocity?.addMovement(ev)
            val dx = ev.rawX - swipeDownX
            val dy = ev.rawY - swipeDownY
            if (!drag.active) {
                val open = drawerOpen || drawerProgress > 0.02f
                val slop = ViewConfiguration.get(this).scaledTouchSlop * 2f
                if (!DrawerGeometry.shouldStartDrag(dx, dy, slop, open, swipeDownX, dp(32).toFloat())) return false
                drag.active = true
                drag.needsCancel = true
                drag.startProgress = drawerProgress
                drag.startDx = dx   // drawer mulai bergerak dari posisinya sekarang, tidak melompat sebesar jarak ambang
                drawerAnim?.cancel()
                if (!open) {
                    drawerRefresh?.invoke()
                    hideKeyboard()
                }
            }
            applyDrawerProgress(DrawerGeometry.progressFor(drag.startProgress, dx - drag.startDx, drawerWidthPx()))
            return true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            drag.velocity?.addMovement(ev)
            val wasDragging = drag.active
            if (wasDragging) {
                drag.velocity?.computeCurrentVelocity(1000)
                val vx = drag.velocity?.xVelocity ?: 0f
                animateDrawer(DrawerGeometry.settle(drawerProgress, vx, dp(500).toFloat()), refresh = false)
            }
            drag.velocity?.recycle()
            drag.velocity = null
            drag.reset()
            return wasDragging
        }
    }
    return drag.active
}

// ------------------------------------------------------------------ Chat samaran

/** Hidupkan/matikan Chat samaran: pesan tidak disimpan ke riwayat di perangkat. */
internal fun MainActivity.setIncognito(on: Boolean) {
    if (on == incognito) return
    if (busy) { toast("Tunggu proses selesai"); return }
    if (on && (hist.isNotEmpty() || chatBox.childCount > 0)) {
        toast("Mulai chat baru dulu untuk memakai Chat samaran")
        return
    }
    if (!on && (hist.isNotEmpty() || chatBox.childCount > 0)) newChat() // chat samaran tidak pernah ditulis ke disk, jadi aman
    // Saat keluar: newChat() sudah membuang checkpoint, pratinjau gambar, dan salinan lampiran. Panggilan ini menjamin checkpoint
    // di memori ikut hilang walau layar sudah kosong. incognito harus masih true selama newChat() agar gambar ikut dihapus.
    if (!on) clearAgentCheckpoint()
    incognito = on
    incognitoWriteWarned = false
    applyIncognitoLook()
}

internal fun MainActivity.applyIncognitoLook() {
    topTitle.visibility = if (incognito) View.VISIBLE else View.INVISIBLE
    ghostCircleBg.setColor(if (incognito) Palette.STRONG else Palette.NAV)
    ghostCircleBg.setStroke(dp(1), if (incognito) Palette.STRONG else Palette.BORDER)
    ghostIcon.color = if (incognito) onColor(Palette.STRONG) else Palette.INK
    emptyNormal.visibility = if (incognito) View.GONE else View.VISIBLE
    emptyIncognito.visibility = if (incognito) View.VISIBLE else View.GONE
}

internal fun MainActivity.showIncognitoInfo() {
    AlertDialog.Builder(this)
        .setTitle("Chat samaran")
        .setMessage(
            "Percakapan di Chat samaran tidak ditulis ke riwayat di perangkat ini, jadi hilang saat Anda keluar dari mode ini atau menutup aplikasi.\n\n" +
                "Pesan tetap dikirim ke penyedia AI yang Anda pilih agar bisa dijawab, dan file yang dibuat agen di folder kerja tidak ikut dihapus."
        )
        .setPositiveButton("Mengerti", null)
        .show()
}

// ------------------------------------------------------------------ Lembar "Tambahkan ke chat"

/** Ambil foto dengan aplikasi kamera; hasilnya masuk sebagai lampiran lewat [MainActivity.onActivityResult] (kode 80). */
internal fun MainActivity.takePhoto() {
    try {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "mythos_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MYTHOS")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) { toast("Kamera tidak tersedia"); return }
        cameraUri = uri
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(i, 80)
    } catch (e: Exception) {
        toast("Kamera tidak tersedia")
    }
}

internal fun MainActivity.showAddSheet() {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    fun close(then: () -> Unit) { d.dismiss(); then() }

    fun tile(glyph: Int, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = pressable(rounded(Palette.TILE, 24))
        addView(GlyphView(context, glyph, Palette.INK).apply { strokeDp = 1.5f }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(context).apply {
            text = label
            textSize = 17f
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        setOnClickListener { close(onClick) }
    }

    fun row(glyph: Int, tone: Int, label: String, trailing: View?, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(rounded(Palette.NAV, 26))
        setPadding(dp(16), 0, dp(14), 0)
        addView(GlyphView(context, glyph, tone).apply { strokeDp = 1.5f }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(TextView(context).apply {
            text = label
            textSize = 17f
            setTextColor(tone)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        if (trailing != null) addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP))
        setOnClickListener { onClick() }
    }

    fun trailingText(t: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = t
            textSize = 15f
            setTextColor(Palette.INK_SOFT)
        })
        addView(GlyphView(context, GlyphView.CHEVRON_R, Palette.INK_SOFT).apply { strokeDp = 1.4f },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
    }

    val deepSwitch = android.widget.Switch(this).apply { isChecked = deepOn(); isClickable = false }

    val header = FrameLayout(this).apply {
        addView(TextView(context).apply {
            text = "Tambahkan ke chat"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH, dp(44), Gravity.CENTER_VERTICAL))
        addView(FrameLayout(context).apply {
            contentDescription = "Tutup"
            background = pressable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.NAV)
                setStroke(dp(1), Palette.BORDER)
            })
            addView(GlyphView(context, GlyphView.CLOSE, Palette.INK).apply { strokeDp = 1.5f }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { d.dismiss() }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
    }

    val tiles = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tile(GlyphView.CAMERA, "Kamera") { takePhoto() }, LinearLayout.LayoutParams(0, dp(100), 1f))
        addView(tile(GlyphView.IMAGE, "Foto") { pickImage() }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginStart = dp(8) })
        addView(tile(GlyphView.FILE_UP, "File") { pickFile() }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginStart = dp(8) })
    }

    val workdirName = workdir().substringAfterLast('/').ifBlank { "MYTHOS" }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.BG, 34)
        setPadding(dp(16), dp(10), dp(16), dp(20))
        addView(View(context).apply { background = rounded(0xFFB9B8B4.toInt(), 3) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        addView(header, LinearLayout.LayoutParams(MATCH, dp(44)))
        addView(tiles, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(28) })
        if (hasRecentPhotoAccess()) {
            addView(buildRecentStrip { uri -> d.dismiss(); importFile(uri) },
                LinearLayout.LayoutParams(MATCH, dp(92)).apply { topMargin = dp(16) })
        } else {
            addView(row(GlyphView.PHOTOS, 0xFF2B5CA8.toInt(), "Tampilkan foto terbaru", null) { close { askRecentPhotoAccess() } },
                LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(16) })
        }
        addView(row(GlyphView.PROJECT, Palette.INK, "Tambahkan ke proyek", trailingText(workdirName)) { close { showSettings() } },
            LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(14) })
        addView(row(GlyphView.BRAIN, Palette.INK, "Penalaran mendalam", deepSwitch) {
            val on = !deepOn()
            setDeep(on)
            deepSwitch.isChecked = on
            toast(if (on) "Penalaran mendalam aktif" else "Penalaran mendalam dimatikan")
        }, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(14) })
    }

    d.setContentView(FrameLayout(this).apply {
        setPadding(dp(6), 0, dp(6), dp(6))
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    })
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        setDimAmount(0.28f)
        attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
    }
    d.show()
}
