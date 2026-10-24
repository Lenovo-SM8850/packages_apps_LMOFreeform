package com.libremobileos.sidebar.ui.all_app

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.canLaunchInFloatingWindow
import com.libremobileos.sidebar.utils.Logger

/**
 * @author KindBrave
 * @since 2023/10/25
 */
class AllAppActivity: ComponentActivity() {
    private val logger = Logger(TAG)
    private val viewModel: AllAppViewModel by viewModels { AllAppViewModel.Factory }
    private var drawerView: ComposeView? = null
    private var drawerWindowManager: WindowManager? = null

    companion object {
        private const val TAG = "AllAppActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.d("onCreate")

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val overlayContext = createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val manager = overlayContext.getSystemService(WindowManager::class.java)
        val content = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AllAppActivity)
            setViewTreeViewModelStoreOwner(this@AllAppActivity)
            setViewTreeSavedStateRegistryOwner(this@AllAppActivity)
            setViewTreeOnBackPressedDispatcherOwner(this@AllAppActivity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                private val back = OnBackInvokedCallback { onBackPressedDispatcher.onBackPressed() }
                private var dispatcher: OnBackInvokedDispatcher? = null
                override fun onViewAttachedToWindow(view: View) {
                    dispatcher = view.findOnBackInvokedDispatcher()
                    dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
                }
                override fun onViewDetachedFromWindow(view: View) {
                    dispatcher?.unregisterOnBackInvokedCallback(back)
                    dispatcher = null
                }
            })
            setContent {
                SidebarTheme {
                    AllAppGridView(
                        viewModel = viewModel,
                        onClick = ::onClick,
                        onDismiss = { finish() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        drawerWindowManager = manager
        drawerView = content
        manager.addView(content, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            privateFlags = privateFlags or WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "Sidebar app drawer"
            setFitInsetsTypes(0)
        })
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        logger.d("onDestroy")
        drawerView?.let { if (it.isAttachedToWindow) drawerWindowManager?.removeViewImmediate(it) }
        drawerView = null
        drawerWindowManager = null
        super.onDestroy()
    }

    private fun onClick(appInfo: AppInfo) {
        val componentName = ComponentName(appInfo.packageName, appInfo.activityName)
        if (!canLaunchInFloatingWindow(componentName, appInfo.userId)) {
            Toast.makeText(this, R.string.floating_window_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = componentName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val options = ActivityOptions.makeBasic().apply {
            setLaunchFloatingWindow(true)
        }
        runCatching {
            startActivityAsUser(intent, options.toBundle(), UserHandle.of(appInfo.userId))
        }.onFailure {
            logger.e("startApp failed", it)
        }
        finish()
    }
}
