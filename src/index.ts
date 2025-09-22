// Reexport the native module. On web, it will be resolved to PCMStreamModule.web.ts
// and on native platforms to PCMStreamModule.ts
export { default } from './PCMStreamModule';
export { default as PCMStreamView } from './PCMStreamView';
export * from  './PCMStream.types';
