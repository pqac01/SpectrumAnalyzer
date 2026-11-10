package com.example.spectrumanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
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

        private const val ROTATION_BIN_DEG = 2f
        private const val ROTATION_MIN_BINS_FOR_LEAKAGE = 45
        private const val ROTATION_FILL_GAP_DEG = 4f

        private const val MIN_TURN_SECONDS = 12.0
        private const val UI_TICK_MS = 200L
    }

    private enum class Mode { MANUAL, ROTATION }

    private lateinit var binding: ActivitySonarBinding
    private val sonarEngine = SonarEngine(sampleRate = SAMPLE_RATE)
    private var debugPcmWriter: PCMFileWriter? = null

    private var mode = Mode.MANUAL
    private var scan = newScan(Mode.MANUAL)

    private var isMeasuring = false

    private var streamer: SonarStreamer? = null
    private var isScanning = false
    private var scanStartMs = 0L
    private val turnProfiles = mutableListOf<RangeProfile>() // guarded by synchronized(turnProfiles)
    @Volatile private var lastStreamStatus: SonarStreamer.Status? = null

    private val uiTick = object : Runnable {
        override fun run() {
            if (!isScanning) return
            updateScanningStatus()
            binding.root.postDelayed(this, UI_TICK_MS)
        }
    }

    private fun newScan(forMode: Mode) = when (forMode) {
        Mode.MANUAL -> SonarScan(
            angleBinDeg = MANUAL_BIN_DEG,
            minBinsForLeakage = MANUAL_MIN_BINS_FOR_LEAKAGE,
            sampleRate = SAMPLE_RATE,
            minRangeMeters = MIN_RANGE_METERS,
            maxRangeMeters = MAX_RANGE_METERS
        )
        Mode.ROTATION -> SonarScan(
            angleBinDeg = ROTATION_BIN_DEG,
            minBinsForLeakage = ROTATION_MIN_BINS_FOR_LEAKAGE,
            sampleRate = SAMPLE_RATE,
            minRangeMeters = MIN_RANGE_METERS,
            maxRangeMeters = MAX_RANGE_METERS
        )
    }

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

        binding.btnPing.setOnClickListener {
            when (mode) {
                Mode.MANUAL -> onPingClicked()
                Mode.ROTATION -> if (isScanning) stopScanning() else startScanning()
            }
        }
        binding.btnClear.setOnClickListener { clearScan() }
        binding.toggleDebugRecording.setOnCheckedChangeListener { _, isChecked ->
            sonarEngine.debugWriter = if (isChecked) debugPcmWriter else null
        }
        binding.toggleRotationMode.setOnCheckedChangeListener { _, isChecked ->
            setMode(if (isChecked) Mode.ROTATION else Mode.MANUAL)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isScanning) stopScanning()
    }

    private fun hasMicPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun setMode(newMode: Mode) {
        if (newMode == mode) return
        if (isScanning) stopScanning()
        mode = newMode
        scan = newScan(newMode)
        binding.polarView.clear()
        binding.tvResult.setTextColor(Color.WHITE)
        binding.tvResult.text = getString(R.string.sonar_result_placeholder)

        val manual = newMode == Mode.MANUAL
        binding.polarView.fillGapDeg = if (manual) MANUAL_FILL_GAP_DEG else ROTATION_FILL_GAP_DEG
        binding.btnPing.text = getString(if (manual) R.string.sonar_ping else R.string.sonar_start)
        binding.etAngle.isEnabled = manual
        binding.toggleDebugRecording.isEnabled = manual
        binding.tvStatus.text = if (manual) "" else getString(R.string.sonar_rotation_hint)
    }

    private fun clearScan() {
        scan.clear()
        binding.polarView.clear()
        binding.tvResult.setTextColor(Color.WHITE)
        binding.tvResult.text = getString(R.string.sonar_result_placeholder)
        if (!isScanning) binding.tvStatus.text = ""
    }

    private fun onPingClicked() {
        if (isMeasuring) return
        if (!hasMicPermission()) {
            binding.tvStatus.text = getString(R.string.sonar_no_permission)
            return
        }

        val angleDeg = binding.etAngle.text?.toString()?.toFloatOrNull() ?: 0f
        val targetScan = scan
        isMeasuring = true
        binding.btnPing.isEnabled = false
        binding.tvStatus.text = getString(R.string.sonar_measuring)

        Thread {
            val result = sonarEngine.measureOnce(
                maxRangeMeters = MAX_RANGE_METERS,
                minRangeMeters = MIN_RANGE_METERS
            )

            // Scan bookkeeping and image building off the UI thread.
            var image: PpiImage? = null
            var markers: List<PolarView.Marker> = emptyList()
            var pingEchoes: List<Echo> = emptyList()
            result.profile?.let { profile ->
                targetScan.add(profile.copy(angleDeg = angleDeg))
                targetScan.recomputeLeakage()
                image = targetScan.toImage(RANGE_CELLS)
                markers = targetScan.occupiedBinIndices().flatMap { bin ->
                    targetScan.echoesAt(bin).map {
                        PolarView.Marker(targetScan.binCenterDeg(bin), it.distanceMeters.toFloat())
                    }
                }
                pingEchoes = targetScan.echoesAt(targetScan.binOf(angleDeg))
            }

            runOnUiThread {
                isMeasuring = false
                binding.btnPing.isEnabled = true
                if (targetScan !== scan) return@runOnUiThread // mode changed meanwhile
                image?.let { binding.polarView.setImage(it) }
                if (result.profile != null) binding.polarView.setMarkers(markers)
                handleManualResult(result, pingEchoes)
            }
        }.start()
    }

    private fun handleManualResult(result: SonarEngine.SonarResult, pingEchoes: List<Echo>) {
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
    private fun startScanning() {
        if (!hasMicPermission()) {
            binding.tvStatus.text = getString(R.string.sonar_no_permission)
            return
        }

        clearScan()
        synchronized(turnProfiles) { turnProfiles.clear() }
        lastStreamStatus = null

        val newStreamer = SonarStreamer(
            sampleRate = SAMPLE_RATE,
            maxRangeMeters = MAX_RANGE_METERS,
            onProfile = { profile -> synchronized(turnProfiles) { turnProfiles.add(profile) } },
            onStatus = { status -> lastStreamStatus = status }
        )
        streamer = newStreamer
        isScanning = true
        scanStartMs = SystemClock.elapsedRealtime()
        newStreamer.start()

        binding.btnPing.text = getString(R.string.sonar_stop)
        binding.root.post(uiTick)
    }

    private fun stopScanning() {
        isScanning = false
        binding.root.removeCallbacks(uiTick)
        val stopped = streamer
        stopped?.stop()
        streamer = null
        binding.btnPing.text = getString(R.string.sonar_start)

        val totalSamples = stopped?.samplesRecorded ?: 0L
        buildTurnImage(totalSamples)
    }

    private fun updateScanningStatus() {
        val elapsedSec = (SystemClock.elapsedRealtime() - scanStartMs) / 1000.0
        binding.tvResult.setTextColor(Color.WHITE)
        binding.tvResult.text = getString(R.string.sonar_elapsed, elapsedSec)
        val count = synchronized(turnProfiles) { turnProfiles.size }
        binding.tvStatus.text = getString(R.string.sonar_turn_progress, count, streamStatusLine())
    }

    private fun streamStatusLine(): String {
        val status = lastStreamStatus
        return when {
            status == null -> getString(R.string.sonar_measuring)
            status.message != null -> status.message
            status.directSnrDb != null -> getString(R.string.sonar_tracking, status.directSnrDb)
            else -> ""
        }
    }

    private fun buildTurnImage(totalSamples: Long) {
        val profiles = synchronized(turnProfiles) { turnProfiles.toList() }
        if (totalSamples <= 0L || profiles.isEmpty()) {
            binding.tvResult.text = getString(R.string.sonar_failed)
            binding.tvStatus.text = getString(R.string.sonar_turn_no_data, streamStatusLine())
            return
        }

        val turnSeconds = totalSamples.toDouble() / SAMPLE_RATE
        val speedDegPerSec = 360.0 / turnSeconds
        val targetScan = scan

        Thread {
            for (p in profiles) {
                val bearing = (360.0 * p.sampleIndex / totalSamples).toFloat()
                targetScan.add(p.copy(angleDeg = bearing))
            }
            targetScan.recomputeLeakage()
            val image = targetScan.toImage(RANGE_CELLS)

            runOnUiThread {
                if (targetScan !== scan) return@runOnUiThread // mode changed meanwhile
                binding.polarView.setImage(image)

                if (turnSeconds < MIN_TURN_SECONDS) {
                    binding.tvResult.setTextColor(Color.rgb(255, 80, 80))
                    binding.tvResult.text = getString(R.string.sonar_turn_too_fast, speedDegPerSec)
                } else {
                    binding.tvResult.setTextColor(Color.WHITE)
                    binding.tvResult.text = getString(R.string.sonar_turn_result, turnSeconds, speedDegPerSec)
                }

                val coverageDeg = (targetScan.occupiedBins() * ROTATION_BIN_DEG).toInt()
                val leakageText = if (targetScan.isLeakageActive()) {
                    getString(R.string.sonar_leakage_on_short)
                } else {
                    getString(R.string.sonar_leakage_off_short)
                }
                binding.tvStatus.text = getString(
                    R.string.sonar_turn_summary,
                    profiles.size,
                    coverageDeg,
                    leakageText,
                    lastStreamStatus?.audioSource ?: "–"
                )
            }
        }.start()
    }
}
