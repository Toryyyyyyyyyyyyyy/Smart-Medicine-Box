/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.smartmedicinebox.data.db.RecordEntity
import com.smartmedicinebox.data.repository.FamilyRemoteRepository
import com.smartmedicinebox.data.repository.SessionState
import com.smartmedicinebox.util.LocaleHelper
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * 记录 tab：服药记录（长按进入多选，可撤销/删除）
 */
class RecordFragment : Fragment() {

    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private lateinit var selectionBar: View
    private lateinit var btnSelectAll: Button
    private lateinit var btnUndo: Button
    private lateinit var btnDelete: Button
    private lateinit var btnCancel: TextView
    private lateinit var tvTitle: TextView
    private lateinit var adapter: RecordAdapter

    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    private fun statusText(status: String): String = when (status) {
        "taken" -> getString(R.string.status_taken)
        "taken_on_time" -> getString(R.string.status_taken_on_time)
        "taken_early" -> getString(R.string.status_taken_early)
        "taken_late" -> getString(R.string.status_taken_late)
        "pending" -> getString(R.string.status_pending)
        "missed" -> getString(R.string.status_missed)
        "skipped" -> getString(R.string.status_skipped)
        else -> status
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_record, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView = view.findViewById(R.id.listView)
        emptyView = view.findViewById(R.id.emptyView)
        selectionBar = view.findViewById(R.id.selectionBar)
        btnSelectAll = view.findViewById(R.id.btnSelectAll)
        btnUndo = view.findViewById(R.id.btnUndo)
        btnDelete = view.findViewById(R.id.btnDelete)
        btnCancel = view.findViewById(R.id.btnCancel)
        tvTitle = view.findViewById(R.id.tvTitle)

        adapter = RecordAdapter()
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
        btnDelete.setOnClickListener { confirmDeleteSelected() }
        btnUndo.setOnClickListener { confirmUndoSelected() }
        setupSelectionBarAutosize()

        viewLifecycleOwner.lifecycleScope.launch {
            SessionState.records().collectLatest { records ->
                adapter.submitList(records)
                val ids = records.map { it.id }.toSet()
                selectedIds.retainAll(ids)
                if (selectionMode && selectedIds.isEmpty()) exitSelectionMode()
                emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                listView.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
                updateSelectionUI()
            }
        }
    }

    /** 时间显示时区：家属远程模式按目标用户本地时区，本人模式按本机时区 */
    private fun displayZone(): ZoneId =
        if (SessionState.isFamilyRemote) FamilyRemoteRepository.seniorZone() else ZoneId.systemDefault()

    private fun formatTime(millis: Long): String =
        SimpleDateFormat(getString(R.string.record_date_format), LocaleHelper.currentLocale())
            .apply { timeZone = TimeZone.getTimeZone(displayZone()) }.format(Date(millis))

    private fun formatTimeOnly(millis: Long): String =
        SimpleDateFormat("HH:mm", LocaleHelper.currentLocale())
            .apply { timeZone = TimeZone.getTimeZone(displayZone()) }.format(Date(millis))

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
            btnDelete.isEnabled = selectedIds.isNotEmpty()
            btnDelete.alpha = if (selectedIds.isNotEmpty()) 1f else 0.5f
            val undoEnabled = selectedIds.size == 1 && selectedIds.firstOrNull()?.let { id ->
                adapter.recordById(id)?.let { record -> isUndoable(record) } ?: false
            } == true
            btnUndo.isEnabled = undoEnabled
            btnUndo.alpha = if (undoEnabled) 1f else 0.5f
        } else {
            tvTitle.text = getString(R.string.record_title)
        }
        applySelectionBarTextSize()
    }

    /** 三个按钮的基准字号（取自 XML 的 _18ssp，已含字体缩放与屏幕比例，单位 px） */
    private var selectionBarBaseTextPx = 0f

    /** 让三个等宽按钮统一字号缩放，保证一排放得下（不省略号） */
    private fun setupSelectionBarAutosize() {
        selectionBarBaseTextPx = btnSelectAll.textSize
        selectionBar.doOnLayout { applySelectionBarTextSize() }
    }

    private fun applySelectionBarTextSize() {
        val buttons = arrayOf(btnSelectAll, btnUndo, btnDelete)
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

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.common_confirm)
            .setMessage(getString(R.string.confirm_delete_records, selectedIds.size))
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteSelected() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun deleteSelected() {
        val ids = selectedIds.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SessionState.deleteRecords(ids)
                Toast.makeText(requireContext(), getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmUndoSelected() {
        if (selectedIds.size != 1) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.common_confirm)
            .setMessage(R.string.confirm_undo_record)
            .setPositiveButton(R.string.btn_undo) { _, _ -> undoSelected() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun undoSelected() {
        val id = selectedIds.firstOrNull() ?: return
        val record = adapter.recordById(id) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SessionState.undoTaken(record)
                Toast.makeText(requireContext(), getString(R.string.toast_undone), Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 是否可撤销：仅当天已服用（按时/提前/推迟）记录，且未被闹钟时间变更冻结 */
    private fun isUndoable(record: RecordEntity): Boolean {
        val taken = record.status == "taken" || record.status == "taken_on_time" ||
            record.status == "taken_early" || record.status == "taken_late"
        return taken && isToday(record.scheduledTime) && record.undoable
    }

    /** 该时间戳是否属于今天（0 点 ~ 次日 0 点，按显示时区；家人模式按本人端当前时间判定，家属手机本地时钟不参与） */
    private fun isToday(millis: Long): Boolean {
        val tz = TimeZone.getTimeZone(displayZone())
        val now = if (SessionState.isFamilyRemote) FamilyRemoteRepository.seniorNow() else System.currentTimeMillis()
        val start = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        return millis >= start && millis < end
    }

    private inner class RecordAdapter : BaseAdapter() {
        private var items: List<RecordEntity> = emptyList()

        fun submitList(list: List<RecordEntity>) {
            items = list
            notifyDataSetChanged()
        }

        fun itemIds(): List<Long> = items.map { it.id }

        fun recordById(id: Long): RecordEntity? = items.firstOrNull { it.id == id }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val record = items[position]
            val context = parent!!.context
            val selected = record.id in selectedIds

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

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            val displayName = record.medicineName.takeIf { it.isNotBlank() }
                ?: getString(R.string.record_fallback_name, record.medicineId.toString())

            // 第一行：药品名称
            val nameText = TextView(context).apply {
                text = displayName
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_size_18))
                setTextColor(Color.parseColor("#333333"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // 第二行：计划时间：<计划时间>（复用统一日期时间格式 record_date_format）
            val plannedText = TextView(context).apply {
                text = getString(R.string.record_scheduled_time_format, formatTime(record.scheduledTime))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_size_15))
                setTextColor(Color.parseColor("#999999"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // 第三行：<状态>·实际<实际时间>（无实际时间只显示状态）；单行显示，禁止换行
            val takenTime = record.takenTime
            val status = TextView(context).apply {
                text = if (takenTime != null) {
                    getString(R.string.record_status_time_format, statusText(record.status), formatTimeOnly(takenTime))
                } else {
                    statusText(record.status)
                }
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_size_15))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(
                    when (record.status) {
                        "taken", "taken_on_time" -> Color.parseColor("#52C41A")
                        "taken_early", "taken_late" -> Color.parseColor("#FAAD14")
                        "missed" -> Color.parseColor("#FF4D4F")
                        else -> Color.parseColor("#FAAD14")
                    }
                )
            }

            content.addView(nameText)
            content.addView(plannedText)
            content.addView(status)

            root.addView(indicator, indicatorLp)
            root.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            root.setOnClickListener {
                if (selectionMode) toggleSelection(record.id)
            }
            root.setOnLongClickListener {
                if (SessionState.viewOnly) return@setOnLongClickListener false
                if (!selectionMode) {
                    enterSelectionMode()
                    selectedIds.add(record.id)
                    adapter.notifyDataSetChanged()
                    updateSelectionUI()
                }
                true
            }

            return root
        }
    }
}
