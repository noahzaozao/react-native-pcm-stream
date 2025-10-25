import { NativeModule, requireNativeModule } from 'expo';

import { PCMStreamModuleEvents, PCMStreamModuleSpec, PlaybackState, PlaybackStats } from './PCMStream.types';

declare class PCMStreamModule extends NativeModule<PCMStreamModuleEvents> implements PCMStreamModuleSpec {
  appendPCMBuffer: (data: Uint8Array, chunkBytes?: number) => void;
  hello(): string;
  
  // 播放相关
  initPlayer(sampleRate?: number): void;
  playPCMChunk(chunk: Uint8Array): void;
  stopPlayback(): void;
  
  // 播放状态和时间统计
  getPlaybackState(): PlaybackState;
  isPlaying(): boolean;
  getTotalDuration(): number;
  getPlayedDuration(): number;
  getRemainingDuration(): number;
  getProgress(): number;
  getPlaybackStats(): PlaybackStats;
  
  // 录音相关
  startRecording(sampleRate?: number, frameSize?: number, targetRate?: number): void;
  stopRecording(): void;
  // 麦克风暂停/恢复现在由播放器自动控制
}

export default requireNativeModule<PCMStreamModule>('PCMStream');
