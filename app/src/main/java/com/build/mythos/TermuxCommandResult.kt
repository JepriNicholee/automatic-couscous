package com.build.mythos

/** Structured response from one Termux RUN_COMMAND request. Output streams remain separate. */
internal data class TermuxCommandResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val serviceError: String? = null,
    val transportError: String? = null,
    val timedOut: Boolean = false
) {
    val succeeded: Boolean
        get() = !timedOut && transportError == null && exitCode == 0

    fun toLegacyString(limit: Int, timeoutSec: Long): String {
        if (timedOut) return timeoutResult(timeoutSec)
        transportError?.let {
            return if (it == "Tidak ada hasil.") it else "Gagal memanggil Termux: $it"
        }

        val body = stdout + stderr
        val shown = if (body.length > limit) {
            val head = limit / 4
            "…(dipotong, ${body.length - limit} karakter di tengah dibuang)\n" + body.take(head) +
                "\n…\n" + body.takeLast(limit - head)
        } else body
        return (serviceError?.takeIf { it.isNotEmpty() }?.let { "ERR: $it\n" } ?: "") +
            "exit=${exitCode ?: -1}\n" + shown
    }
}
