package com.libremobileos.freeform.server.ui

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import android.util.Slog
import android.view.Choreographer
import android.view.Display
import android.view.DisplayInfo
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.InsetsFrameProvider
import android.view.IRotationWatcher
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.policy.SystemBarUtils
import com.android.server.LocalServices
import com.android.server.ServiceThread
import android.view.animation.OvershootInterpolator
import com.android.internal.inputmethod.SoftInputShowHideReason
import com.android.server.inputmethod.InputMethodManagerInternal
import com.android.server.wm.WindowManagerInternal
import com.libremobileos.freeform.ILMOFreeformDisplayCallback
import com.libremobileos.freeform.server.util.Debug.dlog
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import com.libremobileos.freeform.server.util.dpToPx
import com.libremobileos.freeform.server.ui.gesture.FreeformGestureRouter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FreeformWindow(
    private val handler: Handler,
    val context: Context,
    private val appConfig: AppConfig,
    val freeformConfig: FreeformConfig
): TextureView.SurfaceTextureListener, ILMOFreeformDisplayCallback.Stub(), View.OnTouchListener,
    WindowManagerInternal.DisplaySecureContentListener {

    var freeformTaskStackListener: FreeformTaskStackListener? = null
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val windowManagerInt = LocalServices.getService(WindowManagerInternal::class.java)
    val windowParams = WindowManager.LayoutParams()
    private val resourceHolder = RemoteResourceHolder(context, FREEFORM_PACKAGE)
    lateinit var freeformLayout: ViewGroup
    lateinit var freeformRootView: ViewGroup
    lateinit var freeformView: FreeformTextureView
    private lateinit var topBarView: View
    private lateinit var minimizedIconContainer: View
    private lateinit var minimizedIconImage: ImageView
    lateinit var veilView: ViewGroup
    private var bottomBarView: View? = null
    private var optionsMenuView: View? = null
    private var optionsScrimView: View? = null
    private var optionsIcon: ImageView? = null
    private var navPillView: View? = null
    private var bubbleView: ImageView? = null
    private var leftScaleView: View? = null
    private var rightScaleView: View? = null
    private var pillScaleView: View? = null
    private var chromeBottomTint: ColorStateList? = null
    private var displayWm: WindowManager? = null
    private var chromeSampled = false
    private var chromeSampleAttempts = 0
    private var destroyed = false
    private var displaySurfaceTexture: SurfaceTexture? = null
    private var topInsetView: View? = null
    private var bottomInsetView: View? = null
    private val insetsOwner = Binder()
    private var displayId = Display.INVALID_DISPLAY
    private var gestureRouter: FreeformGestureRouter? = null

    private fun router(): FreeformGestureRouter {
        var r = gestureRouter
        if (r == null) {
            r = FreeformGestureRouter(context, object : FreeformGestureRouter.Callbacks {
                override fun surfaceWidth(): Int =
                    if (::freeformView.isInitialized) freeformView.width else 0
                override fun isAlive(): Boolean = !destroyed && displayId != Display.INVALID_DISPLAY
                override fun onDownFocus() = focusFreeformTask()
                override fun forward(event: MotionEvent) = forwardTouch(event)
                override fun fireBack() = LMOFreeformServiceHolder.back(displayId)
                override fun haptic(feedbackConstant: Int) {
                    runCatching {
                        if (::freeformView.isInitialized) freeformView.performHapticFeedback(feedbackConstant)
                    }
                }
            })
            gestureRouter = r
        }
        return r
    }
    var defaultDisplayWidth = context.resources.displayMetrics.widthPixels
    var defaultDisplayHeight = context.resources.displayMetrics.heightPixels
    private val defaultDisplayInfo = DisplayInfo()
    private var isInitialized = false
    private val destroyRunnable = Runnable { destroy("destroyRunnable", true) }
    private val releaseDisplayRunnable = Runnable {
        LMOFreeformServiceHolder.releaseFreeform(this@FreeformWindow)
        displaySurfaceTexture?.release()
        displaySurfaceTexture = null
        workerThread.quitSafely()
    }
    /**
     * For calls into the activity task manager and the display manager: those are synchronous and
     * can block for seconds while the window manager lock is held. Running them on [handler] would
     * stop us from reading this window's input channel, which ANRs the window.
     */
    private val workerThread =
        ServiceThread("FreeformWindow", Process.THREAD_PRIORITY_DISPLAY, false /*allowIo*/)
            .apply { start() }
    private val workerHandler = workerThread.threadHandler
    
    private var userResized = false

    private var pendingX = 0
    private var pendingY = 0
    private var framePending = false
    private var geometryAnimator: Animator? = null

    private val frameCallback = Choreographer.FrameCallback {
        framePending = false
        if (destroyed) return@FrameCallback
        runCatching {
            windowParams.apply { x = pendingX; y = pendingY }
            updateWindowLayout()
        }.onFailure { Slog.w(TAG, "updateViewLayout: $it") }
    }

    fun requestMove(x: Int, y: Int) {
        pendingX = x; pendingY = y
        if (framePending) return
        framePending = true
        runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }
            .onFailure {
                framePending = false
                runCatching {
                    windowParams.apply { this.x = pendingX; this.y = pendingY }
                    updateWindowLayout()
                }
            }
    }

    fun cancelGeometryAnimations() { geometryAnimator?.cancel(); geometryAnimator = null }

    fun animateTo(x: Int? = null, y: Int? = null, dur: Long = 200) {
        if (!::freeformLayout.isInitialized || destroyed) return
        cancelGeometryAnimations()
        val startX = windowParams.x
        val startY = windowParams.y
        val endX = x ?: startX
        val endY = y ?: startY
        if (startX == endX && startY == endY) return
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            addUpdateListener {
                val f = it.animatedValue as Float
                runCatching {
                    windowParams.apply {
                        this.x = (startX + (endX - startX) * f).roundToInt()
                        this.y = (startY + (endY - startY) * f).roundToInt()
                    }
                    updateWindowLayout()
                }.onFailure { e -> Slog.w(TAG, "animateTo failed: $e") }
            }
        }
        geometryAnimator = animator
        animator.start()
    }

    private fun minWidthPx(): Int = (140 * context.resources.displayMetrics.density).roundToInt()
    private fun minHeightPx(): Int = (200 * context.resources.displayMetrics.density).roundToInt()
    private fun maxWidthPx(): Int = (defaultDisplayWidth * 0.8).roundToInt()
    private fun maxHeightPx(): Int = (defaultDisplayHeight * 0.8).roundToInt()

    fun requestResize(w: Int, h: Int) {
        if (!::freeformRootView.isInitialized || destroyed) return
        val clampedW = w.coerceIn(minWidthPx(), defaultDisplayWidth)
        val clampedH = h.coerceIn(minHeightPx(), defaultDisplayHeight)
        val lp = freeformRootView.layoutParams ?: return
        if (lp.width == clampedW && lp.height == clampedH) return
        freeformRootView.layoutParams = lp.apply { width = clampedW; height = clampedH }
    }

    fun animateResizeTo(w: Int, h: Int, dur: Long = RESIZE_BOUNCE_DUR) {
        if (!::freeformRootView.isInitialized || !::freeformLayout.isInitialized || destroyed) return
        cancelGeometryAnimations()
        val lp = freeformRootView.layoutParams ?: return
        val startW = if (lp.width > 0) lp.width else freeformConfig.width
        val startH = if (lp.height > 0) lp.height else freeformConfig.height
        if (startW == w && startH == h) {
            commitGeometry(w, h)
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = OvershootInterpolator(1.0f)
            addUpdateListener {
                val f = it.animatedValue as Float
                runCatching {
                    freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                        width = (startW + (w - startW) * f).roundToInt()
                        height = (startH + (h - startH) * f).roundToInt()
                    }
                }.onFailure { e -> Slog.w(TAG, "animateResizeTo failed: $e") }
            }
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                if (geometryAnimator === animator) geometryAnimator = null
                commitGeometry(w, h)
            }
            override fun onAnimationCancel(animation: Animator) {
                if (geometryAnimator === animator) geometryAnimator = null
            }
            override fun onAnimationRepeat(animation: Animator) {}
        })
        geometryAnimator = animator
        animator.start()
    }

    fun isNearNavbar(rawY: Float): Boolean =
        rawY >= defaultDisplayHeight - dpToPx(NAVBAR_ZONE_DP)

    fun setResizeHighlight(active: Boolean) {
        val bg = if (active) 0x44FFFFFF else Color.TRANSPARENT
        leftScaleView?.setBackgroundColor(bg)
        rightScaleView?.setBackgroundColor(bg)
        pillScaleView?.setBackgroundColor(bg)
        if (active) {
            navPillView?.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        } else {
            chromeBottomTint?.let { navPillView?.backgroundTintList = it }
        }
    }

    fun commitGeometry(w: Int, h: Int, enforceMax: Boolean = true) {
        if (destroyed) return
        val clampedW = if (enforceMax) w.coerceIn(minWidthPx(), maxWidthPx())
            else w.coerceAtLeast(minWidthPx())
        val clampedH = if (enforceMax) h.coerceIn(minHeightPx(), maxHeightPx())
            else h.coerceAtLeast(minHeightPx())
        freeformConfig.width = clampedW
        freeformConfig.height = clampedH
        userResized = true
        measureScale()
        if (::freeformRootView.isInitialized) {
            runCatching {
                freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                    width = clampedW; height = clampedH
                }
            }.onFailure { Slog.w(TAG, "commitGeometry layout failed: $it") }
        }
        runCatching {
            LMOFreeformServiceHolder.resizeFreeform(
                this, freeformConfig.freeformWidth, freeformConfig.freeformHeight, freeformConfig.densityDpi)
        }.onFailure { Slog.w(TAG, "commitGeometry resizeFreeform failed: $it") }
        if (::freeformView.isInitialized) {
            runCatching {
                freeformView.surfaceTexture?.setDefaultBufferSize(
                    freeformConfig.freeformWidth, freeformConfig.freeformHeight)
            }.onFailure { Slog.w(TAG, "commitGeometry buffer failed: $it") }
        }
        updateDisplayInsets()
        handler.post { updateSystemGestureExclusion() }
    }

    fun commitResize() {
        if (!::freeformRootView.isInitialized) return
        val lp = freeformRootView.layoutParams ?: return
        val targetW = lp.width.coerceIn(minWidthPx(), maxWidthPx())
        val targetH = lp.height.coerceIn(minHeightPx(), maxHeightPx())
        if (lp.width != targetW || lp.height != targetH) {
            animateResizeTo(targetW, targetH)
            persistGeometry(targetW, targetH)
        } else {
            commitGeometry(lp.width, lp.height)
            persistGeometry(lp.width, lp.height)
        }
        handler.post { clampPositionAnimated() }
    }

    private fun persistGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (appConfig.userId < 0) return
        runCatching {
            val intent = Intent("com.libremobileos.freeform.SAVE_GEOMETRY").apply {
                setPackage(FREEFORM_PACKAGE)
                putExtra("packageName", appConfig.packageName)
                putExtra("activityName", appConfig.activityName)
                putExtra("width", w)
                putExtra("height", h)
            }
            context.sendBroadcastAsUser(intent, UserHandle.of(appConfig.userId))
            Slog.i(TAG, "persistGeometry ${appConfig.packageName}/${appConfig.activityName} ${w}x$h")
        }.onFailure { Slog.w(TAG, "persistGeometry failed: $it") }
    }

    private val rotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            dlog(TAG, "onRotationChanged($rotation)")
            updateDefaultDisplaySize()
            val generation = ++rotationGeneration
            if (followsDisplayOrientation) {
                appIsLandscape = isDisplayLandscape()
            }
            resizeForOrientationChange()
            if (freeformConfig.isHangUp) {
                FreeformWindowManager.runWhenHostMatchesSize(
                    defaultDisplayWidth,
                    defaultDisplayHeight
                ) {
                    handler.post {
                        if (freeformConfig.isHangUp && rotationGeneration == generation) {
                            toMinimizedIcon()
                        }
                    }
                }
            } else {
                handler.post { makeSureFreeformInScreen() }
            }
        }
    }
    private lateinit var appPackageName: String
    private var appIcon: Drawable? = null
    private var appIsLandscape = false
    private var followsDisplayOrientation = true
    private var hangUpAnimationRunning = false
    private var rotationGeneration = 0

    companion object {
        private const val TAG = "LMOFreeform/FreeformWindow"
        private const val FREEFORM_PACKAGE = "com.libremobileos.freeform"
        private const val FREEFORM_LAYOUT = "view_freeform"
        private const val WINDOW_DESTROY_WAIT_MS = 10000L
        // Long enough for the transition that moves the task off this display to finish.
        private const val DISPLAY_RELEASE_DELAY_MS = 500L
        private const val SIDEBAR_PACKAGE = "com.libremobileos.sidebar"
        private const val ALL_APP_ACTIVITY = "com.libremobileos.sidebar.ui.all_app.AllAppActivity"
        private const val MINIMIZED_CONTAINER_WIDTH_DP = 88
        private const val MINIMIZED_CONTAINER_HEIGHT_DP = 72
        private const val MINIMIZED_PEEK_OFFSET_DP = 24
        private const val INITIAL_WINDOW_SIZE_FRACTION = 0.6f
        private const val MAX_WINDOW_HEIGHT_FRACTION = 0.9f
        // App icon, four 24dp actions, and their margins in the top bar.
        private const val MIN_WINDOW_WIDTH_DP = 160
        private const val TOP_INSET_DP = 18
        private const val BOTTOM_INSET_DP = 16
        private const val MENU_FADE_MS = 120L
        private const val CHROME_TOUCH_EXTRA_DP = 12
        private const val BUBBLE_DP = 72
        private const val CARD_RADIUS_DP = 16
        private const val NAVBAR_ZONE_DP = 120
        private const val RESIZE_BOUNCE_DUR = 280L
    }

    init {
        if (LMOFreeformServiceHolder.ping()) {
            Slog.i(TAG, "FreeformWindow init")
            extractPackageInfo()
            if (freeformConfig.width > 0 && freeformConfig.height > 0) userResized = true
            populateFreeformConfig()
            handler.post {
                if (addFreeformView()) {
                    isInitialized = true
                } else {
                    destroy("init:addFreeform failed")
                }
            }
        } else {
            destroy("init:service not running")
            // NOT RUNNING !!!
        }
    }

    override fun onDisplayPaused() {
        //NOT USED
    }

    override fun onDisplayResumed() {
        //NOT USED
    }

    override fun onDisplayStopped() {
        //NOT USED
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        dlog(TAG, "onSurfaceTextureAvailable width:$width height:$height")
        if (displayId < 0) {
            displaySurfaceTexture = surfaceTexture
            surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
            workerHandler.post {
                if (!destroyed) LMOFreeformServiceHolder.createDisplay(
                    freeformConfig, appConfig, Surface(surfaceTexture), this@FreeformWindow)
            }
            handler.postDelayed({ updateChromeColors() }, 800L)
            return
        }
        val kept = displaySurfaceTexture
        if (kept != null && kept !== surfaceTexture && ::freeformView.isInitialized) {
            freeformView.setSurfaceTexture(kept)
            surfaceTexture.release()
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        return surfaceTexture !== displaySurfaceTexture
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        //NOT USED
    }

    override fun onDisplayAdd(displayId: Int) {
        Slog.i(TAG, "onDisplayAdd displayId=$displayId, $appConfig")
        handler.post {
            this.displayId = displayId
            addDisplayInsets(displayId)
            freeformTaskStackListener = FreeformTaskStackListener(displayId, this)
            workerHandler.post launch@ {
                if (destroyed) return@launch
            runCatching {
                SystemServiceHolder.windowManager.setDisplayImePolicy(
                    displayId, WindowManager.DISPLAY_IME_POLICY_LOCAL)
            }.onFailure { Slog.e(TAG, "setDisplayImePolicy LOCAL failed: $it") }
            runCatching {
                SystemServiceHolder.activityTaskManager.registerTaskStackListener(freeformTaskStackListener)
            }.onFailure { Slog.e(TAG, "registerTaskStackListener failed: $it") }
            if (appConfig.taskId != -1) {
                dlog(TAG, "moving taskId=${appConfig.taskId} to freeform display")
                freeformTaskStackListener!!.taskId = appConfig.taskId
                runCatching {
                    // TODO: find a new way for this since getTaskDescription was removed in fwb commit a7cae90a991e
                    // if (SystemServiceHolder.activityTaskManager.getTaskDescription(appConfig.taskId) == null) {
                    //     throw Exception("stale task")
                    // }
                    SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(appConfig.taskId, displayId)
                }
                .onFailure { e ->
                    Slog.e(TAG, "failed to move task ${appConfig.taskId}: $e, fallback to startApp")
                    startApp()
                }
            } else if (appConfig.userId == -100) {
                if (appConfig.pendingIntent == null) destroy("onDisplayAdd:userId=-100, but pendingIntent is null")
                else {
                    LMOFreeformServiceHolder.startPendingIntent(appConfig.pendingIntent, displayId)
                }
            } else {
                startApp()
            }

            }
            val arrowBack = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "arrowBack")
            if (null == arrowBack) {
                Slog.e(TAG, "right&rightScale view is null")
                destroy("onDisplayAdd:backView is null")
                return@post
            }
            arrowBack.setOnClickListener(RightViewClickListener(displayId))

        }
    }

    private fun startApp() {
        if (displayId == Display.INVALID_DISPLAY) {
            Slog.e(TAG, "cannot startApp: displayId not yet set!")
            return
        }
        if (LMOFreeformServiceHolder.startApp(context, appConfig, displayId).not())
            destroy("startApp failed")
    }

    override fun onDisplayHasSecureWindowOnScreenChanged(displayId: Int, hasSecureWindowOnScreen: Boolean) {
        if (displayId != this.displayId) return;
        dlog(TAG, "onDisplayHasSecureWindowOnScreenChanged: $hasSecureWindowOnScreen")
        handler.post {
            windowParams.flags = if (hasSecureWindowOnScreen) {
                windowParams.flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                windowParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            }
            runCatching { FreeformWindowManager.updateWindowSecurity(this@FreeformWindow) }
                .onFailure { Slog.e(TAG, "updateViewLayout failed: $it") }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) bringToFront()
        if (destroyed) return true
        return router().onSurfaceTouch(view, event)
    }

    private var lastFocusTaskId = -1

    private fun focusFreeformTask() {
        val taskId = taskIdOrNull ?: return
        if (taskId == lastFocusTaskId) return
        lastFocusTaskId = taskId
        runCatching { SystemServiceHolder.activityTaskManager.setFocusedTask(taskId) }
            .onFailure {
                lastFocusTaskId = -1
                dlog(TAG, "setFocusedTask failed: $it")
            }
    }

    private fun hideLocalIme() {
        val did = displayId
        if (did == Display.INVALID_DISPLAY) return
        runCatching {
            val imm = LocalServices.getService(InputMethodManagerInternal::class.java)
            if (imm != null) {
                imm.hideInputMethod(SoftInputShowHideReason.HIDE_CLOSE_CURRENT_SESSION, did)
            } else {
                LMOFreeformServiceHolder.back(did)
            }
        }.onFailure { Slog.w(TAG, "hideLocalIme failed: $it") }
    }

    var typeModeEnabled = true
    private var preTypeGeometry: Triple<Int, Int, Int>? = null

    fun onLocalImeVisibilityChanged(visible: Boolean) = handler.post {
        if (destroyed || freeformConfig.isHangUp || !typeModeEnabled) return@post
        if (visible && preTypeGeometry == null) {
            preTypeGeometry = Triple(freeformConfig.width, freeformConfig.height, windowParams.y)
            val w = min(defaultDisplayWidth, (defaultDisplayWidth * 0.95f).roundToInt())
            val h = min((defaultDisplayHeight * 0.85f).roundToInt(), (freeformConfig.height * 1.6f).roundToInt())
            commitGeometry(w, h, enforceMax = false)
            animateTo(y = -(defaultDisplayHeight - h) / 4)
        } else if (!visible) {
            preTypeGeometry?.let { (w, h, y) ->
                commitGeometry(w, h)
                animateTo(y = y)
            }
            preTypeGeometry = null
        }
    }

    private fun forwardTouch(event: MotionEvent) {
        forward(event)
    }

    private fun forward(event: MotionEvent, actionOverride: Int? = null) {
        if (!::freeformView.isInitialized) return
        val vw = freeformView.width
        val vh = freeformView.height
        if (vw <= 0 || vh <= 0) return
        val sx = freeformConfig.freeformWidth.toFloat() / vw
        val sy = freeformConfig.freeformHeight.toFloat() / vh
        val copy = MotionEvent.obtain(event)
        try {
            if (actionOverride != null) copy.action = actionOverride
            if (sx != 1f || sy != 1f) copy.transform(Matrix().apply { setScale(sx, sy) })
            if (copy.source == InputDevice.SOURCE_UNKNOWN) copy.source = InputDevice.SOURCE_TOUCHSCREEN
            LMOFreeformServiceHolder.touch(copy, displayId)
        } finally {
            copy.recycle()
        }
    }

    private fun forwardCancel(event: MotionEvent) {
        forward(event, MotionEvent.ACTION_CANCEL)
    }

    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!::freeformLayout.isInitialized) return

        val w = freeformLayout.width
        val h = freeformLayout.height
        if (w <= 0 || h <= 0) return

        runCatching { freeformLayout.systemGestureExclusionRects = listOf(Rect(0, 0, w, h)) }
            .onFailure { Slog.w(TAG, "updateSystemGestureExclusion failed: $it") }
    }

    private fun expandChromeTouchTargets() {
        if (!::freeformLayout.isInitialized) return
        freeformLayout.post {
            runCatching {
                val extra = (dpToPx(CHROME_TOUCH_EXTRA_DP)).roundToInt()
                val barRect = Rect()
                topBarView.getHitRect(barRect)
                barRect.top -= extra
                barRect.bottom += extra
                barRect.left -= extra
                barRect.right += extra
                freeformLayout.touchDelegate = android.view.TouchDelegate(barRect, topBarView)
            }.onFailure { Slog.w(TAG, "expandChromeTouchTargets failed: $it") }
        }
    }

    private fun dpToPx(dp: Int): Float = dp * context.resources.displayMetrics.density

    private fun bubbleSizePx(): Int = (BUBBLE_DP * context.resources.displayMetrics.density).roundToInt()

    private fun setCardRadius(radiusPx: Float) {
        runCatching {
            freeformLayout.javaClass.getMethod("setRadius", Float::class.javaPrimitiveType)
                .invoke(freeformLayout, radiusPx)
        }
    }

    private fun applyBubbleChrome() {
        val b = bubbleSizePx()
        freeformConfig.hangUpWidth = b
        freeformConfig.hangUpHeight = b
        bubbleView?.setImageDrawable(appIcon)
        bubbleView?.visibility = View.VISIBLE
        if (::freeformRootView.isInitialized) freeformRootView.visibility = View.GONE
        if (::topBarView.isInitialized) topBarView.visibility = View.GONE
        bottomBarView?.visibility = View.GONE
        setCardRadius(b / 2f)
        attachBubbleTouch()
    }

    private fun clearBubbleChrome() {
        bubbleView?.visibility = View.GONE
        if (::freeformRootView.isInitialized) freeformRootView.visibility = View.VISIBLE
        if (::topBarView.isInitialized) topBarView.visibility = View.VISIBLE
        bottomBarView?.visibility = View.VISIBLE
        setCardRadius(dpToPx(CARD_RADIUS_DP))
        if (::freeformView.isInitialized) freeformView.setOnTouchListener(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBubbleTouch() {
        val bubble = bubbleView ?: return
        bubble.setOnTouchListener(MinimizedIconTouchListener(this))
    }

    private val taskIdOrNull: Int?
        get() = freeformTaskStackListener?.taskId?.takeIf { it != -1 }

    private fun setWindowFlag(flag: Int, enabled: Boolean) {
        windowParams.flags = if (enabled) windowParams.flags or flag else windowParams.flags and flag.inv()
    }

    private fun toggleOptionsMenu() {
        val menu = optionsMenuView ?: return
        if (menu.visibility == View.VISIBLE) hideOptionsMenu() else showOptionsMenu()
    }

    private fun showOptionsMenu() {
        fadeIn(optionsScrimView)
        fadeIn(optionsMenuView)
    }

    private fun hideOptionsMenu() {
        fadeOut(optionsMenuView)
        fadeOut(optionsScrimView)
    }

    private fun fadeIn(view: View?) {
        view ?: return
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate().alpha(1f).setDuration(MENU_FADE_MS).start()
    }

    private fun fadeOut(view: View?) {
        view ?: return
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(MENU_FADE_MS).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    private fun snapToEdges() {
        if (freeformConfig.isHangUp) return
        val screenW = defaultDisplayWidth
        val winW = freeformConfig.width
        if (winW <= 0 || winW >= screenW) return
        if (windowParams.x <= -(screenW / 2) || windowParams.x >= screenW / 2) return
        val threshold = dpToPx(40).roundToInt()
        val leftEdge = screenW / 2 + windowParams.x - winW / 2
        val rightEdge = screenW / 2 + windowParams.x + winW / 2
        val snapLeftX = -((screenW - winW) / 2)
        val snapRightX = (screenW - winW) / 2
        if (leftEdge <= threshold && windowParams.x != snapLeftX) {
            animateTo(x = snapLeftX, dur = 200)
        } else if (rightEdge >= screenW - threshold && windowParams.x != snapRightX) {
            animateTo(x = snapRightX, dur = 200)
        }
    }

    /**
     * get freeform screen dimen / freeform view dimen
     */
    private fun populateFreeformConfig() {
        measureSize()
        measureScale()
        context.display.getDisplayInfo(defaultDisplayInfo)
        val maxRefreshRate = context.display.supportedModes
            .maxOfOrNull { it.refreshRate } ?: defaultDisplayInfo.refreshRate
        freeformConfig.apply {
            refreshRate = maxRefreshRate
            presentationDeadlineNanos = if (maxRefreshRate > 0f) {
                (1_000_000_000L / maxRefreshRate).toLong()
            } else {
                defaultDisplayInfo.presentationDeadlineNanos
            }
            dlog(TAG, "populateFreeformConfig: $this")
        }
    }

    fun measureSize() {
        val aspectRatio = targetAspectRatio()
        val availableWidth = defaultDisplayWidth * INITIAL_WINDOW_SIZE_FRACTION
        val availableHeight = defaultDisplayHeight * INITIAL_WINDOW_SIZE_FRACTION
        val initialHeight = min(availableHeight, availableWidth / aspectRatio)
        val (width, height) = constrainWidth((initialHeight * aspectRatio).toDouble())
        freeformConfig.apply {
            this.width = width
            this.height = height
            dlog(
                TAG,
                "measureSize: appIsLandscape=$appIsLandscape aspectRatio=$aspectRatio " +
                    "width=$width height=${this.height}"
            )
        }
    }

    fun resizeFreeformTo(
        requestedWidth: Float,
        isRight: Boolean,
        startWidth: Int,
        startHeight: Int,
        startLayoutWidth: Int,
        startLayoutHeight: Int,
        startWindowX: Int,
        startWindowY: Int
    ) {
        val (constrainedWidth, constrainedHeight) = constrainWidth(
            requestedWidth.toDouble()
        )
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            this.width = constrainedWidth
            this.height = constrainedHeight
        }
        val targetLayoutWidth = startLayoutWidth + constrainedWidth - startWidth
        val targetLayoutHeight = startLayoutHeight + constrainedHeight - startHeight
        val hostWidth = FreeformWindowManager.getHostWidth().takeIf { it > 0 }
            ?: defaultDisplayWidth
        val hostHeight = FreeformWindowManager.getHostHeight().takeIf { it > 0 }
            ?: defaultDisplayHeight
        val startLeft = (hostWidth - startLayoutWidth) / 2 + startWindowX
        val startTop = (hostHeight - startLayoutHeight) / 2 + startWindowY
        val targetLeft = if (isRight) {
            startLeft
        } else {
            startLeft + startLayoutWidth - targetLayoutWidth
        }
        windowParams.apply {
            // FrameLayout centers children using integer division. Recalculate the translation
            // from the fixed opposite corner so width/height parity cannot move that corner.
            x = targetLeft - (hostWidth - targetLayoutWidth) / 2
            y = startTop - (hostHeight - targetLayoutHeight) / 2
        }
        updateWindowLayout()
    }

    fun updateWindowLayout() {
        FreeformWindowManager.updateWindowLayout(this)
    }

    fun bringToFront() {
        FreeformWindowManager.bringToFront(this)
    }

    fun onActivityRequestedOrientationChanged(requestedOrientation: Int) {
        val fixedLandscape = requestedOrientation.toFixedLandscape()
        val followsDisplay = fixedLandscape == null
        val isLandscape = fixedLandscape ?: isDisplayLandscape()
        if (followsDisplayOrientation == followsDisplay && appIsLandscape == isLandscape) return
        followsDisplayOrientation = followsDisplay
        appIsLandscape = isLandscape
        resizeForOrientationChange()
    }

    private fun resizeForOrientationChange() {
        handler.post {
            val currentArea = freeformConfig.width.toDouble() * freeformConfig.height
            val aspectRatio = targetAspectRatio().toDouble()
            val height = sqrt(currentArea / aspectRatio)
            val (width, constrainedHeight) = constrainWidth(height * aspectRatio)
            freeformConfig.width = width
            freeformConfig.height = constrainedHeight
            measureScale()
            changeOrientation()
            resizeFreeformDisplay()
        }
    }

    private fun targetAspectRatio(): Float {
        val shortSide = min(defaultDisplayWidth, defaultDisplayHeight).toFloat()
        val longSide = max(defaultDisplayWidth, defaultDisplayHeight).toFloat()
        return if (appIsLandscape) longSide / shortSide else shortSide / longSide
    }

    private fun constrainWidth(width: Double): Pair<Int, Int> {
        val aspectRatio = targetAspectRatio().toDouble()
        val maxHeight = defaultDisplayHeight.toDouble() * MAX_WINDOW_HEIGHT_FRACTION
        val maxWidth = min(defaultDisplayWidth.toDouble(), maxHeight * aspectRatio)
        val minWidth = min(MIN_WINDOW_WIDTH_DP.dpToPx(context).toDouble(), maxWidth)
        val constrainedWidth = width.coerceIn(minWidth, maxWidth)
        return constrainedWidth.roundToInt() to (constrainedWidth / aspectRatio).roundToInt()
    }

    private fun isDisplayLandscape(): Boolean = defaultDisplayWidth > defaultDisplayHeight

    private fun Int.toFixedLandscape(): Boolean? = when {
        ActivityInfo.isFixedOrientationLandscape(this) -> true
        ActivityInfo.isFixedOrientationPortrait(this) -> false
        else -> null
    }

    fun measureScale(updateDisplaySize: Boolean = true) {
        freeformConfig.apply {
            if (baseDensityDpi <= 0) baseDensityDpi = densityDpi
            val widthScale = min(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / min(width, height)
            val heightScale = max(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / max(width, height)
            scale = min(widthScale, heightScale)
            if (updateDisplaySize) {
                freeformWidth = (width * scale).roundToInt()
                freeformHeight = (height * scale).roundToInt()
            }
            dlog(TAG, "measureScale: $scale freeformWidth=$freeformWidth freeformHeight=$freeformHeight")
        }
    }

    /**
     * Called in system handler
     */
    @SuppressLint("WrongConstant")
    private fun addFreeformView(): Boolean {
        dlog(TAG, "addFreeformView")
        val tmpFreeformLayout = resourceHolder.getLayout(FREEFORM_LAYOUT)!! ?: return false
        freeformLayout = tmpFreeformLayout
        freeformRootView = resourceHolder.getLayoutChildViewByTag<FrameLayout>(freeformLayout, "freeform_root") ?: return false
        veilView = resourceHolder.getLayoutChildViewByTag<FrameLayout>(freeformLayout, "veilView") ?: return false
        topBarView = resourceHolder.getLayoutChildViewByTag(freeformLayout, "topBarView") ?: return false
        minimizedIconContainer = resourceHolder.getLayoutChildViewByTag(freeformLayout, "minimizedIconContainer") ?: return false
        minimizedIconImage = resourceHolder.getLayoutChildViewByTag(freeformLayout, "minimizedIconImage") ?: return false
        val moveTouchListener = MoveTouchListener(this)
        topBarView.setOnTouchListener(moveTouchListener)
        val appIconView = resourceHolder.getLayoutChildViewByTag<ImageView>(freeformLayout, "appIcon") ?: return false
        val packageNameView = resourceHolder.getLayoutChildViewByTag<TextView>(freeformLayout, "packageName") ?: return false
        val maximizeView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "maximizeView") ?: return false
        val minimizeView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "minimizeView") ?: return false
        val pinView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "pinView") ?: return false
        val leftScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "leftScaleView") ?: return false
        val rightScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "rightScaleView") ?: return false
        val veilAppIconView = resourceHolder.getLayoutChildViewByTag<ImageView>(freeformLayout, "veilAppIcon") ?: return false
        veilAppIconView.setImageDrawable(appIcon)
        appIconView.setImageDrawable(appIcon)
        packageNameView.text = appPackageName
        minimizeView.setOnClickListener(LeftViewClickListener(this))
        maximizeView.setOnClickListener(MaximizeClickListener(this))
        pinView.setOnClickListener(PinClickListener(this))
        leftScaleView.setOnTouchListener(ScaleTouchListener(this, false))
        rightScaleView.setOnTouchListener(ScaleTouchListener(this))

        freeformView = FreeformTextureView(context).apply {
            setOnTouchListener(this@FreeformWindow)
            onGenericMotion = { event ->
                runCatching { forward(event) }.isSuccess
            }
            surfaceTextureListener = this@FreeformWindow
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSystemGestureExclusion() }
        }
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.width
            height = freeformConfig.height
        }
        freeformRootView.addView(freeformView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        windowParams.apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            privateFlags = privateFlags or
                    WindowManager.LayoutParams.PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
        }
        setSidebarAwarePosition()
        runCatching {
            check(FreeformWindowManager.attachWindowView(this))
            updateSystemGestureExclusion()
            SystemServiceHolder.windowManager.watchRotation(rotationWatcher, Display.DEFAULT_DISPLAY)
            windowManagerInt.registerDisplaySecureContentListener(this)
        }.onFailure {
            Slog.e(TAG, "addView failed: $it")
            return false
        }
        return true
    }

    private fun scanTint(bmp: Bitmap, fromTop: Boolean): Int? {
        val w = bmp.width
        val h = bmp.height
        val x0 = w / 4
        val x1 = w - w / 4 - 1
        val rowWidth = x1 - x0 + 1
        val need = (rowWidth / 2).coerceAtLeast(1)
        val row = IntArray(rowWidth)
        val ys = if (fromTop) 0 until h else h - 1 downTo 0
        for (y in ys) {
            bmp.getPixels(row, 0, rowWidth, x0, y, rowWidth, 1)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (c in row) {
                if (Color.alpha(c) < 128) continue
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
            }
            if (n >= need) {
                val lum = Color.luminance(Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()))
                return if (lum < 0.5f) Color.WHITE else Color.BLACK
            }
        }
        return null
    }

    private fun updateChromeColors() {
        handler.post {
            if (chromeSampled || destroyed) return@post
            if (!::freeformView.isInitialized) { retryChromeSample(); return@post }
            val tv = freeformView
            if (!tv.isAvailable) { retryChromeSample(); return@post }
            val bmp = runCatching { tv.getBitmap(32, 32) }.getOrNull()
            if (bmp == null) { retryChromeSample(); return@post }
            val topTint = scanTint(bmp, true)
            val bottomTint = scanTint(bmp, false)
            bmp.recycle()
            if (topTint == null && bottomTint == null) { retryChromeSample(); return@post }
            chromeSampled = true
            val bottomTintList = ColorStateList.valueOf(bottomTint ?: topTint ?: Color.WHITE)
            chromeBottomTint = bottomTintList
            optionsIcon?.imageTintList = ColorStateList.valueOf(topTint ?: Color.WHITE)
            navPillView?.backgroundTintList = bottomTintList
            dlog(TAG, "chrome tint top=$topTint bottom=$bottomTint")
        }
    }

    private fun retryChromeSample() {
        if (destroyed || chromeSampleAttempts >= 6) return
        chromeSampleAttempts++
        handler.postDelayed({ updateChromeColors() }, 500L)
    }

    fun requestChromeResample() {
        if (destroyed) return
        chromeSampled = false
        chromeSampleAttempts = 0
        handler.postDelayed({ updateChromeColors() }, 500L)
    }

    private fun addDisplayInsets(displayId: Int) {
        if (topInsetView != null || bottomInsetView != null) removeDisplayInsets()
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        val display = dm.getDisplay(displayId) ?: return
        val dctx = context.createDisplayContext(display)
        val wm = dctx.getSystemService(WindowManager::class.java) ?: return
        displayWm = wm
        val topPx = (dpToPx(TOP_INSET_DP) * freeformConfig.scale).roundToInt()
        val bottomPx = (dpToPx(BOTTOM_INSET_DP) * freeformConfig.scale).roundToInt()
        topInsetView = addInsetProvider(dctx, wm, 0, WindowInsets.Type.statusBars(), Gravity.TOP, Insets.of(0, topPx, 0, 0))
        bottomInsetView = addInsetProvider(dctx, wm, 1, WindowInsets.Type.navigationBars(), Gravity.BOTTOM, Insets.of(0, 0, 0, bottomPx))
    }

    private fun addInsetProvider(dctx: Context, wm: WindowManager, index: Int, type: Int, gravity: Int, insets: Insets): View? {
        val view = View(dctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (gravity == Gravity.TOP) insets.top else insets.bottom,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = gravity
        lp.setFitInsetsTypes(0)
        lp.providedInsets = arrayOf(
            InsetsFrameProvider(insetsOwner, index, type).setInsetsSize(insets)
        )
        return runCatching {
            wm.addView(view, lp)
            view
        }.onFailure { Slog.e(TAG, "addInsetProvider failed: $it") }.getOrNull()
    }

    private fun removeDisplayInsets() {
        val wm = displayWm ?: return
        topInsetView?.let { runCatching { wm.removeViewImmediate(it) } }
        bottomInsetView?.let { runCatching { wm.removeViewImmediate(it) } }
        topInsetView = null
        bottomInsetView = null
        displayWm = null
    }

    fun updateDisplayInsets() {
        if (destroyed || displayId == Display.INVALID_DISPLAY) return
        handler.post {
            if (destroyed) return@post
            addDisplayInsets(displayId)
        }
    }

    /**
     * Called in system handler
     */
    @SuppressLint("ClickableViewAccessibility")
    fun handleHangUp() {
        dlog(TAG, "handleHangUp isHangUp=${freeformConfig.isHangUp}")
        if (hangUpAnimationRunning) return
        if (freeformConfig.isHangUp) {
            hangUpAnimationRunning = true
            FreeformAnimation.scaleOut(freeformLayout, 120) {
                restoreFromMinimized()
                FreeformAnimation.scaleIn(freeformLayout, 180) {
                    hangUpAnimationRunning = false
                }
            }
        } else {
            hangUpAnimationRunning = true
            FreeformAnimation.scaleOut(freeformLayout, 150) {
                minimizeToIcon()
                FreeformAnimation.scaleIn(freeformLayout, 180) {
                    hangUpAnimationRunning = false
                }
            }
        }
    }

    private fun restoreFromMinimized() {
        freeformConfig.apply {
            inHangUpX = windowParams.x
            inHangUpY = windowParams.y
        }
        windowParams.apply {
            x = freeformConfig.notInHangUpX
            y = freeformConfig.notInHangUpY
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        }
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.width
            height = freeformConfig.height
        }
        updateWindowLayout()
        minimizedIconContainer.setOnTouchListener(null)
        minimizedIconContainer.visibility = View.GONE
        freeformRootView.visibility = View.VISIBLE
        topBarView.visibility = View.VISIBLE
        freeformConfig.isHangUp = false
        freeformView.setOnTouchListener(this)
    }

    private fun minimizeToIcon() {
        freeformConfig.apply {
            notInHangUpX = windowParams.x
            notInHangUpY = windowParams.y
        }
        topBarView.visibility = View.GONE
        freeformRootView.visibility = View.GONE
        minimizedIconContainer.visibility = View.VISIBLE
        minimizedIconContainer.setOnTouchListener(MinimizedIconTouchListener(this))
        windowParams.flags = windowParams.flags xor WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        freeformConfig.isHangUp = true
        toMinimizedIcon()
    }

    /**
     * Called in system handler
     */
    fun toMinimizedIcon() {
        val appIcon =
            runCatching { context.packageManager.getApplicationIcon(appConfig.packageName) }
            .getOrElse { err ->
                Slog.e(TAG, "failed to load app icon: $err")
                context.packageManager.defaultActivityIcon
            }
        val containerWidth = MINIMIZED_CONTAINER_WIDTH_DP.dpToPx(context).roundToInt()
        val containerHeight = MINIMIZED_CONTAINER_HEIGHT_DP.dpToPx(context).roundToInt()
        val peekOffset = MINIMIZED_PEEK_OFFSET_DP.dpToPx(context).roundToInt()
        val displayWidth = getFreeformDisplayWidth()
        val displayHeight = getFreeformDisplayHeight()
        val minX = -displayWidth / 2 + containerWidth / 2
        val maxX = displayWidth / 2 - containerWidth / 2
        val minY = -displayHeight / 2 + containerHeight / 2
        val maxY = displayHeight / 2 - containerHeight / 2
        minimizedIconImage.setImageDrawable(appIcon)
        windowParams.apply {
            width = containerWidth
            height = containerHeight
            x = if (freeformConfig.inHangUpX != -1) {
                freeformConfig.inHangUpX.coerceIn(minX, maxX + peekOffset)
            } else {
                maxX + peekOffset
            }
            y = if (freeformConfig.inHangUpY != -1) {
                freeformConfig.inHangUpY.coerceIn(minY, maxY)
            } else {
                (-displayHeight / 2 * 0.7).roundToInt().coerceIn(minY, maxY)
            }
        }
        runCatching { updateWindowLayout() }
            .onFailure { Slog.e(TAG, "$it") }
    }

    private fun getFreeformDisplayWidth(): Int {
        context.display.getDisplayInfo(defaultDisplayInfo)
        return defaultDisplayInfo.logicalWidth.takeIf { it > 0 }
            ?: defaultDisplayWidth
    }

    fun getFreeformDisplayHeight(): Int {
        context.display.getDisplayInfo(defaultDisplayInfo)
        return defaultDisplayInfo.logicalHeight.takeIf { it > 0 }
            ?: defaultDisplayHeight
    }

    private fun updateDefaultDisplaySize() {
        context.display.getDisplayInfo(defaultDisplayInfo)
        defaultDisplayWidth = defaultDisplayInfo.logicalWidth.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        defaultDisplayHeight = defaultDisplayInfo.logicalHeight.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
    }

    /**
     * Called in uiHandler
     */
    fun makeSureFreeformInScreen() {
        if (freeformConfig.isHangUp) return
        val (width, height) = constrainWidth(freeformRootView.layoutParams.width.toDouble())
        if (freeformRootView.layoutParams.width != width || freeformRootView.layoutParams.height != height) {
            freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                this.width = width
                this.height = height
            }
        }
        val hostWidth = FreeformWindowManager.getHostWidth().takeIf { it > 0 }
            ?: defaultDisplayWidth
        val hostHeight = FreeformWindowManager.getHostHeight().takeIf { it > 0 }
            ?: defaultDisplayHeight
        val systemBarInsets = FreeformWindowManager.getHostWindowInsets()
            ?.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        val statusBarHeight = systemBarInsets?.top?.takeIf { it > 0 }
            ?: SystemBarUtils.getStatusBarHeight(context)
        val navigationBarHeight = systemBarInsets?.bottom?.takeIf { it > 0 }
            ?: context.resources.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_height)
        val maxX = (hostWidth - freeformLayout.width).coerceAtLeast(0) / 2
        val minY = -hostHeight / 2 + statusBarHeight + freeformLayout.height / 2
        val maxY = hostHeight / 2 - navigationBarHeight - freeformLayout.height / 2
        val (lowerY, upperY) = if (minY <= maxY) minY to maxY else {
            val safeAreaCenter = (statusBarHeight - navigationBarHeight) / 2
            safeAreaCenter to safeAreaCenter
        }
        if (windowParams.x < -maxX) FreeformAnimation.moveInScreenAnimator(windowParams.x, -maxX, 300, true, this)
        else if (windowParams.x > maxX) FreeformAnimation.moveInScreenAnimator(windowParams.x, maxX, 300, true, this)
        if (windowParams.y < lowerY) FreeformAnimation.moveInScreenAnimator(windowParams.y, lowerY, 300, false, this)
        else if (windowParams.y > upperY) FreeformAnimation.moveInScreenAnimator(windowParams.y, upperY, 300, false, this)
    }

    fun resizeFreeformDisplay() {
        workerHandler.post {
            LMOFreeformServiceHolder.resizeFreeform(
                this@FreeformWindow,
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight,
                freeformConfig.densityDpi
            )
            freeformView.surfaceTexture?.setDefaultBufferSize(
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight
            )
        }
    }

    /**
     * Change freeform orientation
     * Called in system handler
     */
    fun changeOrientation() {
        if (freeformConfig.isHangUp) return
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.width
            height = freeformConfig.height
        }
    }

    fun getFreeformId(): String {
        return "${appConfig.packageName},${appConfig.activityName},${appConfig.userId}"
    }

    /**
     * Runs [action] on the thread that owns this window's views.
     */
    fun postOnHandler(action: FreeformWindow.() -> Unit) {
        handler.post { action() }
    }

    fun postOnHandlerDelayed(delayMs: Long, action: FreeformWindow.() -> Unit) {
        handler.postDelayed({ action() }, delayMs)
    }

    /**
     * Runs [action] off the window handler, for calls that can block. See [workerThread].
     */
    fun postOnWorkerHandler(action: FreeformWindow.() -> Unit) {
        workerHandler.post { action() }
    }

    fun close() {
        dlog(TAG, "close()")
        workerHandler.post {
            runCatching {
                SystemServiceHolder.activityTaskManager.removeTask(freeformTaskStackListener!!.taskId)
                removeView()
            }.onFailure { exception ->
                Slog.e(TAG, "removeTask failed: ", exception)
                destroy("window.close() fallback")
            }
        }
    }

    fun removeView(runDestroy: Boolean = true) {
        dlog(TAG, "removeView($runDestroy)")
        handler.removeCallbacks(destroyRunnable)
        handler.post {
            runCatching {
                FreeformWindowManager.detachWindowView(this@FreeformWindow)
                dlog(TAG, "removeView success")
            }.onFailure { exception ->
                Slog.e(TAG, "removeView failed $exception")
            }
        }
        // wait for onTaskRemoved(), but take it into our own hands in case its never triggered.
        if (runDestroy)
            handler.postDelayed(destroyRunnable, WINDOW_DESTROY_WAIT_MS)
    }

    @JvmOverloads
    fun destroy(
        callReason: String,
        shouldRemoveTask: Boolean = false,
        delayDisplayRelease: Boolean = false,
    ) {
        if (!isInitialized) {
            Slog.w(TAG, "destroy called on uninitialized window")
            return
        }

        if (destroyed) return
        val preType = preTypeGeometry
        if (preType != null) {
            persistGeometry(preType.first, preType.second)
        } else if (userResized && !freeformConfig.isHangUp) {
            persistGeometry(freeformConfig.width, freeformConfig.height)
        }
        destroyed = true
        Slog.i(TAG, "destroy ${getFreeformId()}, displayId=$displayId callReason: $callReason")
        runCatching { gestureRouter?.destroy() }
        gestureRouter = null
        cancelGeometryAnimations()
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        removeView(false)
        handler.removeCallbacks(destroyRunnable)
        SystemServiceHolder.activityTaskManager.unregisterTaskStackListener(freeformTaskStackListener)
        SystemServiceHolder.windowManager.removeRotationWatcher(rotationWatcher)
        FreeformWindowManager.removeWindow(getFreeformId())
        windowManagerInt.unregisterDisplaySecureContentListener(this)
        // A task moving to another display still hangs from this display's hierarchy until the
        // transition finishes, so let that settle before pulling the display out from under it.
        workerHandler.removeCallbacks(releaseDisplayRunnable)
        workerHandler.postDelayed(releaseDisplayRunnable,
            if (delayDisplayRelease) DISPLAY_RELEASE_DELAY_MS else 0)
        handler.post { removeDisplayInsets() }
        taskIdOrNull?.let {
            if (shouldRemoveTask) {
                Slog.i(TAG, "destroy: remove taskId $it again")
                runCatching { SystemServiceHolder.activityTaskManager.removeTask(it) }
                    .onFailure { e -> Slog.w(TAG, "destroy removeTask failed: $e") }
            }
        }
        isInitialized = false
    }

    private fun extractPackageInfo() {
        try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(appConfig.packageName, 0)
            appPackageName = pm.getApplicationLabel(ai).toString()
            appIcon = pm.getApplicationIcon(ai)
        } catch (e: Exception) {
            Slog.e(TAG, "Failed to retrieve app info: ${e.message}")
            appPackageName = ""
            appIcon = null
        }
        val requestedOrientation = runCatching {
            context.packageManager.getActivityInfo(
                ComponentName(appConfig.packageName, appConfig.activityName),
                0
            ).screenOrientation
        }.getOrElse { error ->
            dlog(TAG, "Failed to determine initial activity orientation: $error")
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val fixedLandscape = requestedOrientation.toFixedLandscape()
        followsDisplayOrientation = fixedLandscape == null
        appIsLandscape = fixedLandscape ?: isDisplayLandscape()
    }
    private fun clampPositionAnimated() {
        val (curW, curH) = if (freeformConfig.isHangUp) {
            freeformConfig.hangUpWidth to freeformConfig.hangUpHeight
        } else if (::freeformRootView.isInitialized) {
            val lp = freeformRootView.layoutParams
            ((lp?.width?.takeIf { it > 0 } ?: freeformConfig.width)) to
                ((lp?.height?.takeIf { it > 0 } ?: freeformConfig.height))
        } else {
            freeformConfig.width to freeformConfig.height
        }
        val limitX = max(0, (defaultDisplayWidth - curW) / 2)
        val limitY = max(0, (defaultDisplayHeight - curH) / 2)
        val targetX = windowParams.x.coerceIn(-limitX, limitX)
        val targetY = windowParams.y.coerceIn(-limitY, limitY)
        if (targetX != windowParams.x || targetY != windowParams.y) {
            animateTo(x = targetX, y = targetY, dur = 300)
        }
    }

    fun dumpState(pw: java.io.PrintWriter) {
        runCatching {
            val (vw, vh) = if (::freeformRootView.isInitialized) {
                val lp = freeformRootView.layoutParams
                (lp?.width ?: -1) to (lp?.height ?: -1)
            } else -1 to -1
            pw.println("  id=${getFreeformId()} displayId=$displayId taskId=${taskIdOrNull ?: "none"}")
            pw.println("    pos=(${windowParams.x},${windowParams.y}) view=${vw}x${vh} " +
                    "display=${freeformConfig.freeformWidth}x${freeformConfig.freeformHeight} " +
                    "scale=${freeformConfig.scale} dpi=${freeformConfig.densityDpi} " +
                    "(base=${freeformConfig.baseDensityDpi} mode=${freeformConfig.densityMode})")
            pw.println("    hangUp=${freeformConfig.isHangUp} destroyed=$destroyed " +
                    "gesture=${gestureRouter?.mode ?: "none"}")
        }.onFailure { pw.println("  <dump failed: $it>") }
    }
    private fun setSidebarAwarePosition() {
        val isLandscape = defaultDisplayWidth > defaultDisplayHeight
        val windowWidth = freeformConfig.width
        val margin = 80 // pixels from edge
        
        windowParams.apply {
            if (isLandscape) {
                // Landscape: position on left side of screen
                x = -(defaultDisplayWidth / 2) + (windowWidth / 2) + margin
                y = 0 // center vertically
            } else {
                // Portrait: center on screen
                x = 0 // center horizontally
                y = -(defaultDisplayHeight / 8) // slightly above center
            }
        }
        
        val mode = if (isLandscape) "landscape (left side)" else "portrait (center)"
        dlog(TAG, "Orientation-aware positioning: $mode, windowX=${windowParams.x}, windowY=${windowParams.y}, screenSize=${defaultDisplayWidth}x${defaultDisplayHeight}")
    }
    private fun setDefaultPosition() {
        // Use the same left side positioning as default
        setSidebarAwarePosition()
    }
}
