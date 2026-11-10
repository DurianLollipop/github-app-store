package dev.githubstore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.githubstore.domain.StoreApp

@Composable fun AppMark(app: StoreApp, modifier: Modifier = Modifier, size: Int = 58, fallbackShading: Boolean = true) {
    var loaded by remember(app.iconUrl) { mutableStateOf(false) }
    // Original artwork sits on a neutral surface. Brand gradients belong only to
    // the explicit letter fallback, never behind a successfully loaded logo.
    Box(modifier.size(size.dp).clip(RoundedCornerShape((size * .24f).dp))
        .background(if (loaded) Color.White else Color(app.color.toInt())), contentAlignment = Alignment.Center) {
        if (!loaded && fallbackShading) Box(Modifier.matchParentSize().background(Brush.linearGradient(
            listOf(Color.White.copy(.18f), Color.Transparent, Color.Black.copy(.10f)))))
        if (!loaded) Text(app.symbol, color = Color.White, fontSize = (size * .46f).sp, fontWeight = FontWeight.Bold)
        if (app.iconUrl.isNotEmpty()) AsyncImage(app.iconUrl, "${app.name} 图标",
            Modifier.fillMaxSize().padding((size * .12f).dp).alpha(if (loaded) 1f else 0f),
            // This README asset is a centered symbol in a 5:1 transparent banner.
            // Crop its empty sides; fit other logos without distorting/cutting them.
            contentScale = if (app.iconUrl.substringBefore('?').endsWith("/banner-logo-solid.svg")) ContentScale.Crop else ContentScale.Fit,
            onLoading = { loaded = false }, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
