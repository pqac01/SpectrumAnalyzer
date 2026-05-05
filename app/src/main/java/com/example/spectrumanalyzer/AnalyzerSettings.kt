package com.example.spectrumanalyzer

import android.graphics.Color

data class AnalyzerSettings(
    var bufferSize: Int = 2048,
    var sampleRate: Int = 44100,
    var smoothingFactor: Float = 0.6f,
    var minDb: Float = 25f,
    var maxDb: Float = 85f,
    var scalingMode: ScalingMode = ScalingMode.LOGARITHMIC,
    var palette: SpectrogramPalette = SpectrogramPalette.DEFAULT
)

enum class ScalingMode(val displayName: String) {
    LINEAR("Lineáris"),
    LOGARITHMIC("Logaritmikus"),
    MEL("Mel-skála");

    override fun toString(): String {
        return displayName
    }
}

enum class SpectrogramPalette(val displayName: String) {
    DEFAULT("Alapértelmezett"), FIRE("Tűz"), EMERALD("Smaragd"), GRAYSCALE("Szürkeárnyalatos"), HEATMAP("Hőtérkép");

    fun getColor(intensity: Int): Int {
        return when (this) {
            DEFAULT -> {
                val r = intensity
                val g = intensity / 4
                val b = 255 - intensity
                Color.rgb(r, g, b)
            }
            FIRE -> {
                val r = intensity
                val g = (intensity * 0.4f).toInt()
                val b = (intensity * 0.2f).toInt()
                Color.rgb(r, g, b)
            }
            EMERALD -> {
                val r = (intensity * 0.2f).toInt()
                val g = intensity
                val b = (intensity * 0.5f).toInt()
                Color.rgb(r, g, b)
            }
            GRAYSCALE -> {
                Color.rgb(intensity, intensity, intensity)
            }
            HEATMAP -> {
                val r = (intensity * 0.3f).toInt()
                val g = (intensity * 0.8f).toInt()
                val b = intensity
                Color.rgb(r, g, b)
            }
        }
    }
    override fun toString(): String {
        return displayName
    }
}