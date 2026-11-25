package com.romankryvolapov.dialogaitranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import com.romankryvolapov.dialogaitranslator.DialogApp
import com.romankryvolapov.dialogaitranslator.MainActivity
import com.romankryvolapov.dialogaitranslator.R
import com.romankryvolapov.dialogaitranslator.capture.audioSourceFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/** Keeps a dialog listening while other apps, a call for one, are on the screen, and shows the overlay over them. */
class DialogService : Service() {

    companion object {
        private const val CHANNEL = "dialog"
        private const val NOTIFICATION = 1
        private const val STOP = "stop"
        private const val START_TIMEOUT_MILLIS = 3_000L

        fun start(context: Context) = context.startForegroundService(Intent(context, DialogService::class.java))
    }

    private val app get() = application as DialogApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: Overlay? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            app.engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        app.engine.start(audioSourceFor(this, app.settings.getAppInfo()))
        if (!watching) watch()
        return START_NOT_STICKY
    }

    private fun watch() {
        watching = true
        scope.launch {
            // A dialog that cannot start leaves no service behind either.
            withTimeoutOrNull(START_TIMEOUT_MILLIS) { app.engine.running.first { it } }
            app.engine.running.first { !it }
            stopSelf()
        }
        scope.launch {
            combine(app.engine.running, app.settings.appInfoFlow.map { it.overlayEnabled }, overlayAllowed()) { running, wanted, allowed -> running && wanted && allowed }
                .distinctUntilChanged()
                .collect { show ->
                    overlay?.close()
                    overlay = if (show) Overlay(this@DialogService, app) else null
                }
        }
    }

    /** Android does not tell when the permission is given, so it is asked for now and then. */
    private fun overlayAllowed() = flow {
        while (true) {
            emit(Settings.canDrawOverlays(this@DialogService))
            delay(1_000)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        overlay?.close()
        overlay = null
        app.engine.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Dialog", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DialogService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Listening")
            .setContentText(app.settings.getAppInfo().audioInput.title)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
