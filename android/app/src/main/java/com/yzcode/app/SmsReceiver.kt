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

        val code = CodeExtractor.extract(body)
        if (code == null) {
            Log.d(TAG, "no verification code found in sms")
            return
        }

        val token = Prefs.token(context) ?: return
        val serverUrl = Prefs.serverUrl(context)
        val appContext = context.applicationContext

        val pendingResult = goAsync()
        ApiClient.uploadCode(serverUrl, token, code, sender, body, receivedAt) { ok ->
            if (ok) {
                HistoryStore.add(appContext, HistoryStore.Entry(code, sender, receivedAt))
                pendingResult.finish()
            } else {
                ApiClient.uploadCode(serverUrl, token, code, sender, body, receivedAt) { ok2 ->
                    if (ok2) {
                        HistoryStore.add(appContext, HistoryStore.Entry(code, sender, receivedAt))
                    } else {
                        Log.w(TAG, "upload code failed after retry")
                    }
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
