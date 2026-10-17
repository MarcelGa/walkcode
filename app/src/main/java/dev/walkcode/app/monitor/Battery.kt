package dev.walkcode.app.monitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimisation exemption. Without it Doze can suspend the monitor's SSH connections while
 * the screen is off. The app is sideloaded, so the direct "ignore optimisations" request is fine.
 */
object Battery {
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
