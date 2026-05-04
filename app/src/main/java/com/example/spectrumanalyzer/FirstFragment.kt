package com.example.spectrumanalyzer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.spectrumanalyzer.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {
    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private lateinit var audioAnalyzer: AudioAnalyzer

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sampleRate = 44100
        val bufferSize = 2048

        val audioMonitor = AudioMonitor(sampleRate, bufferSize)

        audioAnalyzer = AudioAnalyzer(sampleRate, bufferSize) { magnitudes ->
            activity?.runOnUiThread {
                binding.spectrumView.updateData(magnitudes)
                binding.spectrogramView.updateData(magnitudes)
            }
        }
        audioAnalyzer.monitor = audioMonitor

        binding.btnRecord.setOnClickListener {
            if (binding.btnRecord.text.toString() == getString(R.string.startRecording)) {
                audioAnalyzer.start()
                binding.btnRecord.text = getString(R.string.stopRecording)
            } else {
                audioAnalyzer.stop()
                binding.btnRecord.text = getString(R.string.startRecording)
            }
        }

        binding.btnMonitor.setOnClickListener {
            if (binding.btnMonitor.text.toString() == getString(R.string.startMonitoring)) {
                audioMonitor.start()
                binding.btnMonitor.text = getString(R.string.stopMonitoring)
            } else {
                audioMonitor.stop()
                binding.btnMonitor.text = getString(R.string.startMonitoring)
            }
        }

    }

    override fun onDestroyView() {
        audioAnalyzer.stop()
        super.onDestroyView()
        _binding = null
    }
}