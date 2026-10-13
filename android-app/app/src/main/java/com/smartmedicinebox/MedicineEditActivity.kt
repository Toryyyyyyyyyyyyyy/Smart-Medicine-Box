/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.smartmedicinebox.alarm.AlarmScheduler
import com.smartmedicinebox.data.repository.FamilyRemoteRepository
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.data.repository.SessionState
import com.smartmedicinebox.util.SingleFlight
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * 添加/编辑药品
 */
class MedicineEditActivity : BaseActivity() {

    private var editingId: Long? = null
    private val timeSlots = mutableListOf("09:00:00", "12:00:00")
    private val selectedDays = mutableSetOf<Int>()  // 空 = 每天；否则为选中星期（1=周一…7=周日）
    private var repeatMode: String = AlarmScheduler.REPEAT_MODE_WEEKDAY
    private var videoPath: String? = null
    private var videoCrop: String? = null
    private var ringtoneUri: String? = null

    /** 家属远程模式：读写路由到 FamilyRemoteRepository（不写本机 Room、不调度闹钟） */
    private val familyMode: Boolean get() = SessionState.isFamilyRemote

    private lateinit var etName: EditText
    private lateinit var etDosage: EditText
    private lateinit var etStock: EditText
    private lateinit var etNotes: EditText
    private lateinit var timeContainer: LinearLayout
    private lateinit var repeatDaysContainer: LinearLayout
    private lateinit var repeatModeGroup: RadioGroup
    private lateinit var weekdayRepeatContainer: LinearLayout
    private lateinit var intervalContainer: LinearLayout
    private lateinit var etIntervalDays: EditText
    private lateinit var btnDelete: Button
    private lateinit var cbSound: CheckBox
    private lateinit var cbVibration: CheckBox
    private lateinit var cbVideo: CheckBox
    private lateinit var tvRingtoneName: TextView
    private lateinit var tvVideoPath: TextView

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveVideoToInternal(it) }
    }

    // 视频裁剪结果：裁剪已重新编码为新视频文件，直接用新路径，不再需要运行时裁剪
    private val cropVideo = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val croppedPath = result.data?.getStringExtra(CropActivity.EXTRA_CROPPED_PATH)
            if (croppedPath != null) {
                videoPath = croppedPath
                videoCrop = null
                tvVideoPath.text = getString(R.string.video_selected)
            }
        }
    }

    // 选择自定义铃声（本地音频文件）
    private val pickRingtoneAudio = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveRingtoneToInternal(it) }
    }

    // 系统铃声选择器
    private val pickSystemRingtone = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            ringtoneUri = uri.toString()
            tvRingtoneName.text = resolveRingtoneName(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medicine_edit)

        etName = findViewById(R.id.etName)
        etDosage = findViewById(R.id.etDosage)
        etStock = findViewById(R.id.etStock)
        etNotes = findViewById(R.id.etNotes)
        timeContainer = findViewById(R.id.timeSlotsContainer)
        repeatDaysContainer = findViewById(R.id.repeatDaysContainer)
        repeatModeGroup = findViewById(R.id.repeatModeGroup)
        weekdayRepeatContainer = findViewById(R.id.weekdayRepeatContainer)
        intervalContainer = findViewById(R.id.intervalContainer)
        etIntervalDays = findViewById(R.id.etIntervalDays)
        btnDelete = findViewById(R.id.btnDelete)
        cbSound = findViewById(R.id.cbSound)
        cbVibration = findViewById(R.id.cbVibration)
        cbVideo = findViewById(R.id.cbVideo)
        tvRingtoneName = findViewById(R.id.tvRingtoneName)
        tvVideoPath = findViewById(R.id.tvVideoPath)

        findViewById<Button>(R.id.btnAddTime).setOnClickListener { addTimeSlot() }
        findViewById<Button>(R.id.btnEveryDay).setOnClickListener { setEveryDay() }
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideo.launch("video/*") }
        findViewById<Button>(R.id.btnPickRingtone).setOnClickListener { showRingtoneDialog() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        repeatModeGroup.setOnCheckedChangeListener { _, checkedId ->
            repeatMode = if (checkedId == R.id.rbInterval) AlarmScheduler.REPEAT_MODE_INTERVAL else AlarmScheduler.REPEAT_MODE_WEEKDAY
            updateRepeatModeUI()
        }
        updateRepeatModeUI()
        btnDelete.setOnClickListener { confirmDelete() }

        // 编辑模式：加载已有药品
        editingId = intent.getLongExtra("medicine_id", -1L).takeIf { it > 0 }
        if (editingId != null) {
            title = getString(R.string.label_edit_medicine)
            btnDelete.visibility = View.VISIBLE
            loadMedicine(editingId!!)
        }

        setupRepeatDays()
        renderTimeSlots()
    }

    private fun loadMedicine(id: Long) {
        lifecycleScope.launch {
            val medicine = if (familyMode) {
                FamilyRemoteRepository.getMedicine(id)
            } else {
                App.instance.database.medicineDao().getById(id)
            }
            medicine?.let { m ->
                etName.setText(m.name)
                etDosage.setText(m.dosage)
                etStock.setText(m.stock.toString())
                etNotes.setText(m.notes)
                videoPath = m.videoPath
                videoCrop = m.videoCrop
                tvVideoPath.text = if (m.videoPath != null) getString(R.string.video_selected) else getString(R.string.no_video)
                ringtoneUri = m.ringtoneUri
                tvRingtoneName.text = if (m.ringtoneUri != null) {
                    resolveRingtoneName(Uri.parse(m.ringtoneUri))
                } else {
                    getString(R.string.default_ringtone)
                }

                timeSlots.clear()
                timeSlots.addAll(m.getTimeSlotList())
                if (timeSlots.isEmpty()) timeSlots.add("08:00:00")

                selectedDays.clear()
                selectedDays.addAll(parseRepeatDays(m.repeatDays))

                repeatMode = m.repeatMode
                etIntervalDays.setText(if (m.intervalDays >= 1) m.intervalDays.toString() else "")
                repeatModeGroup.check(if (repeatMode == AlarmScheduler.REPEAT_MODE_INTERVAL) R.id.rbInterval else R.id.rbWeekday)
                updateRepeatModeUI()

                val types = m.alarmType.split(",").map { it.trim() }.toSet()
                cbSound.isChecked = "sound" in types
                cbVibration.isChecked = "vibration" in types
                cbVideo.isChecked = "video" in types

                renderRepeatDays()
                renderTimeSlots()
            }
        }
    }

    // ==================== 重复星期 ====================

    /** 初始化周几按钮（一 ~ 日） */
    private fun setupRepeatDays() {
        val labels = resources.getStringArray(R.array.weekdays).toList()
        repeatDaysContainer.removeAllViews()
        labels.forEachIndexed { index, label ->
            val btn = Button(this).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_size_16))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(8, 8, 8, 8)
                setOnClickListener { toggleDay(index + 1) }
            }
            repeatDaysContainer.addView(btn)
        }
        renderRepeatDays()
    }

    /** 切换某天是否选中 */
    private fun toggleDay(day: Int) {
        if (selectedDays.isEmpty()) {
            // 当前「每天」→ 点掉这天，选中其余
            selectedDays.addAll((1..7).toList())
            selectedDays.remove(day)
        } else {
            if (day in selectedDays) selectedDays.remove(day) else selectedDays.add(day)
            if (selectedDays.size == 7) selectedDays.clear()  // 全选 = 每天
        }
        renderRepeatDays()
    }

    /** 「每天」：清空选择（空 = 每天） */
    private fun setEveryDay() {
        selectedDays.clear()
        renderRepeatDays()
    }

    /** 刷新周几按钮高亮 */
    private fun renderRepeatDays() {
        for (i in 0 until repeatDaysContainer.childCount) {
            val btn = repeatDaysContainer.getChildAt(i) as Button
            val selected = selectedDays.isEmpty() || (i + 1) in selectedDays
            if (selected) {
                btn.backgroundTintList = ColorStateList.valueOf(0xFF4A90D9.toInt())
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(0xFFE8F4FD.toInt())
                btn.setTextColor(0xFF4A90D9.toInt())
            }
        }
    }

    private fun parseRepeatDays(s: String): Set<Int> =
        s.split(",").filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }.toSet()

    /** 根据当前模式切换显示：按周几 / 每隔几天 */
    private fun updateRepeatModeUI() {
        val interval = repeatMode == AlarmScheduler.REPEAT_MODE_INTERVAL
        weekdayRepeatContainer.visibility = if (interval) View.GONE else View.VISIBLE
        intervalContainer.visibility = if (interval) View.VISIBLE else View.GONE
    }

    // ==================== 服用时间 ====================

    /** 添加一个时间点 */
    private fun addTimeSlot() {
        if (timeSlots.size >= 6) {
            Toast.makeText(this, getString(R.string.toast_max_time), Toast.LENGTH_SHORT).show()
            return
        }
        syncTimeSlotsFromPickers()
        timeSlots.add("08:00:00")
        renderTimeSlots()
    }

    /** 渲染时间点（每个卡片三个滚轮：时 / 分 / 秒） */
    private fun renderTimeSlots() {
        timeContainer.removeAllViews()
        timeSlots.forEachIndexed { index, time ->
            val card = layoutInflater.inflate(R.layout.item_time_picker, timeContainer, false)
            val parts = time.split(":")
            setupNumberPicker(card.findViewById(R.id.npHour), 0, 23, parts.getOrNull(0)?.toIntOrNull() ?: 8)
            setupNumberPicker(card.findViewById(R.id.npMinute), 0, 59, parts.getOrNull(1)?.toIntOrNull() ?: 0)
            setupNumberPicker(card.findViewById(R.id.npSecond), 0, 59, parts.getOrNull(2)?.toIntOrNull() ?: 0)

            card.findViewById<Button>(R.id.btnRemoveTime).setOnClickListener {
                syncTimeSlotsFromPickers()
                timeSlots.removeAt(index)
                renderTimeSlots()
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            timeContainer.addView(card, lp)
        }
    }

    /** 把当前滚轮的读数写回 timeSlots，避免增删时间点后丢失未保存的改动 */
    private fun syncTimeSlotsFromPickers() {
        for (i in 0 until timeContainer.childCount) {
            val card = timeContainer.getChildAt(i)
            val h = card.findViewById<NumberPicker>(R.id.npHour).value
            val m = card.findViewById<NumberPicker>(R.id.npMinute).value
            val s = card.findViewById<NumberPicker>(R.id.npSecond).value
            if (i < timeSlots.size) {
                timeSlots[i] = String.format("%02d:%02d:%02d", h, m, s)
            }
        }
    }

    private fun setupNumberPicker(picker: NumberPicker, min: Int, max: Int, value: Int) {
        picker.minValue = min
        picker.maxValue = max
        picker.value = value
        picker.setFormatter { String.format("%02d", it) }
    }

    /** 从滚轮读取所有时间点 */
    private fun collectTimeSlots(): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until timeContainer.childCount) {
            val card = timeContainer.getChildAt(i)
            val h = card.findViewById<NumberPicker>(R.id.npHour).value
            val m = card.findViewById<NumberPicker>(R.id.npMinute).value
            val s = card.findViewById<NumberPicker>(R.id.npSecond).value
            result.add(String.format("%02d:%02d:%02d", h, m, s))
        }
        return result
    }

    // ==================== 视频 / 铃声 ====================

    /** 保存视频到 App 内部存储（保证重启后仍可播放） */
    private fun saveVideoToInternal(uri: Uri) {
        lifecycleScope.launch {
            try {
                val dir = File(filesDir, "videos").apply { mkdirs() }
                val file = File(dir, "reminder_${System.currentTimeMillis()}.mp4")
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                videoPath = file.absolutePath
                videoCrop = null
                tvVideoPath.text = getString(R.string.video_selected)
                Toast.makeText(this@MedicineEditActivity, getString(R.string.toast_video_saved), Toast.LENGTH_SHORT).show()
                // 选择完视频后打开裁剪界面，让用户框选全屏显示区域
                // 家属远程裁剪时按控制对象设备的屏幕比例
                cropVideo.launch(Intent(this@MedicineEditActivity, CropActivity::class.java)
                    .putExtra(CropActivity.EXTRA_VIDEO_PATH, file.absolutePath)
                    .apply {
                        if (familyMode) {
                            FamilyRemoteRepository.seniorScreenAspect?.let {
                                putExtra(CropActivity.EXTRA_SCREEN_ASPECT, it)
                            }
                        }
                    })
            } catch (e: Exception) {
                Toast.makeText(this@MedicineEditActivity, getString(R.string.toast_video_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 保存自定义铃声到内部存储 */
    private fun saveRingtoneToInternal(uri: Uri) {
        lifecycleScope.launch {
            try {
                val dir = File(filesDir, "ringtones").apply { mkdirs() }
                val file = File(dir, "ringtone_${System.currentTimeMillis()}.mp3")
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                ringtoneUri = Uri.fromFile(file).toString()
                tvRingtoneName.text = getString(R.string.custom_ringtone_format, file.name)
                Toast.makeText(this@MedicineEditActivity, getString(R.string.toast_ringtone_saved), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MedicineEditActivity, getString(R.string.toast_ringtone_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 弹出铃声选择方式 */
    private fun showRingtoneDialog() {
        val options = arrayOf(getString(R.string.option_system_ringtone), getString(R.string.option_custom_audio))
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_ringtone)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openSystemRingtonePicker()
                    1 -> pickRingtoneAudio.launch("audio/*")
                }
            }
            .setNeutralButton(getString(R.string.option_restore_default)) { _, _ ->
                ringtoneUri = null
                tvRingtoneName.text = getString(R.string.default_ringtone)
            }
            .show()
    }

    /** 打开系统铃声选择器 */
    private fun openSystemRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.choose_ringtone))
            val current = ringtoneUri
            if (current != null && current.startsWith("content://")) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
            }
        }
        pickSystemRingtone.launch(intent)
    }

    /** 解析铃声显示名称 */
    private fun resolveRingtoneName(uri: Uri): String {
        return try {
            if (uri.scheme == "content") {
                RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: getString(R.string.ringtone_selected)
            } else {
                val path = uri.path ?: uri.toString()
                getString(R.string.custom_ringtone_format, path.substringAfterLast('/'))
            }
        } catch (e: Exception) {
            getString(R.string.ringtone_selected)
        }
    }

    private fun save() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_enter_name), Toast.LENGTH_SHORT).show()
            return
        }

        val collectedTimeSlots = collectTimeSlots()
        if (collectedTimeSlots.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_select_time), Toast.LENGTH_SHORT).show()
            return
        }

        val sound = cbSound.isChecked
        val vibration = cbVibration.isChecked
        val video = cbVideo.isChecked

        // 勾选了自定义视频但未选择视频文件
        if (video && videoPath == null) {
            Toast.makeText(this, getString(R.string.toast_select_video), Toast.LENGTH_SHORT).show()
            return
        }

        val types = mutableListOf<String>()
        if (sound) types.add("sound")
        if (vibration) types.add("vibration")
        if (video) types.add("video")
        val alarmType = types.joinToString(",")

        // 根据模式序列化重复信息
        val isInterval = repeatMode == AlarmScheduler.REPEAT_MODE_INTERVAL
        val repeatDays = if (isInterval) "" else selectedDays.sorted().joinToString(",")
        var intervalDays = 1
        if (isInterval) {
            intervalDays = etIntervalDays.text.toString().trim().toIntOrNull() ?: 0
            if (intervalDays < 1) {
                Toast.makeText(this, getString(R.string.toast_invalid_interval), Toast.LENGTH_SHORT).show()
                return
            }
        }
        val anchorDay = if (isInterval) LocalDate.now().toEpochDay() else 0L

        // 铃声和震动都未选择时，弹窗确认
        if (!sound && !vibration) {
            AlertDialog.Builder(this)
                .setTitle(R.string.common_confirm)
                .setMessage(R.string.confirm_no_sound_vibration)
                .setPositiveButton(R.string.common_confirm) { _, _ -> doSave(name, collectedTimeSlots, alarmType, repeatDays, repeatMode, intervalDays, anchorDay) }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
            return
        }

        doSave(name, collectedTimeSlots, alarmType, repeatDays, repeatMode, intervalDays, anchorDay)
    }

    /** 真正执行保存 */
    private fun doSave(name: String, timeSlotsList: List<String>, alarmType: String, repeatDays: String, repeatMode: String, intervalDays: Int, anchorDay: Long) {
        val dosage = etDosage.text.toString().trim()
        val stock = etStock.text.toString().trim().toIntOrNull() ?: 0
        val notes = etNotes.text.toString().trim()

        val saveBtn = findViewById<Button>(R.id.btnSave)
        saveBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                SingleFlight.run("save:${editingId ?: -1}") {
                    val editing = editingId
                    if (editing != null) {
                        if (familyMode) {
                            FamilyRemoteRepository.updateMedicine(
                                editing, name, dosage, timeSlotsList, stock, notes,
                                alarmType, videoPath, videoCrop, ringtoneUri, repeatDays, repeatMode, intervalDays, anchorDay
                            )
                        } else {
                            // 编辑模式：更新现有实体
                            val dao = App.instance.database.medicineDao()
                            val existing = dao.getById(editing)
                            existing?.let { m ->
                                val newTimeSlots = timeSlotsList.joinToString(",")
                                val timeSlotsChanged = m.timeSlots != newTimeSlots
                                dao.update(m.copy(
                                    name = name,
                                    dosage = dosage,
                                    timeSlots = newTimeSlots,
                                    stock = stock,
                                    notes = notes,
                                    alarmType = alarmType,
                                    repeatDays = repeatDays,
                                    repeatMode = repeatMode,
                                    intervalDays = intervalDays,
                                    anchorDay = anchorDay,
                                    videoPath = videoPath,
                                    videoCrop = videoCrop,
                                    ringtoneUri = ringtoneUri
                                ))
                                // 仅当闹钟时间点实际变化时，清空该药今天的未服用/暂不服用按钮并重新初始化状态
                                if (timeSlotsChanged) {
                                    MedicineRepository.resetDoseState(editing)
                                }
                                MedicineRepository.rescheduleAllAlarms()
                                // E2E 已开启时：把改动（含新自定义媒体加密上传）推到 vault
                                try { MedicineRepository.pushVault("medicines") } catch (_: Exception) {}
                            }
                        }
                    } else {
                        if (familyMode) {
                            FamilyRemoteRepository.addMedicine(
                                name, dosage, "daily", timeSlotsList, stock, notes,
                                alarmType, videoPath, videoCrop, ringtoneUri, repeatDays, repeatMode, intervalDays, anchorDay
                            )
                        } else {
                            MedicineRepository.addMedicine(
                                name, dosage, "daily", timeSlotsList, stock, notes, alarmType, videoPath, videoCrop, ringtoneUri, repeatDays, repeatMode, intervalDays, anchorDay
                            )
                        }
                    }
                } ?: return@launch
                Toast.makeText(this@MedicineEditActivity, getString(R.string.toast_save_success), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@MedicineEditActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                saveBtn.isEnabled = true
            }
        }
    }

    /** 删除当前药品（先确认） */
    private fun confirmDelete() {
        val editing = editingId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.common_confirm)
            .setMessage(R.string.confirm_delete_to_trash)
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteMedicine(editing) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 删除药品：移入回收站（取消闹钟与延迟提醒） */
    private fun deleteMedicine(id: Long) {
        btnDelete.isEnabled = false
        lifecycleScope.launch {
            try {
                SingleFlight.run("delete:$id") {
                    if (familyMode) {
                        FamilyRemoteRepository.getMedicine(id)?.let { m -> FamilyRemoteRepository.deleteMedicine(m) }
                    } else {
                        App.instance.database.medicineDao().getById(id)?.let { m ->
                            MedicineRepository.moveToTrash(m)
                        }
                    }
                } ?: return@launch
                Toast.makeText(this@MedicineEditActivity, getString(R.string.toast_moved_to_trash), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@MedicineEditActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                btnDelete.isEnabled = true
            }
        }
    }
}
