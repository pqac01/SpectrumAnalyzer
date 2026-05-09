package com.example.spectrumanalyzer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.spectrumanalyzer.databinding.FragmentFirstBinding

class SpectrogramFragment : Fragment() {
    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private var audioAnalyzer: AudioAnalyzer? = null
    private var audioMonitor: AudioMonitor? = null

    private var currentSettings = AnalyzerSettings()

    private var isRecording = false
    private var isMonitoring = false
    private var isRecordingToFile = false
    private var pcmWriter: PCMFileWriter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pcmWriter = PCMFileWriter(requireContext())

        binding.spectrogramView.settings = currentSettings

        setupAudioSystem()

        binding.btnRecord.setOnClickListener {
            if (!isRecording) {
                if (isRecordingToFile) {
                    pcmWriter?.startFile(currentSettings.sampleRate)
                    audioAnalyzer?.isRecordingToFile = true
                }
                audioAnalyzer?.start()
                isRecording = true
                binding.btnRecord.text = getString(R.string.stopRecording)
            } else {
                audioAnalyzer?.stop()
                if (audioAnalyzer?.isRecordingToFile == true) {
                    audioAnalyzer?.isRecordingToFile = false
                    pcmWriter?.closeFile()
                }

                isRecording = false
                binding.btnRecord.text = getString(R.string.startRecording)
            }
        }

        binding.btnMonitor.setOnClickListener {
            if (!isMonitoring) {
                audioMonitor?.start()
                isMonitoring = true
                binding.btnMonitor.text = getString(R.string.stopMonitoring)
            } else {
                audioMonitor?.stop()
                isMonitoring = false
                binding.btnMonitor.text = getString(R.string.startMonitoring)
            }
        }

        binding.toggleRecordingToFile.setOnCheckedChangeListener { _, isChecked ->
            isRecordingToFile = isChecked

            if (isRecording && isChecked && audioAnalyzer?.isRecordingToFile == false) {
                pcmWriter?.startFile(currentSettings.sampleRate)
                audioAnalyzer?.isRecordingToFile = true
            }
            else if (isRecording && !isChecked && audioAnalyzer?.isRecordingToFile == true) {
                audioAnalyzer?.isRecordingToFile = false
                pcmWriter?.closeFile()
            }
        }
    }

    private fun setupAudioSystem() {
        releaseAudioResources()

        audioMonitor = AudioMonitor(currentSettings.sampleRate, currentSettings.bufferSize)

        audioAnalyzer = AudioAnalyzer(
            sampleRate = currentSettings.sampleRate,
            bufferSize = currentSettings.bufferSize
        ) { magnitudes ->
            activity?.runOnUiThread {
                _binding?.let {
                    it.spectrumView.updateData(magnitudes)
                    it.spectrogramView.updateData(magnitudes)
                }
            }
        }

        audioAnalyzer?.apply {
            minDb = currentSettings.minDb
            maxDb = currentSettings.maxDb
            monitor = audioMonitor
            pcmWriter = this@SpectrogramFragment.pcmWriter
        }
    }

    fun showSettings() {
        val dialog = SettingsBottomSheet(currentSettings.copy()) { newSettings ->
            if (newSettings.sampleRate != currentSettings.sampleRate) {

                val wasRecording = isRecording
                val wasMonitoring = isMonitoring

                currentSettings = newSettings
                setupAudioSystem()

                if (wasRecording) audioAnalyzer?.start()
                if (wasMonitoring) audioMonitor?.start()
            } else {
                val modeChanged = newSettings.scalingMode != currentSettings.scalingMode

                currentSettings = newSettings

                binding.spectrogramView.settings = currentSettings
                if (modeChanged) {
                    binding.spectrogramView.precomputeIndexTable()
                }

                audioAnalyzer?.apply {
                    smoothingFactor = newSettings.smoothingFactor
                    minDb = newSettings.minDb
                    maxDb = newSettings.maxDb
                }
            }
        }
        dialog.show(childFragmentManager, "settings")
    }

    private fun releaseAudioResources() {
        audioAnalyzer?.stop()
        audioMonitor?.stop()
        if (audioAnalyzer?.isRecordingToFile == true) {
            audioAnalyzer?.isRecordingToFile = false
            pcmWriter?.closeFile()
        }
        audioAnalyzer = null
        audioMonitor = null
    }

    override fun onDestroyView() {
        releaseAudioResources()
        super.onDestroyView()
        _binding = null
    }
}