package dev.githubstore.domain

import org.junit.Assert.*
import org.junit.Test

class AssetPolicyTest {
    private fun asset(name: String, code: Long? = null, minimum: Int? = null, enabled: Boolean = true) =
        ReleaseAsset(name.hashCode().toLong(), name, "https://github.com/a/b/releases/download/v1/$name", 10, "a".repeat(64),
            versionCode = code, minSdk = minimum, enabled = enabled)
    private fun release(vararg assets: ReleaseAsset) = AppRelease(1, "v1", "v1", "", "", false, "https://github.com/a/b/releases/tag/v1", assets.toList())

    @Test fun `prefers first supported ABI before universal`() {
        val r = release(asset("app-universal.apk"), asset("app-armeabi-v7a.apk"), asset("app-arm64-v8a.apk"))
        assertEquals("arm64-v8a", AssetPolicy.recommended(r, listOf("arm64-v8a", "armeabi-v7a"), 33)?.abi)
    }
    @Test fun `ambiguous channels require explicit choice`() {
        assertNull(AssetPolicy.recommended(release(asset("app-foss-arm64.apk"), asset("app-full-arm64.apk")), listOf("arm64-v8a"), 33))
    }
    @Test fun `unsupported ABI is never recommended`() {
        assertNull(AssetPolicy.recommended(release(asset("app-x86_64.apk")), listOf("armeabi-v7a"), 33))
    }
    @Test fun `filters unsafe builds unsupported SDK and disabled assets`() {
        val r = release(asset("app-debug.apk"), asset("app-unsigned.apk"), asset("app-release.apk", minimum = 36), asset("app-universal.apk", enabled = false))
        assertTrue(AssetPolicy.candidates(r, listOf("arm64-v8a"), 33).isEmpty())
    }
    @Test fun `version display is never used for upgrade comparison`() {
        val current = InstalledApp("test", "2026.99", 10, setOf("cert"))
        assertTrue(AssetPolicy.upgradeAvailable(current, asset("v1.apk", 11), setOf("cert")))
        assertFalse(AssetPolicy.upgradeAvailable(current, asset("v9999.apk", 9), setOf("cert")))
        assertFalse(AssetPolicy.upgradeAvailable(current, asset("unknown.apk"), setOf("cert")))
    }
    @Test fun `signature mismatch prevents upgrade offer`() {
        assertFalse(AssetPolicy.upgradeAvailable(InstalledApp("test", "1", 1, setOf("store-cert")), asset("app.apk", 2), setOf("github-cert")))
    }
    @Test fun `same version is not an upgrade`() {
        assertFalse(AssetPolicy.upgradeAvailable(InstalledApp("test", "1", 5, setOf("cert")), asset("app.apk", 5), setOf("cert")))
    }
    @Test fun `digest must contain exactly 64 hex characters`() {
        assertTrue(AssetPolicy.validDigest("a9".repeat(32)))
        assertFalse(AssetPolicy.validDigest("sha256:" + "a".repeat(64)))
        assertFalse(AssetPolicy.validDigest("x".repeat(64)))
        assertFalse(AssetPolicy.validDigest(null))
    }
}
