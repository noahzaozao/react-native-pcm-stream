import * as React from 'react';

import { PCMStreamViewProps } from './PCMStream.types';

export default function PCMStreamView(props: PCMStreamViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
