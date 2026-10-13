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
import com.smartmedicinebox.data.api.ApiClient
import com.smartmedicinebox.data.api.FamilyBindRequest
import com.smartmedicinebox.util.Prefs
import com.smartmedicinebox.util.guard

/**
 * 家属绑定页
 */
class FamilyActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 家属绑定需要服务器，未登录先引导登录
        if (!Prefs.isLoggedIn) {
            Toast.makeText(this, getString(R.string.toast_login_first), Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_family)

        findViewById<Button>(R.id.btnBind).setOnClickListener { bind() }
    }

    private fun bind() {
        val userCode = findViewById<EditText>(R.id.etSeniorUserCode).text.toString().trim()
        if (userCode.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_invalid_user_code), Toast.LENGTH_SHORT).show()
            return
        }

        findViewById<Button>(R.id.btnBind).guard(lifecycleScope, "bindSenior") {
            try {
                val token = "Bearer ${Prefs.token.orEmpty()}"
                val resp = ApiClient.getService().bindSenior(token, FamilyBindRequest(userCode))
                val body = resp.body()
                if (resp.isSuccessful && body != null && body.code == 0) {
                    Toast.makeText(this@FamilyActivity, getString(R.string.toast_bind_success), Toast.LENGTH_SHORT).show()
                } else if (!resp.isSuccessful) {
                    Toast.makeText(this@FamilyActivity, ApiClient.errorText(resp), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FamilyActivity, body?.message ?: getString(R.string.toast_bind_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@FamilyActivity, "${getString(R.string.toast_bind_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
