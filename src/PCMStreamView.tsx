import { requireNativeView } from 'expo';
import * as React from 'react';

import { PCMStreamViewProps } from './PCMStream.types';

const NativeView: React.ComponentType<PCMStreamViewProps> =
  requireNativeView('PCMStream');

export default function PCMStreamView(props: PCMStreamViewProps) {
  return <NativeView {...props} />;
}
