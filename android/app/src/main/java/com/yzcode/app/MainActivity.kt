package com.yzcode.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var userText: TextView
    private lateinit var serverText: TextView
    private lateinit var permText: TextView
    private lateinit var listContainer: LinearLayout

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (!hasSmsPermissions()) {
            requestPermissions(requiredPermissions, REQ_PERMS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun hasSmsPermissions(): Boolean =
        requiredPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24))
            setBackgroundColor(Ui.BG)
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "YZ-Code"
            textSize = 24f
            setTextColor(Ui.FG)
        }
        root.addView(title)

        userText = addInfoLine(root)
        serverText = addInfoLine(root)
        permText = addInfoLine(root)

        val permButton = Button(this).apply {
            text = "申请短信权限"
            setOnClickListener {
                if (!hasSmsPermissions()) {
                    requestPermissions(requiredPermissions, REQ_PERMS)
                } else {
                    Toast.makeText(this@MainActivity, "权限已授予", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val permLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        permLp.topMargin = Ui.dp(this, 12)
        root.addView(permButton, permLp)

        val listTitle = TextView(this).apply {
            text = "最近上传的验证码"
            textSize = 16f
            setTextColor(Ui.FG)
            setPadding(0, Ui.dp(this@MainActivity, 20), 0, Ui.dp(this@MainActivity, 8))
        }
        root.addView(listTitle)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer)

        val logoutButton = Button(this).apply {
            text = "退出登录"
            setOnClickListener { logout() }
        }
        val logoutLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        logoutLp.topMargin = Ui.dp(this, 24)
        root.addView(logoutButton, logoutLp)

        setContentView(scroll)
    }

    private fun addInfoLine(parent: LinearLayout): TextView {
        val tv = TextView(this).apply {
            textSize = 14f
            setTextColor(Ui.SUBTLE)
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
        }
        parent.addView(tv)
        return tv
    }

    private fun refresh() {
        userText.text = "当前用户：" + (Prefs.username(this) ?: "-")
        serverText.text = "服务器：" + Prefs.serverUrl(this)
        val granted = hasSmsPermissions()
        if (granted) {
            permText.text = "监听状态：运行中（短信权限已授予）"
            permText.setTextColor(Ui.OK)
        } else {
            permText.text = "监听状态：未开启（缺少 RECEIVE_SMS / READ_SMS 权限）"
            permText.setTextColor(Ui.ERROR)
        }

        listContainer.removeAllViews()
        val items = HistoryStore.list(this)
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无记录"
                textSize = 14f
                setTextColor(Ui.SUBTLE)
            }
            listContainer.addView(empty)
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        for (e in items) {
            val row = TextView(this).apply {
                val time = if (e.time > 0) fmt.format(Date(e.time)) else "-"
                val from = if (e.sender.isEmpty()) "-" else e.sender
                text = e.code + "    " + time + "\n来自：" + from
                textSize = 15f
                setTextColor(Ui.FG)
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8))
            }
            listContainer.addView(row)
            val divider = View(this)
            divider.setBackgroundColor(Ui.DIVIDER)
            listContainer.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
    }

    private fun logout() {
        val token = Prefs.token(this)
        val server = Prefs.serverUrl(this)
        if (token != null) {
            ApiClient.logout(server, token) { }
        }
        Prefs.clearLogin(this)
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        private const val REQ_PERMS = 1001
    }
}
