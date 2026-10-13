package com.libremobileos.sidebar.ui.all_app

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.Logger
import com.libremobileos.sidebar.utils.canLaunchInFloatingWindow

/**
 * @author KindBrave
 * @since 2023/10/25
 */
class AllAppActivity: ComponentActivity() {
    private val logger = Logger(TAG)
    private val viewModel: AllAppViewModel by viewModels { AllAppViewModel.Factory }

    companion object {
        private const val TAG = "AllAppActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.d("onCreate")

        setContent {
            SidebarTheme {
                AllAppGridView(
                    viewModel = viewModel,
                    onClick = ::onClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onDestroy() {
        logger.d("onDestroy")
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
