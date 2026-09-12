package com.yzcode.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LoginActivity : Activity() {

    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var statusText: TextView
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.token(this) != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 40), Ui.dp(this@LoginActivity, 24), Ui.dp(this@LoginActivity, 24))
            setBackgroundColor(Ui.BG)
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "YZ-Code"
            textSize = 28f
            setTextColor(Ui.FG)
            setPadding(0, 0, 0, Ui.dp(this@LoginActivity, 8))
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "短信验证码自动上传"
            textSize = 14f
            setTextColor(Ui.SUBTLE)
            setPadding(0, 0, 0, Ui.dp(this@LoginActivity, 16))
        }
        root.addView(subtitle)

        root.addView(makeLabel("服务器地址"))
        serverInput = makeInput("http://host:port")
        serverInput.setText(Prefs.serverUrl(this))
        root.addView(serverInput)

        root.addView(makeLabel("用户名"))
        usernameInput = makeInput("2-32 位用户名")
        root.addView(usernameInput)

        root.addView(makeLabel("密码"))
        passwordInput = makeInput("至少 6 位密码")
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(passwordInput)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@LoginActivity, 20), 0, 0)
        }
        loginButton = Button(this).apply {
            text = "登录"
            setOnClickListener { doAuth(true) }
        }
        registerButton = Button(this).apply {
            text = "注册"
            setOnClickListener { doAuth(false) }
        }
        val weightLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val loginLp = LinearLayout.LayoutParams(weightLp)
        loginLp.rightMargin = Ui.dp(this, 8)
        val registerLp = LinearLayout.LayoutParams(weightLp)
        registerLp.leftMargin = Ui.dp(this, 8)
        buttonRow.addView(loginButton, loginLp)
        buttonRow.addView(registerButton, registerLp)
        root.addView(buttonRow)

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Ui.ERROR)
            setPadding(0, Ui.dp(this@LoginActivity, 16), 0, 0)
        }
        root.addView(statusText)

        setContentView(scroll)
    }

    private fun makeLabel(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Ui.SUBTLE)
        setPadding(0, Ui.dp(this@LoginActivity, 14), 0, Ui.dp(this@LoginActivity, 4))
    }

    private fun makeInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setTextColor(Ui.FG)
        setHintTextColor(Ui.SUBTLE)
        setSingleLine(true)
        setBackgroundColor(Ui.INPUT_BG)
        setPadding(Ui.dp(this@LoginActivity, 12), 0, Ui.dp(this@LoginActivity, 12), 0)
    }

    private fun setBusy(busy: Boolean, message: String) {
        loginButton.isEnabled = !busy
        registerButton.isEnabled = !busy
        statusText.text = message
    }

    private fun doAuth(isLogin: Boolean) {
        val server = serverInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请填写服务器、用户名和密码", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true, if (isLogin) "登录中…" else "注册中…")
        val callback = ApiClient.AuthCallback { ok, token, error ->
            runOnUiThread {
                setBusy(false, "")
                if (ok && !token.isNullOrEmpty()) {
                    Prefs.saveLogin(this@LoginActivity, server, username, token)
                    Toast.makeText(this@LoginActivity, "欢迎，$username", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    statusText.text = error ?: "请求失败，请稍后再试"
                }
            }
        }
        if (isLogin) {
            ApiClient.login(server, username, password, callback)
        } else {
            ApiClient.register(server, username, password, callback)
        }
    }
}
