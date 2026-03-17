package expo.modules.pcmstream

import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.Process
import android.os.SystemClock
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlin.math.floor

class PCMStreamModule : Module() {

  // 使用新的 PCMStreamPlayer
  private var player: PCMStreamPlayer? = null
  private var currentPlaybackSampleRate: Int? = null

  private var audioRecord: AudioRecord? = null
  private var aec: AcousticEchoCanceler? = null
  private var isRecording = false
  private var recordingThread: Thread? = null

  // 初始化播放器
  private fun initAudioTrack(sampleRate: Int = 16000) {
    // 如果采样率改变，需要重新创建播放器
    if (player != null && currentPlaybackSampleRate != sampleRate) {
      player?.release()
      player = null
    }
    
    // 创建新播放器（如果不存在）
    if (player == null) {
      currentPlaybackSampleRate = sampleRate
      player = PCMStreamPlayer(
        sampleRate = sampleRate,
        channelCount = 1,
        bytesPerSample = 2,
        idleTimeoutMs = 2000 // 2秒空闲超时，适应 JS 定时器抖动和网络延迟
      )
      
      // 设置播放状态监听器
      player?.setPlaybackListener(object : PCMStreamPlayer.PlaybackListener {
        override fun onPlaybackStart() {
          android.util.Log.d("PCMStream", "▶️ 播放开始（AEC 已启用，麦克风保持开启）")
          sendEvent("onPlaybackStart", mapOf(
            "state" to "PLAYING"
          ))
        }
        
        override fun onPlaybackCompleted() {
          android.util.Log.d("PCMStream", "✅ 播放完成")
          val totalDuration = player?.getTotalDuration() ?: 0.0
          val playedDuration = player?.getPlayedDuration() ?: 0.0
          sendEvent("onPlaybackStop", mapOf(
            "state" to "COMPLETED",
            "totalDuration" to totalDuration,
            "playedDuration" to playedDuration
          ))
        }
        
        override fun onPlaybackPaused() {
          android.util.Log.d("PCMStream", "⏸️ 播放暂停")
          sendEvent("onPlaybackPaused", mapOf(
            "state" to "PAUSED"
          ))
        }
        
        override fun onPlaybackResumed() {
          android.util.Log.d("PCMStream", "▶️ 播放恢复")
          sendEvent("onPlaybackResumed", mapOf(
            "state" to "PLAYING"
          ))
        }
        
        override fun onProgressUpdate(playedSeconds: Double, totalSeconds: Double, progress: Double) {
          // 播放进度更新（每秒触发）
          sendEvent("onPlaybackProgress", mapOf(
            "playedDuration" to playedSeconds,
            "totalDuration" to totalSeconds,
            "progress" to progress,
            "remainingDuration" to (totalSeconds - playedSeconds)
          ))
        }
        
        override fun onAmplitudeUpdate(amplitude: Double) {
          // 音频振幅更新（用于口型同步，约每 16ms 触发）
          sendEvent("onAmplitudeUpdate", mapOf(
            "amplitude" to amplitude
          ))
        }
        
        override fun onError(error: Throwable) {
          android.util.Log.e("PCMStream", "❌ 播放错误: ${error.message}")
          sendEvent("onError", mapOf(
            "message" to (error.message ?: "Unknown error"),
            "state" to "ERROR"
          ))
        }
      })
      
      android.util.Log.d("PCMStream", "🎵 播放器已初始化 (${sampleRate}Hz)")
    }
  }

  // 追加 PCM 数据（支持任意大小数据块）
  private fun appendPCMData(chunk: ByteArray) {
    if (chunk.isEmpty()) return
    // ✅ 直接 append 完整数据，PCMStreamPlayer 内部会自动处理
    // - Channel.UNLIMITED 支持任意大小
    // - writeFully() 会循环写入 AudioTrack
    player?.append(chunk)
  }

  // 停止播放
  private fun stopPlaybackInternal() {
    player?.stopAndReset()
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
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize
    )

    isRecording = true
    audioRecord?.startRecording()

    // 挂载硬件回声消除（AEC），允许播放时同时录音
    val audioSessionId = audioRecord?.audioSessionId ?: AudioRecord.ERROR
    if (audioSessionId != AudioRecord.ERROR && AcousticEchoCanceler.isAvailable()) {
      aec?.release()
      aec = AcousticEchoCanceler.create(audioSessionId)
      aec?.enabled = true
      android.util.Log.d("PCMStream", "✅ AEC 已启用 (sessionId=$audioSessionId)")
    } else {
      android.util.Log.w("PCMStream", "⚠️ AEC 不可用，回声消除未启用")
    }

    recordingThread = Thread {
      // 提升录音线程优先级，降低调度抖动
      try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}

      val buffer = ShortArray(frameSize)
      val framePeriodMs = ((frameSize.toDouble() / sampleRate.toDouble()) * 1000.0).toLong().coerceAtLeast(1)
      var lastSendMs = 0L
      var seq: Long = 0
      
      try {
        while (isRecording) {
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
                try { 
                  Thread.sleep(sleepMs) 
                } catch (_: InterruptedException) {
                  // 被中断，重新抛出以便外层捕获
                  throw InterruptedException("Recording thread interrupted during sleep")
                }
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
      } catch (e: InterruptedException) {
        // 线程被中断，正常退出（例如停止录音时）
        android.util.Log.d("PCMStream", "📍 录音线程被中断，正常退出")
      } catch (e: Exception) {
        // 其他异常
        android.util.Log.e("PCMStream", "❌ 录音线程异常: ${e.message}", e)
      }
    }.also { it.start() }
  }

  private fun stopRecordingInternal() {
    isRecording = false
    recordingThread?.interrupt()
    recordingThread = null
    try { aec?.release() } catch (_: Throwable) {}
    aec = null
    try { audioRecord?.stop() } catch (_: Throwable) {}
    try { audioRecord?.release() } catch (_: Throwable) {}
    audioRecord = null
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

    Events("onError", "onPlaybackStart", "onPlaybackStop", "onPlaybackPaused", "onPlaybackResumed", "onPlaybackProgress", "onAmplitudeUpdate", "onAudioFrame")

    // 播放相关
    Function("initPlayer") { sampleRate: Int? -> initAudioTrack(sampleRate ?: 16000) }
    Function("playPCMChunk") { chunk: ByteArray -> appendPCMData(chunk) }
    Function("stopPlayback") { stopPlaybackInternal() }
    
    // ===== 新增：播放状态和时间统计 =====
    
    /**
     * 获取当前播放状态
     * @return 状态字符串: "IDLE" | "PLAYING" | "PAUSED" | "COMPLETED"
     */
    Function("getPlaybackState") {
      player?.getState()?.name ?: "IDLE"
    }
    
    /**
     * 检查是否正在播放
     * @return true 表示正在播放中
     */
    Function("isPlaying") {
      player?.isActivelyPlaying() ?: false
    }
    
    /**
     * 获取已追加数据的总预计播放时长（秒）
     * @return 总时长（秒）
     */
    Function("getTotalDuration") {
      player?.getTotalDuration() ?: 0.0
    }
    
    /**
     * 获取已播放的时长（秒）
     * @return 已播放时长（秒）
     */
    Function("getPlayedDuration") {
      player?.getPlayedDuration() ?: 0.0
    }
    
    /**
     * 获取剩余播放时长（秒）
     * @return 剩余时长（秒）
     */
    Function("getRemainingDuration") {
      player?.getRemainingDuration() ?: 0.0
    }
    
    /**
     * 获取播放进度（0.0 ~ 1.0）
     * @return 播放进度百分比
     */
    Function("getProgress") {
      player?.getProgress() ?: 0.0
    }
    
    /**
     * 获取完整的播放统计信息
     * @return Map 包含所有统计数据
     */
    Function("getPlaybackStats") {
      val p = player
      if (p == null) {
        mapOf(
          "state" to "IDLE",
          "isPlaying" to false,
          "totalDuration" to 0.0,
          "playedDuration" to 0.0,
          "remainingDuration" to 0.0,
          "progress" to 0.0
        )
      } else {
        mapOf(
          "state" to p.getState().name,
          "isPlaying" to p.isActivelyPlaying(),
          "totalDuration" to p.getTotalDuration(),
          "playedDuration" to p.getPlayedDuration(),
          "remainingDuration" to p.getRemainingDuration(),
          "progress" to p.getProgress()
        )
      }
    }

    // 录音相关
    Function("startRecording") { sampleRate: Int?, frameSize: Int?, targetRate: Int? ->
      startRecording(sampleRate ?: 48000, frameSize ?: 1536, targetRate ?: 16000)
    }
    Function("stopRecording") { stopRecordingInternal() }

    OnDestroy {
      player?.release()
      player = null
      stopRecordingInternal()
    }
  }
}
