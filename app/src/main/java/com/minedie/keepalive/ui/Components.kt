package com.minedie.keepalive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minedie.keepalive.core.EventText
import com.minedie.keepalive.core.EventTone
import com.minedie.keepalive.core.EventType
import com.minedie.keepalive.core.tone
import com.minedie.keepalive.data.EventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

@Composable
internal fun PageTitle(text: String) {
    Text(
        text = text,
        color = Ink,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 16.dp),
    )
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Muted,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SoftCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        content()
    }
}

@Composable
internal fun ShieldMark(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.12f)
            lineTo(size.width * 0.82f, size.height * 0.28f)
            lineTo(size.width * 0.82f, size.height * 0.52f)
            quadraticTo(size.width * 0.78f, size.height * 0.78f, size.width * 0.5f, size.height * 0.9f)
            quadraticTo(size.width * 0.22f, size.height * 0.78f, size.width * 0.18f, size.height * 0.52f)
            lineTo(size.width * 0.18f, size.height * 0.28f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
internal fun StatusHero(title: String, subtitle: String, healthy: Boolean) {
    SoftCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(108.dp)
                    .clip(CircleShape)
                    .background(if (healthy) GreenCircle else Color(0xFFD1D5DB)),
                contentAlignment = Alignment.Center,
            ) {
                ShieldMark(Modifier.size(52.dp), Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun StatCard(
    value: String,
    unit: String,
    caption: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(caption, color = Muted, fontSize = 13.sp)
    }
}

@Composable
internal fun MasterRow(enabled: Boolean, subtitle: String?, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "全局总开关",
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            GreenSwitch(enabled, onChange)
        }
        if (subtitle != null) {
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun GreenSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Green,
            uncheckedTrackColor = TrackOff,
            checkedThumbColor = Color.White,
            uncheckedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
internal fun EventList(
    events: List<EventEntity>,
    showLine: Boolean,
    guardServices: Map<String, List<String>> = emptyMap(),
) {
    if (events.isEmpty()) {
        Text("暂无事件", color = Muted, fontSize = 14.sp)
        return
    }
    events.forEachIndexed { index, event ->
        EventRow(
            event,
            lineAbove = showLine && index > 0,
            lineBelow = showLine && index < events.lastIndex,
            guardServices = guardServices,
        )
    }
}

@Composable
internal fun EventRow(
    event: EventEntity,
    lineAbove: Boolean = false,
    lineBelow: Boolean = false,
    guardServices: Map<String, List<String>> = emptyMap(),
) {
    val tone = runCatching { EventType.valueOf(event.type).tone() }.getOrDefault(EventTone.ACTION)
    val color = when (tone) {
        EventTone.ACTION -> Purple
        EventTone.LOSS -> DotGray
        EventTone.FAILURE -> Red
    }
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier.width(18.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (lineAbove || lineBelow) {
                Canvas(Modifier.fillMaxHeight().width(2.dp)) {
                    val center = 6.dp.toPx() + 4.dp.toPx()
                    drawLine(
                        color = DotGray,
                        start = Offset(size.width / 2f, if (lineAbove) 0f else center),
                        end = Offset(size.width / 2f, if (lineBelow) size.height else center),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    EventText.shownTitle(event.type, event.title, event.packageName, guardServices),
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    clock.format(Date(event.createdAt)),
                    color = Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                EventText.shortDetail(event.detail),
                color = Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ActionRow(text: String, color: Color, chevron: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, color = color, fontSize = 16.sp)
        if (chevron) Text("›", color = Muted, fontSize = 20.sp)
    }
}

@Composable
internal fun HeaderMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3DDC97), Color(0xFF3B82F6)))),
        contentAlignment = Alignment.Center,
    ) {
        ShieldMark(Modifier.size(36.dp), Color.White)
    }
}
