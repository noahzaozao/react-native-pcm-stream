import type { StyleProp, ViewStyle } from 'react-native';

export type OnLoadEventPayload = {
  url: string;
};

export type OnErrorEventPayload = {
  message?: string | null;
};

export type OnAudioFrameEventPayload = {
  pcm: Uint8Array;
  ts?: number; // 原生毫秒时间戳 (elapsedRealtime)
  seq?: number; // 帧序号
};

export type PCMStreamModuleEvents = {
  onError?: (params: OnErrorEventPayload) => void;
  onPlaybackStart?: () => void;
  onPlaybackStop?: () => void;
  onAudioFrame?: (params: OnAudioFrameEventPayload) => void;
};

export type PCMStreamViewProps = {
  url: string;
  onLoad?: (event: { nativeEvent: OnLoadEventPayload }) => void;
  style?: StyleProp<ViewStyle>;
};

// 已移除 View，用模块方法替代

export type PCMStreamModuleSpec = {
  hello: () => string;
  initPlayer: (sampleRate?: number) => void;
  playPCMChunk: (chunk: Uint8Array) => void;
  appendPCMBuffer: (data: Uint8Array, chunkBytes?: number) => void;
  stopPlayback: () => void;
  startRecording: (sampleRate?: number, frameSize?: number, targetRate?: number) => void;
  stopRecording: () => void;
  pauseRecordingForPlayback: () => void;
  resumeRecordingAfterPlayback: () => void;
};
