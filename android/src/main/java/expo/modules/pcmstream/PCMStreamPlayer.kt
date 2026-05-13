@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package expo.modules.pcmstream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * PCM 流式播放器
 *
 * 使用协程和 Channel 实现的高性能音频播放器
 * 支持实时流式播放，自动管理缓冲队列
 *
 * @param sampleRate 采样率，默认 44100 Hz
 * @param channelCount 声道数，1 = 单声道，2 = 立体声
 * @param bytesPerSample 每样本字节数，2 = 16-bit PCM
 * @param idleTimeoutMs 空闲超时时间（毫秒），超过此时间无新数据则认为播放完成，默认 2000ms
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PCMStreamPlayer(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1, // 1 = mono, 2 = stereo
    private val bytesPerSample: Int = 2, // 2 bytes = 16-bit PCM
    private val idleTimeoutMs: Long = 2000 // 空闲超时时间（从 800ms 增加到 2000ms 以适应 JS 定时器抖动）
) {
    /**
     * 播放器状态枚举
     */
    enum class PlaybackState {
        IDLE,       // 空闲状态
        PLAYING,    // 播放中
        PAUSED,     // 已暂停
        COMPLETED   // 播放完成
    }

    /**
     * 播放器状态监听器
     */
    interface PlaybackListener {
        /** 播放开始 */
        fun onPlaybackStart()

        /** 播放完成（队列为空且超时） */
        fun onPlaybackCompleted()

        /** 播放暂停 */
        fun onPlaybackPaused()

        /** 播放恢复 */
        fun onPlaybackResumed()

        /** 发生错误 */
        fun onError(error: Throwable)

        /** 播放进度更新（每秒触发一次） */
        fun onProgressUpdate(playedSeconds: Double, totalSeconds: Double, progress: Double)

        /** 音频振幅更新（用于口型同步，约每 16ms 触发一次） */
        fun onAmplitudeUpdate(amplitude: Double)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioTrack: AudioTrack? = null
    private val bufferChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    @Volatile private var isPlaying = false
    @Volatile private var currentState: PlaybackState = PlaybackState.IDLE
    @Volatile private var lastAppendTimeMs: Long = 0L
    @Volatile private var totalBytesWritten: Long = 0L  // 写入 AudioTrack 的总字节数

    // ===== 播放时间统计 =====
    @Volatile private var totalBytesAppended: Long = 0L    // 累计追加的总字节数
    @Volatile private var totalBytesPlayed: Long = 0L      // 已播放的字节数
    @Volatile private var lastProgressReportTimeMs: Long = 0L  // 上次进度报告时间

    // ===== 音频振幅分析（用于口型同步） =====
    private val amplitudeUpdateInterval = 16L  // 振幅更新间隔（约 60fps）
    private val amplitudeWindowMs = 32L        // 当前播放头附近的分析窗口

    private data class AmplitudeSegment(
        val startFrame: Long,
        val frameCount: Int,
        val bytes: ByteArray
    )

    private val amplitudeLock = Any()
    private val amplitudeSegments = ArrayDeque<AmplitudeSegment>()
    private var amplitudeJob: Job? = null

    private var listener: PlaybackListener? = null
    private var playbackJob: Job? = null

    /**
     * 初始化 AudioTrack
     * 幂等操作，重复调用不会重复创建
     */
    fun prepare() {
        if (audioTrack != null) return

        val channelConfig = if (channelCount == 1)
            AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * channelCount * bytesPerSample / 2) // 保守一点

        val attrib = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelConfig)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attrib)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 设置播放状态监听器
     *
     * @param listener 状态监听器，为 null 时移除监听
     */
    fun setPlaybackListener(listener: PlaybackListener?) {
        this.listener = listener
    }

    /**
     * 获取当前播放状态
     *
     * @return 当前状态
     */
    fun getState(): PlaybackState = currentState

    /**
     * 是否正在播放中
     *
     * @return true 表示正在播放
     */
    fun isActivelyPlaying(): Boolean = currentState == PlaybackState.PLAYING

    // ===== 播放时间统计方法 =====

    /**
     * 获取已追加数据的总预计播放时长（秒）
     *
     * @return 总播放时长（秒）
     */
    fun getTotalDuration(): Double {
        return bytesToSeconds(totalBytesAppended)
    }

    /**
     * 获取已播放的时长（秒）
     *
     * @return 已播放时长（秒）
     */
    fun getPlayedDuration(): Double {
        return bytesToSeconds(updatePlayedBytesFromAudioTrack())
    }

    /**
     * 获取剩余播放时长（秒）
     *
     * @return 剩余播放时长（秒）
     */
    fun getRemainingDuration(): Double {
        val remaining = totalBytesAppended - updatePlayedBytesFromAudioTrack()
        return if (remaining > 0) bytesToSeconds(remaining) else 0.0
    }

    /**
     * 获取播放进度百分比（0.0 ~ 1.0）
     *
     * @return 播放进度，0.0 表示未开始，1.0 表示完成
     */
    fun getProgress(): Double {
        if (totalBytesAppended == 0L) return 0.0
        return (updatePlayedBytesFromAudioTrack().toDouble() / totalBytesAppended).coerceIn(0.0, 1.0)
    }

    /**
     * 字节数转换为播放时长（秒）
     *
     * @param bytes 字节数
     * @return 播放时长（秒）
     */
    private fun bytesToSeconds(bytes: Long): Double {
        val bytesPerSecond = sampleRate * bytesPerFrame()
        return bytes.toDouble() / bytesPerSecond
    }

    private fun bytesPerFrame(): Int {
        return channelCount * bytesPerSample
    }

    private fun playbackHeadFramePosition(): Long {
        val at = audioTrack ?: return 0L
        return Integer.toUnsignedLong(at.playbackHeadPosition)
    }

    private fun updatePlayedBytesFromAudioTrack(): Long {
        val playedBytes = (playbackHeadFramePosition() * bytesPerFrame()).coerceAtMost(totalBytesWritten)
        totalBytesPlayed = playedBytes
        return playedBytes
    }

    private fun clearAmplitudeHistory() {
        synchronized(amplitudeLock) {
            amplitudeSegments.clear()
        }
    }

    private fun appendAmplitudeSegment(pcmData: ByteArray) {
        val frameSize = bytesPerFrame()
        if (frameSize <= 0) return

        val frameCount = pcmData.size / frameSize
        if (frameCount <= 0) return

        val startFrame = totalBytesAppended / frameSize
        synchronized(amplitudeLock) {
            amplitudeSegments.add(
                AmplitudeSegment(
                    startFrame = startFrame,
                    frameCount = frameCount,
                    bytes = pcmData.copyOf()
                )
            )
        }
    }

    private fun pruneAmplitudeHistory(beforeFrame: Long) {
        synchronized(amplitudeLock) {
            while (!amplitudeSegments.isEmpty()) {
                val first = amplitudeSegments.peekFirst() ?: break
                val firstEnd = first.startFrame + first.frameCount
                if (firstEnd > beforeFrame) break
                amplitudeSegments.removeFirst()
            }
        }
    }

    private fun calculateAmplitudeAtPlaybackFrame(startFrame: Long): Double {
        val frameSize = bytesPerFrame()
        if (frameSize <= 0 || bytesPerSample != 2) return 0.0

        val windowFrames = ((sampleRate * amplitudeWindowMs) / 1000L).coerceAtLeast(1L)
        val endFrame = startFrame + windowFrames
        var sum = 0.0
        var count = 0

        synchronized(amplitudeLock) {
            val iterator = amplitudeSegments.iterator()
            while (iterator.hasNext()) {
                val segment = iterator.next()
                val segmentEnd = segment.startFrame + segment.frameCount

                if (segmentEnd <= startFrame - windowFrames) {
                    iterator.remove()
                    continue
                }
                if (segment.startFrame >= endFrame) {
                    break  // 后续 segment 更靠后，不可能在窗口内
                }
                if (segmentEnd <= startFrame) {
                    continue
                }

                val fromFrame = maxOf(startFrame, segment.startFrame)
                val toFrame = minOf(endFrame, segmentEnd)
                var byteOffset = ((fromFrame - segment.startFrame) * frameSize).toInt()
                val byteEnd = ((toFrame - segment.startFrame) * frameSize).toInt()

                while (byteOffset < byteEnd - 1) {
                    val sample = ((segment.bytes[byteOffset + 1].toInt() shl 8) or
                        (segment.bytes[byteOffset].toInt() and 0xFF)).toShort()
                    val normalized = sample.toDouble() / Short.MAX_VALUE
                    sum += normalized * normalized
                    count++
                    byteOffset += bytesPerSample
                }
            }
        }

        if (count == 0) return 0.0

        val rms = kotlin.math.sqrt(sum / count)
        return (rms * 8.0).coerceIn(0.0, 1.0)
    }

    private fun startAmplitudeReporter() {
        if (amplitudeJob?.isActive == true) return

        android.util.Log.d("PCMStreamPlayer", "👄 AmplitudeReporter 启动")
        amplitudeJob = scope.launch {
            var tickCount = 0
            while (isPlaying) {
                if (currentState == PlaybackState.PLAYING) {
                    val headFrame = playbackHeadFramePosition()
                    updatePlayedBytesFromAudioTrack()

                    val now = SystemClock.elapsedRealtime()
                    val amplitude = calculateAmplitudeAtPlaybackFrame(headFrame)
                    listener?.onAmplitudeUpdate(amplitude)

                    tickCount++
                    if (tickCount <= 3 || tickCount % 60 == 0) {
                        android.util.Log.d("PCMStreamPlayer",
                            "👄 tick#$tickCount headFrame=$headFrame amp=%.4f segments=${amplitudeSegments.size}".format(amplitude))
                    }

                    if (now - lastProgressReportTimeMs >= 1000) {
                        lastProgressReportTimeMs = now
                        val playedSeconds = getPlayedDuration()
                        val totalSeconds = getTotalDuration()
                        val progress = getProgress()

                        listener?.onProgressUpdate(playedSeconds, totalSeconds, progress)

                        android.util.Log.d("PCMStreamPlayer",
                            "⏱️ 播放进度: %.2f / %.2f 秒 (%.1f%%)".format(
                                playedSeconds, totalSeconds, progress * 100))
                    }

                    // 保留 200ms 历史足够 32ms 窗口使用
                    pruneAmplitudeHistory(headFrame - (sampleRate / 5L).coerceAtLeast(1L))
                } else {
                    listener?.onAmplitudeUpdate(0.0)
                }

                delay(amplitudeUpdateInterval)
            }

            listener?.onAmplitudeUpdate(0.0)
        }
    }

    /**
     * 开始播放
     * 启动消费协程，从 channel 中读取音频数据并播放
     * 幂等操作，重复调用不会启动多个协程
     */
    fun start() {
        if (isPlaying) {
            // 如果已经在播放，只是从暂停恢复
            if (currentState == PlaybackState.PAUSED) {
                currentState = PlaybackState.PLAYING
                audioTrack?.play()
                listener?.onPlaybackResumed()
                android.util.Log.d("PCMStreamPlayer", "▶️ 播放恢复")
                startAmplitudeReporter()
            }
            return
        }

        prepare()
        isPlaying = true
        currentState = PlaybackState.PLAYING
        audioTrack?.play()

        listener?.onPlaybackStart()
        android.util.Log.d("PCMStreamPlayer", "▶️ 播放开始")
        startAmplitudeReporter()

        playbackJob = scope.launch {
            try {
                while (isPlaying) {
                    // 使用带超时的接收，便于检测播放完成
                    val chunk = withTimeoutOrNull(100) {
                        bufferChannel.receive()
                    }

                    if (!isPlaying) break

                    if (chunk == null) {
                        // 超时未收到数据，检查是否播放完成
                        val now = SystemClock.elapsedRealtime()
                        if (bufferChannel.isEmpty && lastAppendTimeMs > 0L &&
                            (now - lastAppendTimeMs) > idleTimeoutMs) {
                            // 队列为空且超过超时时间，需要确认 AudioTrack 真正播放完成
                            if (isAudioTrackPlaybackFinished()) {
                                android.util.Log.d("PCMStreamPlayer", "✅ 播放完成（空闲超过 ${idleTimeoutMs}ms 且 AudioTrack 已完成）")
                                handlePlaybackCompleted()
                                break
                            } else {
                                android.util.Log.d("PCMStreamPlayer", "⏳ 队列空闲但 AudioTrack 仍在播放，等待硬件缓冲区清空...")
                            }
                        }
                        continue
                    }

                    if (chunk.isEmpty()) continue
                    writeFully(chunk)
                }
            } catch (e: CancellationException) {
                // 协程取消时安全退出
                android.util.Log.d("PCMStreamPlayer", "⏹️ 播放协程被取消")
            } catch (e: Throwable) {
                e.printStackTrace()
                listener?.onError(e)
            }
        }
    }

    /**
     * 检查 AudioTrack 是否真正播放完成
     *
     * @return true 表示播放完成，false 表示仍在播放
     */
    private fun isAudioTrackPlaybackFinished(): Boolean {
        val at = audioTrack ?: return true

        try {
            // 检查 AudioTrack 的播放状态
            val playState = at.playState
            if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                return true
            }

            // 获取播放头位置（单位：帧）
            val playbackHeadPosition = playbackHeadFramePosition()

            // 计算已写入的总帧数
            val totalFramesWritten = totalBytesWritten / bytesPerFrame()

            // ⚠️ 关键：必须等待播放头完全追上写入位置
            // playbackHeadPosition 是扬声器输出位置，落后于写入位置
            // 硬件缓冲区通常有 100-300ms 延迟，因此不能用 margin
            val finished = playbackHeadPosition >= totalFramesWritten

            if (!finished) {
                val remainingMs = ((totalFramesWritten - playbackHeadPosition).toDouble() / sampleRate * 1000).toInt()
                android.util.Log.d("PCMStreamPlayer",
                    "📍 播放头: $playbackHeadPosition / $totalFramesWritten 帧 (剩余: ${remainingMs}ms)")
            }

            return finished
        } catch (e: Exception) {
            android.util.Log.w("PCMStreamPlayer", "检查播放状态失败: ${e.message}")
            // 出错时保守处理，认为已完成
            return true
        }
    }

    /**
     * 处理播放完成
     */
    private fun handlePlaybackCompleted() {
        if (currentState == PlaybackState.PLAYING) {
            // ⚠️ 关键：额外等待确保硬件缓冲区完全清空，避免麦克风录入播放尾部
            // 硬件缓冲区通常有 100-300ms 延迟，这里等待 500ms 确保安全
            android.util.Log.d("PCMStreamPlayer", "⏳ 等待硬件缓冲区清空...")
            Thread.sleep(500)

            currentState = PlaybackState.COMPLETED
            isPlaying = false
            audioTrack?.pause()
            updatePlayedBytesFromAudioTrack()
            listener?.onAmplitudeUpdate(0.0)
            listener?.onPlaybackCompleted()
        }
    }

    /**
     * 追加 PCM 数据到播放队列
     * 非阻塞操作，调用方可以来自网络回调、文件读流等
     *
     * @param pcmData PCM 音频数据（16-bit little-endian）
     */
    fun append(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return

        // 如果是完成状态，收到新数据则重新开始
        if (currentState == PlaybackState.COMPLETED) {
            currentState = PlaybackState.IDLE
            isPlaying = false
            android.util.Log.d("PCMStreamPlayer", "🔄 收到新数据，重置完成状态")
        }

        if (!isPlaying) {
            // 自动启动播放
            start()
        }

        // 更新最后追加时间
        lastAppendTimeMs = SystemClock.elapsedRealtime()
        appendAmplitudeSegment(pcmData)

        // ===== 统计播放时间 =====
        totalBytesAppended += pcmData.size
        val chunkDuration = bytesToSeconds(pcmData.size.toLong())
        val totalDuration = getTotalDuration()

        android.util.Log.d("PCMStreamPlayer",
            "📊 追加音频: ${pcmData.size} 字节 (%.3f 秒) | 累计总时长: %.3f 秒"
                .format(chunkDuration, totalDuration))

        // 发送到 channel（尽量不阻塞调用者）
        bufferChannel.trySend(pcmData).getOrThrow()
    }

    /**
     * 完全写入 audioTrack
     * 循环写入直到所有数据都写完
     */
    private fun writeFully(bytes: ByteArray) {
        val at = audioTrack ?: return
        var offset = 0
        val size = bytes.size
        while (offset < size && isPlaying) {
            val written = at.write(bytes, offset, size - offset)
            if (written > 0) {
                offset += written

                // ===== 更新已写入字节数（用于播放完成检测） =====
                totalBytesWritten += written
                updatePlayedBytesFromAudioTrack()
            } else {
                // write 返回 0 或负值时短暂休眠避免死循环
                Thread.sleep(5)
            }
        }
    }

    /**
     * 暂停播放
     * 保留缓冲队列中的数据，可以通过 start() 继续播放
     */
    fun pause() {
        if (!isPlaying) return
        if (currentState != PlaybackState.PLAYING) return

        currentState = PlaybackState.PAUSED
        audioTrack?.pause()
        amplitudeJob?.cancel()
        amplitudeJob = null
        listener?.onAmplitudeUpdate(0.0)
        listener?.onPlaybackPaused()
        android.util.Log.d("PCMStreamPlayer", "⏸️ 播放暂停")
    }

    /**
     * 停止播放并清空队列
     * 清空所有待播放的数据，重置到 IDLE 状态
     */
    fun stopAndReset() {
        if (currentState == PlaybackState.IDLE) return

        isPlaying = false
        currentState = PlaybackState.IDLE
        lastAppendTimeMs = 0L

        // ===== 重置播放时间统计 =====
        val finalPlayedDuration = getPlayedDuration()
        val finalTotalDuration = getTotalDuration()
        android.util.Log.d("PCMStreamPlayer",
            "⏹️ 播放停止 - 最终统计: 播放 %.2f / %.2f 秒".format(
                finalPlayedDuration, finalTotalDuration))

        totalBytesAppended = 0L
        totalBytesPlayed = 0L
        totalBytesWritten = 0L
        lastProgressReportTimeMs = 0L
        clearAmplitudeHistory()

        amplitudeJob?.cancel()
        amplitudeJob = null
        playbackJob?.cancel()
        playbackJob = null

        audioTrack?.pause()
        try { audioTrack?.flush() } catch (_: Throwable) {}
        listener?.onAmplitudeUpdate(0.0)

        // 清空 channel（无阻塞清空）
        scope.launch {
            while (!bufferChannel.isEmpty) {
                bufferChannel.tryReceive().getOrNull()
            }
        }

        android.util.Log.d("PCMStreamPlayer", "⏹️ 播放已停止并重置")
    }

    /**
     * 释放所有资源
     * 调用后此实例不可再使用
     */
    fun release() {
        android.util.Log.d("PCMStreamPlayer", "🗑️ 释放播放器资源")

        isPlaying = false
        currentState = PlaybackState.IDLE
        lastAppendTimeMs = 0L

        // ===== 重置播放时间统计 =====
        totalBytesAppended = 0L
        totalBytesPlayed = 0L
        totalBytesWritten = 0L
        lastProgressReportTimeMs = 0L
        clearAmplitudeHistory()

        amplitudeJob?.cancel()
        amplitudeJob = null
        playbackJob?.cancel()
        playbackJob = null

        scope.cancel()

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        // 关闭 channel
        bufferChannel.close()

        listener = null
    }

    companion object {
        /**
         * 将 float 数组 [-1f,1f] 转换为 PCM16 字节数组（little-endian）
         *
         * @param floatData 浮点音频数据，范围 [-1.0, 1.0]
         * @return PCM 16-bit 字节数组
         */
        fun floatArrayToPcm16Bytes(floatData: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(floatData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floatData) {
                val v = (f.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                buf.putShort(v)
            }
            return buf.array()
        }
    }
}
