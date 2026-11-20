// A frame's words as the screens show them (DeviceFrame `words`): `{name}` is filled from `vars`, `` `x` `` is code,
// `**x**` bold. Lines split on "\n" are a list's items (lines()).
import { Fragment, type ReactNode } from 'react';

export function rich(text: string, vars: Record<string, ReactNode> = {}): ReactNode {
  const parts = text.split(/(`[^`]+`|\*\*[^*]+\*\*|\{[a-zA-Z]+\})/g).filter((x) => x !== '');
  return parts.map((x, i) => (
    x.startsWith('`') ? <code key={i}>{x.slice(1, -1)}</code>
      : x.startsWith('**') ? <b key={i}>{x.slice(2, -2)}</b>
      : /^\{[a-zA-Z]+\}$/.test(x) && x.slice(1, -1) in vars ? <Fragment key={i}>{vars[x.slice(1, -1)]}</Fragment>
      : x
  ));
}

/** A list's items, one per line. */
export const lines = (text: string) => text.split('\n');
