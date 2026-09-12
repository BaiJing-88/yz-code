package com.yzcode.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

// 通知栏兜底通道：验证码短信若被 ROM 隔离（通知类短信）或走 RCS/5G 消息，
// 短信数据库和广播都拿不到，但系统短信 App 会弹通知，从这里提取验证码。
class SmsNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val content = if (big.length > text.length) big else text
            val combined = (title + " " + content).trim()
            if (combined.isEmpty()) return
            if (!CodeExtractor.containsKeyword(combined)) return
            Uploader.process(this, title, combined, sbn.postTime)
        } catch (e: Exception) {
            Log.w(TAG, "onNotificationPosted failed", e)
        }
    }

    companion object {
        private const val TAG = "NotifListener"
    }
}
