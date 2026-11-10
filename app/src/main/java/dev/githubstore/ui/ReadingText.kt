package dev.githubstore.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bound each focus target so a long README paragraph can be read on TV at large font sizes. */
internal fun readingParagraphs(text: String): List<String> = buildList {
    for (paragraph in text.split(Regex("\n[\t ]*\n"))) {
        var remaining = paragraph.trim()
        while (remaining.length > 180) {
            var end = 180
            if (Character.isHighSurrogate(remaining[end - 1])) end--
            val boundary = remaining.lastIndexOfAny(charArrayOf('\n', ' ', '。', '！', '？', '.', ';'), end - 1)
            if (boundary >= end / 2) end = boundary + 1
            add(remaining.substring(0, end).trim())
            remaining = remaining.substring(end).trim()
        }
        if (remaining.isNotEmpty()) add(remaining)
    }
}

@Composable internal fun ReadingText(text: String) {
    val paragraphs = remember(text) { readingParagraphs(text) }
    val active = LocalPageActive.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        paragraphs.forEachIndexed { index, paragraph ->
            key(index, paragraph) {
                var focused by remember { mutableStateOf(false) }
                val border by animateColorAsState(if (focused) Accent else Color.Transparent,
                    motionTween(130), label = "reading-focus")
                val shape = RoundedCornerShape(12.dp)
                Text(paragraph, color = Muted, fontSize = 13.sp, lineHeight = 23.sp,
                    modifier = Modifier.fillMaxWidth().background(if (focused) Panel else Color.Transparent, shape)
                        .border(1.5.dp, border, shape).onFocusChanged { focused = it.isFocused }
                        .focusable(enabled = active).padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}
