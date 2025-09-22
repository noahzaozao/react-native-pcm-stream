import { registerWebModule, NativeModule } from 'expo';

import { PCMStreamModuleEvents } from './PCMStream.types';

class PCMStreamModule extends NativeModule<PCMStreamModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(PCMStreamModule, 'PCMStreamModule');
