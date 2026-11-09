package dev.githubstore.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Backdrop = Color(0xFF080A0F)
val Panel = Color(0xFF14171F)
val Muted = Color(0xFF9299A8)
val Ink = Color(0xFFF5F6FA)
val Accent = Color(0xFF9DBEFF)
val Good = Color(0xFFA1DDBB)

@Composable fun OrbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Backdrop, background = Backdrop,
        surface = Panel, onSurface = Ink, onBackground = Ink),
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp))) {
        androidx.tv.material3.MaterialTheme { content() }
    }
}

@Composable fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    color: Color = Panel,
    radius: Int = 18,
    scale: Float = 1.025f,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val zoom by animateFloatAsState(if (pressed) .98f else if (focused) scale else 1f,
        spring(dampingRatio = 1f, stiffness = 500f), label = "focus-scale")
    val fill by animateColorAsState(if (focused) Color(0xFFF0F3FA) else if (selected) Color(0xFF272F40) else color, label = "focus-fill")
    Box(modifier.graphicsLayer { scaleX = zoom; scaleY = zoom }
        .clip(RoundedCornerShape(radius.dp)).background(fill)
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = if (selected) .14f else .055f), RoundedCornerShape(radius.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)) {
        CompositionLocalProvider(LocalContentColor provides if (focused) Backdrop else Ink) { content(focused) }
    }
}

@Composable fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
                         icon: (@Composable () -> Unit)? = null) {
    FocusSurface(onClick, modifier.heightIn(min = 43.dp), color = if (primary) Color(0xFF344F7D) else Color(0xFF20252F), radius = 50, scale = 1.035f) { _ ->
        Row(Modifier.padding(horizontal = 19.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalContentColor.current)
        }
    }
}

@Composable fun Eyebrow(text: String, color: Color = Accent) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
}

@Composable fun SectionTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        }
        trailing?.invoke()
    }
}
