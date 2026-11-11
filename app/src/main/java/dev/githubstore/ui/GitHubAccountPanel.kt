package dev.githubstore.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.githubstore.data.*
import kotlinx.coroutines.*

@Composable internal fun GitHubAccountPanel(auth: GitHubAuthStore, flow: GitHubDeviceFlow, network: GitHubNetworkState, onExternal: (String) -> Unit) {
    val account by auth.state.collectAsState()
    var configure by remember { mutableStateOf(false) }
    var signing by remember { mutableStateOf(false) }
    var clientId by remember(account.clientId) { mutableStateOf(account.clientId) }
    var configError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GitHub 访问", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(account.login?.let { "已授权：$it · 凭据仅加密保存在本机" } ?: if (account.expired) "授权已失效，请重新授权；仍可游客使用" else "游客模式 · 可选授权以提高 API 可用额度", color = Muted, fontSize = 12.sp)
        if (network.authenticated == (account.login != null)) {
            val core = network.core; val search = network.search
            Text("普通 API：${core?.remaining?.toString() ?: "待获取"} · 搜索：${search?.remaining?.toString() ?: "待获取"}（剩余额度）", color = Muted, fontSize = 11.sp, modifier = Modifier.testTag("github-quota"))
            val reset = listOfNotNull(core?.takeIf { it.remaining == 0 }?.resetAt, search?.takeIf { it.remaining == 0 }?.resetAt,
                network.blockedUntil.takeIf { it > 0 }).filter { it > System.currentTimeMillis() }.minOrNull()
            reset?.let { Text("受限请求预计 ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(it))} 后恢复；缓存仍可浏览", color = Muted, fontSize = 11.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (account.login == null) TvButton(if (account.clientId.isBlank()) "配置 GitHub 授权" else "使用验证码授权", { if (account.clientId.isBlank()) configure = true else signing = true }, Modifier.testTag("github-connect"))
            else TvButton("退出本机授权", auth::disconnect, Modifier.testTag("github-disconnect"))
            TvButton("配置 Client ID", { configure = true }, Modifier.testTag("github-configure"))
            TvButton("管理 GitHub 授权", { onExternal("https://github.com/settings/applications") })
        }
        Text("只读取公开项目，不请求私有仓库或写入权限。退出会删除本机令牌；彻底撤销请前往 GitHub 授权管理。Trending 网页有独立访问限制。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
    }
    OrbitDialog(configure, { configure = false }, width = 640) {
        val first = remember { FocusRequester() }
        Text("GitHub 应用配置", color = Ink, fontSize = 21.sp)
        Text("由应用维护者在 GitHub 注册 OAuth App 并启用 Device flow。Client ID 是公开标识；不要输入个人 Token 或 Client Secret。正式发行可以在构建时预配置，普通用户无需填写。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        OutlinedTextField(clientId, { clientId = it; configError = null }, label = { Text("Client ID") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("github-client-id"))
        configError?.let { Text(it, color = Error, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("保存", { runCatching { auth.configure(clientId) }.onSuccess { configure = false }.onFailure { configError = it.message } }, Modifier.testTag("github-config-save"))
            TvButton("配置说明", { onExternal("https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow") })
            TvButton("取消", { configure = false })
        }
        LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
    }
    OrbitDialog(signing, { signing = false }, width = 600) {
        GitHubDevicePanel(account.clientId, flow, auth, { signing = false }, onExternal)
    }
}

@Composable private fun GitHubDevicePanel(clientId: String, flow: GitHubDeviceFlow, auth: GitHubAuthStore, onClose: () -> Unit, onExternal: (String) -> Unit) {
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val cancel = remember { FocusRequester() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(attempt) {
        code = null; error = null
        try {
            val pending = flow.begin(clientId); code = pending
            val grant = flow.finish(clientId, pending)
            ensureActive(); check(auth.state.value.clientId == clientId) { "应用配置已变化，请重新授权" }
            auth.save(grant); onClose()
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) { error = e.message ?: "授权失败，请重试" }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; cancel.requestFocus(); while (true) { delay(1000); now = System.currentTimeMillis() } }
    Text("在手机上完成 GitHub 授权", color = Ink, fontSize = 22.sp)
    Text("使用手机浏览器打开 github.com/login/device，输入下面的验证码。无需在电视输入密码。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
    Text(code?.userCode ?: "正在获取验证码…", color = Ink, fontSize = if (code == null) 18.sp else 32.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 12.dp).testTag("github-device-code"))
    code?.let { Text("${((it.expiresAt - now).coerceAtLeast(0) / 1000)} 秒后过期 · 正在等待你的确认", color = Muted, fontSize = 11.sp) }
    error?.let { Text(it, color = Error, fontSize = 12.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvButton("取消", onClose, Modifier.focusRequester(cancel).testTag("github-auth-cancel"))
        if (error != null) TvButton("重新获取", { attempt++ })
        TvButton("在此设备打开授权页", { onExternal("https://github.com/login/device") })
    }
}
