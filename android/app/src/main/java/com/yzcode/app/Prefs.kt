package com.yzcode.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val DEFAULT_SERVER = "https://frp-act.com:17435"

    private const val PREFS_NAME = "yzcode_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"
    private const val KEY_SERVER_URL = "server_url"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLogin(context: Context, serverUrl: String, username: String, token: String) {
        sp(context).edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun token(context: Context): String? = sp(context).getString(KEY_TOKEN, null)

    fun username(context: Context): String? = sp(context).getString(KEY_USERNAME, null)

    fun serverUrl(context: Context): String =
        sp(context).getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER

    fun clearLogin(context: Context) {
        sp(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .apply()
    }
}
