/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.smartmedicinebox.data.db.MedicineEntity
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.util.LocaleHelper
import com.smartmedicinebox.util.SingleFlight
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 回收站：查看被删除的药品，长按进入多选（恢复 / 永久删除）；超过 7 天未恢复的自动清除
 */
class TrashActivity : BaseActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private lateinit var selectionBar: View
    private lateinit var btnSelectAll: Button
    private lateinit var btnRestore: Button
    private lateinit var btnPurge: Button
    private lateinit var btnCancel: TextView
    private lateinit var tvTitle: TextView
    private lateinit var adapter: TrashAdapter

    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)

        listView = findViewById(R.id.trashList)
        emptyView = findViewById(R.id.emptyView)
        selectionBar = findViewById(R.id.selectionBar)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnRestore = findViewById(R.id.btnRestore)
        btnPurge = findViewById(R.id.btnPurge)
        btnCancel = findViewById(R.id.btnCancel)
        tvTitle = findViewById(R.id.tvTitle)

        adapter = TrashAdapter()
        listView.adapter = adapter

        btnCancel.setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener {
            if (selectedIds.size == adapter.count) {
                selectedIds.clear()
            } else {
                selectedIds.clear()
                selectedIds.addAll(adapter.itemIds())
            }
            adapter.notifyDataSetChanged()
            updateSelectionUI()
        }
        btnRestore.setOnClickListener { restoreSelected() }
        btnPurge.setOnClickListener { confirmPurgeSelected() }
        setupSelectionBarAutosize()

        // GC 后台执行，失败不影响列表展示（与 MainActivity 一致，包裹 try/catch 避免未捕获异常导致崩溃）
        lifecycleScope.launch {
            try { MedicineRepository.purgeExpiredTrash() } catch (_: Exception) {}
        }
        // 列表独立收集，不因 GC 失败/慢而被阻塞
        lifecycleScope.launch {
            MedicineRepository.getTrashedMedicines().collectLatest { list ->
                adapter.submitList(list)
                val ids = list.map { it.id }.toSet()
                selectedIds.retainAll(ids)
                if (selectionMode && selectedIds.isEmpty()) exitSelectionMode()
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                listView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                updateSelectionUI()
            }
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat(getString(R.string.record_date_format), LocaleHelper.currentLocale()).format(Date(millis))

    // ==================== 多选状态（独立于服药记录页实现） ====================

    private fun enterSelectionMode() {
        selectionMode = true
        selectionBar.visibility = View.VISIBLE
        btnCancel.visibility = View.VISIBLE
        updateSelectionUI()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        selectionBar.visibility = View.GONE
        btnCancel.visibility = View.GONE
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun toggleSelection(id: Long) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        adapter.notifyDataSetChanged()
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        if (selectionMode) {
            tvTitle.text = getString(R.string.selected_count, selectedIds.size)
            val allSelected = adapter.count > 0 && selectedIds.size == adapter.count
            btnSelectAll.text = getString(if (allSelected) R.string.deselect_all else R.string.select_all)
            btnRestore.isEnabled = selectedIds.isNotEmpty()
            btnRestore.alpha = if (selectedIds.isNotEmpty()) 1f else 0.5f
            btnPurge.isEnabled = selectedIds.isNotEmpty()
            btnPurge.alpha = if (selectedIds.isNotEmpty()) 1f else 0.5f
        } else {
            tvTitle.text = getString(R.string.trash_title)
        }
        applySelectionBarTextSize()
    }

    /** 恢复选中的药品（现有恢复逻辑逐个执行）；完成后退出选择状态，列表随 Flow 自动刷新 */
    private fun restoreSelected() {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toList()
        lifecycleScope.launch {
            SingleFlight.run("trash-restore") {
                try {
                    ids.forEach { MedicineRepository.restoreMedicine(it) }
                    Toast.makeText(this@TrashActivity, getString(R.string.toast_restored), Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                } catch (e: Exception) {
                    Toast.makeText(this@TrashActivity, e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmPurgeSelected() {
        if (selectedIds.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.common_confirm)
            .setMessage(getString(R.string.confirm_purge_medicines, selectedIds.size))
            .setPositiveButton(R.string.btn_purge) { _, _ -> purgeSelected() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 永久删除选中的药品（本地立即生效，不依赖服务器）；完成后退出选择状态，列表随 Flow 自动刷新 */
    private fun purgeSelected() {
        val ids = selectedIds.toList()
        lifecycleScope.launch {
            SingleFlight.run("trash-purge") {
                try {
                    ids.forEach { MedicineRepository.hardDeleteMedicine(it) }
                    Toast.makeText(this@TrashActivity, getString(R.string.toast_purged), Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                } catch (e: Exception) {
                    Toast.makeText(this@TrashActivity, e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 三个按钮的基准字号（取自 XML 的 _18ssp，已含字体缩放与屏幕比例，单位 px） */
    private var selectionBarBaseTextPx = 0f

    /** 让三个等宽按钮统一字号缩放，保证一排放得下（不省略号） */
    private fun setupSelectionBarAutosize() {
        selectionBarBaseTextPx = btnSelectAll.textSize
        selectionBar.doOnLayout { applySelectionBarTextSize() }
    }

    private fun applySelectionBarTextSize() {
        val buttons = arrayOf(btnSelectAll, btnRestore, btnPurge)
        if (buttons.any { it.width <= 0 }) return
        val basePx = selectionBarBaseTextPx
        if (basePx <= 0f) return

        // 取各按钮可用文字宽度中的最小值，保证最窄按钮也放得下
        val avail = buttons.minOf { (it.width - it.paddingLeft - it.paddingRight).toFloat() }
        if (avail <= 0f) return

        // 最长文本在基准字号下的宽度，据此统一缩放三个按钮
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = btnSelectAll.typeface
            textSize = basePx
        }
        val longest = buttons.maxOf { paint.measureText(it.text.toString()) }
        if (longest <= 0f) return

        // 放得下保持基准字号，放不下才一起缩小；0.5 倍为下限，避免文字过小难以辨认
        val scale = (avail / longest).coerceIn(0.5f, 1f)
        val targetPx = basePx * scale
        buttons.forEach { b ->
            if (abs(b.textSize - targetPx) > 0.5f) {
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, targetPx)
            }
        }
    }

    private inner class TrashAdapter : BaseAdapter() {
        private var items: List<MedicineEntity> = emptyList()

        fun submitList(list: List<MedicineEntity>) {
            items = list
            notifyDataSetChanged()
        }

        fun itemIds(): List<Long> = items.map { it.id }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val medicine = items[position]
            val context = parent!!.context
            val selected = medicine.id in selectedIds

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (selected) Color.parseColor("#E8F4FD") else Color.WHITE)
                setPadding(48, 32, 48, 32)
            }

            val density = context.resources.displayMetrics.density
            val indicator = ImageView(context).apply {
                setImageResource(if (selected) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked)
                visibility = if (selectionMode) View.VISIBLE else View.GONE
            }
            val indicatorLp = LinearLayout.LayoutParams(
                (24 * density).toInt(),
                (24 * density).toInt()
            ).apply { marginEnd = (24 * density).toInt() }

            // 第一行：药品名称；第二行：删除于<删除时间>（复用统一日期时间格式 record_date_format，两行均单行不换行）
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val nameText = TextView(context).apply {
                text = medicine.name
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_size_18))
                setTextColor(Color.parseColor("#333333"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val deletedAtText = TextView(context).apply {
                text = getString(R.string.trash_deleted_at_format, formatTime(medicine.deletedAt))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_size_15))
                setTextColor(Color.parseColor("#999999"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(nameText)
            content.addView(deletedAtText)

            root.addView(indicator, indicatorLp)
            root.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            root.setOnClickListener {
                if (selectionMode) toggleSelection(medicine.id)
            }
            root.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode()
                    selectedIds.add(medicine.id)
                    adapter.notifyDataSetChanged()
                    updateSelectionUI()
                }
                true
            }

            return root
        }
    }
}
