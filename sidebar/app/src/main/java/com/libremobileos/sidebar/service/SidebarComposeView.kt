package com.libremobileos.sidebar.service

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.libremobileos.sidebar.room.SmartClipboardEntity
import kotlin.math.roundToInt

private val SHADOW_INSET = 12.dp

@Composable
private fun SidebarAppContent(
    viewModel: ServiceViewModel,
    launchApp: (AppInfo) -> Unit,
    closeSidebar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
    
    // Get customization settings
    val columnCount = sharedPrefs.getInt("sidebar_columns", 1)
    val iconSize = sharedPrefs.getInt("sidebar_icon_size", 40)
    val iconPadding = sharedPrefs.getInt("sidebar_icon_padding", 7)
    val columnSpacing = sharedPrefs.getInt("sidebar_column_spacing", 4)
    val cornerRadius = sharedPrefs.getFloat("sidebar_corner_radius", 24f)
    val backgroundTransparency = sharedPrefs.getFloat("sidebar_background_transparency", 0.80f)
    val showShadow = sharedPrefs.getBoolean("sidebar_show_shadow", true)
    
    val sidebarAppList by viewModel.sidebarAppListFlow.collectAsState()
    
    // Create all items list
    val allItems = buildList {
        add(SidebarItem.AllApps(viewModel.allAppActivity))
        addAll(sidebarAppList.map { SidebarItem.App(it) })
        add(SidebarItem.Settings)
    }
    
    var cardModifier = modifier
    
    if (showShadow) {
        // Leave room around the card, otherwise the window bounds clip the shadow into a rectangle
        cardModifier = cardModifier.padding(SHADOW_INSET).shadow(
            elevation = 8.dp,
            shape = RoundedCornerShape(cornerRadius.dp)
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(
                alpha = backgroundTransparency
            )
        ),
        shape = RoundedCornerShape(cornerRadius.dp),
        modifier = cardModifier
    ) {
        if (columnCount == 1) {
            // Single column
            LazyColumn(
                contentPadding = PaddingValues(4.dp)
            ) {
                items(allItems) { item ->
                    SidebarItemView(
                        item = item,
                        iconSize = iconSize,
                        iconPadding = iconPadding,
                        viewModel = viewModel,
                        launchApp = launchApp,
                        closeSidebar = closeSidebar
                    )
                }
            }
        } else {
            // Multi-column
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(columnSpacing.dp),
                verticalArrangement = Arrangement.spacedBy(columnSpacing.dp),
                modifier = Modifier.width((
                    (iconSize + iconPadding * 2) * columnCount +
                    columnSpacing * (columnCount - 1) +
                    8
                ).dp)
            ) {
                items(allItems) { item ->
                    SidebarItemView(
                        item = item,
                        iconSize = iconSize,
                        iconPadding = iconPadding,
                        viewModel = viewModel,
                        launchApp = launchApp,
                        closeSidebar = closeSidebar
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarItemView(
    item: SidebarItem,
    iconSize: Int,
    iconPadding: Int,
    viewModel: ServiceViewModel,
    launchApp: (AppInfo) -> Unit,
    closeSidebar: () -> Unit
) {
    Box(
        modifier = Modifier.padding(iconPadding.dp),
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            is SidebarItem.AllApps -> {
                Icon(
                    imageVector = Icons.Outlined.Widgets,
                    contentDescription = item.appInfo.label,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size((iconSize - 4).dp)
                        .clickable {
                            launchApp(item.appInfo)
                        }
                )
            }
            is SidebarItem.App -> {
                Image(
                    painter = rememberDrawablePainter(item.appInfo.icon),
                    contentDescription = item.appInfo.label,
                    modifier = Modifier
                        .size(iconSize.dp)
                        .clickable {
                            launchApp(item.appInfo)
                        }
                )
            }
            is SidebarItem.Settings -> {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.sidebar_settings_description),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size((iconSize - 4).dp)
                        .clickable {
                            viewModel.openSidebarSettings()
                            closeSidebar()
                        }
                )
            }
        }
    }
}

private sealed class SidebarItem {
    data class AllApps(val appInfo: AppInfo) : SidebarItem()
    data class App(val appInfo: AppInfo) : SidebarItem()
    data object Settings : SidebarItem()
}

@Composable
fun SidebarComposeView(
    viewModel: ServiceViewModel,
    launchApp: (AppInfo) -> Unit,
    closeSidebar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled by viewModel.smartClipboardEnabledFlow.collectAsState()
    val items by viewModel.smartClipboardItemsFlow.collectAsState()
    var clipboardSelected by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val hostView = LocalView.current
    if (!enabled) {
        SidebarAppContent(viewModel, launchApp, closeSidebar, modifier)
        return
    }
    Column(modifier = modifier.widthIn(min = 240.dp, max = 360.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { clipboardSelected = false }) {
                Icon(Icons.Default.Apps, stringResource(R.string.sidebar_app_setting_label))
            }
            IconButton(onClick = { clipboardSelected = true }) {
                Icon(Icons.Default.ContentPaste, stringResource(R.string.smart_clipboard_label))
            }
            if (clipboardSelected) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(painterResource(R.drawable.smart_clipboard_delete_24), stringResource(R.string.smart_clipboard_clear_history))
                }
            }
        }
        if (clipboardSelected) {
            Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ClipboardListContent(items, viewModel, hostView)
            }
        } else {
            SidebarAppContent(viewModel, launchApp, closeSidebar, Modifier.weight(1f))
        }
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.smart_clipboard_clear_title)) },
            text = { Text(stringResource(R.string.smart_clipboard_clear_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearUnpinnedSmartClipboard(); showClearDialog = false }) {
                    Text(stringResource(R.string.smart_clipboard_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.smart_clipboard_cancel)) }
            }
        )
    }
}

@Composable
private fun ClipboardListContent(items: List<SmartClipboardEntity>, viewModel: ServiceViewModel, hostView: android.view.View) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val availableHeight = maxHeight

        if (items.isEmpty()) {
            Text(stringResource(R.string.smart_clipboard_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = availableHeight)
                    .wrapContentHeight(Alignment.CenterVertically)
            ) {
                items(items, key = { it.id }) { item ->
                    SwipeToDeleteContainer(onDelete = { viewModel.deleteSmartClipboardItem(item) }) {
                        ClipboardPreviewCard(item, viewModel, hostView)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeToDeleteContainer(onDelete: () -> Unit, content: @Composable () -> Unit) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX < -300f) {
                            onDelete()
                        } else {
                            offsetX = 0f
                        }
                    },
                    onDragCancel = { offsetX = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount).coerceAtMost(0f)
                }
            }
    ) {
        content()
    }
}

@Composable
private fun ClipboardPreviewCard(item: SmartClipboardEntity, viewModel: ServiceViewModel, hostView: android.view.View) {
    val imageBitmap by produceState<ImageBitmap?>(null, item.imagePath, item.id) {
        value = if (item.type == SmartClipboardEntity.TYPE_IMAGE) {
            item.imagePath?.let { path -> viewModel.loadPreviewBitmap(path, 320, 180) }
        } else null
    }
    val fileExists by produceState<Boolean?>(null, item.imagePath, item.id) {
        value = item.imagePath?.let { path ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.io.File(path).exists() }
        }
    }
    val isFileMissing = fileExists == false
    if (isFileMissing) {
        LaunchedEffect(item.id, item.imagePath) {
            kotlinx.coroutines.delay(500)
            viewModel.deleteMissingPreview(item)
        }
    }
    val timestamp = remember(item.createdAt) {
        DateUtils.getRelativeTimeSpanString(item.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val titleText = when (item.type) {
        SmartClipboardEntity.TYPE_TEXT -> item.text ?: ""
        else -> item.fileName ?: item.mimeType ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .pointerInput(item.id) { detectTapGestures(onLongPress = { viewModel.startSmartClipboardDrag(hostView, item) }) }
            .clickable { viewModel.copySmartClipboardItem(item) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 90.dp),
            contentAlignment = Alignment.Center
        ) {
            if (item.type == SmartClipboardEntity.TYPE_IMAGE && imageBitmap != null) {
                Image(bitmap = imageBitmap!!, contentDescription = stringResource(R.string.smart_clipboard_image_description), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                    Icon(
                        painterResource(if (item.type == SmartClipboardEntity.TYPE_FILE) R.drawable.smart_clipboard_file_24 else R.drawable.smart_clipboard_text_24),
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
                    )
                    if (titleText.isNotBlank()) {
                        Text(
                            text = if (isFileMissing) stringResource(R.string.smart_clipboard_missing_file) else titleText,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            if (item.isPinned) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_pin_24),
                    contentDescription = stringResource(R.string.smart_clipboard_pin_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                )
            }
        }
        Text(
            text = timestamp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.toggleSmartClipboardItemPinned(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_pin_24),
                    contentDescription = if (item.isPinned) stringResource(R.string.smart_clipboard_unpin_description) else stringResource(R.string.smart_clipboard_pin_description),
                    tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { viewModel.copySmartClipboardItem(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_copy_24),
                    contentDescription = stringResource(R.string.smart_clipboard_copy_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { viewModel.shareSmartClipboardItem(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_share_24),
                    contentDescription = stringResource(R.string.smart_clipboard_share_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { viewModel.deleteSmartClipboardItem(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.smart_clipboard_delete_24),
                    contentDescription = stringResource(R.string.smart_clipboard_delete_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
