package dev.githubstore

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.githubstore.domain.*
import dev.githubstore.install.InstallManager
import dev.githubstore.download.sha256
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

/** Opt-in: two tiny APKs built from this repository, never an arbitrary upstream application. */
@RunWith(AndroidJUnit4::class)
class FixtureInstallTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun installsUnknownRepositoryPackageThenUpgradesWithSameSigner() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orbitFixture") == "true")
        val store = rule.activity.application as StoreApplication
        store.agreement.accept()
        val github = store.github
        store.github = object : dev.githubstore.data.GitHubDiscovery(store.client) {
            override suspend fun asset(repository: String, id: Long): ReleaseAsset = throw java.io.IOException("local fixture")
        }
        val app = StoreApp("fixture-outside-catalog", "Orbit 安装测试夹具", "fixture/orbit", "", "测试", "", "", "Apache-2.0", 0xFF168A7F, "F")
        store.library.remember(app)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        for (version in 1..2) {
            resumeOrbit(device, store.packageName)
            val input = File(store.getExternalFilesDir(null), "fixtures/v$version.apk")
            assertTrue("缺少测试 APK，使用 scripts/test-fixture-install.sh", input.isFile)
            val asset = ReleaseAsset(version.toLong(), "fixture.apk", "https://github.com/fixture/orbit/releases/download/v$version/fixture.apk", input.length(), null)
            val release = AppRelease(version.toLong(), "v$version", "v$version", "", "", false, "", listOf(asset))
            val task = store.tasks.enqueue(app, release, asset)
            input.copyTo(store.tasks.file(task.id), overwrite = true)
            val info = store.inspector.verify(app, asset, store.tasks.file(task.id))
            assertEquals("dev.orbit.fixture", info.packageName); assertEquals(version.toLong(), info.versionCode)
            store.tasks.update(task.id) { it.copy(status = TaskStatus.READY, packageName = info.packageName,
                inspected = info, localSha256 = sha256(store.tasks.file(task.id)), sourceConfirmed = true) }
            runBlocking { InstallManager(store).install(store.tasks.find(task.id)!!) }
            val confirm = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)^(install|update|安装|更新)$"))), 20_000)
            assertNotNull("系统安装确认未出现", confirm)
            confirm!!.click()
            rule.waitUntil(30_000) { store.tasks.find(task.id)?.status in setOf(TaskStatus.INSTALLED, TaskStatus.FAILED) }
            val result = store.tasks.find(task.id)!!
            assertEquals(result.error, TaskStatus.INSTALLED, result.status)
            assertEquals(version.toLong(), store.inspector.installed(info.packageName)?.versionCode)
            assertEquals(version.toLong(), store.library.find(app.id)!!.bindings.single().identity.versionCode)
            if (version == 2) {
                val older = File(store.getExternalFilesDir(null), "fixtures/v1.apk")
                assertTrue("覆盖降级应被拒绝", runCatching { store.inspector.verify(app, asset.copy(size = older.length()), older) }.isFailure)
            }
        }
        resumeOrbit(device, store.packageName)
        store.github = github
    }
    private fun resumeOrbit(device: UiDevice, packageName: String) {
        repeat(3) {
            if (device.currentPackageName == packageName) return
            val done = device.findObject(By.text(java.util.regex.Pattern.compile("(?i)^(done|完成)$")))
            if (done != null) done.click() else device.pressBack()
            device.wait(Until.hasObject(By.pkg(packageName)), 3000)
        }
    }
}
