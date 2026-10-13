package com.libremobileos.sidebar.utils

import android.app.Application
import android.app.WindowConfiguration
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.internal.policy.DesktopModeCompatPolicy

fun Application.isResizeableActivity(component: ComponentName): Boolean {
    return runCatching { packageManager.getActivityInfo(component, /* flags */ 0) }
        .onFailure { Log.e(MAIN_TAG, "failed to get activity info for $component: $it") }
        .getOrNull()
        ?.let { isActivityResizeable(it) }
        ?: false
}

fun Application.isResizeableActivity(packageName: String, activityName: String): Boolean =
    isResizeableActivity(ComponentName(packageName, activityName))

fun Application.getBadgedIcon(appInfo: ApplicationInfo, userHandle: UserHandle): Drawable =
    packageManager.getUserBadgedIcon(
        appInfo.loadIcon(packageManager),
        userHandle
    )

fun Application.getBadgedIcon(appInfo: ApplicationInfo, userId: Int): Drawable =
    getBadgedIcon(appInfo, UserHandle.of(userId))

fun Context.canLaunchInFloatingWindow(component: ComponentName, userId: Int): Boolean =
    runCatching {
        val userContext = createContextAsUser(UserHandle.of(userId), 0)
        val info = userContext.packageManager.getActivityInfo(component, 0)
        if (!userContext.isActivityResizeable(info)) return@runCatching false

        val resources = userContext.packageManager.getResourcesForApplication(info.applicationInfo)
        val theme = resources.newTheme().apply {
            val themeId = info.themeResource.takeIf { it != 0 }
                ?: if (info.applicationInfo.targetSdkVersion < android.os.Build.VERSION_CODES.HONEYCOMB) {
                    android.R.style.Theme
                } else {
                    android.R.style.Theme_Holo
                }
            applyStyle(themeId, true)
        }
        val attributes = theme.obtainStyledAttributes(com.android.internal.R.styleable.Window)
        val noDisplay: Boolean
        val transparent: Boolean
        try {
            noDisplay = attributes.getBoolean(
                com.android.internal.R.styleable.Window_windowNoDisplay, false
            )
            transparent = ActivityInfo.isTranslucentOrFloating(attributes) &&
                !attributes.getBoolean(
                    com.android.internal.R.styleable.Window_windowShowWallpaper, false
                )
        } finally {
            attributes.recycle()
        }
        val policyComponent = info.targetActivity?.let { ComponentName(info.packageName, it) }
            ?: component
        val policy = DesktopModeCompatPolicy(userContext)
        !noDisplay && !policy.isTopActivityExemptFromDesktopWindowing(
            policyComponent, noDisplay, transparent, 1, userId, info,
            WindowConfiguration.ACTIVITY_TYPE_STANDARD
        ) && !policy.isPackageLaunchInFullscreen(policyComponent)
    }.getOrDefault(false)

private fun Context.isActivityResizeable(info: ActivityInfo): Boolean =
    Settings.Global.getInt(
        contentResolver, Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES, 0
    ) != 0 || ActivityInfo.isResizeableMode(info.resizeMode) || info.supportsPictureInPicture()
