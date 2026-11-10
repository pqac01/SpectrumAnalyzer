package com.example.spectrumanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.spectrumanalyzer.databinding.ActivitySonarBinding

class SonarActivity : AppCompatActivity() {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val MAX_RANGE_METERS = 4.0
        private const val MIN_RANGE_METERS = 0.3
        private const val RANGE_CELLS = 256

        private const val MANUAL_BIN_DEG = 2f
        private const val MANUAL_MIN_BINS_FOR_LEAKAGE = 5
        private const val MANUAL_FILL_GAP_DEG = 10f
    }

    private lateinit var binding: ActivitySonarBinding
    private val sonarEngine = SonarEngine(sampleRate = SAMPLE_RATE)
    private var isMeasuring = false
    private var debugPcmWriter: PCMFileWriter? = null

    private var scan = newManualScan()

    private fun newManualScan() = SonarScan(
        angleBinDeg = MANUAL_BIN_DEG,
        minBinsForLeakage = MANUAL_MIN_BINS_FOR_LEAKAGE,
        sampleRate = SAMPLE_RATE,
        minRangeMeters = MIN_RANGE_METERS,
        maxRangeMeters = MAX_RANGE_METERS
    )

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

        binding.polarView.maxRangeMeters = MAX_RANGE_METERS.toFloat()
        binding.polarView.fillGapDeg = MANUAL_FILL_GAP_DEG

        binding.btnPing.setOnClickListener { onPingClicked() }
        binding.btnClear.setOnClickListener { clearScan() }
        binding.toggleDebugRecording.setOnCheckedChangeListener { _, isChecked ->
            sonarEngine.debugWriter = if (isChecked) debugPcmWriter else null
        }
    }

    private fun clearScan() {
        scan.clear()
        binding.polarView.clear()
        binding.tvResult.text = getString(R.string.sonar_result_placeholder)
        binding.tvStatus.text = ""
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

            var image: PpiImage? = null
            var markers: List<PolarView.Marker> = emptyList()
            var pingEchoes: List<Echo> = emptyList()
            result.profile?.let { profile ->
                scan.add(profile.copy(angleDeg = angleDeg))
                scan.recomputeLeakage()
                image = scan.toImage(RANGE_CELLS)
                markers = scan.occupiedBinIndices().flatMap { bin ->
                    scan.echoesAt(bin).map { PolarView.Marker(scan.binCenterDeg(bin), it.distanceMeters.toFloat()) }
                }
                pingEchoes = scan.echoesAt(scan.binOf(angleDeg))
            }

            runOnUiThread {
                isMeasuring = false
                binding.btnPing.isEnabled = true
                image?.let { binding.polarView.setImage(it) }
                if (result.profile != null) binding.polarView.setMarkers(markers)
                handleResult(result, pingEchoes)
            }
        }.start()
    }

    private fun handleResult(result: SonarEngine.SonarResult, pingEchoes: List<Echo>) {
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

        val leakageNote = if (scan.isLeakageActive()) {
            getString(R.string.sonar_leakage_active, scan.occupiedBins())
        } else {
            getString(R.string.sonar_leakage_pending, MANUAL_MIN_BINS_FOR_LEAKAGE - scan.occupiedBins())
        }

        val primary = pingEchoes.firstOrNull() // strongest
        if (primary == null) {
            binding.tvResult.text = getString(R.string.sonar_no_echo)
            binding.tvStatus.text = silencedNote + getString(
                R.string.sonar_no_echo_in_range,
                MIN_RANGE_METERS,
                MAX_RANGE_METERS
            ) + "\n$leakageNote\n$diag"
            return
        }

        binding.tvResult.text = getString(R.string.sonar_distance_format, primary.distanceMeters)
        val echoList = pingEchoes.sortedBy { it.distanceMeters }.joinToString(", ") {
            getString(R.string.sonar_echo_item, it.distanceMeters, it.levelDb)
        }
        binding.tvStatus.text =
            "$silencedNote${getString(R.string.sonar_echoes_prefix)} $echoList\n$leakageNote\n$diag"
    }
}
