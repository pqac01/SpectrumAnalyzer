package com.example.spectrumanalyzer

data class AnalyzerSettings(
    var bufferSize: Int = 2048,
    var sampleRate: Int = 44100,
    var smoothingFactor: Float = 0.6f,
    var minDb: Float = 25f,
    var maxDb: Float = 85f,
    var scalingMode: ScalingMode = ScalingMode.LOGARITHMIC
)

enum class ScalingMode(val displayName: String) {
    LINEAR("Lineáris"),
    LOGARITHMIC("Logaritmikus"),
    MEL("Mel-skála");

    override fun toString(): String {
        return displayName
    }
}