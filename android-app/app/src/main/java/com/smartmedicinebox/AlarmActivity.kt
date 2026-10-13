/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.graphics.Matrix
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.min
import com.smartmedicinebox.alarm.AlarmScheduler
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.util.guard
import kotlinx.coroutines.launch

/**
 * 全屏闹钟弹窗：锁屏弹出，播放视频/铃声/震动。
 * 视频已在 CropActivity 裁剪重编码，播放端按 ExoPlayer 上报的旋转角摆正（见 applyVideoTransform）。
 */
class AlarmActivity : BaseActivity() {

    private var medicineId: Long = 0L
    private var medicineName: String = ""
    private var dosage: String = ""
    private var time: String = "08:00"
    private var alarmType: String = "sound"
    private var videoPath: String? = null
    private var videoCrop: String? = null
    private var ringtoneUri: String? = null
    private var repeatDays: String = ""
    private var repeatMode: String = AlarmScheduler.REPEAT_MODE_WEEKDAY
    private var intervalDays: Int = 1
    private var anchorDay: Long = 0L
    private var slotIndex: Int = 0
    private var scheduledTime: Long = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var exoPlayer: ExoPlayer? = null
    private var textureView: TextureView? = null
    // ExoPlayer 上报的实际解码尺寸与未应用旋转角，用于摆正视频
    private var videoSize: VideoSize? = null
    private var alarmButtonBaseTextPx = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏显示 + 点亮屏幕 + 保持唤醒（setShowWhenLocked/setTurnScreenOn 仅 API 27+ 可用）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        setContentView(R.layout.activity_alarm)

        // 读取参数
        medicineId = intent.getLongExtra(AlarmScheduler.EXTRA_MEDICINE_ID, 0L)
        medicineName = intent.getStringExtra(AlarmScheduler.EXTRA_MEDICINE_NAME) ?: getString(R.string.default_medicine_name)
        dosage = intent.getStringExtra(AlarmScheduler.EXTRA_DOSAGE) ?: ""
        time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME) ?: "08:00"
        alarmType = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TYPE) ?: "sound"
        videoPath = intent.getStringExtra(AlarmScheduler.EXTRA_VIDEO_PATH)
        videoCrop = intent.getStringExtra(AlarmScheduler.EXTRA_VIDEO_CROP)
        ringtoneUri = intent.getStringExtra(AlarmScheduler.EXTRA_RINGTONE_URI)
        repeatDays = intent.getStringExtra(AlarmScheduler.EXTRA_REPEAT_DAYS) ?: ""
        repeatMode = intent.getStringExtra(AlarmScheduler.EXTRA_REPEAT_MODE) ?: AlarmScheduler.REPEAT_MODE_WEEKDAY
        intervalDays = intent.getIntExtra(AlarmScheduler.EXTRA_INTERVAL_DAYS, 1)
        anchorDay = intent.getLongExtra(AlarmScheduler.EXTRA_ANCHOR_DAY, 0L)
        slotIndex = intent.getIntExtra(AlarmScheduler.EXTRA_SLOT_INDEX, 0)
        scheduledTime = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_TIME, 0L)

        // 重新调度下一次同时间的闹钟（原先由 AlarmReceiver 负责，现改为全屏 Activity 直接作为闹钟目标）
        if (medicineId > 0) {
            AlarmScheduler.scheduleOne(
                this, medicineId, medicineName, dosage, time, alarmType, videoPath, videoCrop, ringtoneUri,
                repeatDays, repeatMode, intervalDays, anchorDay, slotIndex, scheduledTime
            )
        }

        // 取消顶部通知，避免与全屏弹窗同时出现重复记录
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(medicineId.toInt())
        } catch (_: Exception) {
        }

        bindViews()
        checkStockWarning()
        startAlert()
    }

    private fun bindViews() {
        findViewById<TextView>(R.id.tvTime).text = time.take(5)
        findViewById<TextView>(R.id.tvMedicineName).text = getString(R.string.alarm_take_medicine, medicineName)
        findViewById<TextView>(R.id.tvDosage).text =
            if (dosage.isNotEmpty()) getString(R.string.alarm_dosage_format, dosage)
            else getString(R.string.alarm_dosage_format, getString(R.string.dosage_default))
        loadMedicineNotes()

        findViewById<Button>(R.id.btnTaken).setOnClickListener { onTaken() }
        findViewById<Button>(R.id.btnSnooze).setOnClickListener { onSnooze() }
        findViewById<Button>(R.id.btnSkip).setOnClickListener { onSkip() }
        setupAlarmButtonAutosize()
    }

    /** 备注行：读取当前闹钟药品的备注（与库存提示同一读取方式），非空显示在剂量下方，为空整行隐藏 */
    private fun loadMedicineNotes() {
        val tvNotes = findViewById<TextView>(R.id.tvNotes)
        lifecycleScope.launch {
            val medicine = if (medicineId > 0) App.instance.database.medicineDao().getById(medicineId) else null
            val notes = medicine?.notes?.trim().orEmpty()
            if (notes.isEmpty()) {
                tvNotes.visibility = View.GONE
            } else {
                tvNotes.text = notes
                tvNotes.visibility = View.VISIBLE
            }
        }
    }

    /** 按键文字过长时缩小字号，保证单行放得下 */
    private fun setupAlarmButtonAutosize() {
        val buttons = arrayOf(
            findViewById<Button>(R.id.btnTaken),
            findViewById<Button>(R.id.btnSnooze),
            findViewById<Button>(R.id.btnSkip)
        )
        alarmButtonBaseTextPx = buttons[0].textSize
        buttons.forEach { b ->
            b.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                fitButtonText(v as Button)
            }
        }
    }

    private fun fitButtonText(b: Button) {
        val basePx = alarmButtonBaseTextPx
        if (basePx <= 0f || b.width <= 0) return
        val avail = (b.width - b.paddingLeft - b.paddingRight).toFloat()
        if (avail <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = b.typeface
            textSize = basePx
        }
        val textW = paint.measureText(b.text.toString())
        if (textW <= 0f) return
        val scale = (avail / textW).coerceIn(0.5f, 1f)
        val targetPx = basePx * scale
        if (abs(b.textSize - targetPx) > 0.5f) {
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, targetPx)
        }
    }

    /** 剩余数量为 0 时，在闹钟界面提示「没药了」 */
    private fun checkStockWarning() {
        lifecycleScope.launch {
            val medicine = App.instance.database.medicineDao().getById(medicineId)
            val outOfStock = medicine != null && medicine.stock == 0
            findViewById<TextView>(R.id.tvOutOfStock).visibility =
                if (outOfStock) View.VISIBLE else View.GONE
        }
    }

    /** 启动提醒：按勾选的模式组合播放（弹窗始终出现） */
    private fun startAlert() {
        val types = alarmType.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if ("video" in types) startVideo()
        if ("sound" in types) startSound()
        if ("vibration" in types) startVibration()
    }

    private fun startVideo() {
        val path = videoPath ?: return
        val view = findViewById<TextureView>(R.id.videoView)
        textureView = view
        view.visibility = View.VISIBLE
        findViewById<View>(R.id.overlay).alpha = 0.3f
        // 布局完成前 view 宽高可能为 0，布局变化后重算一次兜底。
        //view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoTransform() }

        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    //Log.i(
                    //    TAG,
                    //    "onVideoSizeChanged ${videoSize.width}x${videoSize.height} unappliedRotation=${videoSize.unappliedRotationDegrees}"
                    //)
                    //this@AlarmActivity.videoSize = videoSize
                    //applyVideoTransform()

                    Log.i(
                        TAG,
                        "video=${videoSize.width}x${videoSize.height}"
                    )
                }
            })
            player.setVideoTextureView(view)
            player.setMediaItem(MediaItem.fromUri(Uri.parse(path)))
            player.repeatMode = ExoPlayer.REPEAT_MODE_ALL
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * 视频摆正后按比例居中适配。
     * 按 ExoPlayer 上报的 unappliedRotationDegrees 补旋转，再等比缩放居中。
     */
    private fun applyVideoTransform() {
        val view = textureView ?: return
        val vs = videoSize ?: return
        if (view.width == 0 || view.height == 0) return
        val w = vs.width.toFloat()
        val h = vs.height.toFloat()
        val r = ((vs.unappliedRotationDegrees % 360) + 360) % 360
        Log.i(TAG, "applyVideoTransform view=${view.width}x${view.height} buffer=${vs.width}x${vs.height} unappliedRot=$r")

        // 旋转后的显示宽高（90/270 时宽高交换）
        val dispW = if (r == 90 || r == 270) h else w
        val dispH = if (r == 90 || r == 270) w else h

        val matrix = Matrix()
        // 1. 以左上角为原点摆正：postRotate 后平移回正坐标象限
        when (r) {
            90 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(h, 0f)
            }
            180 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(w, h)
            }
            270 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, w)
            }
        }
        // 2. 等比缩放 + 居中
        val scale = min(view.width / dispW, view.height / dispH)
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            (view.width - dispW * scale) / 2f,
            (view.height - dispH * scale) / 2f
        )
        view.setTransform(matrix)
    }

    private fun startSound() {
        try {
            val ringtone: Uri = if (ringtoneUri != null) {
                Uri.parse(ringtoneUri)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmActivity, ringtone)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // 铃声播放失败时降级为纯震动
            startVibration()
        }
    }

    private fun startVibration() {
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val pattern = longArrayOf(0, 600, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    /** 已服用：写入记录并扣减剩余数量，停止提醒 */
    private fun onTaken() {
        val scheduled = scheduledTimeOrFallback()
        findViewById<Button>(R.id.btnTaken).guard(lifecycleScope, "alarmTaken:$medicineId:$scheduled") {
            MedicineRepository.markTaken(medicineId, scheduled)
        }
        stopAndFinish()
    }

    /** 暂不服用：本次不记录为已服用，加入未服用列表 */
    private fun onSkip() {
        val scheduled = scheduledTimeOrFallback()
        findViewById<Button>(R.id.btnSkip).guard(lifecycleScope, "alarmSkip:$medicineId:$scheduled") {
            MedicineRepository.skipDose(medicineId, scheduled)
        }
        stopAndFinish()
    }

    /** 稍后提醒：5 分钟后再次触发（走全屏 Activity 闹钟，后台也能全屏弹出） */
    private fun onSnooze() {
        AlarmScheduler.scheduleSnooze(
            this, medicineId, medicineName, dosage, time, alarmType, videoPath, videoCrop, ringtoneUri,
            repeatDays, repeatMode, intervalDays, anchorDay, slotIndex, scheduledTime
        )
        stopAndFinish()
    }

    /** 原定应服时间：优先用闹钟携带的精确时间，兜底用今天的时分秒 */
    private fun scheduledTimeOrFallback(): Long =
        if (scheduledTime > 0L) scheduledTime else alarmTimeToMillis()

    /** 将 "HH:mm:ss" 转成今天对应时间的毫秒时间戳 */
    private fun alarmTimeToMillis(): Long {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, second)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun stopAndFinish() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
        exoPlayer?.release()
        exoPlayer = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        private const val TAG = "AlarmActivity"
    }
}
