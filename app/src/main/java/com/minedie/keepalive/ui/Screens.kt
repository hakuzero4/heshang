package com.minedie.keepalive.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minedie.keepalive.R
import com.minedie.keepalive.core.EventType
import com.minedie.keepalive.core.Ranges
import com.minedie.keepalive.core.componentClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OverviewScreen(
    state: UiState,
    onMaster: (Boolean) -> Unit,
    onOpenGuard: () -> Unit,
    onOpenStarts: () -> Unit,
    onOpenA11y: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("总览")
        StatusHero(state.statusTitle, state.statusSubtitle, state.healthy)
        if (state.warning != null) {
            Text(
                state.warning,
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                state.protectedCount.toString(),
                "个应用",
                "${state.serviceCount} 个服务",
                Modifier.weight(1f),
                onOpenGuard,
            )
            StatCard(
                state.silentStarts.toString(),
                "次",
                "近 24 小时静默拉起",
                Modifier.weight(1f),
                onOpenStarts,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(state.a11yCount.toString(), "个", "无障碍守护中", Modifier.weight(1f), onOpenA11y)
            StatCard(state.intervalSec.toString(), "秒", "全局巡检间隔", Modifier.weight(1f), onOpenSettings)
        }
        Spacer(Modifier.height(12.dp))
        SoftCard {
            MasterRow(state.master, "关闭后所有守护暂停，配置不会丢失", onMaster)
        }
        Spacer(Modifier.height(12.dp))
        SoftCard {
            Text("最近事件", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            EventList(state.recent, showLine = true, state.guardServices)
        }
    }
}

@Composable
internal fun SettingsScreen(
    state: UiState,
    onMaster: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onBootDelay: (Int) -> Unit,
    onRetention: (Int) -> Unit,
    onRestart: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("设置")
        SoftCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                HeaderMark()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.app_name), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("v${state.versionName} · 作者 hakuzero", color = Muted, fontSize = 13.sp)
            }
        }
        SectionLabel("全局")
        SoftCard {
            MasterRow(state.master, null, onMaster)
            Spacer(Modifier.height(8.dp))
            SettingSlider("全局巡检间隔", state.intervalSec, "秒", 5f, 120f, 22, onInterval)
            SettingSlider("开机启动延迟", state.bootDelaySec, "秒", 0f, 120f, 23, onBootDelay)
            SettingSlider("日志保留条数", state.retention, "条", 50f, 2000f, 38, onRetention)
        }
        SectionLabel("操作")
        SoftCard {
            ActionRow("重启守护进程", Blue, chevron = true, onClick = onRestart)
            Box(Modifier.fillMaxWidth().height(1.dp).background(PageBg))
            ActionRow("清空日志", Red, chevron = false, onClick = onClear)
        }
        Text(
            "在 LSPosed 里勾选系统框架和和尚，然后重启一次。同一个应用不要同时交给不死鸟。",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSlider(
    title: String,
    current: Int,
    unit: String,
    min: Float,
    max: Float,
    steps: Int,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember(current) { mutableIntStateOf(current) }
    LaunchedEffect(current, dragging) {
        if (!dragging) value = current
    }
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Ink, fontSize = 16.sp)
            Text("$value $unit", color = Muted, fontSize = 15.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                dragging = true
                value = when (title) {
                    "日志保留条数" -> Ranges.retention(it.toInt())
                    "开机启动延迟" -> Ranges.bootDelaySec(it.toInt())
                    else -> Ranges.intervalSec(it.toInt())
                }
            },
            onValueChangeFinished = {
                dragging = false
                onCommit(value)
            },
            valueRange = min..max,
            steps = steps,
            thumb = {
                Box(
                    Modifier
                        .size(26.dp)
                        .shadow(2.dp, CircleShape)
                        .background(Color.White, CircleShape)
                        .border(1.dp, Color(0x14000000), CircleShape),
                )
            },
            track = { sliderState ->
                Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                    val y = size.height / 2f
                    val stroke = 4.dp.toPx()
                    val fraction = sliderState.coercedValueAsFraction.coerceIn(0f, 1f)
                    drawLine(
                        color = TrackOff,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    if (fraction > 0f) {
                        drawLine(
                            color = Blue,
                            start = Offset(0f, y),
                            end = Offset(size.width * fraction, y),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            },
        )
    }
}

@Composable
internal fun GuardScreen(
    state: UiState,
    onShowSystem: (Boolean) -> Unit,
    onApp: (String, String, Boolean, List<String>) -> Unit,
    servicesOf: (String) -> List<ServiceChoice>,
    onRetry: (String) -> Unit,
    onOpenServices: () -> Unit,
    onRequestApps: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AppRow?>(null) }
    val visible = remember(state.apps, state.showSystem, query) {
        state.apps.filter { app ->
            (state.showSystem || !app.system) &&
                (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
        }.sortedWith(compareByDescending<AppRow> { it.enabled }.thenBy { it.label.lowercase() })
    }
    val serviceSubtitle = if (state.liveServicesKnown) {
        val apps = state.liveServices.map { it.packageName }.distinct().size
        "${state.liveServices.size} 个服务 · $apps 个应用"
    } else {
        "看门狗上报后显示"
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("守护")
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .clickable(onClick = onOpenServices)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("正在运行的服务", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(serviceSubtitle, color = Muted, fontSize = 12.sp)
            }
            Text("›", color = Muted, fontSize = 22.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("显示系统应用", color = Ink, modifier = Modifier.weight(1f), fontSize = 15.sp)
            GreenSwitch(state.showSystem, onShowSystem)
        }
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 15.sp),
            cursorBrush = SolidColor(Blue),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("搜索应用", color = Muted, fontSize = 15.sp)
                inner()
            },
        )
        if (state.appListLimited) {
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .clickable(onClick = onRequestApps)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("读取应用列表", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("系统没有交出其他应用", color = Muted, fontSize = 12.sp)
                }
                Text("允许", color = Blue, fontSize = 14.sp)
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(visible, key = { it.packageName }, contentType = { "app" }) { app ->
                AppLine(
                    app,
                    onToggle = { enabled ->
                        if (enabled && app.components.isEmpty()) editing = app else {
                            onApp(app.packageName, app.label, enabled, app.components)
                        }
                    },
                    onOpen = { editing = app },
                    onRetry = onRetry,
                )
            }
        }
    }
    val current = editing
    if (current != null) {
        ServiceDialog(
            app = current,
            servicesOf = servicesOf,
            onDismiss = { editing = null },
            onPick = { components ->
                onApp(current.packageName, current.label, true, components)
                editing = null
            },
        )
    }
}

@Composable
internal fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val image by produceState(AppIconCache.peek(packageName), packageName) {
        value = AppIconCache.load(context, packageName)
    }
    val bitmap = image
    if (bitmap == null) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(TrackOff))
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
        )
    }
}

@Composable
private fun AppLine(
    app: AppRow,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRetry: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (app.running) {
                        Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(Green))
                    }
                }
                Text(
                    if (app.gaveUp) {
                        "已停止重试"
                    } else if (app.components.isEmpty()) {
                        "未选 Service · 只检测掉线"
                    } else {
                        app.components.joinToString("、") { simpleClass(it) }
                    },
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }
        if (app.gaveUp) {
            TextButton(onClick = { onRetry(app.packageName) }) { Text("再试一次") }
        }
        GreenSwitch(app.enabled, onToggle)
    }
}

@Composable
private fun ServiceDialog(
    app: AppRow,
    servicesOf: (String) -> List<ServiceChoice>,
    onDismiss: () -> Unit,
    onPick: (List<String>) -> Unit,
) {
    var services by remember(app.packageName) { mutableStateOf<List<ServiceChoice>?>(null) }
    var selected by remember(app.packageName) { mutableStateOf(app.components) }
    LaunchedEffect(app.packageName) {
        services = withContext(Dispatchers.IO) { servicesOf(app.packageName) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("可以选多个 Service。掉线后会静默拉起，不会打开界面。", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ServiceRow("只检测掉线", "不拉起进程", selected.isEmpty()) { onPick(emptyList()) }
                val loaded = services
                if (loaded == null) {
                    Text("正在读取 Service", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else if (loaded.isEmpty()) {
                    Text("这个应用没有 Service", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    loaded.forEach { service ->
                        val picked = selected.any { sameComponent(app.packageName, it, service.component) }
                        ServiceRow(
                            service.label,
                            service.className,
                            picked,
                            foreground = service.foreground,
                        ) {
                            selected = if (picked) {
                                selected.filterNot { sameComponent(app.packageName, it, service.component) }
                            } else {
                                selected + service.component
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun sameComponent(packageName: String, left: String, right: String): Boolean {
    val a = componentClass(left, packageName)
    val b = componentClass(right, packageName)
    return a != null && a == b
}

private fun simpleClass(flat: String): String {
    val cls = flat.substringAfterLast('/')
    return cls.substringAfterLast('.').removePrefix(".").ifBlank { cls }
}

@Composable
private fun ServiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    foreground: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = if (selected) Green else Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (foreground) {
                Text("常驻", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Text(detail, color = Muted, fontSize = 12.sp)
    }
}

@Composable
internal fun A11yScreen(state: UiState, onToggle: (String, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageTitle("无障碍") }
        item {
            Text(
                "巡检时只把这里打开的服务写回已启用列表，不会改动其他服务。",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (state.services.isEmpty()) {
            item { Text("没有已安装的无障碍服务", color = Muted, modifier = Modifier.padding(20.dp)) }
        }
        items(state.services, key = { it.id }) { service ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(service.label, color = Ink, fontSize = 16.sp)
                    Text(service.id, color = Muted, fontSize = 12.sp)
                }
                GreenSwitch(service.enabled) { onToggle(service.id, it) }
            }
        }
    }
}

private enum class LogFilter(val label: String) {
    ALL("全部"),
    LOST("掉线"),
    START("拉起"),
    A11Y("无障碍"),
    DAEMON("守护"),
}

@Composable
internal fun LogScreen(state: UiState, startsOnly: Boolean = false) {
    var filter by remember(startsOnly) { mutableStateOf(if (startsOnly) LogFilter.START else LogFilter.ALL) }
    val shown = state.logs.filter { matches(it.type, filter) }
    Column(Modifier.fillMaxSize()) {
        PageTitle("日志")
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogFilter.entries.forEach { item ->
                FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.label) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { event ->
                EventRow(event, guardServices = state.guardServices)
            }
            if (shown.isEmpty()) {
                item { Text("暂无事件", color = Muted, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

private fun matches(typeName: String, filter: LogFilter): Boolean {
    val type = runCatching { EventType.valueOf(typeName) }.getOrNull() ?: return filter == LogFilter.ALL
    return when (filter) {
        LogFilter.ALL -> true
        LogFilter.LOST -> type == EventType.PROCESS_LOST
        LogFilter.START -> type == EventType.SILENT_START_OK || type == EventType.SILENT_START_FAIL
        LogFilter.A11Y -> type == EventType.A11Y_RESTORED || type == EventType.A11Y_RESTORE_FAIL
        LogFilter.DAEMON -> type == EventType.DAEMON_STARTED ||
            type == EventType.WATCHDOG_PULLED_DAEMON ||
            type == EventType.HOOK_FAIL ||
            type == EventType.ADJ_APPLIED
    }
}
