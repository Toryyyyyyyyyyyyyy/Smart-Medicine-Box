/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启接收器：开机后启动前台服务，恢复所有闹钟
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_REBOOT
        ) {
            // 启动前台服务，服务内部会重新调度所有闹钟
            AlarmService.start(context)
        }
    }
}
