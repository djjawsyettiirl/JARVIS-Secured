package com.jarvis.secured

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import java.io.File

object PrivateUpdateInstaller {
    private const val ACTION_RESULT = "com.jarvis.secured.PRIVATE_UPDATE_RESULT"

    @Suppress("DEPRECATION")
    fun versionCode(context: Context, path: String? = null): Long? {
        val info = if (path == null) context.packageManager.getPackageInfo(context.packageName, 0)
        else context.packageManager.getPackageArchiveInfo(path, 0)
        info ?: return null
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun install(context: Context, apk: File) {
        val candidate = versionCode(context, apk.absolutePath) ?: error("The downloaded APK is invalid")
        val installed = versionCode(context) ?: 0L
        if (candidate <= installed) return

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val result = Intent(context, PrivateUpdateResultReceiver::class.java).setAction(ACTION_RESULT)
            val sender = PendingIntent.getBroadcast(
                context,
                sessionId,
                result,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            ).intentSender
            session.commit(sender)
        }
    }
}

class PrivateUpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(confirmation) }.isFailure) {
                    showConfirmationNotification(context, confirmation)
                }
            }
        }
    }

    private fun showConfirmationNotification(context: Context, confirmation: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "jarvis_updates"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "JARVIS updates", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val action = PendingIntent.getActivity(
            context,
            0,
            confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            2002,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("JARVIS update ready")
                .setContentText("Tap to approve the Android system fallback installation.")
                .setContentIntent(action)
                .setAutoCancel(true)
                .build()
        )
    }
}
