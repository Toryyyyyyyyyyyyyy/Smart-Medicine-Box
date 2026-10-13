/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.smartmedicinebox.data.repository.FamilyRemoteRepository
import com.smartmedicinebox.util.FamilyHistoryEntry
import com.smartmedicinebox.util.FamilyHistoryStore
import com.smartmedicinebox.util.SingleFlight
import kotlinx.coroutines.launch

/**
 * 家属控制历史记录：列出所有控制过的用户（用户名、用户id、密码），
 * 点击直接进入控制界面；若对方已改密或关闭控制则提示「控制失败」。
 */
class FamilyHistoryActivity : BaseActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_family_history)

        listView = findViewById(R.id.historyList)
        emptyView = findViewById(R.id.emptyView)
        adapter = HistoryAdapter()
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            enter(adapter.getItem(position) as FamilyHistoryEntry)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            delete(adapter.getItem(position) as FamilyHistoryEntry)
            true
        }

        reload()
    }

    private fun reload() {
        adapter.submitList(FamilyHistoryStore.all())
        emptyView.visibility = if (adapter.count == 0) View.VISIBLE else View.GONE
        listView.visibility = if (adapter.count == 0) View.GONE else View.VISIBLE
    }

    private fun enter(entry: FamilyHistoryEntry) {
        lifecycleScope.launch {
            try {
                SingleFlight.run("familyAccess:${entry.userCode}") {
                    FamilyRemoteRepository.access(entry.userCode, entry.password)
                } ?: return@launch
                startActivity(Intent(this@FamilyHistoryActivity, FamilyRemoteActivity::class.java))
                finish()
            } catch (e: Exception) {
                // 优先展示服务器返回的具体原因（如「控制对象未连接服务器」），无法解析时退回通用提示
                Toast.makeText(
                    this@FamilyHistoryActivity,
                    e.message ?: getString(R.string.toast_control_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun delete(entry: FamilyHistoryEntry) {
        FamilyHistoryStore.remove(entry.userCode)
        reload()
        Toast.makeText(this, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
    }

    private inner class HistoryAdapter : BaseAdapter() {
        private var items: List<FamilyHistoryEntry> = emptyList()

        fun submitList(list: List<FamilyHistoryEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(parent!!.context)
                .inflate(R.layout.item_family_history, parent, false)
            val entry = items[position]
            val context = view.context

            val usernameLabel = context.getString(R.string.label_username_plain)
            val codeLabel = context.getString(R.string.label_user_code)
            val passwordLabel = context.getString(R.string.label_password_plain)

            view.findViewById<TextView>(R.id.tvUsername).text = "$usernameLabel: ${entry.username}"
            view.findViewById<TextView>(R.id.tvUserCode).text = "$codeLabel: ${entry.userCode}"
            view.findViewById<TextView>(R.id.tvPassword).text = "$passwordLabel: ${entry.password}"
            return view
        }
    }
}
