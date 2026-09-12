package com.yzcode.app

import android.content.Context
import android.provider.Telephony

object SmsInbox {

    data class Sms(val address: String, val body: String, val date: Long)

    fun queryRecent(context: Context, limit: Int): List<Sms> {
        val out = ArrayList<Sms>()
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null,
                Telephony.Sms.DATE + " DESC"
            ) ?: return out
            cursor.use {
                while (it.moveToNext() && out.size < limit) {
                    out.add(Sms(it.getString(0) ?: "", it.getString(1) ?: "", it.getLong(2)))
                }
            }
        } catch (e: SecurityException) {
            // READ_SMS 未授予
        }
        return out
    }

    // 扫描最新 limit 条收件箱短信，返回新派发上传的验证码条数
    fun scanNewest(context: Context, limit: Int): Int {
        var dispatched = 0
        for (sms in queryRecent(context, limit)) {
            if (Uploader.process(context, sms.address, sms.body, sms.date)) dispatched++
        }
        return dispatched
    }
}
