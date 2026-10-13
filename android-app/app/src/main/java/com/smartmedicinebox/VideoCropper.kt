/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 把视频裁剪并旋转后重新编码，输出独立文件。
 * 重编码把「旋转 + 裁剪」烘进像素，闹钟端直接播放即可。
 */
object VideoCropper {

    private const val TAG = "VideoCropper"

    /**
     * 裁剪并旋转后重编码。须在主线程调用。
     *
     * @param inputPath  源视频绝对路径
     * @param outputPath 输出视频绝对路径（父目录需已存在）
     * @param crop       归一化裁剪区域 "left,top,right,bottom"（0..1）
     * @param rotation   旋转角度（0/90/180/270，顺时针）
     */
    @OptIn(UnstableApi::class)
    suspend fun crop(
        context: Context,
        inputPath: String,
        outputPath: String,
        crop: String?,
        rotation: Int
    ): String {
        val srcRotation = readRotation(inputPath)
        Log.i(TAG, "CROP input=$inputPath srcRotation=$srcRotation userRotation=$rotation cropStr=$crop")

        val srcRot = ((srcRotation % 360) + 360) % 360

        // 竖屏源（旋转 90/270）先剥掉旋转元数据，得到「横向像素 + 旋转 0」的干净源再裁剪
        var effectiveInput = inputPath
        if (srcRot == 90 || srcRot == 270) {
            effectiveInput = File(inputPath).parentFile!!.resolve(
                File(inputPath).nameWithoutExtension + "_norm.mp4"
            ).absolutePath
            stripRotation(context, inputPath, effectiveInput, srcRot)
        }

        val effects = buildEffects(crop, rotation, srcRot)
        runTransformer(context, effectiveInput, outputPath, effects)
        return outputPath
    }

    /** 剥掉源视频的旋转元数据（remux，不重编码），输出旋转角为 0 */
    @OptIn(UnstableApi::class)
    private suspend fun stripRotation(
        context: Context,
        inputPath: String,
        outputPath: String,
        srcRot: Int
    ) {
        val rotate = ScaleAndRotateTransformation.Builder()
            .setRotationDegrees(srcRot.toFloat())
            .build()
        runTransformer(context, inputPath, outputPath, Effects(emptyList(), listOf(rotate)))
        Log.i(TAG, "STRIP done srcRot=$srcRot outRotation=${readRotation(outputPath)}")
    }

    /**
     * 组装旋转 + 裁剪效果。crop 是相对显示帧的 0..1，Crop 参数是 NDC
     * （x -1..1 左→右、y -1..1 下→上）。
     */
    @OptIn(UnstableApi::class)
    private fun buildEffects(crop: String?, rotation: Int, srcRot: Int): Effects {
        val videoEffects = mutableListOf<Effect>()
        val p = parseCrop(crop)

        if (srcRot == 90 || srcRot == 270) {
            // 横向源摆正 + 用户旋转的总逆时针角
            val uprightCcw = (360 - srcRot) % 360            // 270→90；90→270
            val userCcw = ((360 - (rotation % 360)) % 360)   // 顺时针转逆时针
            val totalCcw = (uprightCcw + userCcw) % 360
            if (totalCcw != 0) {
                videoEffects.add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(totalCcw.toFloat())
                        .build()
                )
            }
            if (p != null) {
                val (left, top, right, bottom) = p
                videoEffects.add(
                    Crop(left * 2f - 1f, right * 2f - 1f, 1f - bottom * 2f, 1f - top * 2f)
                )
            }
        } else {
            // 源无旋转（或 180）：直接套用标准坐标换算
            if (p != null) {
                val (left, top, right, bottom) = p
                videoEffects.add(
                    Crop(left * 2f - 1f, right * 2f - 1f, 1f - bottom * 2f, 1f - top * 2f)
                )
            }
            val ccw = ((360 - (rotation % 360)) % 360)
            if (ccw != 0) {
                videoEffects.add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(ccw.toFloat())
                        .build()
                )
            }
        }

        return Effects(emptyList(), videoEffects)
    }

    /** 跑一次 Transformer：把 inputPath 用给定 effects 处理，写入 outputPath */
    @OptIn(UnstableApi::class)
    private suspend fun runTransformer(
        context: Context,
        inputPath: String,
        outputPath: String,
        effects: Effects
    ): String = suspendCancellableCoroutine { cont ->
        val edited = EditedMediaItem.Builder(
            MediaItem.fromUri(Uri.fromFile(File(inputPath)))
        ).setEffects(effects).build()

        val transformer = Transformer.Builder(context)
            // 开启软件编码回退，避免硬件编码器初始化失败
            .setEncoderFactory(DefaultEncoderFactory(context, EncoderSelector.DEFAULT, true))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult
                ) {
                    if (cont.isActive) cont.resume(outputPath)
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: androidx.media3.transformer.ExportException
                ) {
                    Log.e(
                        TAG,
                        "export failed: code=${exportException.errorCode} " +
                            "(${exportException.errorCodeName}) msg=${exportException.message}",
                        exportException.cause
                    )
                    if (cont.isActive) {
                        cont.resumeWithException(
                            RuntimeException(
                                exportException.errorCodeName ?: "code_${exportException.errorCode}",
                                exportException
                            )
                        )
                    }
                }
            })
            .build()

        cont.invokeOnCancellation { transformer.cancel() }
        try {
            transformer.start(edited, outputPath)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /** 读取源视频的旋转元数据（顺时针 0/90/180/270），读取失败返回 -1 */
    private fun readRotation(path: String): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            -1
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 解析 "left,top,right,bottom"（0..1），非法则返回 null */
    private fun parseCrop(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        val v = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (v.size != 4) return null
        if (v[2] <= v[0] || v[3] <= v[1]) return null
        return floatArrayOf(v[0], v[1], v[2], v[3])
    }
}
