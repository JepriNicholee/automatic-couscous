# Perubahan tampilan v2.2 (modern, sederhana, tajam)

Tujuan: layar lebih bersih, satu cara untuk tiap hal, mudah dioperasikan satu tangan.
Logika agen, penyedia model, dan keamanan tidak diubah.

## Chat
- Latar putih bersih (dulu gradasi biru berawan). Teks AI 16,5sp, spasi baris 1,35, warna tinta tunggal.
- Hapus kilau biru berjalan pada teks yang sedang mengalir, dan hapus kata tebal/kode yang berwarna biru.
  Tebal = tebal biasa; kode inline = abu lembut.
- Hapus header "AI Agent - status" di tiap balasan (mengulang baris aktivitas). Status hanya di SATU pil abu di atas jawaban.
- Pil status tanpa awalan "AI Agent"; hasil akhir ringkas: "Berhasil - n langkah".
- Gelembung pengguna lebih rapat; chip saran putih bergaris halus (tidak lagi biru).

## Header dan input
- Header: pil model di kiri, chat baru + pengaturan (ikon roda gigi) di kanan. Ikon menu tiga garis (yang dulu membuka Pengaturan) dan avatar dihapus.
- Input: satu tombol aksi saja (mikrofon saat kosong, panah kirim saat ada teks, kotak saat berjalan). Tombol mikrofon ganda dihapus.
- Layar kosong kini punya sapaan dan tiga contoh perintah (mengisi kolom input, tidak langsung menjalankan).

## Pengaturan, model, izin
- Pengaturan jadi lembar bawah: Penyedia AI, API key, Simpan. Menu pendukung berupa baris tautan. Opsi lanjutan memakai saklar dengan keterangan satu baris.
- Pemilih model jadi lembar bawah dengan daftar bersih, centang pada model aktif, penanda "sibuk".
- Dialog izin: penjelasan, label risiko berwarna lembut, perintah di kotak abu. Tombol "Jalankan", "Tolak", "Ya, semua".
- Dialog sistem: sudut 28dp, tombol tanpa huruf kapital semua.

## File
- Baru: Ui.kt (pembantu bentuk, tombol, lembar bawah).
- Diubah: Palette, MainActivity, ChatMessages, Markdown, MessageMenu, ActivityZone, AgentStepList, StatusBar,
  SettingsDialog, ModelPicker, ApprovalDialog, ChatControls, ModelCall/ToolExecution (hanya teks tombol), styles/colors/dialog_bg.
- Belum dikompilasi di lingkungan penyusunan: jalankan WITH_TESTS=1 bash build-termux.sh.
