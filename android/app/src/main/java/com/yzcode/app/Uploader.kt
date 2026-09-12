package com.yzcode.app

import android.content.Context
import android.util.Log

object Uploader {
    private const val TAG = "Uploader"

    // 返回 true 表示识别到验证码并已派发上传；onDone 在整条处理链结束后回调（供 goAsync 使用）
    fun process(
        context: Context,
        sender: String,
        body: String,
        receivedAt: Long,
        recordUnrecognized: Boolean = false,
        onDone: (() -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext
        val code = CodeExtractor.extract(body)
        if (code == null) {
            Log.d(TAG, "no verification code found in sms")
            if (recordUnrecognized) {
                HistoryStore.add(appContext, HistoryStore.Entry("（未识别）", sender, receivedAt, "收到短信但未提取到验证码"))
            }
            onDone?.invoke()
            return false
        }
        if (HistoryStore.isUploaded(appContext, code, receivedAt)) {
            onDone?.invoke()
            return false
        }
        val token = Prefs.token(appContext)
        if (token == null) {
            HistoryStore.add(appContext, HistoryStore.Entry(code, sender, receivedAt, "未登录，无法上传"))
            onDone?.invoke()
            return false
        }
        val serverUrl = Prefs.serverUrl(appContext)
        upload(appContext, serverUrl, token, code, sender, body, receivedAt, true, onDone)
        return true
    }

    private fun upload(
        appContext: Context,
        serverUrl: String,
        token: String,
        code: String,
        sender: String,
        body: String,
        receivedAt: Long,
        allowRetry: Boolean,
        onDone: (() -> Unit)?
    ) {
        ApiClient.uploadCode(serverUrl, token, code, sender, body, receivedAt) { ok, err ->
            if (ok) {
                HistoryStore.add(appContext, HistoryStore.Entry(code, sender, receivedAt, "上传成功"))
                onDone?.invoke()
            } else if (allowRetry) {
                upload(appContext, serverUrl, token, code, sender, body, receivedAt, false, onDone)
            } else {
                Log.w(TAG, "upload failed: $err")
                HistoryStore.add(appContext, HistoryStore.Entry(code, sender, receivedAt, "上传失败：" + (err ?: "未知原因")))
                onDone?.invoke()
            }
        }
    }
}
