/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 视频裁剪框选视图：绘制视频首帧 + 一个与屏幕同比例的裁剪框，支持拖动移动、四角缩放。
 * 裁剪框约束在视频显示区域内，比例锁定为屏幕宽高比。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var screenAspect = 0.5625f   // 屏幕宽高比（宽/高）

    private val videoRect = RectF()   // 视频帧在视图内的 fit-center 区域
    private val cropRect = RectF()    // 当前裁剪框（视图坐标，恒在 videoR  ect 内）

    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = 0xFFFFFFFF.toInt()
    }
    private val handlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val cornerRadius = 24f * resources.displayMetrics.density
    private val minCropSize = 60f * resources.displayMetrics.density

    private var mode = MODE_NONE
    private var activeCorner = -1
    private var anchorX = 0f
    private var anchorY = 0f
    private var cornerVecX = 0f
    private var cornerVecY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** 设置视频首帧与屏幕宽高比，并清空旧裁剪框重新初始化 */
    fun setVideo(bitmap: Bitmap?, screenAspect: Float) {
        this.bitmap = bitmap
        this.screenAspect = if (screenAspect > 0f) screenAspect else 0.5625f
        cropRect.setEmpty()
        recomputeVideoRect()
        initCropIfNeeded()
        invalidate()
    }

    /** 旋转后按新的视频方向重新生成全尺寸裁剪框 */
    fun resetCrop() {
        setVideo(bitmap, screenAspect)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeVideoRect()
        initCropIfNeeded()
    }

    /** 计算视频帧在视图内的 fit-center 区域（首帧 bitmap 尺寸即视频显示尺寸） */
    private fun recomputeVideoRect() {
        val bmp = bitmap ?: return
        if (bmp.width <= 0 || bmp.height <= 0 || width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        videoRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun initCropIfNeeded() {
        if (videoRect.isEmpty) return
        // 已有裁剪框则按新的 videoRect 重新约束（旋转/尺寸变化后）
        if (!cropRect.isEmpty) {
            clampCrop()
            return
        }
        val vw = videoRect.width()
        val vh = videoRect.height()
        if (vw <= 0f || vh <= 0f) return
        val videoAspect = vw / vh
        val w: Float
        val h: Float
        if (videoAspect > screenAspect) {
            // 视频比屏幕更宽 → 取全高，宽度按屏幕比例，可左右移动
            h = vh
            w = h * screenAspect
        } else {
            // 视频比屏幕更窄/更高 → 取全宽，高度按屏幕比例，可上下移动
            w = vw
            h = w / screenAspect
        }
        cropRect.set(
            videoRect.left + (vw - w) / 2f,
            videoRect.top + (vh - h) / 2f,
            videoRect.left + (vw - w) / 2f + w,
            videoRect.top + (vh - h) / 2f + h
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp != null && !videoRect.isEmpty) {
            canvas.drawBitmap(bmp, null, videoRect, bitmapPaint)
        }
        if (cropRect.isEmpty) return

        // 裁剪框外的暗色遮罩（上/下/左/右四块）
        canvas.drawRect(videoRect.left, videoRect.top, videoRect.right, cropRect.top, dimPaint)
        canvas.drawRect(videoRect.left, cropRect.bottom, videoRect.right, videoRect.bottom, dimPaint)
        canvas.drawRect(videoRect.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, videoRect.right, cropRect.bottom, dimPaint)

        // 边框
        canvas.drawRect(cropRect, borderPaint)

        // 四角手柄
        canvas.drawCircle(cropRect.left, cropRect.top, cornerRadius, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, cornerRadius, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, cornerRadius, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, cornerRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (cropRect.isEmpty) return true
                activeCorner = hitCorner(x, y)
                if (activeCorner >= 0) {
                    mode = MODE_RESIZE
                    setupResizeAnchor(activeCorner)
                } else if (cropRect.contains(x, y)) {
                    mode = MODE_MOVE
                    lastX = x
                    lastY = y
                } else {
                    mode = MODE_NONE
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    MODE_MOVE -> moveCrop(x - lastX, y - lastY).also {
                        lastX = x
                        lastY = y
                    }
                    MODE_RESIZE -> resizeCrop(x, y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                activeCorner = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 命中四角手柄，返回角索引（0=左上 1=右上 2=右下 3=左下），未命中返回 -1 */
    private fun hitCorner(x: Float, y: Float): Int {
        val r = cornerRadius * 1.4f
        if (abs(x - cropRect.left) < r && abs(y - cropRect.top) < r) return CORNER_TL
        if (abs(x - cropRect.right) < r && abs(y - cropRect.top) < r) return CORNER_TR
        if (abs(x - cropRect.right) < r && abs(y - cropRect.bottom) < r) return CORNER_BR
        if (abs(x - cropRect.left) < r && abs(y - cropRect.bottom) < r) return CORNER_BL
        return -1
    }

    /** 缩放时固定对角点，记录被拖角相对锚点的原始向量 */
    private fun setupResizeAnchor(corner: Int) {
        anchorX = if (corner == CORNER_TL || corner == CORNER_BL) cropRect.right else cropRect.left
        anchorY = if (corner == CORNER_TL || corner == CORNER_TR) cropRect.bottom else cropRect.top
        val cx = if (corner == CORNER_TL || corner == CORNER_BL) cropRect.left else cropRect.right
        val cy = if (corner == CORNER_TL || corner == CORNER_TR) cropRect.top else cropRect.bottom
        cornerVecX = cx - anchorX
        cornerVecY = cy - anchorY
    }

    private fun moveCrop(dx: Float, dy: Float) {
        val newLeft = cropRect.left + dx
        val newTop = cropRect.top + dy
        val cw = cropRect.width()
        val ch = cropRect.height()
        val l = newLeft.coerceIn(videoRect.left, videoRect.right - cw)
        val t = newTop.coerceIn(videoRect.top, videoRect.bottom - ch)
        cropRect.set(l, t, l + cw, t + ch)
    }

    /** 以锚点为准做等比例缩放，并把缩放后区域约束在 videoRect 内 */
    private fun resizeCrop(x: Float, y: Float) {
        if (cornerVecX == 0f || cornerVecY == 0f) return
        val fx = x.coerceIn(videoRect.left, videoRect.right)
        val fy = y.coerceIn(videoRect.top, videoRect.bottom)

        var scaleX = (fx - anchorX) / cornerVecX
        var scaleY = (fy - anchorY) / cornerVecY
        var scale = max(scaleX, scaleY)

        // 缩放到 videoRect 边界时的最大比例
        val maxScaleX = if (cornerVecX > 0f) (videoRect.right - anchorX) / cornerVecX
                        else (videoRect.left - anchorX) / cornerVecX
        val maxScaleY = if (cornerVecY > 0f) (videoRect.bottom - anchorY) / cornerVecY
                        else (videoRect.top - anchorY) / cornerVecY
        scale = scale.coerceIn(minCropScale(), min(maxScaleX, maxScaleY))

        val nx = anchorX + cornerVecX * scale
        val ny = anchorY + cornerVecY * scale
        cropRect.set(min(anchorX, nx), min(anchorY, ny), max(anchorX, nx), max(anchorY, ny))
    }

    private fun minCropScale(): Float {
        val cw = abs(cornerVecX)
        val ch = abs(cornerVecY)
        if (cw <= 0f || ch <= 0f) return 0.1f
        return max(minCropSize / cw, minCropSize / ch)
    }

    /** 把裁剪框约束回 videoRect 内并保持最小尺寸 */
    private fun clampCrop() {
        if (videoRect.isEmpty || cropRect.isEmpty) return
        val vw = videoRect.width()
        val vh = videoRect.height()
        if (vw <= 0f || vh <= 0f) return
        // 宽度/高度先压到不超过 videoRect，再抬到最小尺寸，保证 coerceIn 的区间始终有效
        val cw = cropRect.width().coerceIn(minCropSize.coerceAtMost(vw), vw)
        val ch = cropRect.height().coerceIn(minCropSize.coerceAtMost(vh), vh)
        val l = cropRect.left.coerceIn(videoRect.left, videoRect.right - cw)
        val t = cropRect.top.coerceIn(videoRect.top, videoRect.bottom - ch)
        cropRect.set(l, t, l + cw, t + ch)
    }

    /** 归一化裁剪框（相对视频显示区域，0..1），格式 "left,top,right,bottom" */
    fun getNormalizedCrop(): String {
        if (videoRect.isEmpty || cropRect.isEmpty) return ""
        val l = (cropRect.left - videoRect.left) / videoRect.width()
        val t = (cropRect.top - videoRect.top) / videoRect.height()
        val r = (cropRect.right - videoRect.left) / videoRect.width()
        val b = (cropRect.bottom - videoRect.top) / videoRect.height()
        return listOf(l, t, r, b).joinToString(",") { String.format(Locale.US, "%.6f", it) }
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE = 2
        private const val CORNER_TL = 0
        private const val CORNER_TR = 1
        private const val CORNER_BR = 2
        private const val CORNER_BL = 3
    }
}
