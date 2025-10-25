import type { StyleProp, ViewStyle } from 'react-native';

export type OnLoadEventPayload = {
  url: string;
};

export type OnErrorEventPayload = {
  message?: string | null;
  state?: string;
};

export type OnAudioFrameEventPayload = {
  pcm: Uint8Array;
  ts?: number; // 原生毫秒时间戳 (elapsedRealtime)
  seq?: number; // 帧序号
};

export type OnPlaybackStartEventPayload = {
  state: string; // "PLAYING"
};

export type OnPlaybackStopEventPayload = {
  state: string; // "COMPLETED"
  totalDuration: number; // 总时长（秒）
  playedDuration: number; // 已播放时长（秒）
};

export type OnPlaybackPausedEventPayload = {
  state: string; // "PAUSED"
};

export type OnPlaybackResumedEventPayload = {
  state: string; // "PLAYING"
};

export type OnPlaybackProgressEventPayload = {
  playedDuration: number;    // 已播放时长（秒）
  totalDuration: number;     // 总时长（秒）
  progress: number;          // 播放进度（0.0 ~ 1.0）
  remainingDuration: number; // 剩余时长（秒）
};

export type PCMStreamModuleEvents = {
  onError?: (params: OnErrorEventPayload) => void;
  onPlaybackStart?: (params: OnPlaybackStartEventPayload) => void;
  onPlaybackStop?: (params: OnPlaybackStopEventPayload) => void;
  onPlaybackPaused?: (params: OnPlaybackPausedEventPayload) => void;
  onPlaybackResumed?: (params: OnPlaybackResumedEventPayload) => void;
  onPlaybackProgress?: (params: OnPlaybackProgressEventPayload) => void;
  onAudioFrame?: (params: OnAudioFrameEventPayload) => void;
};

export type PCMStreamViewProps = {
  url: string;
  onLoad?: (event: { nativeEvent: OnLoadEventPayload }) => void;
  style?: StyleProp<ViewStyle>;
};

// 已移除 View，用模块方法替代

/**
 * 播放状态
 */
export type PlaybackState = 'IDLE' | 'PLAYING' | 'PAUSED' | 'COMPLETED';

/**
 * 播放统计信息
 */
export type PlaybackStats = {
  state: PlaybackState;
  isPlaying: boolean;
  totalDuration: number;     // 总时长（秒）
  playedDuration: number;    // 已播放时长（秒）
  remainingDuration: number; // 剩余时长（秒）
  progress: number;          // 播放进度（0.0 ~ 1.0）
};

export type PCMStreamModuleSpec = {
  hello: () => string;
  
  // 播放相关
  initPlayer: (sampleRate?: number) => void;
  playPCMChunk: (chunk: Uint8Array) => void;
  stopPlayback: () => void;
  
  // 播放状态和时间统计
  getPlaybackState: () => PlaybackState;
  isPlaying: () => boolean;
  getTotalDuration: () => number;
  getPlayedDuration: () => number;
  getRemainingDuration: () => number;
  getProgress: () => number;
  getPlaybackStats: () => PlaybackStats;
  
  // 录音相关
  startRecording: (sampleRate?: number, frameSize?: number, targetRate?: number) => void;
  stopRecording: () => void;
  // 注意：麦克风暂停/恢复现在由播放器自动控制
  // pauseRecordingForPlayback 和 resumeRecordingAfterPlayback 已移除
};
