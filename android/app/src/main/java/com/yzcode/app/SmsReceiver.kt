package com.yzcode.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SMS_RECEIVED_ACTION) return

        val pdus = getPdus(intent) ?: return
        val format = intent.getStringExtra("format") ?: "3gpp"

        val messages = ArrayList<SmsMessage>()
        for (pdu in pdus) {
            val bytes = pdu as? ByteArray ?: continue
            try {
                val msg = SmsMessage.createFromPdu(bytes, format)
                if (msg != null) messages.add(msg)
            } catch (e: Exception) {
                Log.w(TAG, "createFromPdu failed", e)
            }
        }
        if (messages.isEmpty()) return

        val body = buildString {
            for (m in messages) append(m.messageBody ?: "")
        }
        if (body.isEmpty()) return
        val sender = messages[0].originatingAddress ?: ""
        val receivedAt = messages[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        val appContext = context.applicationContext
        val code = CodeExtractor.extract(body)
        if (code == null) {
            Log.d(TAG, "no verification code found in sms")
            HistoryStore.add(appContext, HistoryStore.Entry("（未识别）", sender, receivedAt, "收到短信但未提取到验证码"))
            return
        }

        val token = Prefs.token(context)
        if (token == null) {
            HistoryStore.add(appContext, HistoryStore.Entry(code, sender, receivedAt, "未登录，无法上传"))
            return
        }
        val serverUrl = Prefs.serverUrl(context)

        val pendingResult = goAsync()
        ApiClient.uploadCode(serverUrl, token, code, sender, body, receivedAt) { ok, err ->
            if (ok) {
                HistoryStore.add(appContext, HistoryStore.Entry(code, sender, receivedAt, "上传成功"))
                pendingResult.finish()
            } else {
                ApiClient.uploadCode(serverUrl, token, code, sender, body, receivedAt) { ok2, err2 ->
                    HistoryStore.add(
                        appContext,
                        HistoryStore.Entry(code, sender, receivedAt, if (ok2) "上传成功" else "上传失败：" + (err2 ?: err ?: "未知原因"))
                    )
                    pendingResult.finish()
                }
            }
        }
    }

    private fun getPdus(intent: Intent): Array<*>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_PDUS, Array<Any>::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_PDUS) as? Array<*>
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private const val SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED"
        private const val EXTRA_PDUS = "pdus"
    }
}
