/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.smartmedicinebox.data.db.MedicineEntity
import com.smartmedicinebox.data.db.RecordEntity
import com.smartmedicinebox.util.SingleFlight
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 药品列表适配器（含「服用」与「未服用」按钮）
 */
class MedicineAdapter(
    private val onItemClick: (MedicineEntity) -> Unit,
    private val onTake: (MedicineEntity) -> Unit,
    private val onTakePending: (RecordEntity) -> Unit,
    private val viewOnly: () -> Boolean = { false },
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }
) : RecyclerView.Adapter<MedicineAdapter.ViewHolder>() {

    /** 一行数据：药品 + 其未服用剂量 + 主「服用」按钮是否可用（R5 状态机） */
    data class MedicineRow(
        val medicine: MedicineEntity,
        val pendingDoses: List<RecordEntity>,
        val takeEnabled: Boolean = true
    )

    private var items: List<MedicineRow> = emptyList()

    fun submitList(list: List<MedicineRow>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_medicine, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvDosage: TextView = itemView.findViewById(R.id.tvDosage)
        private val tvStock: TextView = itemView.findViewById(R.id.tvStock)
        private val btnTake: Button = itemView.findViewById(R.id.btnTake)
        private val timeContainer: LinearLayout = itemView.findViewById(R.id.timeContainer)
        private val pendingContainer: LinearLayout = itemView.findViewById(R.id.pendingContainer)

        fun bind(row: MedicineRow) {
            val medicine = row.medicine
            tvName.text = medicine.name
            tvDosage.text = if (medicine.dosage.isNotEmpty()) medicine.dosage else itemView.context.getString(R.string.dosage_default)
            tvStock.text = if (medicine.stock > 0) {
                itemView.context.getString(R.string.stock_format, medicine.stock)
            } else {
                itemView.context.getString(R.string.stock_out)
            }
            tvStock.setTextColor(Color.parseColor(if (medicine.stock > 0) "#FA8C16" else "#FF4D4F"))
            tvStock.visibility = View.VISIBLE

            btnTake.visibility = if (viewOnly()) View.GONE else View.VISIBLE
            btnTake.isEnabled = row.takeEnabled
            btnTake.alpha = if (row.takeEnabled) 1f else 0.5f
            btnTake.setOnClickListener { onTake(medicine) }

            // 渲染时间点 chips
            timeContainer.removeAllViews()
            medicine.getTimeSlotList().forEach { time ->
                val chip = TextView(itemView.context).apply {
                    text = time.take(5)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, itemView.context.resources.getDimension(R.dimen.text_size_14))
                    setTextColor(Color.parseColor("#4A90D9"))
                    setBackgroundColor(Color.parseColor("#E8F4FD"))
                    setPadding(24, 8, 24, 8)
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 16 }
                timeContainer.addView(chip, lp)
            }

            // 渲染「xx:xx 未服用」（已按时间升序）；仅查看模式下不可点击（不能补服）
            pendingContainer.removeAllViews()
            row.pendingDoses.forEach { dose ->
                val busy = SingleFlight.isRunning("pending:${dose.id}")
                val btn = TextView(itemView.context).apply {
                    text = itemView.context.getString(R.string.pending_dose_format, formatTime(dose.scheduledTime))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, itemView.context.resources.getDimension(R.dimen.text_size_15))
                    setTextColor(Color.parseColor("#FF4D4F"))
                    setBackgroundColor(Color.parseColor("#FFF1F0"))
                    setPadding(24, 12, 24, 12)
                    if (!viewOnly()) {
                        setOnClickListener { onTakePending(dose) }
                        isClickable = !busy
                        alpha = if (busy) 0.5f else 1f
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
                pendingContainer.addView(btn, lp)
            }
            pendingContainer.visibility = if (row.pendingDoses.isEmpty()) View.GONE else View.VISIBLE

            itemView.setOnClickListener { onItemClick(medicine) }
        }

        private fun formatTime(millis: Long): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone(zoneProvider())
            }.format(Date(millis))
    }
}
