import { NativeModule, requireNativeModule } from 'expo';

import { PCMStreamModuleEvents, PCMStreamModuleSpec } from './PCMStream.types';

declare class PCMStreamModule extends NativeModule<PCMStreamModuleEvents> implements PCMStreamModuleSpec {
  hello(): string;
  initPlayer(sampleRate?: number): void;
  playPCMChunk(chunk: Uint8Array): void;
  stopPlayback(): void;
}

export default requireNativeModule<PCMStreamModule>('PCMStream');
