package com.build.mythos

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** GitHub REST/Git Database client. The token stays in Android memory and is never sent to Termux or the model. */
internal object GitHubApi {
    private const val API = "https://api.github.com"
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    data class TreeFile(val path: String, val size: Long, val sha: String)
    data class TreeResult(val files: List<TreeFile>, val truncated: Boolean, val commitSha: String?)
    data class RepoInfo(val fullName: String, val defaultBranch: String, val isPrivate: Boolean)

    class ApiException(val status: Int, message: String) : Exception(message)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun repoPath(ref: RepoRef) = "/repos/${enc(ref.owner)}/${enc(ref.repo)}"
    private fun branchPath(branch: String) = branch.split('/').joinToString("/") { enc(it) }

    private fun request(method: String, path: String, token: String, body: JSONObject? = null): JSONObject {
        val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 25_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MYTHOS-Android-SourceReader")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            if (code !in 200..299) {
                val message = try { JSONObject(text).optString("message") } catch (_: Exception) { "" }
                val reason = when (code) {
                    401 -> "Token GitHub ditolak atau kedaluwarsa."
                    403 -> if (text.contains("rate limit", true)) "Batas permintaan GitHub tercapai. Coba lagi nanti." else "Token tidak memiliki izin yang diperlukan."
                    404 -> "Repository, branch, atau file tidak ditemukan / tidak dapat diakses."
                    409, 422 -> "GitHub menolak perubahan karena konflik atau validasi branch."
                    else -> "GitHub API menjawab HTTP $code."
                }
                throw ApiException(code, if (message.isNotBlank() && code !in setOf(401, 403, 404)) "$reason ($message)" else reason)
            }
            if (text.isBlank()) return JSONObject()
            return try { JSONObject(text) } catch (e: Exception) { throw ApiException(code, "Respons GitHub bukan JSON yang valid.") }
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= max) {
            val n = input.read(buf, 0, minOf(buf.size, max + 1 - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw ApiException(502, "Respons GitHub terlalu besar untuk dibaca sekaligus.")
        }
        return out.toByteArray()
    }

    fun repoInfo(ref: RepoRef, token: String): RepoInfo {
        val o = request("GET", repoPath(ref), token)
        return RepoInfo(o.optString("full_name", "${ref.owner}/${ref.repo}"), o.optString("default_branch", "main"), o.optBoolean("private"))
    }

    fun tree(ref: RepoRef, branch: String, token: String): TreeResult {
        val branchInfo = request("GET", "${repoPath(ref)}/branches/${branchPath(branch)}", token)
        val commit = branchInfo.getJSONObject("commit")
        val commitSha = commit.getString("sha")
        val treeSha = commit.getJSONObject("commit").getJSONObject("tree").getString("sha")
        val o = request("GET", "${repoPath(ref)}/git/trees/$treeSha?recursive=1", token)
        val arr = o.optJSONArray("tree") ?: JSONArray()
        val files = ArrayList<TreeFile>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("type") != "blob") continue
            files += TreeFile(item.optString("path"), item.optLong("size", 0), item.optString("sha"))
        }
        return TreeResult(files, o.optBoolean("truncated"), commitSha)
    }

    fun readFile(ref: RepoRef, branch: String, path: String, token: String): String {
        val encodedPath = path.split('/').joinToString("/") { enc(it) }
        val o = request("GET", "${repoPath(ref)}/contents/$encodedPath?ref=${enc(branch)}", token)
        if (o.optString("type") != "file") throw ApiException(422, "Path bukan file biasa.")
        if (o.optLong("size", 0) > 128 * 1024) throw ApiException(413, "File lebih besar dari batas pembacaan 128 KB.")
        if (o.optString("encoding") != "base64") throw ApiException(422, "GitHub tidak mengembalikan file dalam encoding base64.")
        val encoded = o.optString("content").replace("\n", "").replace("\r", "")
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { throw ApiException(502, "Konten file GitHub gagal didekode.") }
        if (bytes.any { it == 0.toByte() }) throw ApiException(415, "File biner tidak ditampilkan sebagai teks.")
        return String(bytes, Charsets.UTF_8)
    }

    /** Create an atomic commit with multiple blobs; no remote file is deleted. */
    fun commitFiles(ref: RepoRef, branch: String, token: String, message: String, files: List<Pair<String, String>>): String {
        require(files.isNotEmpty()) { "Tidak ada file yang aman untuk dikirim." }
        val info = repoInfo(ref, token)
        val (parentSha, existed) = try {
            val r = request("GET", "${repoPath(ref)}/git/ref/heads/${branchPath(branch)}", token)
            r.getJSONObject("object").getString("sha") to true
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            val defaultRef = try {
                request("GET", "${repoPath(ref)}/git/ref/heads/${branchPath(info.defaultBranch)}", token)
                    .getJSONObject("object").getString("sha")
            } catch (defaultError: ApiException) {
                if (defaultError.status != 404) throw defaultError
                throw ApiException(409, "Repository tujuan masih kosong. Buat satu commit awal di GitHub (misalnya README.md), lalu ulangi pemeriksaan/push.")
            }
            defaultRef to false
        }
        val parentTreeSha = request("GET", "${repoPath(ref)}/git/commits/$parentSha", token)
            .getJSONObject("tree").getString("sha")

        val tree = JSONArray()
        for ((path, contentBase64) in files) {
            val blob = request(
                "POST", "${repoPath(ref)}/git/blobs", token,
                JSONObject().put("content", contentBase64).put("encoding", "base64")
            )
            tree.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", blob.getString("sha")))
        }
        val treeRequest = JSONObject().put("tree", tree).put("base_tree", parentTreeSha)
        val treeObj = request("POST", "${repoPath(ref)}/git/trees", token, treeRequest)
        val commitBody = JSONObject().put("message", message.take(120).ifBlank { "Pembaruan dari MYTHOS" })
            .put("tree", treeObj.getString("sha"))
        commitBody.put("parents", JSONArray().put(parentSha))
        val commit = request("POST", "${repoPath(ref)}/git/commits", token, commitBody)
        val sha = commit.getString("sha")
        if (existed) {
            request("PATCH", "${repoPath(ref)}/git/refs/heads/${branchPath(branch)}", token, JSONObject().put("sha", sha).put("force", false))
        } else {
            request("POST", "${repoPath(ref)}/git/refs", token, JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
        }
        return sha
    }
}

internal object GitAgentPolicy {
    const val SOURCE_OWNER = "JepriNicholee"
    const val SOURCE_REPO = "automatic-couscous"
    const val SOURCE_BRANCH = "main"
    const val DEFAULT_AGENT_BRANCH = "agent-work"
    const val TOKEN_PREF = "agent_github_token"
    const val REPO_PREF = "agent_repo_url"
    const val BRANCH_PREF = "agent_repo_branch"
    const val MAX_FILES = 120
    const val MAX_FILE_BYTES = 128 * 1024L

    fun isSource(ref: RepoRef): Boolean =
        ref.owner.equals(SOURCE_OWNER, true) && ref.repo.equals(SOURCE_REPO, true)

    fun validBranch(raw: String): Boolean {
        val b = raw.trim()
        if (b.isEmpty() || b == "@" || b.length > 180 || b.startsWith("/") || b.endsWith("/") ||
            b.contains("..") || b.contains("//") || b.contains("@{") ||
            b.any { it.isWhitespace() || Character.isISOControl(it) } ||
            b.any { it in charArrayOf('~', '^', ':', '?', '*', '[', '\\') }) return false
        return b.split('/').all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith('.') && !it.endsWith(".lock") }
    }

    fun safeSourcePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains('\n') || path.contains('\r') || path.contains('\t')) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        val name = parts.last().lowercase()
        if (parts.any { it.equals(".git", ignoreCase = true) }) return false
        if (name.startsWith(".env") || name in setOf("local.properties", "keystore.properties", ".npmrc", ".pypirc") ||
            listOf(".jks", ".keystore", ".pem", ".key", ".p12", ".pfx", ".gpg", ".asc").any { name.endsWith(it) }) return false
        return path.length <= 240 && path.all { it.code in 32..126 }
    }

    fun safeRepoFile(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains('\n') || path.contains('\r') || path.contains('\t')) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        val lower = path.lowercase()
        val name = parts.last().lowercase()
        val excludedDirs = setOf(".git", "build", ".gradle", "node_modules", ".idea", ".kotlin", ".venv", "venv", "attachments", "artifacts", "backups", "logs", "memory", "secrets", ".secrets", "credentials", "certs", ".ssh", ".gnupg", ".aws", ".azure", ".kube")
        if (parts.any { it.lowercase() in excludedDirs }) return false
        if (name.startsWith(".env") || name == "local.properties" || name == "keystore.properties" || name == ".npmrc") return false
        if (name in setOf("id_rsa", "id_ed25519", "id_ecdsa", "id_dsa", "authorized_keys", "known_hosts", "credentials", "credentials.json", "service_account.json", "token", "access_token", "client_secret")) return false
        if (listOf(".jks", ".keystore", ".pem", ".p12", ".pfx", ".apk", ".aab", ".zip", ".tar", ".gz", ".db", ".sqlite", ".log").any { lower.endsWith(it) }) return false
        return path.length <= 240 && path.all { it.code in 32..126 }
    }

    /** Conservative last-line check for credentials embedded in otherwise ordinary source/config files. */
    fun containsSensitiveMaterial(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false // Binary assets are separately bounded and path-filtered.
        val text = String(bytes, Charsets.UTF_8)
        val patterns = listOf(
            Regex("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
            Regex("(?i)\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
            Regex("(?i)\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
            Regex("\\bAKIA[0-9A-Z]{16}\\b"),
            Regex("\\bAIza[0-9A-Za-z_-]{30,}\\b"),
            Regex("(?i)\\bsk-ant-[A-Za-z0-9_-]{20,}\\b"),
            Regex("(?i)\\bsk-[A-Za-z0-9_-]{24,}\\b"),
            Regex("(?i)\\bhf_[A-Za-z0-9]{20,}\\b"),
            Regex("(?i)\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
            Regex("(?i)\\bxox[baprs]-[A-Za-z0-9-]{20,}\\b"),
            Regex("(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|private[_-]?key|password)\\s*[:=]\\s*[\"']?[^\"'\\s]{12,}")
        )
        return patterns.any { it.containsMatchIn(text) }
    }
}
