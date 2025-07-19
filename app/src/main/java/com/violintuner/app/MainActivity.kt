package com.violintuner.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.violintuner.app.ui.theme.ViolinTunerTheme
import kotlinx.coroutines.delay
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, can start audio recording
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            ViolinTunerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ViolinTunerApp()
                }
            }
        }
    }
}

// Violin string frequencies in Hz
enum class ViolinString(val displayName: String, val frequency: Double) {
    G("G", 196.0),
    D("D", 293.66),
    A("A", 440.0),
    E("E", 659.25)
}

class AudioManager {
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    fun playTone(frequency: Double, durationMs: Int = 2000) {
        val sampleRate = 44100
        val numSamples = (durationMs * sampleRate) / 1000
        val samples = ShortArray(numSamples)

        // Generate sine wave
        for (i in samples.indices) {
            val sample = (sin(2 * PI * frequency * i / sampleRate) * 32767).toInt()
            samples[i] = sample.toShort()
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .build()

        audioTrack.play()
        audioTrack.write(samples, 0, samples.size)

        Handler(Looper.getMainLooper()).postDelayed({
            audioTrack.stop()
            audioTrack.release()
        }, durationMs.toLong())
    }

    fun startListening(onFrequencyDetected: (Double) -> Unit) {
        if (isRecording) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            Thread {
                val buffer = ShortArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        val frequency = detectPitch(buffer, sampleRate)
                        if (frequency > 0) {
                            Handler(Looper.getMainLooper()).post {
                                onFrequencyDetected(frequency)
                            }
                        }
                    }
                }
            }.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopListening() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun detectPitch(buffer: ShortArray, sampleRate: Int): Double {
        // Simple autocorrelation pitch detection
        val normalizedBuffer = buffer.map { it.toDouble() / 32768.0 }
        val minPeriod = sampleRate / 800 // 800 Hz max
        val maxPeriod = sampleRate / 80  // 80 Hz min

        var maxCorrelation = 0.0
        var bestPeriod = 0

        for (period in minPeriod..minOf(maxPeriod, normalizedBuffer.size / 2)) {
            var correlation = 0.0
            for (i in 0 until normalizedBuffer.size - period) {
                correlation += normalizedBuffer[i] * normalizedBuffer[i + period]
            }

            if (correlation > maxCorrelation) {
                maxCorrelation = correlation
                bestPeriod = period
            }
        }

        return if (bestPeriod > 0) sampleRate.toDouble() / bestPeriod else 0.0
    }
}

@Composable
fun ViolinTunerApp() {
    val context = LocalContext.current
    val audioManager = remember { AudioManager() }
    var selectedString by remember { mutableStateOf(ViolinString.A) }
    var currentFrequency by remember { mutableStateOf(0.0) }
    var isListening by remember { mutableStateOf(false) }

    // Calculate tuning difference
    val frequencyDifference = currentFrequency - selectedString.frequency
    val centsDifference = if (currentFrequency > 0) {
        1200 * log2(currentFrequency / selectedString.frequency)
    } else 0.0

    // Determine tuning status
    val tuningStatus = when {
        abs(centsDifference) < 5 -> TuningStatus.IN_TUNE
        centsDifference > 0 -> TuningStatus.SHARP
        else -> TuningStatus.FLAT
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "Violin Tuner",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 24.dp)
        )

        // String selection buttons
        Row(
            modifier = Modifier.padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ViolinString.values().forEach { string ->
                StringButton(
                    string = string,
                    isSelected = selectedString == string,
                    onStringSelected = { selectedString = it },
                    onPlayTone = { audioManager.playTone(it.frequency) }
                )
            }
        }

        // Tuning dial
        TuningDial(
            currentFrequency = currentFrequency,
            targetFrequency = selectedString.frequency,
            tuningStatus = tuningStatus,
            modifier = Modifier
                .size(280.dp)
                .padding(bottom = 32.dp)
        )

        // Frequency display
        FrequencyDisplay(
            currentFrequency = currentFrequency,
            targetFrequency = selectedString.frequency,
            selectedString = selectedString
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Listen button
        Button(
            onClick = {
                if (isListening) {
                    audioManager.stopListening()
                    isListening = false
                } else {
                    audioManager.startListening { frequency ->
                        currentFrequency = frequency
                    }
                    isListening = true
                }
            },
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isListening) "STOP" else "LISTEN",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // Auto-stop listening after 30 seconds
    LaunchedEffect(isListening) {
        if (isListening) {
            delay(30000)
            if (isListening) {
                audioManager.stopListening()
                isListening = false
            }
        }
    }
}

@Composable
fun StringButton(
    string: ViolinString,
    isSelected: Boolean,
    onStringSelected: (ViolinString) -> Unit,
    onPlayTone: (ViolinString) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = { onStringSelected(string) },
            modifier = Modifier.size(60.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(
                text = string.displayName,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { onPlayTone(string) },
            modifier = Modifier.width(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text("♪", fontSize = 16.sp)
        }
    }
}

enum class TuningStatus { FLAT, IN_TUNE, SHARP }

@Composable
fun TuningDial(
    currentFrequency: Double,
    targetFrequency: Double,
    tuningStatus: TuningStatus,
    modifier: Modifier = Modifier
) {
    val centsDifference = if (currentFrequency > 0) {
        1200 * log2(currentFrequency / targetFrequency)
    } else 0.0

    // Clamp to -50 to +50 cents for display
    val clampedCents = centsDifference.coerceIn(-50.0, 50.0)
    val needleAngle = (clampedCents / 50.0 * 90.0).toFloat() // -90 to +90 degrees

    Canvas(modifier = modifier) {
        val center = size.center
        val radius = size.minDimension / 2 * 0.8f

        // Draw outer circle
        drawCircle(
            color = Color.Gray,
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx())
        )

        // Draw colored arcs
        val sweepAngle = 60f

        // Flat zone (red)
        drawArc(
            color = Color.Red,
            startAngle = 180f + sweepAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(width = 8.dp.toPx()),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )

        // In-tune zone (green)
        drawArc(
            color = Color.Green,
            startAngle = 180f + sweepAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(width = 8.dp.toPx()),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )

        // Sharp zone (red)
        drawArc(
            color = Color.Red,
            startAngle = 180f + 2 * sweepAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(width = 8.dp.toPx()),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )

        // Draw tick marks
        for (i in -5..5) {
            val angle = (i * 18f) * PI / 180 // Convert to radians
            val startRadius = radius * 0.85f
            val endRadius = radius * 0.95f

            val startX = center.x + cos(angle + PI).toFloat() * startRadius
            val startY = center.y + sin(angle + PI).toFloat() * startRadius
            val endX = center.x + cos(angle + PI).toFloat() * endRadius
            val endY = center.y + sin(angle + PI).toFloat() * endRadius

            drawLine(
                color = Color.Black,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Draw needle
        rotate(needleAngle, center) {
            val needleLength = radius * 0.7f
            drawLine(
                color = when (tuningStatus) {
                    TuningStatus.IN_TUNE -> Color.Green
                    TuningStatus.FLAT -> Color.Red
                    TuningStatus.SHARP -> Color.Red
                },
                start = center,
                end = Offset(center.x, center.y - needleLength),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Draw center dot
        drawCircle(
            color = Color.Black,
            radius = 8.dp.toPx(),
            center = center
        )
    }
}

@Composable
fun FrequencyDisplay(
    currentFrequency: Double,
    targetFrequency: Double,
    selectedString: ViolinString
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "String: ${selectedString.displayName}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Current",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${currentFrequency.toInt()} Hz",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "vs",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 20.dp)
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Target",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${targetFrequency.toInt()} Hz",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (currentFrequency > 0) {
                val difference = currentFrequency - targetFrequency
                val cents = 1200 * log2(currentFrequency / targetFrequency)

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = when {
                        abs(cents) < 5 -> "IN TUNE ✓"
                        cents > 0 -> "SHARP (+${cents.toInt()} cents)"
                        else -> "FLAT (${cents.toInt()} cents)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        abs(cents) < 5 -> Color(0xFF4CAF50)
                        else -> Color(0xFFF44336)
                    }
                )
            }
        }
    }
}