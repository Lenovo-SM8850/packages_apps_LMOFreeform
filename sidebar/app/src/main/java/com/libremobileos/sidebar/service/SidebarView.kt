package com.libremobileos.sidebar.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.SurfaceControl
import android.graphics.Rect
import android.graphics.RectF
import com.android.wm.shell.shared.animation.Interpolators
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import com.android.wm.shell.shared.sidebar.SidebarLaunchPolicy.ACTION_QUERY_HOME
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Canvas
import android.graphics.Point
import android.os.PersistableBundle
import com.android.wm.shell.shared.draganddrop.DragAndDropConstants.EXTRA_DISALLOW_HIT_REGION
import com.android.wm.shell.shared.draganddrop.DragAndDropConstants.EXTRA_SIDEBAR_APP_DRAG
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.Logger
import com.libremobileos.sidebar.utils.canLaunchInFloatingWindow
import kotlin.math.roundToInt

/**
 * @author KindBrave
 * @since 2023/9/26
 */
class SidebarView(
    private val context: Context,
    private val viewModel: ServiceViewModel,
    private val callback: Callback
) : SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val composeView: View
    private var sidebarPositionX = 0
    private var sidebarPositionY = 0
    private var isShowing = false
    private var isRemoved = false
    private var appDragInProgress = false
    private var appDragBounds = Rect()
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutParams = LayoutParams()
    private val logger = Logger(TAG)
    private val handler = Handler(Looper.getMainLooper())

    private val sharedPrefs by lazy {
        context.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
    }

    companion object {
        private const val OFFSET_X = 90
        private const val TAG = "SidebarView"
    }

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        composeView = object : AbstractComposeView(context) {
            private val backCallback = OnBackInvokedCallback {
                logger.d("onBackInvoked")
                removeView()
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
                val dispatcher = findOnBackInvokedDispatcher()
                if (dispatcher == null) {
                    logger.e("no OnBackInvokedDispatcher after attach")
                    return
                }
                dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    backCallback
                )
            }

            override fun onDetachedFromWindow() {
                findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(backCallback)
                super.onDetachedFromWindow()
            }

            override fun dispatchDragEvent(event: DragEvent): Boolean {
                val handled = super.dispatchDragEvent(event)
                if (event.action == DragEvent.ACTION_DRAG_ENDED && appDragInProgress) {
                    setPanelOnlyDragTarget(false)
                    val surface = event.dragSurface
                    if (!event.result && surface != null && surface.isValid) {
                        animateReturnedDrag(event, surface)
                    } else {
                        appDragInProgress = false
                        removeView()
                    }
                }
                return handled
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        logger.d("KEYCODE_BACK")
                        removeView()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            @Composable
            override fun Content() {
                SidebarTheme {
                    SidebarComposeView(
                        viewModel = viewModel,
                        launchApp = { launchAppInFloatingWindow(it) },
                        dragApp = { app, size, bounds -> startAppDrag(app, size, bounds) },
                        closeSidebar = { removeView() },
                        modifier = Modifier
                            .fillMaxHeight()
                            .wrapContentWidth()
                    )
                }
            }
        }.apply {
            // The root is focusable to receive back and outside touches. Without this, the default
            // focus highlight paints a translucent rectangle over the whole window when it has focus
            defaultFocusHighlightEnabled = false
            setViewTreeLifecycleOwner(this@SidebarView)
            setViewTreeSavedStateRegistryOwner(this@SidebarView)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    private fun launchAppInFloatingWindow(appInfo: AppInfo) {
        val component = ComponentName(appInfo.packageName, appInfo.activityName)
        if (component == ComponentName(context,
                com.libremobileos.sidebar.ui.all_app.AllAppActivity::class.java)) {
            launchApp(appInfo, fullscreen = true)
            return
        }
        // Shell also knows overview visibility, which a HOME activity type alone cannot distinguish.
        val query = Intent(ACTION_QUERY_HOME).setPackage("com.android.systemui")
            .putExtra(com.android.wm.shell.shared.sidebar.SidebarLaunchPolicy.EXTRA_DISPLAY_ID, composeView.display?.displayId ?: context.displayId)
            .putExtra(Intent.EXTRA_INTENT, Intent.makeMainActivity(component))
            .putExtra(Intent.EXTRA_USER, UserHandle.of(appInfo.userId))
        context.sendOrderedBroadcastAsUser(query, UserHandle.SYSTEM,
            android.Manifest.permission.INTERNAL_SYSTEM_WINDOW,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (isRemoved) return
                    if (resultCode == Activity.RESULT_FIRST_USER) removeView()
                    else launchApp(appInfo, resultCode == Activity.RESULT_OK)
                }
            }, handler, Activity.RESULT_CANCELED, null, null)
    }

    private fun launchApp(appInfo: AppInfo, fullscreen: Boolean) {
        val componentName = ComponentName(appInfo.packageName, appInfo.activityName)
        val drawer = componentName == ComponentName(context,
            com.libremobileos.sidebar.ui.all_app.AllAppActivity::class.java)
        if (!fullscreen && !drawer && !context.canLaunchInFloatingWindow(componentName, appInfo.userId)) {
            Toast.makeText(context, R.string.floating_window_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = componentName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val options = ActivityOptions.makeBasic().apply {
            setLaunchFloatingWindow(!fullscreen && !drawer)
            if (fullscreen || drawer) setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN)
        }
        runCatching {
            context.startActivityAsUser(intent, options.toBundle(), UserHandle.of(appInfo.userId))
        }.onFailure {
            logger.e("startApp failed", it)
        }
        removeView()
    }

    private fun startAppDrag(appInfo: AppInfo, iconSize: Int, sourceBounds: Rect) {
        if (appDragInProgress || isRemoved) return
        val componentName = ComponentName(appInfo.packageName, appInfo.activityName)
        if (!context.canLaunchInFloatingWindow(componentName, appInfo.userId)) {
            Toast.makeText(context, R.string.floating_window_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val user = UserHandle.of(appInfo.userId)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = componentName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivityAsUser(
            context, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE, null, user
        )
        val description = ClipDescription(
            appInfo.label, arrayOf(ClipDescription.MIMETYPE_APPLICATION_ACTIVITY)
        ).apply {
            extras = PersistableBundle().apply { putBoolean(EXTRA_SIDEBAR_APP_DRAG, true) }
        }
        // The sidebar stays visible during the drag; dropping back onto it cancels, like the taskbar
        val location = IntArray(2).also { composeView.getLocationOnScreen(it) }
        val sidebarRegion = RectF(location[0].toFloat(), location[1].toFloat(),
            (location[0] + composeView.width).toFloat(), (location[1] + composeView.height).toFloat())
        val data = ClipData(description, ClipData.Item(Intent().apply {
            putExtra(ClipDescription.EXTRA_PENDING_INTENT, pendingIntent)
            putExtra(Intent.EXTRA_USER, user)
            putExtra(EXTRA_DISALLOW_HIT_REGION, sidebarRegion)
        }))
        val icon = appInfo.icon.constantState?.newDrawable()?.mutate() ?: appInfo.icon
        val shadow = object : View.DragShadowBuilder() {
            override fun onProvideShadowMetrics(size: Point, touch: Point) {
                size.set(iconSize, iconSize)
                touch.set(iconSize / 2, iconSize)
            }
            override fun onDrawShadow(canvas: Canvas) {
                val oldBounds = android.graphics.Rect(icon.bounds)
                icon.setBounds(0, 0, iconSize, iconSize)
                icon.draw(canvas)
                icon.bounds = oldBounds
            }
        }
        appDragBounds.set(sourceBounds)
        appDragInProgress = true
        setPanelOnlyDragTarget(true)
        val started = runCatching {
            composeView.startDragAndDrop(data, shadow, this, View.DRAG_FLAG_GLOBAL or
                View.DRAG_FLAG_OPAQUE or View.DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION)
        }.onFailure { logger.e("startApp drag failed", it) }.getOrDefault(false)
        if (!started) {
            appDragInProgress = false
            setPanelOnlyDragTarget(false)
        }
    }

    /**
     * A touch-modal window receives drag events over the whole screen, which would keep them from
     * the Shell drop targets. While our app drag runs, only the panel itself takes them.
     */
    private fun setPanelOnlyDragTarget(panelOnly: Boolean) {
        val flags = if (panelOnly) layoutParams.flags or LayoutParams.FLAG_NOT_TOUCH_MODAL
            else layoutParams.flags and LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        if (flags == layoutParams.flags || !composeView.isAttachedToWindow) return
        layoutParams.flags = flags
        runCatching { windowManager.updateViewLayout(composeView, layoutParams) }
            .onFailure { logger.e("failed to update drag target flags", it) }
    }

    private fun animateReturnedDrag(event: DragEvent, surface: SurfaceControl) {
        val fromX = event.x - event.offsetX
        val fromY = event.y - event.offsetY
        val transaction = SurfaceControl.Transaction()
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            try {
                if (surface.isValid) transaction.remove(surface).apply()
            } finally {
                transaction.close()
                surface.release()
                // A refused drop is a cancel: the icon returns and the sidebar stays open
                appDragInProgress = false
            }
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = Interpolators.FAST_OUT_SLOW_IN
            addUpdateListener {
                val t = it.animatedFraction
                if (surface.isValid) transaction
                    .setPosition(surface, fromX + (appDragBounds.left - fromX) * t,
                        fromY + (appDragBounds.top - fromY) * t)
                    .setAlpha(surface, 1f - t * t * t * t).apply()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
                override fun onAnimationCancel(animation: Animator) = finish()
            })
            start()
        }
    }

    private fun hideForAppDrag() {
        // Keep the source window attached until it receives DRAG_ENDED.
        composeView.animate().cancel()
        composeView.alpha = 0f
        layoutParams.flags = layoutParams.flags or LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(composeView, layoutParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showView() {
        if (isShowing || isRemoved) return

        layoutParams.apply {
            type = LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_HARDWARE_ACCELERATED
            privateFlags = LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                    LayoutParams.PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }

        updateSidebarPosition()
        isShowing = true

        composeView.translationX = sidebarPositionX * 1.0f * 200
        composeView.setOnTouchListener { view, event ->
            logger.d("composeView: $event")
            if (event.action == MotionEvent.ACTION_UP) {
                removeView()
                true
            }
            false
        }

        composeView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    appDragInProgress || viewModel.supportsSmartClipboardContent(event.clipDescription)
                }
                DragEvent.ACTION_DROP -> {
                    // Our own app drag dropped back onto the sidebar is a cancel, not clipboard content
                    if (appDragInProgress) return@setOnDragListener false
                    if (!viewModel.supportsSmartClipboardContent(event.clipDescription)) {
                        return@setOnDragListener false
                    }
                    if (!viewModel.importClipboardDrop(event)) return@setOnDragListener false
                    true
                }
                DragEvent.ACTION_DRAG_ENTERED,
                DragEvent.ACTION_DRAG_LOCATION,
                DragEvent.ACTION_DRAG_EXITED,
                DragEvent.ACTION_DRAG_ENDED -> true
                else -> false
            }
        }

        handler.post {
            if (isRemoved) return@post
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) // this also emits ON_START
            runCatching {
                windowManager.addView(composeView, layoutParams)
                composeView.animate().translationX(0f).setDuration(300).start()
            }.onFailure {
                logger.e("wm.addView failed, bail out", it)
                removeView()
            }
        }
    }

    fun removeView() {
        if (isRemoved) return
        if (appDragInProgress) {
            hideForAppDrag()
            return
        }

        logger.d("removeView")
        isRemoved = true
        isShowing = false
        handler.post {
            runCatching {
                windowManager.removeViewImmediate(composeView)
            }.onFailure {
                logger.e("wm.removeViewImmediate failed", it)
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            callback.onRemove()
        }
    }

    fun updateSidebarPosition() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val sidebarHeight = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            screenHeight / 3
        } else {
            (screenHeight * 0.8f).roundToInt()
        }

        sidebarPositionX = sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_X, 1)
        sidebarPositionY = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_Y_PORTRAIT, -screenHeight / 6)
        } else {
            0
        }

        layoutParams.apply {
            width = LayoutParams.WRAP_CONTENT
            height = sidebarHeight
            x = sidebarPositionX * (screenWidth / 2 - OFFSET_X)
            y = sidebarPositionY
        }

        logger.d("updateSidebarPosition: posX=$sidebarPositionX posY=$sidebarPositionY" +
                " lp.x=${layoutParams.x} lp.y=${layoutParams.y} height=${layoutParams.height}")

        if (isShowing) {
            handler.post {
                runCatching {
                    windowManager.updateViewLayout(composeView, layoutParams)
                }.onFailure { e ->
                    logger.e("failed to updateViewLayout: ${e.message}")
                }
            }
        }
    }

    interface Callback {
        fun onRemove()
    }
}
