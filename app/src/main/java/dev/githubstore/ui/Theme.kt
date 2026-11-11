package dev.githubstore.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Backdrop = Color(0xFFF7F8FC)
val Panel = Color.White
val Sidebar = Color(0xFFEBEDF2)
val Control = Color(0xFFE8E8ED)
val Selection = Color(0xFFE3EEFF)
val Muted = Color(0xFF778198)
val Ink = Color(0xFF202437)
val Accent = Color(0xFF0066CC)
val Good = Color(0xFF24804A)
val Error = Color(0xFFB42332)

@Composable fun OrbitTheme(forceReduced: Boolean = false, content: @Composable () -> Unit) {
    val reducedMotion = rememberSystemReducedMotion()
    CompositionLocalProvider(LocalReducedMotion provides (reducedMotion || forceReduced)) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Accent, onPrimary = Color.White, background = Backdrop,
            surface = Panel, surfaceVariant = Control, onSurface = Ink, onSurfaceVariant = Muted,
            onBackground = Ink, outline = Color(0xFFC7C7CC), error = Error),
            typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 20.sp))) {
            androidx.tv.material3.MaterialTheme(colorScheme = androidx.tv.material3.lightColorScheme(
                primary = Accent, onPrimary = Color.White, background = Backdrop, onBackground = Ink,
                surface = Panel, onSurface = Ink)) { content() }
        }
    }
}

@Composable fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    color: Color = Panel,
    radius: Int = 18,
    scale: Float = 1.025f,
    contentColor: Color = Ink,
    focusedColor: Color = Panel,
    focusedContentColor: Color = contentColor,
    focusRing: Color = Accent,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val active = LocalPageActive.current
    val zoom by animateFloatAsState(if (reduced) 1f else if (pressed) .975f else if (focused) scale else 1f,
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 900f), label = "focus-scale")
    val lift by animateFloatAsState(if (focused && !pressed && !reduced) 1.5f else 0f,
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 900f), label = "focus-lift")
    val shadow by animateFloatAsState(if (focused) 8f else 0f, motionTween(140), label = "focus-depth")
    val fill by animateColorAsState(if (focused) focusedColor else if (selected) Selection else color,
        motionTween(130), label = "focus-fill")
    val foreground by animateColorAsState(if (focused) focusedContentColor else contentColor, motionTween(100), label = "focus-ink")
    val shape = RoundedCornerShape(radius.dp)
    Box(modifier.zIndex(if (focused) 1f else 0f).graphicsLayer {
            scaleX = zoom; scaleY = zoom; translationY = -lift.dp.toPx()
            shadowElevation = shadow.dp.toPx(); this.shape = shape
            ambientShadowColor = Color.Black.copy(.10f); spotShadowColor = Color.Black.copy(.15f)
        }
        .clip(shape).background(fill)
        .border(if (focused) 2.dp else 1.dp, if (focused) focusRing else Ink.copy(alpha = if (selected) .05f else .035f), shape)
        .semantics { this.selected = selected }
        .clickable(enabled = active, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)) {
        CompositionLocalProvider(LocalContentColor provides foreground) { content(focused) }
    }
}

@Composable fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
                         icon: (@Composable () -> Unit)? = null) {
    FocusSurface(onClick, modifier.heightIn(min = 43.dp), color = if (primary) Accent else Control,
        contentColor = if (primary) Color.White else Ink,
        focusedColor = if (primary) Color(0xFF0057B8) else Panel,
        focusRing = if (primary) Color.White else Accent, radius = 50, scale = 1.035f) { _ ->
        Row(Modifier.padding(horizontal = 19.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            Text(text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = LocalContentColor.current)
        }
    }
}

@Composable fun Eyebrow(text: String, color: Color = Accent) {
    Text(text, color = color, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
}

@Composable fun SectionTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp))
        }
        trailing?.invoke()
    }
}
