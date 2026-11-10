package com.example.spectrumanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.spectrumanalyzer.databinding.ActivitySonarBinding

class SonarActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySonarBinding
    private val sonarEngine = SonarEngine()
    private var isMeasuring = false
    private var debugPcmWriter: PCMFileWriter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySonarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        }

        debugPcmWriter = PCMFileWriter(this)

        binding.btnPing.setOnClickListener { onPingClicked() }
        binding.btnClear.setOnClickListener {
            binding.polarView.clear()
            binding.tvResult.text = getString(R.string.sonar_result_placeholder)
            binding.tvStatus.text = ""
        }
        binding.toggleDebugRecording.setOnCheckedChangeListener { _, isChecked ->
            sonarEngine.debugWriter = if (isChecked) debugPcmWriter else null
        }
    }

    private fun onPingClicked() {
        if (isMeasuring) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            binding.tvStatus.text = getString(R.string.sonar_no_permission)
            return
        }

        val angleDeg = binding.etAngle.text?.toString()?.toFloatOrNull() ?: 0f
        isMeasuring = true
        binding.btnPing.isEnabled = false
        binding.tvStatus.text = getString(R.string.sonar_measuring)

        Thread {
            val result = sonarEngine.measureOnce(
                maxRangeMeters = MAX_RANGE_METERS,
                minRangeMeters = MIN_RANGE_METERS
            )

            runOnUiThread {
                isMeasuring = false
                binding.btnPing.isEnabled = true
                handleResult(angleDeg, result)
            }
        }.start()
    }

    private fun handleResult(angleDeg: Float, result: SonarEngine.SonarResult) {
        val diag = getString(
            R.string.sonar_diag_format,
            result.audioSourceUsed,
            result.rawPeakAmplitude,
            result.clippedSamples,
            result.directPeakTimeMs?.let { "%.0f ms".format(it) } ?: "–",
            result.directSnrDb?.let { "%.0f dB".format(it) } ?: "–"
        )
        val silencedNote = if (result.clientSilenced == true) {
            getString(R.string.sonar_silenced_warning) + "\n"
        } else ""

        if (!result.success) {
            binding.tvResult.text = getString(R.string.sonar_failed)
            binding.tvStatus.text = "$silencedNote${result.errorMessage ?: ""}\n$diag"
            return
        }

        val primary = result.primaryDistanceMeters
        if (primary == null) {
            binding.tvResult.text = getString(R.string.sonar_no_echo)
            binding.tvStatus.text = silencedNote + getString(
                R.string.sonar_no_echo_in_range,
                MIN_RANGE_METERS,
                MAX_RANGE_METERS
            ) + "\n$diag"
            return
        }

        binding.tvResult.text = getString(R.string.sonar_distance_format, primary)
        binding.polarView.addPoint(angleDeg, primary.toFloat())

        val echoList = result.echoes.joinToString(", ") {
            getString(R.string.sonar_echo_item, it.distanceMeters, it.levelDb)
        }
        binding.tvStatus.text = "$silencedNote${getString(R.string.sonar_echoes_prefix)} $echoList\n$diag"
    }

    companion object {
        private const val MAX_RANGE_METERS = 4.0
        private const val MIN_RANGE_METERS = 0.3
    }
}
