/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.smartmedicinebox.data.repository.FamilyRemoteRepository
import com.smartmedicinebox.util.Prefs
import com.smartmedicinebox.util.guard

/**
 * 家属控制入口：输入控制对象用户id + 家人控制密码，无需登录即可远程查看/操作。
 */
class FamilyAccessActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Prefs.serverBaseUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.toast_server_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(R.layout.activity_family_access)

        findViewById<Button>(R.id.btnAccess).setOnClickListener { access() }
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, FamilyHistoryActivity::class.java))
        }
    }

    private fun access() {
        val userCode = findViewById<EditText>(R.id.etUserCode).text.toString().trim()
        val password = findViewById<EditText>(R.id.etPassword).text.toString()

        if (userCode.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_invalid_user_code), Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, getString(R.string.toast_password_too_short), Toast.LENGTH_SHORT).show()
            return
        }

        findViewById<Button>(R.id.btnAccess).guard(lifecycleScope, "familyAccess") {
            try {
                FamilyRemoteRepository.access(userCode, password)
                startActivity(Intent(this@FamilyAccessActivity, FamilyRemoteActivity::class.java))
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@FamilyAccessActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
