package dev.githubstore

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.githubstore.domain.TaskStatus
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in only: downloads a real upstream APK and installs it on a local emulator. */
@RunWith(AndroidJUnit4::class)
class LiveDownloadTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun officialTVBroDownloadAndSystemInstall() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orbitLive") == "true")
        val store = rule.activity.application as StoreApplication
        rule.onNodeWithText("分类").performClick()
        rule.onNode(hasText("TV Bro") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText("下载 · 6.5 MB").performClick()
        val taskId = store.tasks.tasks.value.last { it.appId == "tv-bro" }.id
        rule.waitUntil(120_000) { store.tasks.find(taskId)?.status in setOf(TaskStatus.READY, TaskStatus.FAILED) }
        val task = store.tasks.find(taskId)!!
        assertEquals(task.error, TaskStatus.READY, task.status)
        val app = store.catalog.find("tv-bro")!!
        val packageInfo = rule.activity.packageManager.getPackageArchiveInfo(store.tasks.file(task.id).absolutePath,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES or android.content.pm.PackageManager.GET_SIGNATURES)!!
        val actualCertificates = packageInfo.signingInfo!!.apkContentsSigners.map { signature ->
            java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        assertEquals("APK 当前签名应与官方文件检查结果一致", app.certificateSha256, actualCertificates)
        val inspected = store.inspector.verify(app, task.asset, store.tasks.file(task.id))
        assertEquals(69L, inspected.versionCode)
        rule.onNodeWithText("安装应用").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val install = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)^(install|update|安装|更新)$"))), 25_000)
        assertNotNull("系统安装确认没有出现；请检查模拟器的来源安装授权", install)
        val screenshots = File(rule.activity.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
        device.takeScreenshot(File(screenshots, "system-install.png"))
        requireNotNull(install).click()
        rule.waitUntil(60_000) { store.tasks.find(task.id)?.status in setOf(TaskStatus.INSTALLED, TaskStatus.FAILED) }
        val result = store.tasks.find(task.id)!!
        assertEquals(result.error, TaskStatus.INSTALLED, result.status)
        assertEquals(69L, store.inspector.installed(app.packageName)?.versionCode)
    }
}
