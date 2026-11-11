package dev.githubstore.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.githubstore.domain.*
import dev.githubstore.download.sha256
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class PackageInspector(private val context: Context) {
    @Suppress("DEPRECATION")
    private val flags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    fun installed(packageName: String): InstalledApp? = try {
        val info = context.packageManager.getPackageInfo(packageName, flags)
        InstalledApp(packageName, info.versionName ?: "未知版本", code(info), certificates(info))
    } catch (_: PackageManager.NameNotFoundException) { null }

    @Suppress("DEPRECATION")
    fun verify(app: StoreApp, asset: ReleaseAsset, file: File): InstalledApp {
        check(file.isFile && file.length() == asset.size) { "安装包不存在或大小已变化，请重新下载" }
        if (asset.sha256 != null) check(sha256(file).equals(asset.sha256, true)) { "安装包摘要不一致，请重新下载" }
        // Android 13's archive parser only collects certificates when GET_SIGNATURES is also set.
        // Still read SigningInfo.apkContentsSigners on API 28+ so rotation never falls back to an old signer.
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags or PackageManager.GET_SIGNATURES)
            ?: error("无法解析 APK，暂不支持分包格式")
        if (app.packageName.isNotBlank()) check(info.packageName == app.packageName) { "安装包包名与选择的应用不符" }
        val signatures = certificates(info)
        check(signatures.isNotEmpty()) { "安装包没有有效签名" }
        if (app.certificateSha256.isNotEmpty()) check(signatures == app.certificateSha256) { "发布签名与已记录身份不同，已阻止安装" }
        check((info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "此版本需要更新的 Android 系统" }
        asset.versionCode?.let { check(code(info) == it) { "内部版本号与审核记录不一致" } }
        val abis = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
            .map { it.split('/')[1] }.toSet() }
        check(abis.isEmpty() || abis.any { it in Build.SUPPORTED_ABIS }) { "此安装包不支持当前设备的 CPU 架构" }
        installed(info.packageName)?.let { current ->
            check(code(info) >= current.versionCode) { "系统不支持直接覆盖降级。卸载可能删除数据，请在系统设置中自行处理。" }
            check(current.certificates == signatures) { "已安装应用来自不同签名渠道，无法覆盖更新" }
        }
        return InstalledApp(info.packageName, info.versionName.orEmpty(), code(info), signatures)
    }

    fun launch(packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return true
    }
}
