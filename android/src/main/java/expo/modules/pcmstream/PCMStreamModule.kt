package expo.modules.pcmstream

import android.media.*
import android.os.Process
import android.os.SystemClock
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.floor

class PCMStreamModule : Module() {

  private var audioTrack: AudioTrack? = null
  private var isPlaying = false
  private val playQueue = LinkedBlockingQueue<ByteArray>()
  private var playbackThread: Thread? = null
  private var currentPlaybackSampleRate: Int? = null
  @Volatile private var lastEnqueueMs: Long = 0L

  private var audioRecord: AudioRecord? = null
  private var isRecording = false
  private var recordingThread: Thread? = null
  
  // 音频反馈控制
  private var isPlaybackActive = false
  private var recordingPausedForPlayback = false

  // 初始化播放
  private fun initAudioTrack(sampleRate: Int = 16000) {
    // 若已在播放且采样率一致，跳过重复初始化，避免多余的 onPlaybackStop 事件
    if (isPlaying && audioTrack != null && currentPlaybackSampleRate == sampleRate) {
      return
    }
    if (isPlaying || audioTrack != null) stopPlaybackInternal()

    val bufferSize = AudioTrack.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )

    // 使用STREAM_VOICE_CALL减少反馈，或使用STREAM_MUSIC但降低音量
    audioTrack = AudioTrack(
      AudioManager.STREAM_MUSIC, // 使用媒体流，获得更大外放音量
      sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize,
      AudioTrack.MODE_STREAM
    )
    
    playQueue.clear()
    audioTrack?.play()
    isPlaying = true
    isPlaybackActive = true
    currentPlaybackSampleRate = sampleRate

    // 播放开始时暂停录音以防止反馈
    pauseRecordingForPlayback()

    sendEvent("onPlaybackStart", emptyMap())

    playbackThread = Thread {
      try {
        while (isPlaying) {
          // 使用带超时的poll，便于检测播放是否结束（队列长时间为空）
          val chunk = playQueue.poll(100, TimeUnit.MILLISECONDS)
          if (!isPlaying) break
          if (chunk == null) {
            val now = SystemClock.elapsedRealtime()
            // 若近期无新的enqueue且队列为空，判定为播放完成
            if (playQueue.isEmpty() && lastEnqueueMs > 0L && (now - lastEnqueueMs) > 300) {
              break
            }
            continue
          }
          if (chunk.isEmpty()) continue
          val track = audioTrack ?: continue
          track.write(chunk, 0, chunk.size)
        }
      } catch (_: InterruptedException) {
      } finally {
        stopPlaybackInternal()
      }
    }.also { it.start() }
  }

  private fun appendPCMData(chunk: ByteArray) {
    if (chunk.isEmpty()) return
    playQueue.offer(chunk)
    lastEnqueueMs = SystemClock.elapsedRealtime()
  }

  // 批量追加整段 PCM，原生侧切片入队以减少 JS 循环
  private fun appendPCMBuffer(data: ByteArray, chunkBytes: Int = 1024) {
    if (data.isEmpty() || chunkBytes <= 0) return
    var pos = 0
    while (pos < data.size) {
      val end = kotlin.math.min(pos + chunkBytes, data.size)
      val chunk = data.copyOfRange(pos, end)
      appendPCMData(chunk)
      pos = end
    }
  }

  private fun stopPlaybackInternal() {
    isPlaying = false
    isPlaybackActive = false
    playbackThread?.interrupt()
    playbackThread = null
    try { audioTrack?.stop() } catch (_: Throwable) {}
    try { audioTrack?.release() } catch (_: Throwable) {}
    audioTrack = null
    playQueue.clear()
    
    // 播放停止后恢复录音
    resumeRecordingAfterPlayback()
    
    sendEvent("onPlaybackStop", emptyMap())
  }

  // 初始化录音 - 修复32ms发送机制
  private fun startRecording(sampleRate: Int = 48000, frameSize: Int = 1536, targetRate: Int = 16000) {
    if (isRecording) stopRecordingInternal()

    val bufferSize = AudioRecord.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )

    audioRecord = AudioRecord(
      MediaRecorder.AudioSource.MIC,
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize
    )

    isRecording = true
    audioRecord?.startRecording()

    recordingThread = Thread {
      // 提升录音线程优先级，降低调度抖动
      try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}

      val buffer = ShortArray(frameSize)
      val framePeriodMs = ((frameSize.toDouble() / sampleRate.toDouble()) * 1000.0).toLong().coerceAtLeast(1)
      var lastSendMs = 0L
      var seq: Long = 0
      while (isRecording) {
        // 如果播放活跃且录音被暂停，跳过录音数据
        if (isPlaybackActive && recordingPausedForPlayback) {
          Thread.sleep(32) // 等待32ms
          continue
        }
        
        val read = audioRecord?.read(buffer, 0, frameSize) ?: 0
        if (read > 0) {
          // 重采样：从48kHz到16kHz，1536样本变成512样本（32ms）
          val resampled = resampleAudio(buffer, read, sampleRate, targetRate)
          
          // 转 ByteArray
          val byteBuf = ByteArray(resampled.size * 2)
          var idx = 0
          for (s in resampled) {
            byteBuf[idx++] = (s.toInt() and 0xFF).toByte()
            byteBuf[idx++] = ((s.toInt() shr 8) and 0xFF).toByte()
          }
          
          // 固定节拍控制：确保 onAudioFrame 发出间隔不小于 framePeriodMs
          val nowMs = SystemClock.elapsedRealtime()
          if (lastSendMs > 0) {
            val elapsed = nowMs - lastSendMs
            val sleepMs = framePeriodMs - elapsed
            if (sleepMs > 0) {
              try { Thread.sleep(sleepMs) } catch (_: Throwable) {}
            }
          }
          val tsMs = SystemClock.elapsedRealtime()
          seq += 1
          lastSendMs = tsMs

          // 发送事件到 JS - 附带时间戳与序号
          sendEvent("onAudioFrame", mapOf(
            "pcm" to byteBuf,
            "ts" to tsMs,
            "seq" to seq
          ))
        }
      }
    }.also { it.start() }
  }

  private fun stopRecordingInternal() {
    isRecording = false
    recordingThread?.interrupt()
    recordingThread = null
    try { audioRecord?.stop() } catch (_: Throwable) {}
    try { audioRecord?.release() } catch (_: Throwable) {}
    audioRecord = null
    recordingPausedForPlayback = false
  }
  
  // 播放时暂停录音以防止反馈
  private fun pauseRecordingForPlayback() {
    if (isRecording && !recordingPausedForPlayback) {
      recordingPausedForPlayback = true
      android.util.Log.d("PCMStream", "🔇 播放开始，暂停录音防止反馈")
    }
  }
  
  // 播放停止后恢复录音
  private fun resumeRecordingAfterPlayback() {
    if (recordingPausedForPlayback) {
      recordingPausedForPlayback = false
      android.util.Log.d("PCMStream", "🎤 播放停止，恢复录音")
    }
  }

  // 线性插值重采样
  private fun resampleAudio(input: ShortArray, length: Int, originalRate: Int, targetRate: Int): ShortArray {
    val ratio = targetRate.toDouble() / originalRate.toDouble()
    val outputLength = floor(length * ratio).toInt()
    val result = ShortArray(outputLength)
    for (i in 0 until outputLength) {
      val pos = i / ratio
      val index = floor(pos).toInt()
      val frac = pos - index
      val s1 = input[index]
      val s2 = if (index + 1 < length) input[index + 1] else s1
      result[i] = ((s1 * (1 - frac) + s2 * frac)).toInt().toShort()
    }
    return result
  }

  override fun definition() = ModuleDefinition {
    Name("PCMStream")

    Events("onError", "onPlaybackStart", "onPlaybackStop", "onAudioFrame")

    // 播放相关
    Function("initPlayer") { sampleRate: Int? -> initAudioTrack(sampleRate ?: 16000) }
    Function("playPCMChunk") { chunk: ByteArray -> appendPCMData(chunk) }
    Function("appendPCMBuffer") { data: ByteArray, chunkBytes: Int? ->
      appendPCMBuffer(data, (chunkBytes ?: 1024))
    }
    Function("stopPlayback") { stopPlaybackInternal() }

    // 录音相关
    Function("startRecording") { sampleRate: Int?, frameSize: Int?, targetRate: Int? ->
      startRecording(sampleRate ?: 48000, frameSize ?: 1536, targetRate ?: 16000)
    }
    Function("stopRecording") { stopRecordingInternal() }
    
    // 反馈控制
    Function("pauseRecordingForPlayback") { pauseRecordingForPlayback() }
    Function("resumeRecordingAfterPlayback") { resumeRecordingAfterPlayback() }

    OnDestroy {
      stopPlaybackInternal()
      stopRecordingInternal()
    }
  }
}
