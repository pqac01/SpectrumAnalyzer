package com.example.spectrumanalyzer

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriter(private val context: Context) {
    private var fileOutputStream: FileOutputStream? = null
    private var currentFile: File? = null
    private var totalAudioLen: Long = 0

    fun startRecording(fileName: String) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        currentFile = File(dir, "$fileName.wav")

        fileOutputStream = FileOutputStream(currentFile)
        fileOutputStream?.write(ByteArray(44))
        totalAudioLen = 0
    }

    fun writeData(data: ShortArray, size: Int) {
        val byteBuffer = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size) {
            byteBuffer.putShort(data[i])
        }
        fileOutputStream?.write(byteBuffer.array())
        totalAudioLen += size * 2
    }


}