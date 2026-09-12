package com.yzcode.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var notifText: TextView
    private lateinit var listContainer: LinearLayout

    private val smsPermissions = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS
    )

    private fun allPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            smsPermissions + Manifest.permission.POST_NOTIFICATIONS
        else
            smsPermissions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val missing = allPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing) {
            requestPermissions(allPermissions(), REQ_PERMS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.token(this) != null && hasSmsPermissions()) {
            SmsMonitorService.start(this)
        }
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun hasSmsPermissions(): Boolean =
        smsPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun isNotifListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

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
        notifText = addInfoLine(root)

        val permButton = Button(this).apply {
            text = "申请短信权限"
            setOnClickListener {
                val missing = allPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing) {
                    requestPermissions(allPermissions(), REQ_PERMS)
                } else {
                    Toast.makeText(this@MainActivity, "权限已授予", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val permLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        permLp.topMargin = Ui.dp(this, 12)
        root.addView(permButton, permLp)

        val scanButton = Button(this).apply {
            text = "立即扫描最近短信"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "扫描中…", Toast.LENGTH_SHORT).show()
                Thread {
                    val n = SmsInbox.scanNewest(applicationContext, 15)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "扫描完成，新识别并上传 $n 条验证码", Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }.start()
            }
        }
        val scanLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        scanLp.topMargin = Ui.dp(this, 8)
        root.addView(scanButton, scanLp)

        val notifButton = Button(this).apply {
            text = "开启通知读取（验证码兜底）"
            setOnClickListener {
                if (isNotifListenerEnabled()) {
                    Toast.makeText(this@MainActivity, "通知读取已开启", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "请在列表中找到 YZ-Code 并允许读取通知", Toast.LENGTH_LONG).show()
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
            }
        }
        val notifLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        notifLp.topMargin = Ui.dp(this, 8)
        root.addView(notifButton, notifLp)

        val listTitle = TextView(this).apply {
            text = "最近记录（含状态诊断）"
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
            permText.text = "监听状态：运行中（前台服务 + 广播双通道）"
            permText.setTextColor(Ui.OK)
        } else {
            permText.text = "监听状态：未开启（缺少 RECEIVE_SMS / READ_SMS 权限）"
            permText.setTextColor(Ui.ERROR)
        }
        if (isNotifListenerEnabled()) {
            notifText.text = "通知读取：已开启（兜底通道就绪）"
            notifText.setTextColor(Ui.OK)
        } else {
            notifText.text = "通知读取：未开启（验证码被 ROM 隔离时需要）"
            notifText.setTextColor(Ui.ERROR)
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
                text = e.code + "    " + time + "\n来自：" + from + "    " + e.status
                textSize = 15f
                setTextColor(if (e.status == "上传成功") Ui.FG else Ui.ERROR)
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
            ApiClient.logout(server, token) { _, _ -> }
        }
        Prefs.clearLogin(this)
        SmsMonitorService.stop(this)
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        private const val REQ_PERMS = 1001
    }
}
