package com.yzcode.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Telephony

class SmsMonitorService : Service() {

    private var observerThread: HandlerThread? = null
    private var observer: ContentObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        registerSmsObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observerThread?.quitSafely()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "验证码监听", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("YZ-Code 正在监听验证码")
            .setContentText("收到验证码短信将自动上传")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun registerSmsObserver() {
        val thread = HandlerThread("sms-observer")
        thread.start()
        observerThread = thread
        val handler = Handler(thread.looper)
        val obs = object : ContentObserver(handler) {
            private var lastFiredAt = 0L
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val now = System.currentTimeMillis()
                if (now - lastFiredAt < 1500) return // 一条短信可能触发多次，去抖
                lastFiredAt = now
                SmsInbox.scanNewest(applicationContext, 5)
            }
        }
        observer = obs
        contentResolver.registerContentObserver(Telephony.Sms.Inbox.CONTENT_URI, true, obs)
    }

    companion object {
        private const val CHANNEL_ID = "yz_code_monitor"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, SmsMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsMonitorService::class.java))
        }
    }
}
