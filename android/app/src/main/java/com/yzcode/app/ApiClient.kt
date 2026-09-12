package com.yzcode.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun interface AuthCallback {
        fun onResult(ok: Boolean, token: String?, error: String?)
    }

    fun interface SimpleCallback {
        fun onResult(ok: Boolean)
    }

    private fun baseUrl(serverUrl: String): String {
        var s = serverUrl.trim()
        while (s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    fun login(serverUrl: String, username: String, password: String, callback: AuthCallback) {
        auth(baseUrl(serverUrl) + "/api/login", username, password, callback)
    }

    fun register(serverUrl: String, username: String, password: String, callback: AuthCallback) {
        auth(baseUrl(serverUrl) + "/api/register", username, password, callback)
    }

    private fun auth(url: String, username: String, password: String, callback: AuthCallback) {
        val json = JSONObject()
        json.put("username", username)
        json.put("password", password)
        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onResult(false, null, "网络错误：" + (e.message ?: "连接失败"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = resp.body.string()
                    try {
                        val obj = JSONObject(text)
                        if (obj.optBoolean("ok", false)) {
                            callback.onResult(true, obj.optString("token", ""), null)
                        } else {
                            callback.onResult(false, null, obj.optString("error", "请求失败（HTTP " + resp.code + "）"))
                        }
                    } catch (e: Exception) {
                        callback.onResult(false, null, "响应解析失败（HTTP " + resp.code + "）")
                    }
                }
            }
        })
    }

    fun uploadCode(
        serverUrl: String,
        token: String,
        code: String,
        sender: String,
        message: String,
        receivedAt: Long,
        callback: SimpleCallback
    ) {
        val json = JSONObject()
        json.put("code", code)
        json.put("sender", sender)
        json.put("message", message)
        json.put("received_at", receivedAt)
        val request = Request.Builder()
            .url(baseUrl(serverUrl) + "/api/codes")
            .header("Authorization", "Bearer " + token)
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onResult(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = resp.body.string()
                    val ok = try {
                        JSONObject(text).optBoolean("ok", false)
                    } catch (e: Exception) {
                        false
                    }
                    callback.onResult(resp.isSuccessful && ok)
                }
            }
        })
    }

    fun logout(serverUrl: String, token: String, callback: SimpleCallback) {
        val request = Request.Builder()
            .url(baseUrl(serverUrl) + "/api/logout")
            .header("Authorization", "Bearer " + token)
            .post(JSONObject().toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onResult(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                callback.onResult(true)
            }
        })
    }
}
