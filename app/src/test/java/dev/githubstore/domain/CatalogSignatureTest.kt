package dev.githubstore.domain

import dev.githubstore.data.CatalogSignature
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class CatalogSignatureTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
    private fun envelope(revision: Int): String {
        val payload = """{"schemaVersion":1,"revision":$revision,"apps":[]}""".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(payload); sign() }
        return JSONObject().put("payload", Base64.getEncoder().encodeToString(payload))
            .put("signature", Base64.getEncoder().encodeToString(signature)).toString()
    }
    @Test fun `valid publisher signature loads snapshot`() { assertEquals(5L, CatalogSignature.verify(envelope(5), publicKey).revision) }
    @Test fun `tampered content is rejected`() {
        val envelope = JSONObject(envelope(5)).put("payload", Base64.getEncoder().encodeToString("""{"schemaVersion":1,"revision":6,"apps":[]}""".toByteArray()))
        assertTrue(runCatching { CatalogSignature.verify(envelope.toString(), publicKey) }.isFailure)
    }
    @Test fun `validly signed rollback is rejected`() { assertTrue(runCatching { CatalogSignature.verify(envelope(4), publicKey, 5) }.isFailure) }
    @Test fun `same revision cannot change payload`() {
        assertTrue(runCatching { CatalogSignature.verify(envelope(5), publicKey, 5, "different-fingerprint") }.isFailure)
    }
    @Test fun `another publisher cannot replace catalog`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(runCatching { CatalogSignature.verify(envelope(5), Base64.getEncoder().encodeToString(other.public.encoded)) }.isFailure)
    }
}
