package com.build.mythos

class AgentGuard {
    fun checkPermissions(
        code: AgentRequest,
        context: android.content.Context,
    ): AgentResult {
        // Hukum konfirmasi penggunaan izin otomatis untuk menetapkan batas kode yang bersifat otomatis.
        val permissions = listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        )

        for (perm in permissions) {
            if (context.checkCallingPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return AgentResult.Denied(perm)
            }
        }
        return AgentResult.Allowed
    }
}

data class AgentRequest(
    val requestCode: Int,
    val serviceId: String,
)

sealed interface AgentResult {
    data class Denied(val permission: String): AgentResult
    object Allowed: AgentResult
}
