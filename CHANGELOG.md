# Perubahan terbaru

## 2.39 — Kalimat pembuka sebelum alat
- Prompt sistem jalur API kini meminta satu kalimat pembuka sebelum alat dan temuan singkat sesudahnya (`API_NARRATION_GUIDE`). Versi 2.39 (kode 54).

## 2.38 — Langkah agen API terasa
- Langkah alat agen API kini lewat antrean aktivitas yang sama dengan Codex (berurutan, berjeda, ada ringkasan di chat), jadi tidak lewat sekejap. Versi 2.38 (kode 53). Rincian: docs/PERBAIKAN_ALUR_STREAMING_227.md.

## 2.37 — Live streaming agen API sesuai rekaman acuan
- Teks model kini mengalir live di chat pada penyedia API (OpenRouter dll.), baris aktivitas muncul sesudahnya; teks pembuka giliran beralat tidak lagi dibuang. Versi 2.37 (kode 52). Rincian: docs/PERBAIKAN_ALUR_STREAMING_226.md.

## 2.36 — Teks live streaming sering tidak muncul
- Pembacaan aliran Termux yang macet kini diberi tahu di chat; event alat yang antre tidak lagi tertahan sampai akhir; indikator live kembali bila teks tak tiba dalam 5 detik. Versi 2.36 (kode 51). Rincian: docs/PERBAIKAN_ALUR_STREAMING_225.md (termasuk perbaikan layar kosong pada run agen API).

## 2.35 — Daftar pilihan model Gemini CLI
- Pengaturan -> Gemini CLI: tombol "Pilih dari daftar" menampilkan auto, pro, flash, flash-lite, dan beberapa ID bernomor; tidak perlu menghafal nama.
- "Ketik sendiri" dan kolom ketik tetap ada untuk model lain. Versi 2.35 (kode 50). Rincian: docs/PERBAIKAN_PILIHAN_MODEL_GEMINI.md.

## 2.34 — Alur live streaming: Gemini disamakan dengan Codex
- Gemini CLI kini menerima panduan alur penyampaian yang sama dengan Codex (`CLI_NARRATION_GUIDE`).
- Baris status live tampil lagi di setiap putaran baru, sehingga pesan kedua tidak lagi kosong tanpa indikator.
- Versi dinaikkan ke 2.34 (kode 49). Rincian: docs/PERBAIKAN_ALUR_STREAMING_224.md.

## 2.30 — Pemilih model Gemini CLI
- Tambah kolom Model Gemini CLI pada Pengaturan saat provider Gemini CLI dipilih; tampilan utama MYTHOS tidak diubah.
- `auto` menjadi nilai bawaan dan menggunakan routing otomatis native Gemini CLI; alias (`pro`, `flash`, `flash-lite`) serta ID model konkret bisa dimasukkan manual.
- Perintah chat meneruskan pilihan melalui `--model`, membersihkan flag model lama, dan mendukung `-p`, `--prompt`, serta `--prompt=...`.
- Validasi nama model dan shell quoting mencegah input model berubah menjadi argumen/perintah shell.
- Uji helper terisolasi lulus untuk 13 skenario. Build Gradle penuh belum terverifikasi karena distribusi Gradle tidak dapat diunduh di lingkungan ini.

## 2.22
- Narasi aktivitas tidak lagi diketik per karakter; tampil utuh lewat pergantian teks seperti rekaman acuan (teks lama memudar, teks baru memudar masuk, lebar dan panah ikut bergeser, teks berkilau).
- Teks narasi diturunkan dari item Codex yang nyata: nama file yang dibaca, perintah yang dijalankan, file yang diubah, atau kueri pencarian. Kalimat umum "Saya sedang ..." dihapus.
- Dua jenis baris selesai di chat sesuai video: ringkasan alat ("Membaca sebuah file, menjalankan 2 perintah" + centang kecil + panah, tanpa ikon kiri) dan baris penalaran (ikon jam abu-abu + teks abu-abu + panah).
- Sub-baris "Masih diproses..." muncul di bawah baris status bila 6 detik tanpa aktivitas baru.
- Tempo disesuaikan dengan hitungan dari video acuan: narasi tertahan 3,6 detik (video: 2-6 detik), lalu status umum 1,5 detik ("Sedang menjalankan perintah", "Sedang membaca file", "Proses berpikir") sebelum narasi berikutnya.
- Narasi kini berupa kalimat penjelasan yang lebih panjang (memuat nama file/perintah nyata), bukan frasa pendek.
- Prompt Codex diberi panduan: dua sampai tiga kalimat penjelasan sebelum tiap rangkaian alat dan satu paragraf temuan sesudahnya, tanpa kalimat pendek tunggal di antara alat.
- Bentuk ikon bola mengikuti hasil ukur video: dasar 3 titik mengorbit; 4 titik (grid) tampil 7 detik di awal tiap rangkaian alat, lalu kembali ke 3 titik. Kode gerak ikon (ClaudeDots.kt) dan Palette.kt tidak diubah.

## 2.21
- Narasi aktivitas tidak lagi menyembunyikan ikon tiga bola; status kerja kembali di antara narasi.

# Changelog

## 2.20
- Tampilkan narasi aktivitas singkat secara bertahap berdasarkan event tool Codex yang nyata.
- Setelah narasi selesai, kembalikan indikator kerja tanpa membentuk ulang ikon tiga bola oranye.
- Jawaban tetap dirender dari delta Codex; tidak memakai efek ketik sintetis untuk jawaban.
- Ikon oranye tidak diubah, dan UI lain dipertahankan.

## 2.18 — Alur live streaming mengikuti video referensi
- Output Codex App Server dibingkai sebagai JSONL tunggal berisi delta teks dan event aktivitas, sehingga urutan pesan → aktivitas alat → pesan berikutnya tetap terjaga.
- Render Codex memperbarui teks langsung ketika delta diterima; tidak ada typewriter sintetis yang tertinggal dari output model.
- Item `commandExecution`, `fileChange`, pencarian web, dan alat lain ditampilkan sebagai baris aktivitas kecil tanpa kartu latar; mengetuk baris membuka detail aktivitas.
- Pesan AI difinalisasi pada batas item alat agar aktivitas tampil di posisi kronologis yang tepat; segmen akhir tetap mendapat kontrol salin.
- Pembacaan event memperhitungkan potongan JSONL dan UTF-8 parsial; penanganan pending chunk saat proses selesai diperbaiki untuk jalur Codex.
- Uji protokol tiruan Node.js lulus; build Android dan tes Gradle belum terverifikasi karena `services.gradle.org` tidak dapat diakses di lingkungan ini.

## 2.16 — Codex streaming stabilitas
- Codex menggunakan `app-server` JSON-RPC dan event `item/agentMessage/delta` untuk menampilkan teks ketika dihasilkan, dengan fallback item final untuk versi tanpa delta.
- Pembacaan file live berjalan secara inkremental berdasarkan offset byte dan base64 untuk mempertahankan UTF-8 serta mencegah pemotongan hasil panjang oleh batas stdout satu perintah.
- Diagnostik stderr dipisahkan dari teks jawaban AI; status gagal Codex diteruskan ke UI.
- Ticker animasi mengetik dipertahankan sepanjang stream, tidak dibuat ulang pada setiap polling.
- Pemeriksaan kesiapan/pemasangan Codex memastikan Node.js dan dukungan `app-server` tersedia.
- Versi aplikasi dinaikkan ke versionCode 29 / versionName 2.16; tes login CLI diperbarui.


## 2.15 (versionCode 28) - BELUM DIKOMPILASI
- Preset Codex tidak lagi menganggap keberadaan `codex` saja sebagai instalasi yang valid; pemeriksaan memastikan paket Termux kompatibel terpasang dan menolak pembungkus/mock `Termux Mode` yang sebelumnya hanya mengaku menerima prompt.
- Jika perlu memasang ulang, aplikasi memasang paket komunitas yang ditargetkan untuk Android/Termux (`@bash0816/codex-termux`) setelah menghapus paket upstream `@openai/codex` yang tidak menargetkan Termux secara resmi.
- Status login memakai `codex login status`, bukan hanya memeriksa `~/.codex/auth.json`; UI membedakan CLI tidak siap dengan akun belum login.
- Catatan keamanan: build Termux tersebut adalah distribusi komunitas, bukan rilis resmi OpenAI. Login tetap dijalankan di sesi Termux; evaluasi sumber paket sebelum menghubungkan akun. Tes metadata: `CliLoginsTest`. Build lokal belum berhasil diverifikasi karena Gradle tidak bisa mengakses `services.gradle.org` dari lingkungan ini.

## Memori obrolan tahan upgrade - BELUM DIKOMPILASI
- File memori berversi (`v:2`); format lama tetap terbaca. `ChatMemory.merge` menyatukan dua daftar catatan.
- Cadangan otomatis ke Termux `~/MYTHOS/memory/chat_memory.json` dan pemulihan (penggabungan) saat aplikasi dibuka; "Hapus memori obrolan" ikut menghapus cadangan.
- Impor chat lama kini menggabung, bukan menimpa, isi memori terkini.
- Pemanggilan ingatan memberi bobot lebih pada kata yang jarang muncul (makin banyak catatan, makin tajam). Tes: `ChatMemoryTest` (+3).

## Perbaikan tulis ulang berulang dan respons kosong (patch v2) - BELUM DIKOMPILASI
- `WriteTracker` (AgentGuard.kt): menghitung penimpaan file yang sama; peringatan ke model di penimpaan ke-3, agen berhenti di ke-5. Tes/build yang berhasil (bukan perintah baca seperti `cat test_all.py`) mengosongkan hitungan.
- `unittest` dan `pytest` kini dikenali sebagai tes (`verificationKind`).
- Respons model kosong: diulang sekali, lalu `EmptyResponseException` memindahkan ke model cadangan (status BUSY, tidak memblokir model permanen). Bila semua model kosong, pesan jelas ke pengguna.
- Tes: `WriteTrackerTest`.

## 2.14 (versionCode 27) - BELUM DIKOMPILASI
- Konektor Email (IMAP/SMTP), Telegram (bot), dan WhatsApp (Cloud API, hanya kirim): setelah dihubungkan di Pengaturan > Koneksi, agen punya alat
  `email_daftar`, `email_baca`, `email_kirim`, `telegram_baca`, `telegram_kirim`, `whatsapp_kirim` (hanya alat konektor yang aktif dikirim ke model).
  Membaca langsung jalan; mengirim selalu lewat dialog persetujuan yang tidak bisa dilewati mode otonom (kecuali "Kirim otomatis" per konektor).
  Isi email/pesan masuk dibungkus penanda data tidak tepercaya (anti prompt injection). Rahasia disimpan terenkripsi. Tes: `ConnectorsTest`. Rincian: `KONEKTOR.md`.
- Layar "Jelajahi konektor" (Pengaturan > Konektor): kartu dengan tombol **Hubungkan** dan alur pendek per konektor. Email: preset server + sandi aplikasi dari papan klip;
  Telegram: token dari papan klip lalu chat ID terpasang sendiri lewat tautan /start berkode; WhatsApp di HP: izin Akses notifikasi (layanan `WaListenerService`,
  alat `whatsapp_baca` dan `whatsapp_balas`); WhatsApp Business API: token Meta. Manifest: layanan `WaListenerService` (BIND_NOTIFICATION_LISTENER_SERVICE).

## 2.13 (versionCode 26) - BELUM DIKOMPILASI
- Berangkas bawaan APK (`app/src/main/assets/berangkas/`): aturan kerja, skrip, panduan, templat; hanya baca, ikut berganti tiap upgrade.
  Alat baru `berangkas_baca` dan `berangkas_salin` (salinan dibuat ulang di `~/.mythos/berangkas`), dengan pemeriksaan `SHA256SUMS`.
  Skrip baru `kita-cek.sh` untuk cek kesiapan alat di Termux tanpa menilai dari folder kerja. Tes: `BerangkasTest`.

## Belum dirilis (BELUM DIKOMPILASI)
- Memori obrolan (`ChatMemory.kt`, `ChatMemoryStore.kt`, `MEMORI_OBROLAN.md`): tanya-jawab dari semua chat disimpan lokal dan 1-3 catatan paling relevan disisipkan
  ke pesan di mode obrolan model lokal; riwayat lama diimpor sekali; tombol hapus di Pengaturan > Opsi lain. Tes: `ChatMemoryTest`.
- Layar Pengaturan > Lanjutan disederhanakan: "Setujui otomatis perintah baca" tidak lagi ditampilkan (nilainya tetap, bawaan aktif; sudah tercakup
  "Konfirmasi seperlunya"); "Mode obrolan" hanya tampil untuk model lokal; "Mode sandbox" dan "Kurangi gerakan" pindah ke tombol "Opsi lain".
  Mode obrolan model lokal kini selalu aktif dan saklarnya dihapus (AI internet tidak terpengaruh; model lokal tidak lagi bisa menjalankan alat). Label GitHub diperjelas. "Konfirmasi seperlunya" kini selalu aktif dan saklarnya dihapus dari Pengaturan (`minimalConfirm()` selalu true).
- Cache hasil uji model kedaluwarsa setelah 24 jam (`ModelCache.kt`): daftar lama dihitung ulang, model pilihan tetap dipakai bila masih lolos.
  Penyegaran diam-diam di latar saat aplikasi dibuka (`autoSetupModels(silent = true)`), tidak jalan saat tugas berlangsung dan dibatasi sekali per jam bila gagal.
  Cache lama tanpa waktu simpan dianggap basi sekali, lalu diuji ulang.
- Error 404 "model not found" / dihentikan penyedia kini dikenali (`ModelGoneException`): tugas dialihkan ke model lain, model itu dibuang dari cache uji,
  dan bila itu model tersimpan, diganti model terbaik yang tersisa. Model terkunci (OpenRouter) tidak disentuh. Tes: `ModelCacheTest`.
- Peralihan AI otomatis juga berjalan saat penyedia internet tidak menjawab (koneksi gagal/putus/timeout setelah percobaan ulang); server lokal tetap tidak dialihkan.
  Tes: `UnreachableTest`. Rincian: `PERBAIKAN_PINDAH_AI_TIDAK_MENJAWAB.md`.
- OpenRouter jadi penyedia utama bawaan; kode pilih-model lama OpenRouter dihapus; model dikunci ke `openrouter/free` (tidak bisa dipilih, diketik, atau diganti failover dalam OpenRouter). Tes: `ModelLockTest`. Rincian: `KUNCI_MODEL_OPENROUTER.md`.
- Model lokal: status "membaca prompt" menampilkan perkiraan token dan waktu (kecepatan dipelajari otomatis); laporan masalah memuat versi Ollama, `ollama ps`, daftar model
  dan log cache; `CliLogin` memakai pembungkus LD_PRELOAD yang sama dengan `TermuxShell` (kini fungsi `preloadWrapper`); "Halo" dibalas "Halo". Tes: `LocalProgressTest`.
- Model lokal: hanya `run_command`, `write_file`, `ask_user` yang dikirim (prompt ±2.200 → ±1.000 token menurut hitungan); playbook ringkas disesuaikan.
  Catatan "Awal prompt berubah" bila sistem/alat berubah antar giliran (cache Ollama). Tes: `LocalToolsTest`. Rincian: `PERBAIKAN_KECEPATAN_LOKAL.md`.
- Model lokal: obrolan pendek tanpa kata kerja tindakan ("Waalaikum salam, sangat siap") kini lewat jalur sapaan (tanpa alat, prompt ringkas);
  cadangan failover di server lokal hanya model terpilih; langkah "Varian cepat (6 thread)" di Siapkan Termux membuat Modelfile `num_thread` otomatis.
  Tes: `LightChatTest`. Rincian dan status verifikasi: `PERBAIKAN_KECEPATAN_LOKAL.md`.
- Perintah Termux: bash kini dijalankan ulang sekali dengan `LD_PRELOAD` ke `libtermux-exec-ld-preload.so` (bila ada), supaya skrip ber-shebang
  `#!/usr/bin/env` (gemini, codex) tidak lagi gagal dengan "bad interpreter". Versi dinaikkan ke 2.12 (versionCode 25) agar mudah dibedakan di laporan.
- Riwayat banyak chat: tiap chat satu file di `filesDir/chats`, indeks untuk drawer, chat lama tidak lagi terhapus saat "Chat baru"; `history.json` lama dimigrasi otomatis.
  Daftar "Terbaru", halaman "Semua chat" (cari, ubah nama, hapus). Tes: `ChatStoreTest`.
- Drawer: "Proyek" dan "Artefak" kini halaman sungguhan (folder proyek aktif/arsip; file buatan agen yang bisa dibuka, dibagikan, dihapus).
  `ShareProvider` baru di manifest. Tes: `WorkspaceListTest`. Rincian: `PERUBAHAN_RIWAYAT_PROYEK_ARTEFAK.md`.
- Lembar "Tambahkan ke chat": strip "Foto terbaru" (12 foto terakhir, ketuk untuk melampirkan). Izin `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED`
  (Android 14 akses sebagian didukung); ditolak = jatuh kembali ke pemilih foto. Tes: `RecentPhotosTest`.
- Chat samaran: checkpoint agen hanya di memori (tidak ke disk), sapuan pratinjau gambar yatim saat aplikasi dibuka, dan peringatan di chat
  saat agen menulis file di folder kerja. Tes: `IncognitoGuardTest`. Rincian: `PERUBAHAN_FOTO_TERBARU_SAMARAN.md`.
- Drawer: lebar dibatasi 360dp (tablet/lanskap) dan ikut diperbarui saat layar diputar; geser kini mengikuti jari dan menyelesaikan gerak
  berdasarkan kecepatan lemparan atau setengah jalan. Tes: `DrawerGeometryTest`. Rincian: `PERUBAHAN_DRAWER_GESER.md`.

## 2.11 (versionCode 22)
- Aksesibilitas: `contentDescription` (teks di `res/values/strings.xml`, awal lokalisasi) untuk tombol menu, ikon hantu, tambah, pilih model,
  penalaran mendalam, mikrofon, kirim/hentikan (deskripsi tombol aksi ikut berganti saat agen sibuk).
- CI: berjalan juga pada pull request; `concurrency` membatalkan run lama hanya untuk pull request (push tidak, agar `build_apk` tidak melihat run-nya dibatalkan); batas waktu 30 menit;
  laporan tes selalu diunggah; lint dijalankan (belum menggagalkan job).
- Threading: 14 `Thread { ... }.start()` kerja latar sekali jalan (ApkInstall, CliLogin, FileImport, LiveTail.stop, ModelPicker x2,
  Report, SettingsDialog, SetupScreen, TaskCleanup, TermuxFiles, TermuxSetup, TermuxShell.killRemote, WorkspaceMigration)
  dipindah ke helper `bg { }` (Background.kt, coroutine di Dispatchers.IO). `Thread.sleep` di `ModelCall`/`TermuxShell`
  dibiarkan karena kini berjalan di dalam runInterruptible milik AgentLoop. Tes: `BackgroundTest`.
- Agen: `AgentLoop` kini berjalan sebagai Job (`bgCancellable`, runInterruptible) bukan Thread lepas. `worker` bertipe `Job?`; tombol
  Berhenti dan `onDestroy` memakai `worker?.cancel()` yang tetap menginterupsi thread yang sedang memblokir (HTTP, `Thread.sleep`,
  Termux). `onFinally` (bersih-bersih `busy`, laporan, antrean pesan) dijamin jalan walau dibatalkan sebelum loop mulai. Alasan
  start ATOMIC ada di Background.kt. Tes: `BgCancellableTest`. Perilaku `cancelled`/`Cancelled` tidak diubah.
- `LiveTail` kini coroutine polling (`delay` + `runInterruptible`), bukan thread daemon; `stop()` memanggil `job.cancel()`.
  `ApkInstall` (thread rx) sengaja tetap thread: pendek, di-`join` dengan batas waktu, dan diputus lewat `server.close()`.
- Kolom pesan (`EditText`) tidak diberi `contentDescription`, karena bisa menimpa teks yang dibacakan TalkBack; `hint` sudah cukup.
- Tampilan chat: tabel Markdown kini dirender sebagai tabel (`MarkdownTable.kt`, `tableView` di `Markdown.kt`; sebelumnya tampil sebagai
  baris `| a | b |` mentah). Tombol gulir ke bawah memakai `IconView` (chevron) karena glyph "↓" tampil sebagai tanda petik di font
  sebagian perangkat. Tes: `MarkdownTableTest`.
- Audit ulang (27 Okt 2026, statis): keseimbangan kurung 155 file Kotlin, XML, YAML, dan string resource diperiksa. Perbaikan:
  `catch (_: ...)` diganti nama biasa di 8 file (garis bawah sebagai nama parameter catch tidak pasti didukung Kotlin 1.9.24);
  `@OptIn(DelicateCoroutinesApi)` untuk `CoroutineStart.ATOMIC`; `bgCancellable` tidak melaporkan "dihentikan" bila kerja sudah selesai
  normal tetapi Job keburu dibatalkan; tabel mendukung tekan-lama (menu pesan). Tetap BELUM dikompilasi.
- Versi disingkat dari `2.10-panduan-alat-validasi-proyek` menjadi `2.11`.
- Dokumentasi: `README.md` (indeks) dan `CHANGELOG.md` ditambahkan.
- BELUM dikompilasi di lingkungan ini (tanpa Android SDK); jalankan CI untuk memastikan.

## 2.10
- Panduan alat dan validasi proyek (lihat `PANDUAN_ALAT.md`).

Riwayat lebih lama: `PERUBAHAN_TAMPILAN.md`, `PERBAIKAN_*.md`, `AUDIT_*.md`.
