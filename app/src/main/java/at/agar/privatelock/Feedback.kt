package at.agar.privatelock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibration */
object Feedback {
    @Suppress("DEPRECATION")
    fun vibrate(ctx: Context, ms: Long) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}

/** Sperr-Berechtigung (Geräteadministrator) */
object Admin {
    fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isActive(ctx: Context): Boolean = try {
        dpm(ctx).isAdminActive(component(ctx))
    } catch (_: Exception) {
        false
    }

    fun requestIntent(ctx: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, L.of(ctx).adminExplain)
    }

    /** true = gesperrt */
    fun lockNow(ctx: Context): Boolean = try {
        if (!isActive(ctx)) false else {
            dpm(ctx).lockNow()
            true
        }
    } catch (_: Exception) {
        false
    }

    fun remove(ctx: Context) {
        try {
            dpm(ctx).removeActiveAdmin(component(ctx))
        } catch (_: Exception) {
        }
    }
}
