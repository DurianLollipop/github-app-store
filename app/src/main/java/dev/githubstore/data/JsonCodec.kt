package dev.githubstore.data

import dev.githubstore.domain.*
import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
private fun JSONObject.optionalLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

object JsonCodec {
    fun readAsset(j: JSONObject) = ReleaseAsset(
        id = j.getLong("id"), name = j.getString("name"), url = j.getString("url"), size = j.getLong("size"),
        sha256 = j.optString("sha256").takeIf { AssetPolicy.validDigest(it) },
        abi = j.optString("abi", inferAbi(j.getString("name"))), versionCode = j.optionalLong("versionCode"),
        minSdk = j.optionalLong("minSdk")?.toInt(), enabled = j.optBoolean("enabled", true),
    )
    fun asset(a: ReleaseAsset) = JSONObject().put("id", a.id).put("name", a.name).put("url", a.url)
        .put("size", a.size).put("sha256", a.sha256).put("abi", a.abi).put("versionCode", a.versionCode)
        .put("minSdk", a.minSdk).put("enabled", a.enabled)

    fun readRelease(j: JSONObject) = AppRelease(j.getLong("id"), j.getString("tag"), j.optString("name", j.getString("tag")),
        j.optString("publishedAt"), j.optString("notes"), j.optBoolean("prerelease"), j.getString("url"),
        j.optJSONArray("assets")?.objects()?.map(::readAsset).orEmpty())
    fun release(r: AppRelease) = JSONObject().put("id", r.id).put("tag", r.tag).put("name", r.name)
        .put("publishedAt", r.publishedAt).put("notes", r.notes).put("prerelease", r.prerelease).put("url", r.url)
        .put("assets", JSONArray(r.assets.map(::asset)))

    fun readApp(j: JSONObject) = StoreApp(
        id = j.getString("id"), name = j.getString("name"), repository = j.getString("repository"),
        packageName = j.getString("packageName"), category = j.getString("category"), tagline = j.getString("tagline"),
        description = j.getString("description"), license = j.getString("license"),
        color = j.getString("color").removePrefix("#").toLong(16) or 0xFF000000,
        symbol = j.getString("symbol"), iconUrl = j.optString("iconUrl"),
        screenshots = j.optJSONArray("screenshots")?.strings().orEmpty(), mediaSource = j.optString("mediaSource"),
        tvStatus = j.optString("tvStatus", "尚未进行 TV 真机验证"),
        certificateSha256 = j.optJSONArray("certificateSha256")?.strings()?.map { it.lowercase() }?.toSet().orEmpty(),
        releases = j.optJSONArray("releases")?.objects()?.map(::readRelease).orEmpty(), checkedAt = j.optLong("checkedAt"),
    )

    fun task(t: DownloadTask) = JSONObject().put("id", t.id).put("appId", t.appId).put("appName", t.appName)
        .put("packageName", t.packageName).put("releaseTag", t.releaseTag).put("asset", asset(t.asset))
        .put("status", t.status.name).put("downloaded", t.downloaded).put("error", t.error)
        .put("sessionId", t.sessionId).put("createdAt", t.createdAt)
    fun readTask(j: JSONObject) = DownloadTask(j.getString("id"), j.getString("appId"), j.getString("appName"),
        j.getString("packageName"), j.getString("releaseTag"), readAsset(j.getJSONObject("asset")),
        TaskStatus.valueOf(j.getString("status")), j.optLong("downloaded"), j.optString("error").takeIf(String::isNotBlank),
        j.optionalLong("sessionId")?.toInt(), j.getLong("createdAt"))
}
