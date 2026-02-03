package org.fossify.phone.mesh

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.fossify.mesh.call.MeshCallQuality

class MeshAudioEngine(
    private val quality: MeshCallQuality,
    private val onEncodedFrame: (ByteArray) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var playbackThread: Thread? = null
    private val incomingFrames = ConcurrentLinkedQueue<ByteArray>()
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null
    private val frameSize = quality.sampleRate / 50 // 20ms frames

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        setupCodec()
        setupAudioIO()
        startCapture()
        startPlayback()
    }

    fun stop() {
        isRunning.set(false)
        recordThread?.interrupt()
        playbackThread?.interrupt()
        audioRecord?.stop()
        audioTrack?.stop()
        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null
        encoder = null
        decoder = null
        incomingFrames.clear()
    }

    fun enqueueFrame(opusFrame: ByteArray) {
        if (!isRunning.get()) return
        incomingFrames.add(opusFrame)
    }

    private fun setupCodec() {
        encoder = OpusEncoder(quality.sampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            bitrate = quality.bitrate
        }
        decoder = OpusDecoder(quality.sampleRate, 1)
    }

    private fun setupAudioIO() {
        val minIn = AudioRecord.getMinBufferSize(
            quality.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameSize * 2)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            quality.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minIn * 2
        )

        val minOut = AudioTrack.getMinBufferSize(
            quality.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameSize * 2)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(quality.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minOut * 2)
            .build()
    }

    private fun startCapture() {
        val record = audioRecord ?: return
        val enc = encoder ?: return
        record.startRecording()
        recordThread = thread(start = true, name = "mesh-audio-record") {
            val pcm = ShortArray(frameSize)
            val output = ByteArray(4000)
            while (isRunning.get()) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                val encodedLength = enc.encode(pcm, 0, frameSize, output, 0, output.size)
                if (encodedLength > 0) {
                    val frame = output.copyOfRange(0, encodedLength)
                    onEncodedFrame(frame)
                }
            }
        }
    }

    private fun startPlayback() {
        val track = audioTrack ?: return
        val dec = decoder ?: return
        track.play()
        playbackThread = thread(start = true, name = "mesh-audio-playback") {
            val pcm = ShortArray(frameSize * 2)
            while (isRunning.get()) {
                val frame = incomingFrames.poll()
                if (frame == null) {
                    Thread.sleep(10)
                    continue
                }
                val decoded = dec.decode(frame, 0, frame.size, pcm, 0, frameSize, false)
                if (decoded > 0) {
                    track.write(pcm, 0, decoded)
                }
            }
        }
    }
}
