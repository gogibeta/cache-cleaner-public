package com.cachecleaner.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cachecleaner.app.R
import com.cachecleaner.app.data.AppEntry
import com.cachecleaner.app.data.IconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    onOpenWhitelist: () -> Unit
) {
    val context = LocalContext.current
    val apps by vm.apps.collectAsState()
    val totalCache by vm.totalCache.collectAsState()
    val loading by vm.loading.collectAsState()
    val selection by vm.selection.collectAsState()
    val turbo by vm.turbo.collectAsState()
    val running by vm.running.collectAsState()
    val hasUsage by vm.hasUsageAccess.collectAsState()
    val a11y by vm.a11yEnabled.collectAsState()
    val query by vm.query.collectAsState()
    val logVersion by vm.logVersion.collectAsState()
    val lastSummary by vm.lastSummary.collectAsState()
    val toastMsg by vm.toastMsg.collectAsState()
    val logLineCount by vm.logLines.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(AppFilter.ALL) }

    // One-shot toast for log export results.
    toastMsg?.let { msg ->
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        vm.consumeToast()
    }

    val visible = remember(apps, query, filter) {
        apps.filter { e ->
            (filter == AppFilter.ALL ||
                (filter == AppFilter.CACHED && e.cacheBytes > 0) ||
                (filter == AppFilter.RUNNING && e.isRunning)) &&
                (query.isBlank() ||
                    e.label.contains(query, ignoreCase = true) ||
                    e.packageName.contains(query, ignoreCase = true))
        }
    }
    // Recompose log panel when new lines arrive.
    @Suppress("UNUSED_VARIABLE")
    val logTick = logVersion
    val logLines = remember(logTick) { vm.logLines() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onOpenWhitelist) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.whitelist_title))
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ---- cache card ----
            item { CacheCard(totalCache = totalCache, hasUsage = hasUsage) }

            // ---- setup checklist ----
            if (!hasUsage || !a11y) {
                item {
                    SetupCard(
                        hasUsage = hasUsage,
                        a11y = a11y,
                        onGrantUsage = { vm.openUsageSettings() },
                        onOpenA11y = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            } catch (_: Exception) {
                            }
                        }
                    )
                }
            }

            // ---- run controls ----
            item {
                RunControls(
                    running = running,
                    turbo = turbo,
                    selectedCount = selection.size,
                    canRun = a11y && selection.isNotEmpty(),
                    onTurboChange = vm::setTurbo,
                    onClean = vm::cleanSelected,
                    onCancel = vm::cancelRun
                )
            }

            // ---- success card (last finished run) ----
            if (!running && lastSummary != null) {
                item {
                    ResultCard(
                        summary = lastSummary!!,
                        onDismiss = vm::clearSummary
                    )
                }
            }

            // ---- log panel ----
            if (logLines.isNotEmpty() || running) {
                item {
                    LogPanel(
                        lines = logLines,
                        expanded = showLog,
                        onToggle = { showLog = !showLog },
                        persistentCount = logLineCount,
                        onDownload = vm::downloadLog,
                        onShare = {
                            try {
                                vm.shareLogIntent()?.let {
                                    context.startActivity(
                                        Intent.createChooser(it, "Share log.json").apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            } catch (_: Exception) {
                            }
                        }
                    )
                }
            }

            // ---- search + filters ----
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filter == AppFilter.ALL,
                        onClick = { filter = AppFilter.ALL },
                        label = { Text(stringResource(R.string.filter_all)) }
                    )
                    FilterChip(
                        selected = filter == AppFilter.CACHED,
                        onClick = { filter = AppFilter.CACHED },
                        label = { Text(stringResource(R.string.filter_cached)) }
                    )
                    FilterChip(
                        selected = filter == AppFilter.RUNNING,
                        onClick = { filter = AppFilter.RUNNING },
                        label = { Text(stringResource(R.string.filter_running)) }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.selectAllVisible(visible) }) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(onClick = vm::clearSelection) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            // ---- app list ----
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (visible.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if ((filter == AppFilter.RUNNING || filter == AppFilter.CACHED) && !hasUsage)
                                stringResource(R.string.no_apps_no_usage)
                            else
                                stringResource(R.string.no_apps),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(visible, key = { it.packageName }) { entry ->
                    AppRow(
                        entry = entry,
                        checked = entry.packageName in selection,
                        onToggle = { vm.toggleSelect(entry.packageName) },
                        onWhitelist = { vm.toggleWhitelist(entry.packageName) }
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private enum class AppFilter { ALL, CACHED, RUNNING }

@Composable
private fun CacheCard(totalCache: Long, hasUsage: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(R.drawable.ic_memory),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.cache_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (!hasUsage) {
                    Text(
                        stringResource(R.string.cache_needs_usage),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        stringResource(R.string.cache_stats, formatBytes(totalCache)),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    hasUsage: Boolean,
    a11y: Boolean,
    onGrantUsage: () -> Unit,
    onOpenA11y: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setup_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            SetupRow(
                done = hasUsage,
                text = stringResource(R.string.setup_usage),
                actionText = stringResource(R.string.grant),
                onAction = onGrantUsage
            )
            SetupRow(
                done = a11y,
                text = stringResource(R.string.setup_a11y),
                actionText = stringResource(R.string.open_settings),
                onAction = onOpenA11y
            )
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, text: String, actionText: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Text(text, modifier = Modifier.weight(1f), fontSize = 14.sp)
        if (!done) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun RunControls(
    running: Boolean,
    turbo: Boolean,
    selectedCount: Int,
    canRun: Boolean,
    onTurboChange: (Boolean) -> Unit,
    onClean: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.turbo_title),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.turbo_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = turbo, onCheckedChange = onTurboChange, enabled = !running)
            }
            if (running) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(painterResource(R.drawable.ic_stop), null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cancel_run))
                }
            } else {
                Button(
                    onClick = onClean,
                    enabled = canRun,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selectedCount > 0)
                            stringResource(R.string.clean_n_apps, selectedCount)
                        else
                            stringResource(R.string.clean_apps),
                        fontSize = 16.sp
                    )
                }
                if (selectedCount == 0) {
                    Text(
                        stringResource(R.string.select_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(summary: RunSummary, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (summary.cleaned == 1) stringResource(R.string.result_1_cleaned)
                        else stringResource(R.string.result_n_cleaned, summary.cleaned),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "in ${summary.durationMs / 1000}s" +
                            if (summary.turbo) " • Turbo mode" else "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                            .copy(alpha = 0.75f)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ResultStat(
                    value = "${summary.cleaned}",
                    label = stringResource(R.string.stat_cleaned),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                ResultStat(
                    value = "≈${formatBytes(summary.cacheFreedBytes)}",
                    label = stringResource(R.string.stat_cache_freed),
                    color = MaterialTheme.colorScheme.tertiary
                )
                ResultStat(
                    value = "${summary.durationMs / 1000}s",
                    label = stringResource(R.string.stat_time),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            if (summary.failed > 0 || summary.skipped > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${summary.failed} failed • ${summary.skipped} skipped",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                        .copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ResultStat(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = color,
            maxLines = 1
        )
        Text(
            label,
            fontSize = 12.sp,
            color = color.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun LogPanel(
    lines: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    persistentCount: Long,
    onDownload: () -> Unit,
    onShare: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.run_log, lines.size),
                        fontWeight = FontWeight.SemiBold
                    )
                    if (persistentCount > 0) {
                        Text(
                            "Saved log: $persistentCount lines (all sessions)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                    Text("Download log.json")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text("Share")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val tail = lines.takeLast(60)
                    for (line in tail) {
                        Text(
                            line,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    entry: AppEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    onWhitelist: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = entry.packageName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    statusText(entry),
                    fontSize = 12.sp,
                    color = if (entry.isRunning) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onWhitelist) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.whitelist_action),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun statusText(entry: AppEntry): String {
    val cache = if (entry.cacheBytes > 0)
        formatBytes(entry.cacheBytes)
    else
        stringResource(R.string.cache_none)
    val state = if (entry.isRunning)
        stringResource(R.string.status_running)
    else
        stringResource(R.string.status_not_running)
    return "$state • $cache"
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    var drawable by remember(packageName) { mutableStateOf<Drawable?>(null) }
    // Icons load lazily, off the UI thread, through the shared IconCache:
    // loading every installed app's icon eagerly at list time kept the home
    // screen on its spinner for minutes and ANR'd the CI emulator (runs
    // 94/95). Visible rows fill in as their icons arrive; the placeholder
    // shows until then.
    LaunchedEffect(packageName) {
        drawable = withContext(Dispatchers.IO) {
            IconCache.get(context.packageManager, packageName)
        }
    }
    val bitmap = remember(drawable) { drawable?.toBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowRight, null)
        }
    }
}

private fun Drawable.toBitmap(): Bitmap? {
    return try {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val w = intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = intrinsicHeight.takeIf { it > 0 } ?: 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(canvas)
        bmp
    } catch (_: Exception) {
        null
    }
}
