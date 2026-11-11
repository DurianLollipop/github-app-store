package dev.githubstore.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.githubstore.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GitHubAuthState(val clientId: String = "", val login: String? = null, val expired: Boolean = false)

/** Credentials never enter repository records, APKs, logs, or cache JSON. */
class GitHubAuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("github-auth", Context.MODE_PRIVATE)
    private val alias = "orbit-github-device-token-v1"
    private val mutableState = MutableStateFlow(GitHubAuthState(prefs.getString("clientId", null) ?: BuildConfig.GITHUB_CLIENT_ID))
    val state = mutableState.asStateFlow()
    @Volatile private var access: String? = null
    @Volatile private var expires = 0L
    init {
        val saved = prefs.getString("grant", null)
        if (saved != null) runCatching {
            val parts = saved.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val j = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            access = j.getString("token"); expires = j.optLong("expires")
            mutableState.value = state.value.copy(login = j.getString("login"))
        }.onFailure { access = null; expires = 0; prefs.edit().remove("grant").apply(); mutableState.value = state.value.copy(expired = true) }
        token() // Expired grants do not silently fall back to an apparently connected account.
    }
    @Synchronized fun token(): String? {
        if (expires > 0 && System.currentTimeMillis() >= expires) invalidate(access.orEmpty())
        return access
    }
    @Synchronized fun configure(value: String) {
        val id = value.trim()
        require(id.isEmpty() || id.matches(Regex("[A-Za-z0-9_.-]{8,100}"))) { "Client ID 格式不正确" }
        if (id != state.value.clientId) disconnect()
        prefs.edit().putString("clientId", id).apply(); mutableState.value = state.value.copy(clientId = id)
    }
    suspend fun save(grant: DeviceGrant) = withContext(Dispatchers.IO) {
        synchronized(this@GitHubAuthStore) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            val json = JSONObject().put("token", grant.token).put("login", grant.login).put("expires", grant.expiresAt).toString()
            val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(json.toByteArray()), Base64.NO_WRAP)
            check(prefs.edit().putString("grant", encrypted).commit()) { "无法保存本机授权，请重试" }
            access = grant.token; expires = grant.expiresAt; mutableState.value = state.value.copy(login = grant.login, expired = false)
        }
    }
    @Synchronized fun disconnect() {
        access = null; expires = 0; prefs.edit().remove("grant").apply()
        mutableState.value = state.value.copy(login = null, expired = false)
    }
    @Synchronized fun invalidate(rejected: String) { if (access == rejected) { disconnect(); mutableState.value = state.value.copy(expired = true) } }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
}
