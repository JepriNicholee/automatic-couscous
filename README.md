# mythos-validation

Proyek ini dibuat oleh AI Software Engineering Studio.

## Struktur
- app/src/main/java/com/build/mythos/ : source Kotlin (kelas PascalCase)
- app/src/test/java/ : unit test
- .github/workflows/build-apk.yml : build APK debug di GitHub Actions
- info.json : metadata proyek (nama, package, dependensi utama)

## Alur kerja
Ubah source -> jalankan test -> build APK debug.

Catatan: kegagalan alat agen dicatat di ~/MYTHOS/logs/error_summary.log.
