package com.libremobileos.sidebar.service

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.room.SmartClipboardEntity
import kotlin.math.roundToInt

private val SHADOW_RADIUS = 8.dp

/** Width the clipboard sheet adds to the panel; the window grows by exactly this much. */
val CLIPBOARD_SHEET_WIDTH = 280.dp

// M3 expressive springs: spatial for the sheet width, effects for its alpha
private val clipboardSpatialSpring = spring<IntSize>(dampingRatio = 0.8f, stiffness = 380f)
private val clipboardEffectsSpring = spring<Float>(dampingRatio = 1f, stiffness = 1600f)

@Composable
fun SidebarComposeView(
    viewModel: ServiceViewModel,
    launchApp: (AppInfo) -> Unit,
    dragApp: (AppInfo, Int, android.graphics.Rect) -> Unit,
    closeSidebar: () -> Unit,
    clipboardExpanded: Boolean,
    setClipboardExpanded: (Boolean) -> Unit,
    onClipboardCollapsed: () -> Unit,
    panelOnRight: Boolean,
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
    val smartClipboardItems by viewModel.smartClipboardItemsFlow.collectAsState()
    val smartClipboardEnabled by viewModel.smartClipboardEnabledFlow.collectAsState()
    val hostView = LocalView.current
    
    // Create all items list
    val allItems = buildList {
        add(SidebarItem.AllApps(viewModel.allAppActivity))
        addAll(sidebarAppList.map { SidebarItem.App(it) })
        if (smartClipboardEnabled) add(SidebarItem.Clipboard)
        add(SidebarItem.Settings)
    }

    if (!smartClipboardEnabled && clipboardExpanded) {
        SideEffect { setClipboardExpanded(false) }
    }
    val sheetState = remember { MutableTransitionState(false) }
    val showSheet = clipboardExpanded && smartClipboardEnabled
    SideEffect { sheetState.targetState = showSheet }
    val collapsed by rememberUpdatedState(onClipboardCollapsed)
    LaunchedEffect(sheetState) {
        // Also fires at animator duration scale 0, where the transition settles at once
        snapshotFlow { sheetState.isIdle && !sheetState.currentState }
            .collect { idleCollapsed -> if (idleCollapsed) collapsed() }
    }

    var cardModifier = modifier
    
    if (showShadow) {
        // An elevation shadow assumes an opaque panel and shows through the translucent one, so draw
        // it outside the outline only. The padding keeps it inside the overlay window.
        val panelShape = RoundedCornerShape(cornerRadius.dp)
        cardModifier = cardModifier
            .padding(SHADOW_RADIUS)
            .drawWithCache {
                val outline = Path().apply {
                    addOutline(panelShape.createOutline(size, layoutDirection, this@drawWithCache))
                }
                val shadow = DropShadowPainter(
                    panelShape,
                    Shadow(radius = SHADOW_RADIUS, color = Color.Black.copy(alpha = 0.24f))
                )
                onDrawWithContent {
                    clipPath(outline, ClipOp.Difference) {
                        with(shadow) { draw(size) }
                    }
                    drawContent()
                }
            }
    }

    val iconColumn: @Composable () -> Unit = {
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
                        dragApp = dragApp,
                        closeSidebar = closeSidebar,
                        clipboardExpanded = showSheet,
                        setClipboardExpanded = setClipboardExpanded
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
                        dragApp = dragApp,
                        closeSidebar = closeSidebar,
                        clipboardExpanded = showSheet,
                        setClipboardExpanded = setClipboardExpanded
                    )
                }
            }
        }
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
        if (!smartClipboardEnabled) {
            iconColumn()
        } else {
            // The sheet opens toward the screen inside the same surface; the icon column stays at
            // the outer edge whatever the locale's layout direction is.
            val layoutDirection = LocalLayoutDirection.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    val sheet: @Composable () -> Unit = {
                        AnimatedVisibility(
                            visibleState = sheetState,
                            // Measured after the icon column, so the spring's overshoot cannot
                            // take width from the icons and shift them
                            modifier = Modifier.weight(1f, fill = false),
                            enter = expandHorizontally(
                                clipboardSpatialSpring,
                                if (panelOnRight) Alignment.End else Alignment.Start
                            ) + fadeIn(clipboardEffectsSpring),
                            exit = shrinkHorizontally(
                                clipboardSpatialSpring,
                                if (panelOnRight) Alignment.End else Alignment.Start
                            ) + fadeOut(clipboardEffectsSpring)
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                                SmartClipboardSheet(smartClipboardItems, viewModel, hostView)
                            }
                        }
                    }
                    if (panelOnRight) sheet()
                    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                        iconColumn()
                    }
                    if (!panelOnRight) sheet()
                }
            }
        }
    }
}

@Composable
private fun SmartClipboardSheet(
    smartClipboardItems: List<SmartClipboardEntity>,
    viewModel: ServiceViewModel,
    hostView: android.view.View
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier
            .width(CLIPBOARD_SHEET_WIDTH)
            .fillMaxHeight()
    ) {
        item {
            Text(
                text = stringResource(R.string.smart_clipboard_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp)
            )
        }
        if (smartClipboardItems.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.smart_clipboard_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        } else {
            items(smartClipboardItems, key = { it.id }) { item ->
                SmartClipboardListItem(
                    item = item,
                    onCopy = { viewModel.copySmartClipboardItem(item) },
                    onShare = { viewModel.shareSmartClipboardItem(item) },
                    onDelete = { viewModel.deleteSmartClipboardItem(item) },
                    onStartDrag = { viewModel.startSmartClipboardDrag(hostView, item) },
                    onPin = { viewModel.toggleSmartClipboardItemPinned(item) }
                )
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
    dragApp: (AppInfo, Int, android.graphics.Rect) -> Unit,
    closeSidebar: () -> Unit,
    clipboardExpanded: Boolean,
    setClipboardExpanded: (Boolean) -> Unit
) {
    val host = LocalView.current
    var dragBounds by remember { mutableStateOf(android.graphics.Rect()) }
    val iconSizePx = with(LocalDensity.current) { iconSize.dp.roundToPx() }
    Box(
        modifier = Modifier.padding(iconPadding.dp),
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            is SidebarItem.AllApps -> {
                // The sidebar panel is already the tonal surface; a second circle reads as a misplaced chip
                IconButton(
                    onClick = { launchApp(item.appInfo) },
                    modifier = Modifier.size(iconSize.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_taskbar_all_apps),
                        contentDescription = stringResource(R.string.sidebar_all_apps),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size((iconSize - 4).dp)
                    )
                }
            }
            is SidebarItem.App -> {
                Image(
                    painter = rememberDrawablePainter(item.appInfo.icon),
                    contentDescription = item.appInfo.label,
                    modifier = Modifier
                        .size(iconSize.dp)
                        .onGloballyPositioned { coordinates ->
                            val position = IntArray(2)
                            host.getLocationOnScreen(position)
                            val bounds = coordinates.boundsInRoot()
                            dragBounds = android.graphics.Rect(
                                position[0] + bounds.left.roundToInt(), position[1] + bounds.top.roundToInt(),
                                position[0] + bounds.right.roundToInt(), position[1] + bounds.bottom.roundToInt())
                        }
                        .combinedClickable(
                            onClick = { launchApp(item.appInfo) },
                            onLongClick = { dragApp(item.appInfo, iconSizePx, dragBounds) }
                        )
                )
            }
            is SidebarItem.Clipboard -> {
                IconToggleButton(
                    checked = clipboardExpanded,
                    onCheckedChange = setClipboardExpanded,
                    modifier = Modifier.size(iconSize.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.materialsymbols_ic_content_paste_rounded_filled),
                        contentDescription = stringResource(R.string.smart_clipboard_label),
                        tint = if (clipboardExpanded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(((iconSize - 4) * 0.65f).dp)
                    )
                }
            }
            is SidebarItem.Settings -> {
                IconButton(
                    onClick = {
                        viewModel.openSidebarSettings()
                        closeSidebar()
                    },
                    modifier = Modifier.size(iconSize.dp)
                ) {
                    // Same optical size as the all-apps glyph, which fills ~56% of its 48-unit box
                    Icon(
                        painter = painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled),
                        contentDescription = stringResource(R.string.sidebar_settings_description),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(((iconSize - 4) * 0.65f).dp)
                    )
                }
            }
        }
    }
}

private sealed class SidebarItem {
    data class AllApps(val appInfo: AppInfo) : SidebarItem()
    data class App(val appInfo: AppInfo) : SidebarItem()
    data object Clipboard : SidebarItem()
    data object Settings : SidebarItem()
}

@Composable
private fun SmartClipboardListItem(
    item: SmartClipboardEntity,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onStartDrag: () -> Boolean,
    onPin: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartClipboardPreview(
                item = item,
                onLongPress = onStartDrag
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = when (item.type) {
                        SmartClipboardEntity.TYPE_IMAGE -> stringResource(R.string.smart_clipboard_image)
                        else -> item.text.orEmpty()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = remember(item.createdAt) {
                        DateUtils.getRelativeTimeSpanString(
                            item.createdAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmartClipboardAction(
                        iconRes = R.drawable.smart_clipboard_copy_24,
                        contentDescription = stringResource(R.string.smart_clipboard_copy_description),
                        onClick = onCopy
                    )
                    SmartClipboardAction(
                        iconRes = R.drawable.smart_clipboard_share_24,
                        contentDescription = stringResource(R.string.smart_clipboard_share_description),
                        onClick = onShare
                    )
                    SmartClipboardAction(
                        iconRes = R.drawable.smart_clipboard_delete_24,
                        contentDescription = stringResource(R.string.smart_clipboard_delete_description),
                        onClick = onDelete
                    )
                    SmartClipboardAction(
                        iconRes = R.drawable.smart_clipboard_pin_24,
                        contentDescription = stringResource(R.string.smart_clipboard_pin_description),
                        onClick = onPin,
                        tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartClipboardPreview(
    item: SmartClipboardEntity,
    onLongPress: () -> Boolean
) {
    val imageBitmap = remember(item.imagePath) {
        item.imagePath?.let {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 4
            }
            BitmapFactory.decodeFile(it, opts)?.asImageBitmap()
        }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .size(64.dp)
            .pointerInput(item.id) {
                detectTapGestures(
                    onLongPress = {
                        onLongPress()
                    }
                )
            }
    ) {
        if (item.type == SmartClipboardEntity.TYPE_IMAGE && imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = stringResource(R.string.smart_clipboard_image_description),
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                painter = painterResource(
                    if (item.type == SmartClipboardEntity.TYPE_IMAGE) {
                        R.drawable.smart_clipboard_image_24
                    } else if (item.type == SmartClipboardEntity.TYPE_FILE) {
                        R.drawable.smart_clipboard_file_24
                    } else {
                        R.drawable.smart_clipboard_text_24
                    }
                ),
                contentDescription = if (item.type == SmartClipboardEntity.TYPE_IMAGE) {
                    stringResource(R.string.smart_clipboard_image_description)
                } else if (item.type == SmartClipboardEntity.TYPE_FILE) {
                    stringResource(R.string.smart_clipboard_file_description)
                } else {
                    stringResource(R.string.smart_clipboard_text_description)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(32.dp)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun SmartClipboardAction(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint
        )
    }
}
