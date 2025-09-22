import { registerWebModule, NativeModule } from 'expo';

import { PCMStreamModuleEvents, PCMStreamModuleSpec } from './PCMStream.types';

class PCMStreamModule extends NativeModule<PCMStreamModuleEvents> implements PCMStreamModuleSpec {
  hello() {
    return 'Hello from PCMStream (web mock)';
  }
  initPlayer(_sampleRate?: number) {}
  playPCMChunk(_chunk: Uint8Array) {}
  stopPlayback() {}
}

export default registerWebModule(PCMStreamModule, 'PCMStream');
