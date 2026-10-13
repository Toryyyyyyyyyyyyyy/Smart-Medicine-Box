/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * 运行时语言切换：将语言选择包装进 Context 配置。
 * 在 attachBaseContext 阶段调用，可保证 Activity / Service / Receiver 都使用目标语言。
 */
object LocaleHelper {

    /** 用目标语言 + 字体大小包装 Context（在 attachBaseContext 中调用） */
    fun wrap(context: Context): Context {
        val locale = currentLocale(context)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.fontScale = fontScale(context)
        return context.createConfigurationContext(config)
    }

    /** 读取字体缩放倍率（不依赖 App.instance，attachBaseContext 阶段可用） */
    fun fontScale(context: Context): Float {
        val size = context.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Prefs.KEY_FONT_SIZE, Prefs.FONT_NORMAL) ?: Prefs.FONT_NORMAL
        // 默认稍小于系统默认；“大”取默认与“特大”的中间值
        return when (size) {
            Prefs.FONT_LARGE -> 1.1f
            Prefs.FONT_XLARGE -> 1.3f
            else -> 0.9f
        }
    }

    /** 读取当前语言（不依赖 App.instance，attachBaseContext 阶段可用） */
    fun currentLocale(context: Context): Locale {
        val lang = context.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Prefs.KEY_LANGUAGE, Prefs.LANG_SYSTEM) ?: Prefs.LANG_SYSTEM
        return resolve(lang)
    }

    /** Activity 内取当前 Locale（用于日期格式化等） */
    fun currentLocale(): Locale = resolve(Prefs.language)

    /** 解析语言设置：zh→中文，en→英文，system→跟随系统（非中文一律英文） */
    private fun resolve(lang: String): Locale = when (lang) {
        Prefs.LANG_ZH -> Locale.SIMPLIFIED_CHINESE
        Prefs.LANG_EN -> Locale.ENGLISH
        else -> if (isSystemChinese()) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
    }

    /** 系统语言是否为中文；其余（含英文）一律回落英文 */
    private fun isSystemChinese(): Boolean {
        val systemLocale = Resources.getSystem().configuration.locales[0]
        return systemLocale.language.equals("zh", ignoreCase = true)
    }
}
