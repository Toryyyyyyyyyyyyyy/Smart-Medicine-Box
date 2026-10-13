/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.smartmedicinebox.util.LocaleHelper

/**
 * 所有 Activity 的基类：在 attachBaseContext 应用当前语言。
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
