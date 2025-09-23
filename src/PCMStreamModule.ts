import { NativeModule, requireNativeModule } from 'expo';

import { PCMStreamModuleEvents, PCMStreamModuleSpec } from './PCMStream.types';

declare class PCMStreamModule extends NativeModule<PCMStreamModuleEvents> implements PCMStreamModuleSpec {
  hello(): string;
  initPlayer(sampleRate?: number): void;
  playPCMChunk(chunk: Uint8Array): void;
  appendPCMBuffer(data: Uint8Array, chunkBytes?: number): void;
  stopPlayback(): void;
  startRecording(sampleRate?: number, frameSize?: number, targetRate?: number): void;
  stopRecording(): void;
  pauseRecordingForPlayback(): void;
  resumeRecordingAfterPlayback(): void;
}

export default requireNativeModule<PCMStreamModule>('PCMStream');
