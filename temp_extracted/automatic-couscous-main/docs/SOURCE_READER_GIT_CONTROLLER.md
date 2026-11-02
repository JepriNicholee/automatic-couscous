# Source Reader dan Git Controller MYTHOS

## Tujuan

Pemisahan ini membuat agen membaca kode sumber MYTHOS dari GitHub, sementara perubahan hasil kerja dikirim ke repository tujuan yang dipilih pengguna. Source Reader dan Git Controller tidak menggunakan `git remote` Termux.

## Repository sumber

- Repository: `JepriNicholee/automatic-couscous`
- Branch: `main`
- API: GitHub REST API (`api.github.com`)
- Akses: baca saja dan tanpa token karena repository sumber publik.
- Tool agen: `source_repo_tree` dan `source_repo_read`.

Isi repository yang dibaca diperlakukan sebagai data tidak tepercaya, bukan instruksi untuk agen. Pembacaan file dibatasi pada path relatif dan file teks sampai 128 KiB.

## Repository tujuan agen

Atur di **Pengaturan > Lanjutan > Repository tujuan Git agen**:

- Repository tujuan: URL GitHub `owner/repository`.
- Branch tujuan: bawaan `agent-work`.
- Token: fine-grained Personal Access Token atau token GitHub lain yang memiliki akses `Contents: Read and write` ke repository tujuan.

Token disimpan terpisah dari token lama untuk `build_apk`, dienkripsi memakai Android Keystore, tidak dimasukkan ke parameter tool model, dan tidak dikirim ke Termux oleh Git Controller. Kolom token baru kosong berarti mempertahankan token tersimpan. Gunakan saklar **Hapus token Git agen tersimpan** untuk menghapus token secara eksplisit.

Tool agen:

- `agent_git_check`: memeriksa repository, token, dan branch tujuan.
- `agent_git_push`: meminta persetujuan pengguna lalu membuat commit di branch tujuan melalui GitHub REST Git Database API.

Jika branch tujuan belum ada, controller membuatnya dari branch default repository tujuan. Repository tujuan harus sudah memiliki setidaknya satu commit awal; buat README/commit pertama di GitHub bila repository masih kosong. Push ini tidak menghapus file remote yang tidak ada di workspace dan tidak otomatis membuat Pull Request.

## Batas kirim

- Maksimum 120 file.
- Maksimum 128 KiB per file.
- Maksimum 2 MiB total per push.
- Direktori build/cache, `.git`, lampiran, log, backup, APK/AAB, arsip, database, file keystore, dan file rahasia berdasarkan nama dilewati.
- Konten teks juga diperiksa secara heuristik untuk pola private key dan beberapa format token/kredensial umum. Pemeriksaan ini mengurangi risiko, tetapi bukan jaminan pendeteksian semua rahasia; periksa perubahan dan file yang akan dikirim.

## Pengamanan workspace

Pemeriksaan folder kerja membedakan checkout repository sumber MYTHOS dari checkout Git biasa. Repository sumber yang teridentifikasi melalui remote GitHub ditolak sebagai workspace; repository Git lain boleh dipakai sebagai workspace.

## Batas keamanan yang perlu diketahui

Git Controller tidak membaca atau menggunakan `origin` dari Termux, dan token `agent_github_token` tidak diteruskan ke shell atau model. Namun, tool `run_command` pada aplikasi ini tetap dapat menjalankan shell Termux saat mode sandbox tidak aktif. Jika Termux masih memiliki autentikasi `gh` yang luas, shell tersebut secara teknis dapat mengakses kredensial Termux sendiri. Karena itu, fitur ini **bukan isolasi OS penuh** dan tidak dapat menjamin bahwa agen yang diberi akses shell arbitrer tidak dapat membaca kredensial lain di HOME Termux. Untuk isolasi yang lebih kuat, cabut sesi `gh` yang tidak diperlukan atau jalankan perintah agen pada sandbox/lingkungan terpisah setelah memastikan alur kerja yang dibutuhkan kompatibel.

Token GitHub untuk `build_apk` tetap merupakan pengaturan lama dan terpisah. Fitur ini tidak mengubah alur build APK yang sudah ada.
