package com.build.mythos

/** Migrasi satu kali dari workspace lama ~/kita ke reference read-only tanpa menghapus sumber asli. */
internal object WorkspaceMigration {
    const val DONE = "mythos_legacy_migration_v3_readonly_done"

    fun command(): String = """
old="${'$'}HOME/kita"; new="${'$'}HOME/MYTHOS/reference/automatic-couscous-2"
mkdir -p "${'$'}HOME/MYTHOS/reference" "${'$'}HOME/MYTHOS/projects/aktif" "${'$'}HOME/MYTHOS/projects/arsip" "${'$'}HOME/MYTHOS/artifacts" "${'$'}HOME/MYTHOS/attachments" "${'$'}HOME/MYTHOS/backups" "${'$'}HOME/MYTHOS/logs"
if [ ! -d "${'$'}old" ]; then echo MYTHOS_MIGRATION=none; exit 0; fi
mkdir -p "${'$'}new"
# Allowlist ini mengikuti automatic-couscous-github-build.zip: proyek Android AgentApp saja.
for item in \
  .gitignore .github app gradle tools \
  build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat \
  keystore.properties.example \
  STRUKTUR.md DOKUMENTASI.md PERBAIKAN_BUILD_AGEN.md PERBAIKAN_LOGIKA_AGEN.md \
  PERBAIKAN_KONEKSI.md PERUBAHAN_TAMPILAN.md AUDIT_KODE_DAN_UNIT_TEST.md \
  AUDIT_KEDUA_PERBAIKAN.md AUDIT_LOGIKA_AGEN.md SANDBOX.md SELF_UPGRADE.md \
  build-termux.sh; do
  if [ -e "${'$'}old/${'$'}item" ]; then
    parent=${'$'}(dirname "${'$'}item")
    mkdir -p "${'$'}new/${'$'}parent"
    cp -a "${'$'}old/${'$'}item" "${'$'}new/${'$'}item"
  fi
done
chmod -R a-w "${'$'}new"
printf '%s\n' MYTHOS_MIGRATION=ok MYTHOS_SOURCE="${'$'}new" MYTHOS_POLICY=android-source-readonly
""".trimIndent()
}

internal fun MainActivity.startLegacyWorkspaceMigration() {
    if (prefs.getBoolean(WorkspaceMigration.DONE, false)) return
    bg {
        val result = try { termux(WorkspaceMigration.command(), limit = 4000, timeoutSec = 120) }
        catch (ignored: Exception) { "exit=1 migrasi gagal" }
        if (result.startsWith("exit=0") && (result.contains("MYTHOS_MIGRATION=ok") || result.contains("MYTHOS_MIGRATION=none"))) {
            prefs.edit().putBoolean(WorkspaceMigration.DONE, true).apply()
        }
    }
}
