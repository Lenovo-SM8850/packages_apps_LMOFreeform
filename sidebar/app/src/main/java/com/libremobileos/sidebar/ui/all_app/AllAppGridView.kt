package com.libremobileos.sidebar.ui.all_app

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.android.wm.shell.shared.search.AppLabelSearch
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onClick: (AppInfo, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appList by viewModel.appListFlow.collectAsState()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val matcher = remember(locale) { AppLabelSearch(locale) }
    val filtered = remember(appList, query, locale) { appList.filter { matcher.matches(it.label, query) } }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val fromLeft = remember {
        context.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
            .getInt(SidebarService.SIDELINE_POSITION_X, 1) < 0
    }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val motion = tween<Float>(500, easing = Easing { Interpolators.EMPHASIZED.getInterpolation(it) })
    fun dismiss(restoreImmediately: Boolean = true, after: () -> Unit = onDismiss) {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, motion)
            after()
            if (restoreImmediately && context is Activity && !context.isFinishing) {
                closing = false
                progress.animateTo(1f, motion)
            }
        }
    }
    LaunchedEffect(Unit) { progress.animateTo(1f, motion) }
    BackHandler { dismiss() }
    BackHandler(enabled = !closing && (searchFocused || query.isNotEmpty() || imeVisible)) {
        if (imeVisible) keyboard?.hide()
        else {
            query = ""
            focus.clearFocus()
        }
    }
    // Side sheet container, exposed corners on the screen side only
    val corner = MaterialTheme.shapes.extraLarge.topStart
    val sheetShape = AbsoluteRoundedCornerShape(
        topLeft = if (fromLeft) ZeroCornerSize else corner,
        topRight = if (fromLeft) corner else ZeroCornerSize,
        bottomRight = if (fromLeft) corner else ZeroCornerSize,
        bottomLeft = if (fromLeft) ZeroCornerSize else corner)
    BoxWithConstraints(modifier = modifier) {
        val scrim = Color(context.getColor(com.android.internal.R.color.shade_panel_scrim))
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(scrim.copy(alpha = scrim.alpha * progress.value))
        }
            .clickable(onClickLabel = stringResource(android.R.string.cancel)) { dismiss() })
        val panelWidth = minOf(600.dp, maxWidth * 0.85f)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = sheetShape,
            modifier = Modifier.align(if (fromLeft) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
                .width(panelWidth).fillMaxHeight().graphicsLayer {
                    translationX = (if (fromLeft) -1 else 1) * size.width * (1f - progress.value)
                }
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Vertical)).imePadding().padding(16.dp)) {
                Text(stringResource(R.string.sidebar_all_apps), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.sidebar_app_search)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.materialsymbols_ic_search_rounded_filled), null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(painterResource(R.drawable.materialsymbols_ic_close_rounded_filled),
                                stringResource(R.string.sidebar_app_search_clear))
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused }
                )
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.sidebar_app_search_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filtered, key = { "${it.packageName}/${it.activityName}:${it.userId}" }) { appInfo ->
                        AllAppGridItem(appInfo) { selected -> dismiss(restoreImmediately = false) {
                            onClick(selected) {
                                closing = false
                                scope.launch { progress.animateTo(1f, motion) }
                            }
                        } }
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
            .clip(MaterialTheme.shapes.large)
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
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
