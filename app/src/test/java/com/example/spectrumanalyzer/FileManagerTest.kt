package com.example.spectrumanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import android.content.Context

class FileManagerTest {

    @Test
    fun `test sample rate extraction from filename`() {
        val mockContext = mock(Context::class.java)
        val fileManager = PCMFileManager(mockContext)

        val validName = "REC_20240509_120000_44100Hz.pcm"
        val alternativeRate = "REC_test_16000Hz.pcm"
        val invalidName = "random_file.txt"

        assertEquals(44100, fileManager.getSampleRateFromFileName(validName))
        assertEquals(16000, fileManager.getSampleRateFromFileName(alternativeRate))

        assertEquals(44100, fileManager.getSampleRateFromFileName(invalidName))
    }
}