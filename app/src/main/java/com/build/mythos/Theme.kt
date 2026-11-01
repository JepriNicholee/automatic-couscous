package com.build.mythos

import android.content.Context
import android.content.res.Configuration
import android.view.View

// Mode gelap: mengikuti pengaturan tema sistem. Warna ada di Palette (getter yang bergantung pada Palette.dark);
// berkas ini hanya menentukan nilai awalnya dan hanya menyentuh Android lewat Configuration.

/** True bila [uiMode] (Configuration.uiMode) menyatakan mode malam. Murni, bisa diuji tanpa Android. */
internal fun isNightMode(uiMode: Int): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Atur [Palette.dark] dari konfigurasi sistem. Panggil sebelum membangun tampilan. Mengembalikan true bila nilainya berubah. */
internal fun applyTheme(context: Context): Boolean {
    val night = isNightMode(context.resources.configuration.uiMode)
    val changed = Palette.dark != night
    Palette.dark = night
    return changed
}

/** Bendera system UI untuk status bar (dan bar navigasi bila [navLight]): ikon gelap di tema terang, bawaan (ikon terang) di tema gelap. */
internal fun barFlags(navLight: Boolean): Int {
    if (Palette.dark) return 0
    var f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    if (navLight) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    return f
}
