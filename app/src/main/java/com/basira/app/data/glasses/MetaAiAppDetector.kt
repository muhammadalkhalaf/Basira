package com.basira.app.data.glasses

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects the Meta AI companion app, which DAT requires for pairing, registration, permissions,
 * firmware updates, and Developer Mode during the developer preview.
 */
@Singleton
class MetaAiAppDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Returns `true` when the Meta AI app is installed. */
    fun isInstalled(): Boolean = META_AI_PACKAGES.any(::isPackageInstalled)

    /** Returns an intent that opens the Meta AI store listing. */
    fun storeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=$META_AI_PACKAGE".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns a browser fallback for [storeIntent]. */
    fun storeWebIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$META_AI_PACKAGE".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns an intent that launches Meta AI, or `null` when it is not installed. */
    fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(META_AI_PACKAGE)

    private fun isPackageInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        // Package names declared in the DAT core manifest <queries> element.
        const val META_AI_PACKAGE = "com.facebook.stella"
        val META_AI_PACKAGES = listOf(META_AI_PACKAGE, "com.facebook.stella_debug")
    }
}
