package com.build.mythos

import org.json.JSONObject

/**
 * Definisi alat yang dikirim ke model: penuh, atau dipadatkan (deskripsi dipendekkan) saat anggaran konteks model ini
 * sudah diperkecil karena batas token kecil. Nama, tipe, dan parameter wajib tidak berubah.
 */
internal fun MainActivity.promptDecls(): List<JSONObject> {
    // Model lokal hanya mendapat alat inti (LocalTools.kt): tiap definisi alat dibaca ulang CPU HP di setiap giliran.
    val all = declList()
    val names = toolNamesFor(isLocalUrl(cfgBase()), all.map { it.getString("name") }).toSet()
    val list = all.filter { it.getString("name") in names }
    return if (compactPromptOn()) list.map { compactDecl(it) } else list
}
