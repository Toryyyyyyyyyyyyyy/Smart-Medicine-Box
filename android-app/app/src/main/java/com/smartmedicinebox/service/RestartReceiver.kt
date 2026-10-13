/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 被系统闹钟触发的重启接收器：进程被杀/从最近任务划掉后，重新拉起前台服务
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AlarmService.start(context)
    }
}
