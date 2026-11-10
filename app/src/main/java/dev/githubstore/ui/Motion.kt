package dev.githubstore.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalPageActive = compositionLocalOf { true }
val OrbitEase = CubicBezierEasing(.22f, 1f, .36f, 1f)

@Composable fun <T> motionTween(duration: Int): TweenSpec<T> =
    tween(if (LocalReducedMotion.current) 0 else duration, easing = OrbitEase)

@Composable fun rememberSystemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** One composition owns each route, so outgoing pages cannot retain remote focus. */
@Composable fun PageTransition(key: String, detail: Boolean, content: @Composable (String) -> Unit) {
    val reduced = LocalReducedMotion.current
    val route = key to detail
    AnimatedContent(route, Modifier.fillMaxSize(), label = "page-transition", transitionSpec = {
        val enter = when {
            reduced -> EnterTransition.None
            targetState.second -> fadeIn(tween(180)) + slideInHorizontally(tween(250, easing = OrbitEase)) { it / 18 }
            initialState.second -> fadeIn(tween(180)) + slideInHorizontally(tween(220, easing = OrbitEase)) { -it / 28 }
            else -> fadeIn(tween(160)) + slideInVertically(tween(200, easing = OrbitEase)) { it / 55 }
        }
        val exit = when {
            reduced -> ExitTransition.None
            initialState.second -> fadeOut(tween(120)) + slideOutHorizontally(tween(200, easing = OrbitEase)) { it / 18 }
            targetState.second -> fadeOut(tween(100)) + scaleOut(tween(200, easing = OrbitEase), targetScale = .99f)
            else -> fadeOut(tween(90))
        }
        (enter togetherWith exit).using(SizeTransform(clip = true))
    }) { shown ->
        CompositionLocalProvider(LocalPageActive provides (shown == route)) {
            Box(Modifier.fillMaxSize()) { content(shown.first) }
        }
    }
}

@Composable fun Modifier.revealOnLaunch(): Modifier {
    val reduced = LocalReducedMotion.current
    val reveal = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) { if (reduced) reveal.snapTo(1f) else reveal.animateTo(1f, tween(240, easing = OrbitEase)) }
    return graphicsLayer { alpha = reveal.value; translationY = (1f - reveal.value) * 8.dp.toPx() }
}

/** Keep the window and its content alive through the exit, then restore underlying focus. */
@Composable fun OrbitDialog(visible: Boolean, onDismiss: () -> Unit, width: Int = 440,
                           onClosed: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    val reduced = LocalReducedMotion.current
    LaunchedEffect(state.currentState, state.isIdle) {
        if (state.isIdle && !state.currentState) onClosed()
    }
    if (state.currentState || state.targetState) Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(state,
            enter = if (reduced) EnterTransition.None else fadeIn(tween(150)) + scaleIn(tween(230, easing = OrbitEase), initialScale = .965f),
            exit = if (reduced) ExitTransition.None else fadeOut(tween(110)) + scaleOut(tween(140, easing = OrbitEase), targetScale = .985f),
            modifier = Modifier.widthIn(max = width.dp).padding(20.dp)) {
            CompositionLocalProvider(LocalPageActive provides visible) {
                Surface(shape = RoundedCornerShape(24.dp), color = Panel, contentColor = Ink, shadowElevation = 16.dp) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                }
            }
        }
    }
}
