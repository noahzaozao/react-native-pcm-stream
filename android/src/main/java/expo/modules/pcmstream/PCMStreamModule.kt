package expo.modules.pcmstream

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.LinkedBlockingQueue

class PCMStreamModule : Module() {

    private var audioTrack: AudioTrack? = null
    private var isPlaying: Boolean = false
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackThread: Thread? = null

    private fun initAudioTrack(sampleRate: Int = 16000) {
        // 幂等：若已在播放，先停止
        if (isPlaying || audioTrack != null) {
            stopPlaybackInternal()
        }

        val sr = if (sampleRate > 0) sampleRate else 16000
        val bufferSize = AudioTrack.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
        // 清空历史队列，重新开始
        playQueue.clear()

        audioTrack?.play()
        isPlaying = true

        // 事件：开始播放
        try { sendEvent("onPlaybackStart", emptyMap<String, Any>()) } catch (_: Throwable) {}

        playbackThread = Thread {
            try {
                while (isPlaying) {
                    val chunk = playQueue.take() // 可被 interrupt 打断
                    if (!isPlaying) break
                    if (chunk.isEmpty()) continue

                    val track = audioTrack ?: continue
                    var offset = 0
                    while (offset < chunk.size && isPlaying) {
                        val written = try {
                            track.write(chunk, offset, chunk.size - offset)
                        } catch (t: Throwable) {
                            try { sendEvent("onError", mapOf("message" to (t.message ?: "write failed"))) } catch (_: Throwable) {}
                            break
                        }
                        if (written < 0) {
                            try { sendEvent("onError", mapOf("message" to "AudioTrack write error: $written")) } catch (_: Throwable) {}
                            break
                        }
                        if (written == 0) {
                            try { Thread.sleep(1) } catch (_: InterruptedException) { }
                        }
                        offset += written
                    }
                }
            } catch (_: InterruptedException) {
                // 线程被中断，正常退出
            } finally {
                // 清理交由 stopPlaybackInternal
            }
        }.also { it.start() }
    }

    private fun appendPCMData(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        if (chunk.size % 2 != 0) {
            try { sendEvent("onError", mapOf("message" to "PCM chunk is not 16-bit aligned")) } catch (_: Throwable) {}
            return
        }
        playQueue.offer(chunk)
    }

    private fun stopPlaybackInternal() {
        if (!isPlaying && audioTrack == null && playbackThread == null) return
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread = null
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        playQueue.clear()
        try { sendEvent("onPlaybackStop", emptyMap<String, Any>()) } catch (_: Throwable) {}
    }

    override fun definition() = ModuleDefinition {

        Name("PCMStream")

        // 事件可选，用于播放状态或错误回调
        Events("onError", "onPlaybackStart", "onPlaybackStop")

        // 无 View 版本：播放 PCM 块
        Function("playPCMChunk") { chunk: ByteArray ->
            appContext.mainQueue.run {
                if (!isPlaying || audioTrack == null) {
                    try { sendEvent("onError", mapOf("message" to "Player not initialized")) } catch (_: Throwable) {}
                    return@run
                }
                appendPCMData(chunk)
            }
        }

        // 无 View 版本：停止播放
        Function("stopPlayback") {
            appContext.mainQueue.run {
                stopPlaybackInternal()
            }
        }

        // 无 View 版本：初始化播放器（可自定义采样率）
        Function("initPlayer") { sampleRate: Int? ->
            appContext.mainQueue.run {
                initAudioTrack(sampleRate ?: 16000)
            }
        }

        // 示例简单方法
        Function("hello") { "Hello from PCMStreamModule!" }

        // 模块销毁时清理
        OnDestroy {
            stopPlaybackInternal()
        }
    }
}
