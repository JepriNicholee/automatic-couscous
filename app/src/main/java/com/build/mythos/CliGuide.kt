package com.build.mythos

// Panduan alur penyampaian pekerjaan AI (kalimat sebelum alat -> temuan setelah alat -> jawaban akhir terstruktur).
// Dipakai BERSAMA oleh Codex (disisipkan wrapper Node di ModelCall.kt) dan Gemini CLI, supaya alur streaming aktivitasnya sama.
// Teks ini masuk ke string JavaScript berkutip ganda: jangan memakai tanda kutip ganda, backslash, `$`, atau baris baru.
internal const val CLI_NARRATION_GUIDE = "Cara menyampaikan pekerjaan, ikuti urutan ini. (1) Sebelum menjalankan alat, tulis satu kalimat pendek tentang apa yang akan kamu periksa dan alasannya, lalu langsung jalankan alatnya. (2) Setelah hasil alat masuk, sampaikan temuan terpenting dalam satu sampai dua kalimat yang jelas, lalu lanjut ke langkah berikutnya; jangan mengulang hal yang sudah kamu katakan. (3) Jangan menulis paragraf panjang di tengah pekerjaan. (4) Setelah semua selesai, tutup dengan satu jawaban akhir yang rapi: baris pertama berisi hasil utamanya, lalu bagian Inti penjelasan yang menerangkan apa yang berubah dan bagaimana cara mengatur atau memakainya (langkah atau nilai pengaturan yang konkret), lalu bila ada satu kalimat tentang apa yang belum terverifikasi. (5) Pakai bahasa Indonesia yang sederhana dan jangan menampilkan isi penalaran mentah. Permintaan pengguna: "

/** Prompt yang dikirim ke CLI. Gemini mendapat panduan yang sama dengan Codex; provider lain apa adanya. */
internal fun cliPromptFor(prov: String, msg: String, deepOn: Boolean, simple: Boolean): String {
    val base = deepCliPrompt(msg, deepOn, simple)
    return if (prov == "Gemini CLI (Google)") CLI_NARRATION_GUIDE + base else base
}

/**
 * Panduan alur untuk model API (OpenRouter dll.): kalimat pembuka sebelum alat, temuan singkat sesudahnya, jawaban akhir rapi.
 * Tanpa ini model bebas langsung memanggil alat tanpa teks, sehingga tidak ada teks live di antara langkah.
 * Sengaja terpisah dari [CLI_NARRATION_GUIDE] (isinya dikunci tes untuk wrapper Codex).
 */
internal const val API_NARRATION_GUIDE = "Cara menyampaikan pekerjaan: sebelum memanggil alat, tulis satu kalimat pendek tentang apa yang akan kamu periksa dan alasannya, lalu langsung panggil alatnya. Setelah hasil masuk, sampaikan temuan terpenting dalam satu sampai dua kalimat, lalu lanjut. Jangan menulis paragraf panjang di tengah pekerjaan dan jangan menampilkan penalaran mentah. Setelah semuanya selesai, tutup dengan satu jawaban akhir yang rapi: baris pertama berisi hasil utama, lalu penjelasan singkat. Untuk sapaan atau pertanyaan biasa, cukup jawab langsung."
