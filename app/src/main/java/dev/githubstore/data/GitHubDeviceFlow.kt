package dev.githubstore.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException

data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresAt: Long, val intervalMs: Long)
data class DeviceGrant(val token: String, val login: String, val expiresAt: Long)

/** Public-client device flow: client ID only, no app secret or shared personal token. */
class GitHubDeviceFlow(private val client: OkHttpClient, private val loginBase: HttpUrl = "https://github.com/".toHttpUrl(),
    private val apiBase: HttpUrl = "https://api.github.com/".toHttpUrl(), private val clock: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun begin(clientId: String): DeviceCode {
        require(clientId.matches(Regex("[A-Za-z0-9_.-]{8,100}"))) { "请输入有效的 GitHub Client ID；不要填写 Token 或 Client Secret" }
        val j = post("login/device/code", mapOf("client_id" to clientId))
        val uri = j.optString("verification_uri")
        check(uri == "https://github.com/login/device") { "授权地址不受支持，请在 GitHub 官方页面授权" }
        return DeviceCode(j.getString("device_code"), j.getString("user_code"), uri,
            clock() + j.getLong("expires_in").coerceIn(1, 1800) * 1000, j.optLong("interval", 5).coerceAtLeast(5) * 1000)
    }
    suspend fun finish(clientId: String, code: DeviceCode): DeviceGrant {
        var interval = code.intervalMs
        while (clock() < code.expiresAt) {
            wait(interval); currentCoroutineContext().ensureActive()
            if (clock() >= code.expiresAt) break
            val j = post("login/oauth/access_token", mapOf("client_id" to clientId, "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            when (j.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> { interval = maxOf(interval + 5000, j.optLong("interval") * 1000); continue }
                "access_denied" -> error("你已取消 GitHub 授权，可继续游客使用")
                "expired_token", "token_expired" -> error("验证码已过期，请重新开始授权")
                "device_flow_disabled" -> error("此 Client ID 尚未启用 Device flow，请在 GitHub 应用设置启用")
                "incorrect_client_credentials" -> error("Client ID 无效，请核对 GitHub 应用配置")
                "" -> Unit
                else -> error("GitHub 授权未完成，请重试或检查应用配置")
            }
            val access = j.optString("access_token").takeIf { it.isNotBlank() } ?: error("GitHub 没有返回有效授权")
            // No repo/write/admin scopes are requested. Do not accept a previously over-privileged OAuth grant.
            val scopes = j.optString("scope").split(',', ' ').filter { it.isNotBlank() }
            check(scopes.all { it in setOf("read:user", "user:email") }) { "此应用已有超出公开读取的授权，请在 GitHub 撤销旧授权后重试" }
            val request = Request.Builder().url(apiBase.newBuilder().addPathSegment("user").build())
                .header("Authorization", "Bearer $access").header("Accept", "application/vnd.github+json").header("User-Agent", "OrbitStore/0.3").build()
            val user = json(request)
            val expires = j.optLong("expires_in", 0).takeIf { it > 0 }?.let { clock() + it * 1000 } ?: 0L
            return DeviceGrant(access, user.getString("login"), expires)
        }
        error("验证码已过期，请重新开始授权")
    }
    private suspend fun post(path: String, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        return json(Request.Builder().url(loginBase.newBuilder().addPathSegments(path).build()).post(form)
            .header("Accept", "application/json").header("User-Agent", "OrbitStore/0.3").build())
    }
    private suspend fun json(request: Request): JSONObject = withContext(Dispatchers.IO) {
        withGitHubResponse(client.newCall(request)) { res ->
            if (!res.isSuccessful) throw IOException(if (res.code in setOf(403, 429)) "GitHub 授权暂时受限，请稍后重试" else "GitHub 授权返回 ${res.code}")
            val source = res.body?.source() ?: error("GitHub 授权返回为空")
            source.request(64 * 1024L + 1); check(source.buffer.size <= 64 * 1024) { "GitHub 授权响应过大" }
            JSONObject(source.readUtf8())
        }
    }
}
