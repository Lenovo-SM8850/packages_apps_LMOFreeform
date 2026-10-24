package com.libremobileos.sidebar.ui.all_app

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.wm.shell.shared.animation.Interpolators
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.service.SidebarService
import kotlinx.coroutines.launch

@Composable
fun AllAppGridView(
    viewModel: AllAppViewModel,
    onClick: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appList by viewModel.appListFlow.collectAsState()
    val context = LocalContext.current
    val fromLeft = remember {
        context.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
            .getInt(SidebarService.SIDELINE_POSITION_X, 1) < 0
    }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val motion = tween<Float>(500, easing = Easing { Interpolators.EMPHASIZED.getInterpolation(it) })
    fun dismiss(after: () -> Unit = onDismiss) {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, motion)
            after()
            if (context is Activity && !context.isFinishing) {
                closing = false
                progress.animateTo(1f, motion)
            }
        }
    }
    LaunchedEffect(Unit) { progress.animateTo(1f, motion) }
    BackHandler { dismiss() }
    val panelColor = Color(context.getColor(if (isSystemInDarkTheme())
        android.R.color.system_accent2_800 else android.R.color.system_accent2_200))
    BoxWithConstraints(modifier = modifier) {
        val scrim = Color(context.getColor(com.android.internal.R.color.shade_panel_scrim))
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(scrim.copy(alpha = scrim.alpha * progress.value))
        }
            .clickable(onClickLabel = stringResource(android.R.string.cancel)) { dismiss() })
        val panelWidth = minOf(600.dp, maxWidth * 0.85f)
        Surface(
            color = panelColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = if (fromLeft) androidx.compose.foundation.shape.AbsoluteRoundedCornerShape(
                topRight = 26.dp, bottomRight = 26.dp)
                else androidx.compose.foundation.shape.AbsoluteRoundedCornerShape(
                    topLeft = 26.dp, bottomLeft = 26.dp),
            modifier = Modifier.align(if (fromLeft) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
                .width(panelWidth).fillMaxHeight().graphicsLayer {
                    translationX = (if (fromLeft) -1 else 1) * size.width * (1f - progress.value)
                }
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Vertical)).imePadding().padding(16.dp)) {
                Text(stringResource(R.string.sidebar_all_apps), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(appList, key = { "${it.packageName}/${it.activityName}:${it.userId}" }) { appInfo ->
                        AllAppGridItem(appInfo) { selected -> dismiss { onClick(selected) } }
                    }
                }
            }
        }
    }
}

@Composable
fun AllAppGridItem(appInfo: AppInfo, onClick: (AppInfo) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)
            .clickable { onClick(appInfo) }.padding(8.dp)
    ) {
        Image(
            painter = rememberDrawablePainter(appInfo.icon),
            contentDescription = appInfo.label,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = appInfo.label,
            maxLines = 2,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
