package com.forjrking.xluban

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.forjrking.xluban.Checker.TAG
import com.forjrking.xluban.io.ArrayProvide
import com.forjrking.xluban.io.InputStreamProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * @Des: 向下采样压缩引擎 主要有临近采样和双线性采样2种压缩方式,会改变图片原有分辨率,质量压缩会大幅度压缩大小但是会损失图片质量
 *
 * 双线性采样压缩更加适合纯文字图片的压缩,缺点就是必须加载进内存中
 * Matrix matrix = new Matrix();
 * matrix.setScale(scale, scale); // 压缩系数 0.5 即原来 1/2
 * Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
 *
 * 临近采样可以不加载进入内存,相邻像素丢弃方式压缩
 * BitmapFactory.Options options = new BitmapFactory.Options();
 * options.inSampleSize = 2; //取值为2的次幂
 * Bitmap tagBitmap = BitmapFactory.decodeStream(stream, null, options);
 *
 * JPEG 图像没有alpha 如果使用ARGB_888 后续加载进入内存会浪费alpha 所以必须根据原图像数据处理需要压缩后的图像
 * @Author: 岛主
 * @Time:  2020/10/8
 * @Version: 1.0.0
 * <lp>
</lp> */
class CompressEngine constructor(private val srcStream: InputStreamProvider, private val resFile: File,
                                 private val compress4Sample: Boolean, private val maxSize: Int,
                                 private val quality: Int, private val compressFormat: CompressFormat,
                                 private val compressConfig: Bitmap.Config) {


    @Throws(IOException::class)
    suspend fun compress(): File {
        var quality = quality
        //预期压缩的期望大小
        val rqSize = maxSize.toLong()
        //获取jpeg旋转角度
        val angle = Checker.getOrientation(srcStream.rewindAndGet())
        //解析Bitmap
        val options = BitmapFactory.Options()
        //不加载进内存
        options.inJustDecodeBounds = true
        //默认采样率 采样率是2的次幂
        options.inSampleSize = 1
        //不加载进内存解析一次 获取宽高
        BitmapFactory.decodeStream(srcStream.rewindAndGet(), null, options)
        //解析出宽高
        val width = options.outWidth
        val height = options.outHeight
        //计算采样率 来压缩
        val scale = if (compress4Sample) {
            options.inSampleSize = computeSampleSize(width, height)
            1f
        } else {
            options.inSampleSize = 1
            computeScaleSize(width, height)
        }
        Log.d(TAG, "scale :$scale,inSampleSize :${options.inSampleSize}")
        // 指定图片 ARGB 或者RGB
        options.inPreferredConfig = compressConfig
        //预判内存不足情况
        val isAlpha = compressConfig == Bitmap.Config.ARGB_8888
        if (!hasEnoughMemory(width / options.inSampleSize, height / options.inSampleSize, isAlpha)) {
            //TODO 内存不足使用
            Log.w(TAG, "memory warring 降低位图像素")
            options.inPreferredConfig = Bitmap.Config.RGB_565
            //减低像素 减低内存
            if (!hasEnoughMemory(width / options.inSampleSize, height / options.inSampleSize, false)) {
                throw IOException("image memory is too large")
            }
        }
        //加载入内存中
        options.inJustDecodeBounds = false
        //提供高质量
        options.inPreferQualityOverSpeed = true;
        //临时解码缓冲区 建议是16k
        val bytes4Option = ArrayProvide.get(16 * 1024)
        options.inTempStorage = bytes4Option
        //此处OOM
        var bitmap = BitmapFactory.decodeStream(srcStream.rewindAndGet(), null, options)
                ?: throw IOException("decodeStream error")
        //处理角度和缩放
        bitmap = transformBitmap(bitmap, scale, angle)
        // 获取解析流
        val stream = ByteArrayOutputStream()
        try {//质量压缩开始
            bitmap.compress(compressFormat, quality, stream)
            //PNG等无损格式不支持压缩
            if (compressFormat != CompressFormat.PNG) {
                //耗时由此处触发
                while (stream.size() / 1024 > rqSize && quality > 6) {
                    stream.reset()
                    quality -= 6
                    bitmap.compress(compressFormat, quality, stream)
                }
            }
        } finally {
            //位图释放
            bitmap.recycle()
            ArrayProvide.put(bytes4Option)
        }
        //输出文件
        stream.use { bos ->
            FileOutputStream(resFile).use { fos ->
                bos.writeTo(fos)
                fos.flush()
            }
        }
        return resFile
    }

    /**
     * 邻近采样率  核心算法(来自 luban)
     */
    private fun computeSampleSize(width: Int, height: Int): Int {
        val srcWidth = if (width % 2 == 1) width + 1 else width
        val srcHeight = if (height % 2 == 1) height + 1 else height
        val longSide: Int = max(srcWidth, srcHeight)
        val shortSide: Int = min(srcWidth, srcHeight)
        val scale = shortSide.toFloat() / longSide
        return if (scale <= 1 && scale > 0.5625) {
            if (longSide < 1664) {
                1
            } else if (longSide < 4990) {
                2
            } else if (longSide in 4991..10239) {
                4
            } else {
                if (longSide / 1280 == 0) 1 else longSide / 1280
            }
        } else if (scale <= 0.5625 && scale > 0.5) {
            if (longSide / 1280 == 0) 1 else longSide / 1280
        } else {
            ceil(longSide / (1280.0 / scale)).toInt()
        }
    }

    /**
     * 精确缩放
     */
    private fun computeScaleSize(width: Int, height: Int): Float {
        var scale = 1f
        val max = max(width, height)
        val min = min(width, height)
        val ratio = min / (max * 1f)
        if (ratio >= 0.5f) {
            if (max > 1280f) scale = 1280f / (max * 1f)
        } else {
            val multiple = max / min
            if (multiple < 10) {
                if (min > 1000f && (1f - ratio / 2f) * min > 1000f) {
                    scale = 1f - ratio / 2f
                }
            } else {
                val arg = Math.pow(multiple.toDouble(), 2.0).toInt()
                scale = 1f - arg / 1000f + if (multiple > 10) 0.01f else 0.03f
                if (min * scale < 640f) {
                    scale = 1f
                }
            }
        }
        return scale
    }

    /**
     * 缩放 旋转bitmap scale =1f不缩放
     */
    private fun transformBitmap(bitmap: Bitmap, scale: Float, angle: Int): Bitmap {
        if (scale == 1f || angle <= 0) return bitmap
        val matrix = Matrix()
        //旋转角度处理
        if (angle > 0) {
            matrix.postRotate(angle.toFloat())
        }
        //双线性压缩
        if (scale != 1f) {
            matrix.setScale(scale, scale)
        }
        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } finally {
            System.gc()
//            System.runFinalization()
        }
    }

    /**
     * 判断内存是否足够 32位每个像素占用4字节
     */
    private fun hasEnoughMemory(width: Int, height: Int, isAlpha32: Boolean): Boolean {
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        val allocation = width * height shl if (isAlpha32) 2 else 1
        Log.d(TAG, "free : " + (free shr 20) + "MB, need : " + (allocation shr 20) + "MB")
        return allocation < free
    }

}