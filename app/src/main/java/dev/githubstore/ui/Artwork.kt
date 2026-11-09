package dev.githubstore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.githubstore.domain.StoreApp

@Composable fun OrbitArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * .58f, size.height * .5f)
        val r = size.minDimension * .31f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF244577).copy(.32f), Color.Transparent), center, r * 2f), r * 2f, center)
        rotate(-29f, center) {
            drawOval(Brush.linearGradient(listOf(Color(0xFF263854), Color(0xFF9CBEFA).copy(.8f), Color(0xFF263854))),
                topLeft = Offset(center.x - r * 1.58f, center.y - r * .52f), size = Size(r * 3.16f, r * 1.04f), style = Stroke(r * .018f))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFB2D5F8), Color(0xFF487BC1), Color(0xFF182D53), Color(0xFF0B1121)),
            center = Offset(center.x - r * .4f, center.y - r * .5f), radius = r * 1.9f), r, center)
        drawArc(Brush.linearGradient(listOf(Color(0xFF102444), Color(0xFFB4D8FF), Color(0xFF294B79))),
            startAngle = 15f, sweepAngle = 152f, useCenter = false,
            topLeft = Offset(center.x - r * 1.51f, center.y - r * .7f), size = Size(r * 3.02f, r * 1.4f), style = Stroke(r * .025f))
        drawCircle(Color(0xFFDAE8FF), r * .04f, Offset(center.x - r * 1.25f, center.y - r * .8f))
        drawCircle(Color(0xFF9AB6DE).copy(.6f), r * .018f, Offset(center.x + r * 1.42f, center.y - r * 1.12f))
        drawCircle(Color(0xFF9AB6DE).copy(.4f), r * .014f, Offset(center.x - r * 1.6f, center.y + r * 1.25f))
    }
}

@Composable fun AppMark(app: StoreApp, modifier: Modifier = Modifier, size: Int = 58) {
    Box(modifier.size(size.dp).clip(RoundedCornerShape((size * .24f).dp))
        .background(Brush.linearGradient(listOf(Color(app.color.toInt()), Color(app.color.toInt()).copy(.35f)))), contentAlignment = Alignment.Center) {
        if (app.iconUrl.isNotEmpty()) AsyncImage(app.iconUrl, "${app.name} 图标", Modifier.fillMaxSize().padding((size * .12f).dp))
        else Text(app.symbol, color = Color.White, fontSize = (size * .46f).sp, fontWeight = FontWeight.Bold)
    }
}
