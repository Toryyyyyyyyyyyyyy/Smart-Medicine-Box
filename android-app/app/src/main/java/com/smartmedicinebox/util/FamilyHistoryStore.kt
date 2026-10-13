/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.smartmedicinebox.App

/**
 * 家属控制历史记录：本机保存「控制过的用户」的用户名、用户id、密码，
 * 供下次在历史记录里一键进入控制界面。
 *
 * 同步：历史记录随「登录账号」同步——本人端把记录（含删除墓碑）作为账户级
 * vault blob 端到端加密上传（服务器只见密文，密码绝不明文传输）；换设备登录后
 * 拉取按时间戳合并。家属设备无账号，其历史保持纯本地，不参与同步。
 */
data class FamilyHistoryEntry(
    val username: String,
    val userCode: String,
    val password: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** 删除墓碑：>0 表示该记录已在某设备上被删除，供跨设备删除传播 */
    val deletedAt: Long = 0
)

object FamilyHistoryStore {

    private const val KEY = "family_control_history"
    private const val KEY_DIRTY = "family_control_history_dirty"
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<FamilyHistoryEntry>>() {}.type

    private val sp
        get() = App.instance.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE)

    private fun readAll(): List<FamilyHistoryEntry> {
        val json = sp.getString(KEY, null) ?: return emptyList()
        return try {
            gson.fromJson<List<FamilyHistoryEntry>>(json, listType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(list: List<FamilyHistoryEntry>) {
        sp.edit().putString(KEY, gson.toJson(list)).apply()
    }

    /** 全部有效历史记录（不含删除墓碑），最新的在最前 */
    fun all(): List<FamilyHistoryEntry> =
        readAll().filter { it.deletedAt == 0L }.sortedByDescending { it.timestamp }

    /** 全部记录（含删除墓碑），供同步上传 */
    fun rawAll(): List<FamilyHistoryEntry> = readAll()

    /** 保存/更新一条记录：同 userCode 覆盖并置顶；标记待同步 */
    fun save(entry: FamilyHistoryEntry) {
        val list = readAll().toMutableList()
        list.removeAll { it.userCode == entry.userCode }
        list.add(0, entry.copy(deletedAt = 0, timestamp = System.currentTimeMillis()))
        writeAll(list)
        markDirty()
    }

    /** 删除某条记录：本地标记删除墓碑（跨设备传播用），列表立即不再显示 */
    fun remove(userCode: String) {
        val now = System.currentTimeMillis()
        val list = readAll().map {
            if (it.userCode == userCode) it.copy(deletedAt = now, timestamp = now) else it
        }
        writeAll(list)
        markDirty()
    }

    /** 清空历史记录 */
    fun clear() {
        sp.edit().remove(KEY).apply()
    }

    // ---- 同步 ----

    /** 合并拉取到的远端记录（按时间戳较新者优先；远端墓碑删除本地旧记录）。返回是否有变化。 */
    fun merge(remote: List<FamilyHistoryEntry>): Boolean {
        if (remote.isEmpty()) return false
        val local = readAll().toMutableList()
        var changed = false
        for (r in remote) {
            val idx = local.indexOfFirst { it.userCode == r.userCode }
            if (r.deletedAt > 0) {
                // 远端墓碑：本地记录若未在删除之后被修改，则一并删除
                if (idx >= 0 && local[idx].timestamp <= r.timestamp) {
                    local.removeAt(idx)
                    changed = true
                }
            } else if (idx < 0) {
                local.add(r)
                changed = true
            } else if (r.timestamp > local[idx].timestamp) {
                local[idx] = r
                changed = true
            }
        }
        if (changed) writeAll(local)
        return changed
    }

    /** 本地是否有未同步的改动（保存/删除后置位，成功推送后清除） */
    fun isDirty(): Boolean = sp.getBoolean(KEY_DIRTY, false)

    fun markDirty() {
        sp.edit().putBoolean(KEY_DIRTY, true).apply()
    }

    fun clearDirty() {
        sp.edit().putBoolean(KEY_DIRTY, false).apply()
    }
}
