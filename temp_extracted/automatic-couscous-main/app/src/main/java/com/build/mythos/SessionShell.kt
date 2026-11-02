package com.build.mythos

// Sesi shell "semu" untuk run_command: Termux RUN_COMMAND memulai bash baru tiap panggilan (stateless),
// jadi state (folder kerja + variabel yang di-export) disimpan ke file lalu dipulihkan di awal panggilan berikutnya.
// Murni JVM (tanpa Android) agar bisa diuji. Batasan jujur: yang bertahan hanya cwd dan variabel ter-export;
// proses latar, alias, fungsi shell, dan program interaktif TIDAK bertahan.

object SessionShell {
    /** Folder penyimpanan state di Termux utama. */
    const val DIR_TERMUX = "~/.mythos/sessions"

    /** Folder di dalam tamu proot (rootfs tamu bersifat persisten; home Termux tidak terlihat di sana). */
    const val DIR_SANDBOX = "/var/tmp/mythos-sessions"

    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,32}$")

    /** Null bila kosong atau tidak valid. */
    fun cleanId(raw: String?): String? = raw?.trim()?.takeIf { ID_RE.matches(it) }

    /** true bila ada isi tetapi bentuknya tidak valid (supaya pemanggil bisa menolak dengan pesan jelas). */
    fun isBadId(raw: String?): Boolean = !raw.isNullOrBlank() && cleanId(raw) == null

    // Tanda § diganti "$" saat dirakit, karena "$" di raw string Kotlin dianggap template.
    private const val TEMPLATE = """
__D=@@DIR@@
__S="§__D"/@@ID@@
mkdir -p "§__D" 2>/dev/null
find "§__D" -type f -mtime +7 -delete 2>/dev/null
__mythos_save() {
  pwd > "§__S.cwd.tmp" 2>/dev/null && mv -f "§__S.cwd.tmp" "§__S.cwd"
  {
    for __v in §(compgen -e); do
      case "§__v" in
        PWD|OLDPWD|SHLVL|_|PATH|HOME|TMPDIR|PREFIX|LD_PRELOAD|TERM|LANG|ANDROID_ROOT|ANDROID_DATA|__*) ;;
        *) declare -p "§__v" 2>/dev/null ;;
      esac
    done
  } > "§__S.env.tmp" && mv -f "§__S.env.tmp" "§__S.env"
}
if [ -f "§__S.cwd" ]; then
  __c=§(cat "§__S.cwd")
  cd "§__c" 2>/dev/null || echo "[sesi] folder §__c sudah tidak ada; tetap di folder awal" >&2
fi
if [ -f "§__S.env" ]; then . "§__S.env" 2>/dev/null; fi
trap __mythos_save EXIT
"""

    /**
     * Bungkus [userCmd] agar state sesi [id] dipulihkan sebelum dan disimpan sesudah (juga saat perintah memanggil `exit N`).
     * Kode keluar perintah tidak berubah. [dir] boleh berawalan "~/" (dibiarkan tanpa kutip agar diperluas shell).
     */
    fun wrap(userCmd: String, id: String, dir: String = DIR_TERMUX): String {
        require(ID_RE.matches(id)) { "session_id tidak valid" }
        val d = if (dir.startsWith("~/")) "~/" + SandboxCommand.shq(dir.removePrefix("~/")) else SandboxCommand.shq(dir)
        return TEMPLATE.replace("§", "$").replace("@@DIR@@", d).replace("@@ID@@", id).trimStart('\n') + userCmd
    }
}
