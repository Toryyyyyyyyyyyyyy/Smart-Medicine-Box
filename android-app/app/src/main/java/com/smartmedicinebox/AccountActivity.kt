/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.util.Prefs
import com.smartmedicinebox.util.SingleFlight
import kotlinx.coroutines.launch

/**
 * 账号页：用户id（点击复制）/ 自定义用户名 / 更改登录密码 / 允许家人控制 / 注销账号。
 * 开启家人控制后额外显示「更改家人控制密码」与「仅允许查看」。
 */
class AccountActivity : BaseActivity() {

    private lateinit var tvUserIdValue: TextView
    private lateinit var tvUsernameValue: TextView
    private lateinit var swFamilyControl: Switch
    private lateinit var rowChangeLoginPassword: View
    private lateinit var rowViewOnly: View
    private lateinit var swViewOnly: Switch

    /** 刷新开关状态时的保护标志，避免程序化赋值触发回调 */
    private var refreshingFamilyControl = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        tvUserIdValue = findViewById(R.id.tvUserIdValue)
        tvUsernameValue = findViewById(R.id.tvUsernameValue)
        swFamilyControl = findViewById(R.id.swFamilyControl)
        rowChangeLoginPassword = findViewById(R.id.rowChangeLoginPassword)
        rowViewOnly = findViewById(R.id.rowViewOnly)
        swViewOnly = findViewById(R.id.swViewOnly)

        findViewById<View>(R.id.rowUserId).setOnClickListener { copyUserId() }
        findViewById<View>(R.id.rowUsername).setOnClickListener { editUsername() }
        findViewById<View>(R.id.rowDeleteAccount).setOnClickListener { confirmDeleteAccount() }
        rowChangeLoginPassword.setOnClickListener { changeLoginPassword() }

        swFamilyControl.setOnCheckedChangeListener { _, isChecked ->
            if (!refreshingFamilyControl) onFamilyControlToggled(isChecked)
        }
        swViewOnly.setOnCheckedChangeListener { _, isChecked ->
            if (!refreshingFamilyControl) onViewOnlyToggled(isChecked)
        }

        refreshAccount()
        refreshFamilyControl()
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
        refreshFamilyControl()
    }

    private fun refreshAccount() {
        tvUserIdValue.text = Prefs.userCode ?: getString(R.string.not_logged_in)
        tvUsernameValue.text = Prefs.username?.takeIf { it.isNotBlank() } ?: getString(R.string.not_logged_in)
    }

    private fun copyUserId() {
        val code = Prefs.userCode ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("user_code", code))
        Toast.makeText(this, getString(R.string.toast_user_code_copied), Toast.LENGTH_SHORT).show()
    }

    private fun refreshFamilyControl() {
        refreshingFamilyControl = true
        swFamilyControl.isChecked = Prefs.familyControlEnabled
        swViewOnly.isChecked = Prefs.familyControlViewOnly
        val showExtra = Prefs.familyControlEnabled
        rowViewOnly.visibility = if (showExtra) View.VISIBLE else View.GONE
        refreshingFamilyControl = false
    }

    private fun editUsername() {
        val input = EditText(this).apply {
            setText(Prefs.username.orEmpty())
            hint = getString(R.string.hint_username)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.label_username)
            .setView(input)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.length < 2 || name.length > 20 || name.any { it.isWhitespace() }) {
                    Toast.makeText(this, getString(R.string.toast_invalid_username), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        SingleFlight.run("updateUsername") { MedicineRepository.updateUsername(name) }
                            ?: return@launch
                        refreshAccount()
                        Toast.makeText(this@AccountActivity, getString(R.string.toast_username_updated), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AccountActivity, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 开启家人控制 → 要求设置密码；关闭 → 仅关开关（保留密码以便重开） */
    private fun onFamilyControlToggled(enabled: Boolean) {
        if (enabled) {
            showSetPasswordDialog()
        } else {
            lifecycleScope.launch {
                try {
                    SingleFlight.run("familyControl") { MedicineRepository.setFamilyControl(null, null, false) }
                        ?: return@launch
                    refreshFamilyControl()
                    Toast.makeText(this@AccountActivity, getString(R.string.toast_family_control_disabled), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@AccountActivity, e.message, Toast.LENGTH_SHORT).show()
                    refreshFamilyControl()
                }
            }
        }
    }

    private fun showSetPasswordDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_family_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.label_family_password)
            .setView(input)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                val pw = input.text.toString()
                if (pw.length < 6) {
                    Toast.makeText(this, getString(R.string.toast_password_too_short), Toast.LENGTH_SHORT).show()
                    refreshFamilyControl()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        SingleFlight.run("familyControl") { MedicineRepository.setFamilyControl(pw, null, true) }
                            ?: return@launch
                        refreshFamilyControl()
                        Toast.makeText(this@AccountActivity, getString(R.string.toast_family_control_enabled), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AccountActivity, e.message, Toast.LENGTH_SHORT).show()
                        refreshFamilyControl()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel) { _, _ -> refreshFamilyControl() }
            .show()
    }

    private fun onViewOnlyToggled(viewOnly: Boolean) {
        lifecycleScope.launch {
            try {
                SingleFlight.run("familyControl") { MedicineRepository.setFamilyControl(null, viewOnly, null) }
                    ?: return@launch
                refreshFamilyControl()
                Toast.makeText(this@AccountActivity, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AccountActivity, e.message, Toast.LENGTH_SHORT).show()
                refreshFamilyControl()
            }
        }
    }

    /** 更改登录密码（数据恒用登录密码加密，需同步重加密；家人控制开启时还需家人密码重包裹） */
    private fun changeLoginPassword() {
        val needFamily = Prefs.familyControlEnabled
        showChangePasswordDialog(
            titleRes = R.string.label_change_login_password,
            extraHintRes = if (needFamily) R.string.hint_family_password else null,
            onSubmit = { old, new, family -> MedicineRepository.changeLoginPassword(old, new, family) }
        )
    }

    private fun showChangePasswordDialog(
        titleRes: Int,
        extraHintRes: Int? = null,
        onSubmit: suspend (String, String, String?) -> Unit
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val oldInput = EditText(this).apply {
            hint = getString(R.string.hint_old_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val newInput = EditText(this).apply {
            hint = getString(R.string.hint_new_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(oldInput)
        container.addView(newInput)

        // 改登录密码且家人控制开启时，额外要求输入家人控制密码以重包裹家人密钥
        val extraInput: EditText? = extraHintRes?.let { hintRes ->
            EditText(this).apply {
                hint = getString(hintRes)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }.also { container.addView(it) }
        }

        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                val old = oldInput.text.toString()
                val new = newInput.text.toString()
                if (new.length < 6) {
                    Toast.makeText(this, getString(R.string.toast_password_too_short), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val extra = extraInput?.text?.toString()?.takeIf { it.isNotBlank() }
                lifecycleScope.launch {
                    try {
                        SingleFlight.run("changeLoginPassword") { onSubmit(old, new, extra) }
                            ?: return@launch
                        Toast.makeText(this@AccountActivity, getString(R.string.toast_password_changed), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AccountActivity, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle(R.string.label_delete_account)
            .setMessage(R.string.confirm_delete_account)
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteAccount() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun deleteAccount() {
        lifecycleScope.launch {
            try {
                SingleFlight.run("deleteAccount") { MedicineRepository.deleteAccount() }
                    ?: return@launch
                Toast.makeText(this@AccountActivity, getString(R.string.toast_account_deleted), Toast.LENGTH_SHORT).show()
                val intent = Intent(this@AccountActivity, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AccountActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
