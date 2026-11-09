package dev.githubstore.data

import dev.githubstore.domain.StoreApp
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class VerifiedCatalog(val revision: Long, val fingerprint: String, val apps: List<StoreApp>)

/** The signature covers the exact UTF-8 payload bytes, not re-serialized JSON. */
object CatalogSignature {
    fun verify(envelope: String, publicKey: String, minimumRevision: Long = 0, previousFingerprint: String? = null): VerifiedCatalog {
        require(envelope.length <= 4 * 1024 * 1024) { "目录快照过大" }
        val wrapper = JSONObject(envelope)
        val payload = Base64.getDecoder().decode(wrapper.getString("payload"))
        val signature = Base64.getDecoder().decode(wrapper.getString("signature"))
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        check(Signature.getInstance("SHA256withRSA").run { initVerify(key); update(payload); verify(signature) }) { "目录签名验证失败，已保留上次有效内容" }
        val body = JSONObject(String(payload, Charsets.UTF_8))
        check(body.getInt("schemaVersion") == 1) { "目录需要更新版本的客户端" }
        val revision = body.getLong("revision")
        check(revision >= minimumRevision && revision > 0) { "目录版本倒退，已拒绝加载" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        if (revision == minimumRevision && previousFingerprint != null) check(fingerprint == previousFingerprint) { "同一目录版本的内容发生变化" }
        val apps = body.getJSONArray("apps").objects().map(JsonCodec::readApp)
        check(apps.map { it.id }.distinct().size == apps.size) { "目录包含重复应用" }
        check(apps.none { it.id == "orbit-store" }) { "远程目录不能替换商店自身的发布身份" }
        for (app in apps) {
            check(Regex("[a-z0-9-]+").matches(app.id) && Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(app.repository)) { "目录包含无效来源" }
            check(app.certificateSha256.all { Regex("[a-f0-9]{64}").matches(it) }) { "目录证书指纹无效" }
        }
        return VerifiedCatalog(revision, fingerprint, apps)
    }
}
