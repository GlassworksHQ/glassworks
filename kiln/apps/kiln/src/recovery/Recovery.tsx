'use client';
// A frame's recovery steps: the player's drawing, and the step chips under it that follow it.
import { useEffect, useRef, useState } from 'react';
import { LABELS, mount, type RecoveryData } from './player';

export function Recovery({ data }: { data: RecoveryData }) {
  const svg = useRef<SVGSVGElement>(null);
  const [beat, setBeat] = useState(-1);
  useEffect(() => {
    const p = mount(svg.current!, data, setBeat);
    p.play();
    return () => p.stop();
  }, [data]);
  const label = (st: RecoveryData['steps'][number]) => data.labels?.[st] ?? LABELS[st];
  return (
    <>
      <div className="recview">
        <svg ref={svg} role="img" aria-label={`How to put the ${data.name} in recovery mode: ${data.steps.map(label).join(', ')}. ${data.button?.what ?? ''}`} />
      </div>
      <ol className="beats" aria-hidden="true">
        {data.steps.map((st, i) => <li key={st} className={i === beat ? 'on' : beat > i ? 'past' : ''}>{i + 1} · {label(st)}</li>)}
      </ol>
    </>
  );
}
