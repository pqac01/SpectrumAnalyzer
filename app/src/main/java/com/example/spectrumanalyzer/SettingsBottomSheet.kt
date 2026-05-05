package com.example.spectrumanalyzer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.example.spectrumanalyzer.databinding.AnalyzerSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheet(
    private val initialSettings: AnalyzerSettings,
    private val onSettingsChanged: (AnalyzerSettings) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: AnalyzerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = AnalyzerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Smoothing
        binding.sliderSmoothing.value = initialSettings.smoothingFactor
        binding.sliderSmoothing.addOnChangeListener { _, value, _ ->
            initialSettings.smoothingFactor = value
            onSettingsChanged(initialSettings)
        }

        // dB Range
        binding.rangeSliderDb.setValues(initialSettings.minDb, initialSettings.maxDb)
        binding.rangeSliderDb.addOnChangeListener { slider, _, _ ->
            initialSettings.minDb = slider.values[0]
            initialSettings.maxDb = slider.values[1]
            onSettingsChanged(initialSettings)
        }

        // Scaling Mode Spinner
        val scalingModeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            ScalingMode.entries)
        binding.spectrogramScalingMode.adapter = scalingModeAdapter
        binding.spectrogramScalingMode.setSelection(ScalingMode.entries.indexOf(initialSettings.scalingMode))
        binding.spectrogramScalingMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selectedMode = ScalingMode.entries[position]
                initialSettings.scalingMode = selectedMode
                onSettingsChanged(initialSettings)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                TODO("Not yet implemented")
            }
        }

        // Sample Rate Spinner
        val rates = listOf(16000, 22050, 44100, 48000)
        val sampleRateAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, rates)
        binding.spinnerSampleRate.adapter = sampleRateAdapter
        binding.spinnerSampleRate.setSelection(rates.indexOf(initialSettings.sampleRate))

        binding.btnApplyRate.setOnClickListener {
            initialSettings.sampleRate = binding.spinnerSampleRate.selectedItem as Int
            onSettingsChanged(initialSettings)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}