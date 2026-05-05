package com.example.spectrumanalyzer

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class PCMFileWriter(private val context: Context) {
    private var fileOutputStream: FileOutputStream? = null

    fun startFile(sampleRate: Int): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (dir?.exists() == false) dir.mkdirs()

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "REC_${timeStamp}_${sampleRate}Hz.pcm"

        val file = File(dir, fileName)
        fileOutputStream = FileOutputStream(file)
        return file
    }

    fun appendData(data: ShortArray, size: Int) {
        val byteBuffer = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size) {
            byteBuffer.putShort(data[i])
        }
        fileOutputStream?.write(byteBuffer.array())
    }

    fun closeFile() {
        fileOutputStream?.flush()
        fileOutputStream?.close()
        fileOutputStream = null
    }
}

class PCMFileManager(private val context: Context) {

    fun getAllRecordings(): List<File> {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return dir?.listFiles { file ->
            file.extension == "pcm"
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun deleteRecording(file: File): Boolean {
        return if (file.exists()) file.delete() else false
    }

    fun getSampleRateFromFileName(fileName: String): Int {
        return try {
            val parts = fileName.split("_")
            val ratePart = parts.last().replace("Hz.pcm", "")
            ratePart.toInt()
        } catch (e: Exception) {
            44100
        }
    }
}