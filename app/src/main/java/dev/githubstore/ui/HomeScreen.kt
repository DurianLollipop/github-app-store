package dev.githubstore.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.githubstore.R
import dev.githubstore.domain.StoreApp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Plain focus bookkeeping: moving focus does not invalidate the grid composition. */
internal class HomeFocusMemory {
    var lastId: String? = null
    val requesters = mutableMapOf<String, FocusRequester>()
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
}

internal fun focusVisibleHomeApp(apps: List<StoreApp>, scroll: LazyGridState, memory: HomeFocusMemory): Boolean {
    val app = apps.firstOrNull { it.id == memory.lastId } ?: apps.firstOrNull() ?: return true
    return if (scroll.layoutInfo.visibleItemsInfo.any { it.key == app.id }) memory.requester(app.id).requestFocus() else false
}

internal suspend fun focusHomeApp(apps: List<StoreApp>, scroll: LazyGridState, memory: HomeFocusMemory) {
    if (apps.isEmpty()) return
    val index = apps.indexOfFirst { it.id == memory.lastId }.coerceAtLeast(0)
    // Do not reset a retained viewport. Compose a missing cell before requesting it.
    if (scroll.layoutInfo.visibleItemsInfo.none { it.key == apps[index].id }) scroll.scrollToItem(index)
    withFrameNanos { }
    memory.requester(apps[index].id).requestFocus()
}

private class SilkScene(val image: ImageBitmap, val viewport: IntSize, val phase: State<Float>, val lowMemory: Boolean)
private val LocalSilkScene = staticCompositionLocalOf<SilkScene?> { null }

/** One baked, smoothly shaded surface; only a small transform changes each frame. */
@Composable internal fun SilkSceneHost(modifier: Modifier = Modifier, animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val lowMemory = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }
    val image = ImageBitmap.imageResource(R.drawable.silk_wallpaper_v2)
    val moving = animate && !LocalReducedMotion.current && !lowMemory
    val phase = if (moving) {
        val loop = rememberInfiniteTransition(label = "silk-wallpaper")
        loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "silk-drift")
    } else remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.background(Backdrop)) {
        val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        val scene = remember(image, viewport, phase, lowMemory) { SilkScene(image, viewport, phase, lowMemory) }
        CompositionLocalProvider(LocalSilkScene provides scene) {
            Box(Modifier.matchParentSize().graphicsLayer {
                scaleX = 1.025f; scaleY = 1.025f
                translationX = phase.value * 7.dp.toPx(); translationY = phase.value * 3.dp.toPx()
            }.drawWithCache {
                onDrawBehind { drawImage(image, dstSize = viewport, filterQuality = FilterQuality.Medium) }
            })
            content()
        }
    }
}

/** Sample the same wallpaper in root coordinates, including its animated transform. */
private fun Modifier.wallpaperSample(scene: SilkScene?, origin: () -> IntOffset): Modifier = drawWithCache {
    onDrawBehind {
        if (scene != null) {
            val scale = 1.025f
            val width = (scene.viewport.width * scale).roundToInt()
            val height = (scene.viewport.height * scale).roundToInt()
            val position = origin()
            val x = ((scene.viewport.width - width) / 2f + scene.phase.value * 7.dp.toPx()).roundToInt() - position.x
            val y = ((scene.viewport.height - height) / 2f + scene.phase.value * 3.dp.toPx()).roundToInt() - position.y
            drawImage(scene.image, dstOffset = IntOffset(x, y), dstSize = IntSize(width, height), filterQuality = FilterQuality.Medium)
        }
    }
}

@Composable private fun FrostedNavigationMaterial(modifier: Modifier = Modifier) {
    val scene = LocalSilkScene.current
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val pill = RoundedCornerShape(50)
    Box(modifier.clip(pill).onGloballyPositioned {
        val position = it.positionInRoot()
        origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
    }) {
        // A dedicated background copy is blurred; foreground text never is.
        // Compose uses RenderEffect on API 31+; older/low-RAM TVs retain the tint/rim fallback.
        Box(Modifier.matchParentSize()
            .blur(if (scene?.lowMemory == true) 0.dp else 18.dp, BlurredEdgeTreatment.Unbounded)
            .wallpaperSample(scene) { origin })
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.White.copy(.62f), Color(0xFFF3F1FF).copy(.32f), Color.White.copy(.38f)))))
        Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(
            listOf(Color.White.copy(.95f), Color.White.copy(.34f), Color(0xFFD7DAEB).copy(.48f))), pill))
        Box(Modifier.matchParentSize().padding(1.dp).border(.5.dp, Color.White.copy(.32f), pill))
    }
}

@Composable internal fun FloatingNavigation(selected: Destination, activeDownloads: Int,
    requesters: Map<Destination, FocusRequester>, onContentDown: () -> Boolean, onSelect: (Destination) -> Unit) {
    var focused by remember { mutableStateOf<Destination?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 38.dp), contentAlignment = Alignment.Center) {
        val compact = maxWidth < 740.dp
        if (!compact) Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BlurCircular, null, Modifier.size(23.dp), tint = Ink)
            Text("orbit", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp,
                modifier = Modifier.padding(start = 5.dp))
        }
        val tabWidth = if (compact) ((maxWidth - 8.dp) / 6).coerceAtMost(94.dp) else 94.dp
        val pill = RoundedCornerShape(50)
        val target = (focused ?: selected).ordinal
        val indicator by animateFloatAsState(target.toFloat(), motionTween(220), label = "navigation-position")
        val opacity by animateFloatAsState(if (focused != null) 1f else .78f, motionTween(180), label = "navigation-opacity")
        Box(Modifier.width(tabWidth * 6 + 8.dp).height(46.dp).graphicsLayer {
            shape = pill; shadowElevation = 7.dp.toPx()
            ambientShadowColor = Color(0xFF65718E).copy(.08f); spotShadowColor = Color(0xFF65718E).copy(.10f)
        }.clip(pill).focusGroup()) {
            FrostedNavigationMaterial(Modifier.matchParentSize())
            Box(Modifier.fillMaxSize().padding(4.dp)) {
                Box(Modifier.width(tabWidth).fillMaxHeight().graphicsLayer {
                    translationX = indicator * tabWidth.toPx(); alpha = opacity
                    shape = pill; shadowElevation = 4.dp.toPx()
                    ambientShadowColor = Ink.copy(.06f); spotShadowColor = Ink.copy(.10f)
                }.background(Color.White, pill))
                Row {
                    Destination.entries.forEach { item ->
                        val interaction = remember { MutableInteractionSource() }
                        val isFocused by interaction.collectIsFocusedAsState()
                        val pressed by interaction.collectIsPressedAsState()
                        val color by animateColorAsState(if (isFocused || selected == item) Ink else Muted, motionTween(180), label = "navigation-ink")
                        val press by animateFloatAsState(if (pressed && !LocalReducedMotion.current) .975f else 1f, motionTween(120), label = "navigation-press")
                        Row(Modifier.width(tabWidth).fillMaxHeight().testTag("nav-${item.name}")
                            .graphicsLayer { scaleX = press; scaleY = press }
                            .focusRequester(requesters.getValue(item))
                            .onFocusChanged { state -> if (state.isFocused) focused = item else if (focused == item) focused = null }
                            .onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                                    onContentDown()
                                } else false
                            }
                            .semantics { this.selected = selected == item }
                            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(item) }),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(item.icon, null, Modifier.size(if (compact) 15.dp else 16.dp), tint = color)
                            Text(if (item == Destination.Downloads && activeDownloads > 0) "下载 $activeDownloads" else item.title,
                                color = color, fontSize = if (compact) 11.sp else 12.sp, fontWeight = if (isFocused || selected == item) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.padding(start = 7.dp))
                        }
                    }
                }
            }
        }
    }
}

private class GridNavigationSession {
    var job: Job? = null
    var pendingId: String? = null
}

@Composable internal fun HomePage(apps: List<StoreApp>, scroll: LazyGridState, memory: HomeFocusMemory,
    navigationFocus: FocusRequester, restoreFocus: Boolean, onSelect: (StoreApp) -> Unit) {
    val active = LocalPageActive.current
    val scope = rememberCoroutineScope()
    val navigation = remember { GridNavigationSession() }
    // Pending target is independent of animation/layout completion: rapid repeats
    // advance through rows rather than reusing the last fully laid-out cell.
    LaunchedEffect(active, restoreFocus) {
        if (active && restoreFocus) focusHomeApp(apps, scroll, memory)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 780.dp || maxHeight < 390.dp
        val columns = if (maxWidth < 780.dp) 4 else 6
        val side = if (compact) 30.dp else 44.dp
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().height(if (compact) 112.dp else 144.dp).padding(start = side + 4.dp, top = if (compact) 13.dp else 31.dp)) {
                Text("开 源 应 用", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                Text("好应用，自由探索。", color = Ink, fontSize = if (compact) 27.sp else 30.sp, lineHeight = 39.sp,
                    letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text("让开源的精彩，来到你的大屏。", color = Muted, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp))
            }
            if (apps.isEmpty()) {
                Column(Modifier.padding(horizontal = side, vertical = 30.dp)) {
                    Text("暂无应用", color = Ink, fontSize = 20.sp)
                    Text("可前往设置刷新应用目录。", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            } else LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxWidth().weight(1f).testTag("home-grid")
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !active) return@onPreviewKeyEvent false
                    val index = apps.indexOfFirst { it.id == (navigation.pendingId ?: memory.lastId) }
                    if (index < 0) return@onPreviewKeyEvent false
                    val target = when (event.key) {
                        Key.DirectionLeft -> if (index % columns > 0) index - 1 else index
                        Key.DirectionRight -> if (index % columns < columns - 1) (index + 1).coerceAtMost(apps.lastIndex) else index
                        Key.DirectionDown -> if (index + columns <= apps.lastIndex) index + columns else index
                        Key.DirectionUp -> if (index >= columns) index - columns else -1
                        else -> return@onPreviewKeyEvent false
                    }
                    navigation.job?.cancel()
                    navigation.pendingId = if (target >= 0) apps[target].id else null
                    if (target < 0) navigationFocus.requestFocus()
                    else if (scroll.layoutInfo.visibleItemsInfo.any { it.key == apps[target].id }) {
                        memory.requester(apps[target].id).requestFocus()
                        navigation.pendingId = null
                    } else navigation.job = scope.launch {
                        scroll.scrollToItem(target)
                        withFrameNanos { }
                        memory.requester(apps[target].id).requestFocus()
                        navigation.pendingId = null
                    }
                    true
                }.focusGroup(),
                state = scroll, contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                itemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
                    HomeAppTile(app, Modifier.focusRequester(memory.requester(app.id))
                        .focusProperties {
                            if (index < columns) up = navigationFocus
                            if (index % columns == 0) left = FocusRequester.Cancel
                            if (index % columns == columns - 1 || index == apps.lastIndex) right = FocusRequester.Cancel
                            if (index + columns > apps.lastIndex) down = FocusRequester.Cancel
                        }, onFocus = { memory.lastId = app.id }, onClick = { onSelect(app) })
                }
            }
        }
    }
}

private val HomeCardCornerRadius = 22.dp
private val HomeCardShape = RoundedCornerShape(HomeCardCornerRadius)

@Composable private fun HomeAppTile(app: StoreApp, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (reduced) 1f else if (pressed) .975f else if (focused) 1.08f else 1f,
        if (reduced) snap() else spring(dampingRatio = .86f, stiffness = 560f), label = "app-focus-scale")
    val lift by animateFloatAsState(if (focused && !pressed && !reduced) 3f else 0f,
        if (reduced) snap() else spring(dampingRatio = .90f, stiffness = 560f), label = "app-focus-lift")
    val elevation by animateFloatAsState(if (focused) 16f else 7f, motionTween(210), label = "app-shadow")
    val glow by animateFloatAsState(if (focused) 1f else 0f, motionTween(200), label = "app-halo")
    val nameColor by animateColorAsState(if (focused) Ink else Ink.copy(.82f), motionTween(180), label = "app-name")
    val shape = HomeCardShape
    val density = LocalDensity.current
    val symbolShadow = remember(density) { with(density) {
        Shadow(Color(0xFF142039).copy(.17f), Offset(0f, .4.dp.toPx()), .8.dp.toPx())
    } }
    Column(modifier.fillMaxWidth().testTag("app-tile-${app.id}").zIndex(if (focused) 1f else 0f)
        .onFocusChanged { if (it.isFocused) onFocus() }
        .clickable(enabled = LocalPageActive.current, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(horizontal = 4.dp).fillMaxWidth().aspectRatio(1.4f).graphicsLayer {
            scaleX = scale; scaleY = scale; translationY = -lift.dp.toPx()
            this.shape = shape; shadowElevation = elevation.dp.toPx()
            ambientShadowColor = Color(0xFF6A7196).copy(.06f); spotShadowColor = Color.Transparent
        }.drawWithCache {
            val halo = Brush.radialGradient(listOf(Color(0xFF9C9FF1).copy(.22f), Color(0xFFBFC9FF).copy(.08f), Color.Transparent),
                Offset(size.width / 2, size.height / 2), size.maxDimension * .78f)
            val shadowRadius = size.width * .70f
            val shadowCenter = Offset(size.width / 2 + 11.dp.toPx(), size.height / 2 + 13.dp.toPx())
            val castShadow = Brush.radialGradient(listOf(Color(0xFF4D5678).copy(.22f),
                Color(0xFF4D5678).copy(.08f), Color.Transparent), Offset.Zero, shadowRadius)
            val contactRadius = size.width * .48f
            val contactCenter = Offset(size.width / 2 + 9.dp.toPx(), size.height + 4.dp.toPx())
            val contactShadow = Brush.radialGradient(listOf(Color(0xFF34415F).copy(.12f),
                Color(0xFF34415F).copy(.045f), Color.Transparent), Offset.Zero, contactRadius)
            onDrawBehind {
                // A narrow contact shadow grounds the rounded body; the broad
                // ambient shadow establishes its separation from the wallpaper.
                withTransform({
                    translate(contactCenter.x, contactCenter.y)
                    scale(1f, .22f, Offset.Zero)
                }) { drawCircle(contactShadow, contactRadius, Offset.Zero, alpha = .85f + glow * .15f) }
                // All cards share a light above-left; the soft cast falls down-right.
                withTransform({
                    translate(shadowCenter.x, shadowCenter.y)
                    scale(1f, .70f, Offset.Zero)
                }) { drawCircle(castShadow, shadowRadius, Offset.Zero, alpha = .65f + glow * .35f) }
                if (glow > 0f) drawCircle(halo, size.maxDimension * .78f, alpha = glow)
            }
        }) {
            // Flatten artwork and light first, then apply one shared 22dp mask.
            // Separate corner masks left mismatched edges on the lower curves.
            Box(Modifier.matchParentSize().graphicsLayer {
                this.shape = shape
                clip = true
                compositingStrategy = CompositingStrategy.Offscreen
            }) {
                AppMark(app, Modifier.fillMaxSize(), size = 86, fallbackShading = false, shape = null, symbolShadow = symbolShadow)
                Box(Modifier.matchParentSize().beveledCardMaterial(HomeCardCornerRadius,
                    neutralSurface = app.iconUrl.isNotEmpty() && !isCenteredBannerArtwork(app.iconUrl)))
            }
            // Focus is a separate state cue, never a permanent white rim.
            Box(Modifier.matchParentSize().border(1.25.dp, Color(0xFF9DA3E2).copy(alpha = glow * .82f), shape))
        }
        Text(app.name, color = nameColor, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 5.dp))
    }
}
