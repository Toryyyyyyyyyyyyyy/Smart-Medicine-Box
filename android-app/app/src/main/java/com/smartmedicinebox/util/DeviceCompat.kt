/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

import android.os.Build

/** 设备/系统特性判断：特殊权限的适用条件（首次引导与设置页权限列表共用，保持一致） */
object DeviceCompat {

    /** 是否华为/鸿蒙设备：其系统要求「悬浮窗」与「后台弹窗」权限才会在锁屏上直接弹出全屏闹钟 */
    fun isHuaweiDevice(): Boolean {
        val display = Build.DISPLAY ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        return display.contains("HarmonyOS", true) ||
            display.contains("EMUI", true) ||
            manufacturer.contains("huawei", true)
    }
}
