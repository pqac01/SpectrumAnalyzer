package com.example.spectrumanalyzer

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.sqrt
class MatchedFilter(private val template: ShortArray) {

    val templateLength: Int get() = template.size

    private val ffts = HashMap<Int, DoubleFFT_1D>()
    private val templateSpectra = HashMap<Int, DoubleArray>()

    @Synchronized
    fun envelope(signal: ShortArray, offset: Int, length: Int): DoubleArray {
        val fftSize = nextPowerOfTwo(length + template.size - 1)
        val fft = ffts.getOrPut(fftSize) { DoubleFFT_1D(fftSize.toLong()) }
        val b = templateSpectra.getOrPut(fftSize) {
            val t = DoubleArray(2 * fftSize) // interleaved re/im
            for (i in template.indices) t[2 * i] = template[i].toDouble()
            fft.complexForward(t)
            t
        }

        val a = DoubleArray(2 * fftSize)
        for (i in 0 until length) a[2 * i] = signal[offset + i].toDouble()
        fft.complexForward(a)

        val half = fftSize / 2
        for (i in 0 until fftSize) {
            val ar = a[2 * i]; val ai = a[2 * i + 1]
            val br = b[2 * i]; val bi = -b[2 * i + 1] // conj(B)
            val weight = when {
                i == 0 || i == half -> 1.0
                i < half -> 2.0
                else -> 0.0
            }
            a[2 * i] = (ar * br - ai * bi) * weight
            a[2 * i + 1] = (ar * bi + ai * br) * weight
        }

        fft.complexInverse(a, true)

        return DoubleArray(fftSize) { k ->
            val re = a[2 * k]
            val im = a[2 * k + 1]
            sqrt(re * re + im * im)
        }
    }

    companion object {
        fun median(values: DoubleArray, from: Int, toExclusive: Int): Double {
            if (toExclusive <= from) return 0.0
            val slice = values.copyOfRange(from, toExclusive)
            slice.sort()
            return slice[slice.size / 2]
        }

        fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }
    }
}
