/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频裁剪页：显示视频首帧（原始方向、不做自动旋转），提供「向左/向右旋转」按钮，
 * 用户拖拽一个与屏幕同比例的方框选择裁剪区域。确认后按用户选择的旋转角度重新编码成
 * 方向正确的新视频，返回新文件路径。
 */
class CropActivity : BaseActivity() {

    private lateinit var cropView: CropOverlayView
    private var rotation = 0                 // 用户累计旋转角度（顺时针 0/90/180/270）
    private var currentFrame: Bitmap? = null
    private var screenAspect = 0.5625f
    private var rotating = false             // 防止快速连点导致旋转竞态

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        cropView = findViewById(R.id.cropView)
        val overlay = findViewById<View>(R.id.progressOverlay)
        val btnConfirm = findViewById<Button>(R.id.btnCropConfirm)

        findViewById<Button>(R.id.btnRotateLeft).setOnClickListener { rotateBy(-90) }
        findViewById<Button>(R.id.btnRotateRight).setOnClickListener { rotateBy(90) }

        findViewById<Button>(R.id.btnCropCancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        btnConfirm.setOnClickListener {
            val crop = cropView.getNormalizedCrop()
            val inputPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
            if (crop.isEmpty() || inputPath == null) {
                setResult(RESULT_CANCELED)
                finish()
                return@setOnClickListener
            }
            // 输出到同目录下，避免重复裁剪后文件重名覆盖
            val input = File(inputPath)
            val output = File(input.parentFile, "${input.nameWithoutExtension}_crop.mp4")

            overlay.visibility = View.VISIBLE
            btnConfirm.isEnabled = false
            lifecycleScope.launch {
                try {
                    // Transformer 须在主线程调用
                    val outPath = VideoCropper.crop(
                        this@CropActivity, inputPath, output.absolutePath, crop, rotation
                    )
                    val data = Intent().putExtra(EXTRA_CROPPED_PATH, outPath)
                    setResult(RESULT_OK, data)
                    finish()
                } catch (e: Exception) {
                    Log.e(TAG, "crop failed", e)
                    overlay.visibility = View.GONE
                    btnConfirm.isEnabled = true
                    val detail = e.message?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
                    Toast.makeText(
                        this@CropActivity,
                        getString(R.string.crop_failed) + detail,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val path = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (path == null) {
            finish()
            return
        }

        // 家属远程裁剪时按控制对象设备的屏幕比例；未传（本人使用）则用本机比例
        val remoteAspect = intent.getFloatExtra(EXTRA_SCREEN_ASPECT, 0f)
        screenAspect = if (remoteAspect > 0f) {
            remoteAspect
        } else {
            resources.displayMetrics.widthPixels.toFloat() /
                resources.displayMetrics.heightPixels.toFloat()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val frame = loadFrame(path)
            withContext(Dispatchers.Main) {
                currentFrame = frame
                cropView.setVideo(frame, screenAspect)
            }
        }
    }

    /** 向左（-90）/ 向右（+90）旋转当前帧（后台线程执行） */
    private fun rotateBy(delta: Int) {
        if (rotating) return
        val bmp = currentFrame ?: return
        rotating = true
        lifecycleScope.launch(Dispatchers.Default) {
            val rotated = try {
                rotateBitmap(bmp, delta)
            } catch (e: Throwable) {
                Log.e(TAG, "rotateBitmap failed", e)
                null
            }
            withContext(Dispatchers.Main) {
                if (rotated != null) {
                    currentFrame = rotated
                    rotation = (rotation + delta + 360) % 360
                    // setVideo 内部会清空旧裁剪框并重算，无需再单独 resetCrop
                    cropView.setVideo(rotated, screenAspect)
                }
                rotating = false
            }
        }
    }

    /**
     * 读取视频首帧并摆正。getFrameAtTime 返回的帧可能未摆正，
     * 用 uprightFrame 判断，未摆正则按旋转角补一次。
     */
    private fun loadFrame(path: String): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime()
            Log.i(TAG, "loadFrame rotation=$rotation rawFrame=${frame?.width}x${frame?.height}")
            frame?.let { raw ->
                // 硬件位图需先复制为软件位图才能缩放
                val software = if (raw.config == Bitmap.Config.HARDWARE) {
                    raw.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    raw
                }
                scaleDown(uprightFrame(software, rotation), MAX_FRAME_DIM)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadFrame failed", e)
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 摆正 getFrameAtTime 返回的帧：90/270 时若仍是横帧则按旋转角补一次 */
    private fun uprightFrame(frame: Bitmap, rotation: Int): Bitmap {
        val r = ((rotation % 360) + 360) % 360
        if (r != 90 && r != 270) return frame
        val alreadyUpright = frame.height > frame.width
        Log.i(TAG, "uprightFrame r=$r frame=${frame.width}x${frame.height} already=$alreadyUpright")
        return if (alreadyUpright) frame else (rotateBitmap(frame, r) ?: frame)
    }

    /** 等比缩小，最长边不超过 maxDim */
    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val largest = maxOf(w, h)
        if (largest <= maxDim) return src
        val scale = maxDim.toFloat() / largest
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun rotateBitmap(src: Bitmap?, degrees: Int): Bitmap? {
        if (src == null || degrees % 360 == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "rotateBitmap OOM", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "rotateBitmap failed", e)
            null
        }
    }

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_CROP = "crop"
        const val EXTRA_CROPPED_PATH = "cropped_path"
        const val EXTRA_SCREEN_ASPECT = "screen_aspect"
        private const val TAG = "CropActivity"
        private const val MAX_FRAME_DIM = 1280
    }
}
