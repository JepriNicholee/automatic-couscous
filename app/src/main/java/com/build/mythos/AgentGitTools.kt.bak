package com.build.mythos

import android.util.Base64
import org.json.JSONObject

/** Source-reader and Git destination tools. All GitHub credentials stay inside Android Keystore-backed prefs. */
internal fun MainActivity.agentGithubToken(): String {
    val stored = prefs.getString(GitAgentPolicy.TOKEN_PREF, "").orEmpty()
    val decrypted = when (val result = Secret.decrypt(stored)) {
        is SecretDecryptResult.Success -> result.value
        else -> ""
    }
    if (decrypted.isNotBlank()) return decrypted
    return try {
        val res = termux("gh auth token", limit = 200, timeoutSec = 5, useWorkdir = false, killable = false)
        if (res.startsWith("exit=0\n")) {
            res.removePrefix("exit=0\n").trim()
        } else {
            ""
        }
    } catch (_: Exception) {
        ""
    }
}

internal fun MainActivity.agentDestination(): Pair<RepoRef, String>? {
    val ref = parseRepoLink(prefs.getString(GitAgentPolicy.REPO_PREF, "").orEmpty()) ?: return null
    val branch = prefs.getString(GitAgentPolicy.BRANCH_PREF, GitAgentPolicy.DEFAULT_AGENT_BRANCH).orEmpty().trim()
    if (branch.isBlank()) return ref to GitAgentPolicy.DEFAULT_AGENT_BRANCH
    return ref to branch
}

internal fun MainActivity.agentGitCheck(): String = try {
    val token = agentGithubToken()
    if (token.isBlank()) return "exit=1 Tidak ada token GitHub. Atur di Settings > Git Agent > Token."
    val resp = termux(
        "curl -s -H \"Authorization: Bearer $token\" -H \"Accept: application/vnd.github+json\" \"https://api.github.com/user\"",
        limit = 1000, timeoutSec = 8, useWorkdir = false, killable = false
    )
    if (!resp.startsWith("exit=0\n")) return "exit=1 Curl gagal: ${resp.take(200)}"
    val body = resp.removePrefix("exit=0\n").trim()
    val login = JSONObject(body).optString("login", "?")
    val repoPref = prefs.getString(GitAgentPolicy.REPO_PREF, "").orEmpty()
    val branchPref = prefs.getString(GitAgentPolicy.BRANCH_PREF, GitAgentPolicy.DEFAULT_AGENT_BRANCH).orEmpty()
    "exit=0 GitHub user: $login\nRepo tujuan: ${if (repoPref.isBlank()) "belum diatur" else repoPref}\nBranch: ${if (branchPref.isBlank()) GitAgentPolicy.DEFAULT_AGENT_BRANCH else branchPref}"
} catch (e: Exception) {
    "exit=1 Cek gagal: ${e.message?.take(200)}"
}

internal fun MainActivity.agentGitPush(
    workspaceRoot: String,
    fileCandidates: List<Pair<String, Long>>,
    message: String,
    warning: String,
    force: Boolean,
    routine: Boolean
): String {
    val card = StepCard(Risk.WRITE, "Mengirim proyek ke GitHub", warning, routine = routine)
    card.show()
    val token = agentGithubToken()
    val target = agentDestination() ?: run {
        card.finish(false, "tujuan tidak siap", "Repository/branch belum diatur di Settings > Git Agent.", StepState.FAIL)
        return "exit=1 Tujuan Git belum diatur."
    }
    val (ref, branch) = target
    if (!force) {
        val (approved, lastApprovalMode, lastDenyUnanswered) = requestApproval(
            Risk.WRITE,
            "agent_git_push ${ref.owner}/${ref.repo}@$branch",
            message,
            allowedModes = setOf(ApprovalMode.ONCE, ApprovalMode.SESSION)
        )
        if (!approved) {
            card.finish(false, if (lastDenyUnanswered) "tidak dijawab" else "ditolak", state = StepState.DENIED)
            return deniedResult()
        }
    }
    return try {
        val blobs = ArrayList<Pair<String, String>>()
        for ((index, item) in fileCandidates.withIndex()) {
            val (path, size) = item
            val filePath = "$workspaceRoot/$path"
            val readCommand = "root=\$(realpath -e -- ${sh(workspaceRoot())}) || exit 91; " +
                "file=\$(realpath -e -- ${sh(filePath)}) || exit 92; " +
                "case \"\$file\" in \"\$root/\"*) ;; *) echo MYTHOS_GIT_PATH_BLOCKED; exit 93;; esac; " +
                "base64 -w 0 -- \"\$file\""
            val raw = termux(readCommand, limit = 180_000, timeoutSec = 20, useWorkdir = false, killable = false)
            if (!raw.startsWith("exit=0\n")) throw IllegalStateException("Gagal membaca file $path dari workspace atau path hasil resolve keluar dari workspace.")
            val encoded = raw.removePrefix("exit=0\n").trim()
            val bytes = try { Base64.decode(encoded, Base64.NO_WRAP) } catch (_: Exception) { throw IllegalStateException("Base64 file $path tidak valid.") }
            if (bytes.size.toLong() != size) throw IllegalStateException("Ukuran file $path berubah saat proses push; proses dibatalkan.")
            if (GitAgentPolicy.containsSensitiveMaterial(bytes)) {
                skipped += "$path (indikasi rahasia/kredensial)"
                continue
            }
            blobs += path to Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (index == fileCandidates.lastIndex || index % 10 == 9) {
                card.liveOutput("Mengumpulkan file workspace: ${index + 1}/${fileCandidates.size}")
            }
        }
        if (blobs.isEmpty()) throw IllegalStateException("Tidak ada file yang dapat dikirim setelah pemeriksaan rahasia. Periksa file yang dipilih dan kebijakan keamanan.")
        val sha = GitHubApi.commitFiles(ref, branch, token, message, blobs)
        val report = "Push berhasil.\nRepository: ${ref.owner}/${ref.repo}\nBranch: $branch\nCommit: $sha\nFile dikirim: ${blobs.size}\nFile dilewati: ${skipped.size}\nTidak ada file lokal yang dihapus. File remote yang tidak ada di lokal dipertahankan."
        card.finish(true, "push berhasil", report)
        logApproved("git", "agent_git_push ${ref.owner}/${ref.repo}@$branch", message, Risk.WRITE, lastApprovalMode, true, "commit ${sha.take(12)}", network = true)
        report
    } catch (e: Exception) {
        val msg = githubFriendly(e)
        card.finish(false, "push gagal", msg, StepState.FAIL)
        "exit=1 Push gagal: $msg"
    }
}

private fun githubFriendly(e: Exception): String = when (e) {
    is GitHubApi.ApiException -> e.message ?: "GitHub API gagal."
    else -> e.message?.take(300) ?: e.javaClass.simpleName
}