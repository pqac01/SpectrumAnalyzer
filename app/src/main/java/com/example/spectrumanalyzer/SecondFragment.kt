package com.example.spectrumanalyzer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.spectrumanalyzer.databinding.FragmentSecondBinding
import java.io.File
import java.io.FileInputStream

class SecondFragment : Fragment() {
    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!
    private lateinit var fileManager: PCMFileManager

    private var audioTrack: AudioTrack? = null

    @Volatile private var isPlaying = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fileManager = PCMFileManager(requireContext())

        val adapter = FileAdapter(
            files = fileManager.getAllRecordings(),
            onPlayClick = { file ->
                playPcmFile(file)
            },
            onDeleteClick = { file ->
                val currentAdapter = binding.recyclerViewFiles.adapter as? FileAdapter
                if (isPlaying && currentAdapter?.playingFile == file) {
                    stopPlayback()
                }
                fileManager.deleteRecording(file)
                refreshList()
            }
        )

        binding.recyclerViewFiles.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewFiles.adapter = adapter

        checkEmptyState()
    }

    private fun refreshList() {
        val adapter = binding.recyclerViewFiles.adapter as? FileAdapter
        val files = fileManager.getAllRecordings()
        adapter?.updateData(files)
        checkEmptyState()
    }

    private fun checkEmptyState() {
        binding.tvEmpty.visibility = if (fileManager.getAllRecordings().isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playPcmFile(file: File) {
        val adapter = binding.recyclerViewFiles.adapter as? FileAdapter

        if (isPlaying && adapter?.playingFile == file) {
            stopPlayback()
            return
        }

        if (isPlaying) {
            stopPlayback()
            Thread.sleep(50)
        }

        val sampleRate = fileManager.getSampleRateFromFileName(file.name)

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            isPlaying = true
            adapter?.playingFile = file
            adapter?.notifyDataSetChanged()

            Thread {
                val buffer = ByteArray(minBufferSize)
                var fis: FileInputStream? = null
                try {
                    fis = FileInputStream(file)
                    audioTrack?.play()

                    var read: Int
                    while (isPlaying) {
                        read = fis.read(buffer)
                        if (read == -1) break

                        audioTrack?.let {
                            if (it.state == AudioTrack.STATE_INITIALIZED) {
                                it.write(buffer, 0, read)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Playback", "Hiba lejátszás közben", e)
                } finally {
                    fis?.close()
                    if (isPlaying) {
                        activity?.runOnUiThread { stopPlayback() }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e("Playback", "AudioTrack inicializálási hiba", e)
        }
    }

    private fun stopPlayback() {
        isPlaying = false

        try {
            audioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.stop()
                    it.flush()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("Playback", "Hiba a leállításnál", e)
        }
        audioTrack = null

        activity?.runOnUiThread {
            val adapter = binding.recyclerViewFiles.adapter as? FileAdapter
            adapter?.playingFile = null
            adapter?.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        stopPlayback()
        super.onDestroyView()
        _binding = null
    }
}