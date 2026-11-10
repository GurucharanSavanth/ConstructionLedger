package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object WebColors {
    val ink = Color(0xFF243630)
    val muted = Color(0xFF64736D)
    val line = Color(0xFFE1E7E2)
    val soft = Color(0xFFF4F7F4)
    val background = Color(0xFFF6F7F5)
    val header = Color(0xFFFCFDFB)
    val blue = Color(0xFF17665A)
    val blueSoft = Color(0xFFE9F1EC)
    val teal = Color(0xFF17665A)
    val chart = listOf(Color(0xFF17665A), Color(0xFF628E80), Color(0xFFA8BCB1), Color(0xFFD2B988), Color(0xFF8196A3), Color(0xFFA8867B), Color(0xFFC8D2D0))
}

@Composable
internal fun WebTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = lightColorScheme(primary = WebColors.blue, onPrimary = Color.White,
            secondary = WebColors.teal, background = WebColors.background, surface = Color.White,
            surfaceContainer = Color.White, surfaceContainerLow = WebColors.soft,
            onSurface = WebColors.ink, onBackground = WebColors.ink,
            onSurfaceVariant = WebColors.muted, outline = WebColors.line,
            outlineVariant = WebColors.line, error = Color(0xFFC04C56)),
        typography = base.copy(
            headlineSmall = base.headlineSmall.copy(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
            titleLarge = base.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
            labelLarge = base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)),
        shapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(17.dp), large = RoundedCornerShape(17.dp), extraLarge = RoundedCornerShape(20.dp)),
        content = content)
}

@Composable
internal fun WebIcon(name: String, modifier: Modifier = Modifier, tint: Color = WebColors.blue) {
    Canvas(modifier.size(22.dp)) {
        val s = size.minDimension / 24f
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(tint, Offset(x*s,y*s), Offset(xx*s,yy*s), 1.7f*s, StrokeCap.Round)
        fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
            val p = Path(); points.forEachIndexed { i, v -> if(i==0) p.moveTo(v.first*s,v.second*s) else p.lineTo(v.first*s,v.second*s) }; if(close) p.close()
            drawPath(p,tint,style=Stroke(1.7f*s,cap=StrokeCap.Round))
        }
        when(name) {
            "home" -> { path(3f to 11f,12f to 3f,21f to 11f); path(5f to 10f,5f to 21f,19f to 21f,19f to 10f); path(9f to 21f,9f to 14f,15f to 14f,15f to 21f) }
            "entry", "add" -> { path(4f to 4f,20f to 4f,20f to 20f,4f to 20f,close=true); line(12f,8f,12f,16f); line(8f,12f,16f,12f) }
            "activity", "time" -> { drawCircle(tint,9*s,Offset(12*s,12*s),style=Stroke(1.7f*s)); line(12f,6f,12f,12f);line(12f,12f,17f,14f) }
            "documents", "file" -> { path(6f to 3f,15f to 3f,20f to 8f,20f to 21f,6f to 21f,close=true);path(15f to 3f,15f to 8f,20f to 8f);line(9f,12f,17f,12f);line(9f,16f,17f,16f) }
            "explore", "chart" -> { line(4f,21f,21f,21f);line(5f,18f,5f,11f);line(10f,18f,10f,5f);line(15f,18f,15f,9f);line(20f,18f,20f,3f) }
            "settings" -> { drawCircle(tint,4*s,Offset(12*s,12*s),style=Stroke(1.7f*s));drawCircle(tint,8*s,Offset(12*s,12*s),style=Stroke(1.7f*s));for(i in 0..7){val a=i*Math.PI/4;line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+11*kotlin.math.cos(a)).toFloat(),(12+11*kotlin.math.sin(a)).toFloat())} }
            "backup" -> { path(5f to 17f,3f to 15f,3f to 11f,6f to 9f,8f to 5f,13f to 4f,17f to 7f,18f to 10f,21f to 12f,21f to 16f,18f to 18f,5f to 18f);line(12f,10f,12f,20f);path(9f to 17f,12f to 20f,15f to 17f) }
            "back" -> { path(14f to 5f,7f to 12f,14f to 19f) }
            "chevron" -> path(9f to 5f,16f to 12f,9f to 19f)
            "search" -> {drawCircle(tint,7*s,Offset(10*s,10*s),style=Stroke(1.7f*s));line(15f,15f,21f,21f)}
            "calendar" -> { path(3f to 5f,21f to 5f,21f to 21f,3f to 21f,close=true);line(3f,10f,21f,10f);line(8f,2f,8f,7f);line(16f,2f,16f,7f) }
            "money" -> {path(6f to 4f,18f to 4f);line(6f,8f,18f,8f);path(8f to 4f,14f to 6f,14f to 10f,7f to 12f,16f to 21f)}
            "contract" -> {path(5f to 3f,19f to 3f,19f to 21f,5f to 21f,close=true);for(y in listOf(8f,12f,16f))line(8f,y,16f,y)}
            "allocate" -> {path(5f to 5f,2f to 8f,5f to 11f);line(2f,8f,21f,8f);path(19f to 13f,22f to 16f,19f to 19f);line(3f,16f,22f,16f)}
            "labour" -> {drawCircle(tint,3*s,Offset(12*s,6*s),style=Stroke(1.7f*s));path(5f to 21f,5f to 17f,8f to 12f,16f to 12f,19f to 17f,19f to 21f)}
            else -> {path(4f to 8f,12f to 3f,20f to 8f,20f to 18f,12f to 22f,4f to 18f,close=true);path(4f to 8f,12f to 13f,20f to 8f);line(12f,13f,12f,22f)}
        }
    }
}

@Composable
internal fun WebCard(modifier: Modifier = Modifier, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = color,
        border = BorderStroke(1.dp, WebColors.line)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun PageHeading(title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            action?.invoke()
        }
        if(description.isNotBlank()) Text(description, color = WebColors.muted, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun EmptyPanel(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = WebColors.muted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun WebTabs(options: List<Pair<String,String>>, selected: String, select: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (key,label) ->
            Surface(onClick = { select(key) }, shape = RoundedCornerShape(10.dp),
                color = if(selected == key) WebColors.blueSoft else Color.White,
                border = BorderStroke(1.dp, if(selected == key) Color(0xFFCDDEF1) else WebColors.line)) {
                Text(label, Modifier.padding(horizontal=14.dp,vertical=11.dp), color=if(selected==key) WebColors.blue else WebColors.muted,
                    fontSize=13.sp, fontWeight=FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun WebListRow(title: String, subtitle: String, amount: String? = null, click: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth().then(if(click != null) Modifier.clickable(onClick=click) else Modifier).padding(vertical=14.dp),
            horizontalArrangement=Arrangement.spacedBy(10.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(title, style=MaterialTheme.typography.titleMedium)
                if(subtitle.isNotBlank()) Text(subtitle,color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
            }
            if(amount != null) Text(amount,style=MaterialTheme.typography.titleMedium)
            if(click != null) WebIcon("chevron",Modifier.size(16.dp),WebColors.muted)
        }
        HorizontalDivider(color=WebColors.line)
    }
}

@Composable
internal fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.Button(onClick, modifier.heightIn(min=42.dp), enabled=enabled,
        shape=RoundedCornerShape(7.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),content=content)
}

@Composable
internal fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(7.dp),
    border: BorderStroke = BorderStroke(1.dp, WebColors.line),
    contentPadding: PaddingValues = PaddingValues(horizontal=16.dp,vertical=10.dp),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor=WebColors.ink),
    content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick,modifier.heightIn(min=42.dp),enabled=enabled,shape=shape,
        border=border,colors=colors,contentPadding=contentPadding,content=content)
}
