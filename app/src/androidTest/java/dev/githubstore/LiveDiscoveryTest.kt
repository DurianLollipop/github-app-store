package dev.githubstore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit opt-in real network diagnostic; never part of the deterministic suite. */
@RunWith(AndroidJUnit4::class)
class LiveDiscoveryTest {
    @Test fun comparesTrendingTransportsOnTheActualAndroidRuntime() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orbitNetwork") == "true")
        val store = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StoreApplication
        val results = JSONArray().put(JSONObject().put("githubDNS", JSONArray(java.net.InetAddress.getAllByName("github.com").map { it.hostAddress })))
        for ((name, client) in listOf("default" to store.client, "http1" to store.client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build(), "tls12" to store.client.newBuilder().connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build())).build())) {
            try {
                client.newCall(Request.Builder().url("https://github.com/trending?since=weekly").header("User-Agent", "OrbitStore/0.3").build()).execute().use { response ->
                    val body = response.body!!.string()
                    results.put(JSONObject().put("transport", name).put("status", response.code).put("protocol", response.protocol.toString())
                        .put("bytes", body.length).put("entries", org.jsoup.Jsoup.parse(body).select("article.Box-row").size))
                }
            } catch (e: Exception) { results.put(JSONObject().put("transport", name).put("error", e.stackTraceToString().take(8000))) }
        }
        try { store.client.newCall(Request.Builder().url("https://api.github.com/rate_limit").header("User-Agent", "OrbitStore/0.3").build()).execute().use { response ->
            results.put(JSONObject().put("apiStatus", response.code).put("apiBodyBytes", response.body!!.string().length))
        } } catch (e: Exception) { results.put(JSONObject().put("apiError", e.toString())) }
        File(store.getExternalFilesDir(null), "network-diagnostic.json").writeText(results.toString())
    }
}
