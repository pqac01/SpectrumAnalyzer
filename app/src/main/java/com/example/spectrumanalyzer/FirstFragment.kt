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

        audioAnalyzer = AudioAnalyzer { magnitudes ->
            activity?.runOnUiThread {
                binding.spectrumView.updateData(magnitudes)
                binding.spectrogramView.updateData(magnitudes)
            }
        }

        binding.btnRecord.setOnClickListener {
            if (binding.btnRecord.text == "Start") {
                audioAnalyzer.start()
                binding.btnRecord.text = "Stop"
            } else {
                audioAnalyzer.stop()
                binding.btnRecord.text = "Start"
            }
        }
    }

    override fun onDestroyView() {
        audioAnalyzer.stop()
        super.onDestroyView()
        _binding = null
    }
}