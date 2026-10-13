/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一防重复操作机制：
 *  - [SingleFlight]：逻辑层单飞守卫，同一 key 在执行期间再次触发会被跳过（不排队），
 *    异常/取消时通过 finally 必释放，杜绝同一操作并发执行。
 *  - [View.guard]：静态可用按钮的 UI 锁，操作期间禁用、结束（含异常）恢复。
 * 状态驱动的按钮（如服药主按钮）不适用 [guard]，应订阅 [SingleFlight.inflight] 由现有状态逻辑决定可用性。
 */
object SingleFlight {
    private val inflightKeys = ConcurrentHashMap<String, Boolean>()
    private val _inflight = MutableStateFlow<Set<String>>(emptySet())

    /** 反应式「正在执行」的 key 集合，供状态驱动按钮 combine 订阅 */
    val inflight: StateFlow<Set<String>> = _inflight

    fun isRunning(key: String): Boolean = inflightKeys.containsKey(key)

    /** 单飞执行：若 key 已在执行则跳过返回 null；否则执行并在 finally 释放 */
    suspend fun <T> run(key: String, block: suspend () -> T): T? {
        if (inflightKeys.putIfAbsent(key, true) != null) return null
        _inflight.value = inflightKeys.keys.toSet()
        try {
            return block()
        } finally {
            inflightKeys.remove(key)
            _inflight.value = inflightKeys.keys.toSet()
        }
    }
}

/** 静态可用按钮：点击后立即禁用，操作结束（含异常）恢复可用。 */
fun View.guard(scope: CoroutineScope, key: String, block: suspend () -> Unit) {
    isEnabled = false
    scope.launch {
        try {
            SingleFlight.run(key, block)
        } finally {
            isEnabled = true
        }
    }
}
