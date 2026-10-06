package com.minedie.keepalive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ServiceGroup(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val services: List<LiveService>,
)

@Composable
internal fun RunningServicesScreen(state: UiState, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val labels = remember(state.apps) { state.apps.associate { it.packageName to (it.label to it.system) } }
    val groups = remember(state.liveServices, labels, query) {
        val needle = query.trim()
        state.liveServices
            .groupBy { it.packageName }
            .mapNotNull { (pkg, services) ->
                val known = labels[pkg]
                val label = known?.first ?: pkg
                val matched = services.filter { service ->
                    needle.isEmpty() ||
                        label.contains(needle, true) ||
                        pkg.contains(needle, true) ||
                        service.className.contains(needle, true) ||
                        service.processName.contains(needle, true)
                }
                if (matched.isEmpty()) {
                    null
                } else {
                    ServiceGroup(
                        packageName = pkg,
                        label = label,
                        system = known?.second == true,
                        services = matched.sortedBy { it.className.substringAfterLast('.').lowercase() },
                    )
                }
            }
            .sortedWith(compareBy<ServiceGroup> { it.system }.thenBy { it.label.lowercase() })
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = Blue,
                fontSize = 34.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text("正在运行的服务", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            if (state.liveServicesKnown) {
                "只列出进程还在的服务，按应用分组"
            } else {
                "看门狗还没有上报。新的读取在系统进程里，要再重启一次手机。"
            },
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 15.sp),
            cursorBrush = SolidColor(Blue),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("搜索应用或服务", color = Muted, fontSize = 15.sp)
                inner()
            },
        )
        if (!state.liveServicesKnown || groups.isEmpty()) {
            Text(
                when {
                    !state.liveServicesKnown -> "重启并等一轮巡检之后，这里会列出系统里正在运行的服务。"
                    query.isNotBlank() -> "没有匹配的服务"
                    else -> "这次巡检没有读到正在运行的服务"
                },
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(groups, key = { it.packageName }) { group ->
                ServiceGroupBlock(group)
            }
        }
    }
}

@Composable
private fun ServiceGroupBlock(group: ServiceGroup) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(group.packageName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.label,
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    group.packageName,
                    color = Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${group.services.size}", color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        group.services.forEach { service ->
            val shortName = service.className.substringAfterLast('.')
            Column(Modifier.padding(start = 52.dp, bottom = 8.dp)) {
                Text(
                    shortName,
                    color = Ink,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (service.processName.isNotBlank() && service.processName != service.packageName) {
                    Text(
                        service.processName,
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
