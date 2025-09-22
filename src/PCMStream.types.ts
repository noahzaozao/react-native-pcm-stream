import type { StyleProp, ViewStyle } from 'react-native';

export type OnLoadEventPayload = {
  url: string;
};

export type OnErrorEventPayload = {
  message?: string | null;
};

export type PCMStreamModuleEvents = {
  onError?: (params: OnErrorEventPayload) => void;
  onPlaybackStart?: () => void;
  onPlaybackStop?: () => void;
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
  stopPlayback: () => void;
};
