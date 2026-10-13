/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.smartmedicinebox.data.api.ApiClient
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.service.AlarmService
import kotlinx.coroutines.launch

/**
 * 登录页：用户id+密码登录，或 用户名+密码注册（注册后自动生成唯一用户id）。
 */
class LoginActivity : BaseActivity() {

    private lateinit var etAccount: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSubmit: Button
    private lateinit var rbRegister: RadioButton
    private lateinit var btnSkip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etAccount = findViewById(R.id.etAccount)
        etPassword = findViewById(R.id.etPassword)
        btnSubmit = findViewById(R.id.btnSubmit)
        rbRegister = findViewById(R.id.rbRegister)
        btnSkip = findViewById(R.id.btnSkip)

        findViewById<RadioGroup>(R.id.rgMode).setOnCheckedChangeListener { _, _ -> updateModeUi() }
        btnSubmit.setOnClickListener { onSubmit() }
        btnSkip.setOnClickListener {
            // 不登录，离线使用
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        updateModeUi()
    }

    private fun isRegisterMode(): Boolean = rbRegister.isChecked

    /** 根据登录/注册模式切换输入框提示与键盘类型 */
    private fun updateModeUi() {
        if (isRegisterMode()) {
            etAccount.hint = getString(R.string.hint_username)
            etAccount.inputType = InputType.TYPE_CLASS_TEXT
            btnSubmit.text = getString(R.string.btn_register)
        } else {
            etAccount.hint = getString(R.string.hint_user_code)
            etAccount.inputType = InputType.TYPE_CLASS_NUMBER
            btnSubmit.text = getString(R.string.btn_login)
        }
    }

    private fun validateAccount(): String? {
        val input = etAccount.text.toString().trim()
        if (isRegisterMode()) {
            if (input.length < 2 || input.length > 20 || input.any { it.isWhitespace() }) {
                Toast.makeText(this, getString(R.string.toast_invalid_username), Toast.LENGTH_SHORT).show()
                return null
            }
        } else {
            if (input.length != 8 || !input.all { it.isDigit() }) {
                Toast.makeText(this, getString(R.string.toast_invalid_user_code), Toast.LENGTH_SHORT).show()
                return null
            }
        }
        return input
    }

    private fun validatePassword(): String? {
        val password = etPassword.text.toString()
        if (password.length < 6) {
            Toast.makeText(this, getString(R.string.toast_password_too_short), Toast.LENGTH_SHORT).show()
            return null
        }
        return password
    }

    private fun onSubmit() {
        val account = validateAccount() ?: return
        val password = validatePassword() ?: return
        val block: suspend () -> Unit = if (isRegisterMode()) {
            { MedicineRepository.register(account, password) }
        } else {
            { MedicineRepository.login(account, password) }
        }
        doAuth(block)
    }

    private fun doAuth(block: suspend () -> Unit) {
        btnSubmit.isEnabled = false
        lifecycleScope.launch {
            try {
                block()
                // 登录/注册后双向合并同步（云端独有下载、本地独有上传）；E2E 未解锁时由 MainActivity 引导解锁后再同步
                try { MedicineRepository.syncMerge() } catch (_: Exception) {}
                AlarmService.start(this@LoginActivity)

                Toast.makeText(this@LoginActivity, getString(R.string.toast_login_success), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                // 网络/证书/身份错误与服务器业务错误区分提示（安全失败不误报成普通故障）
                Toast.makeText(this@LoginActivity, ApiClient.friendlyMessage(e), Toast.LENGTH_SHORT).show()
                btnSubmit.isEnabled = true
            }
        }
    }
}
