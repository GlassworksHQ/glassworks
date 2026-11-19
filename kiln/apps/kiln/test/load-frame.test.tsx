// @vitest-environment jsdom
// A frame's code loads once per page, from the registry. A load that failed (a chunk the network dropped) isn't kept:
// going back to the frame tries again.
import { act, cleanup, render } from '@testing-library/react';
import { Component, type ReactNode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';

const loader = vi.hoisted(() => vi.fn());
vi.mock('@glassworks/frames/kiln', () => ({ KILN: { 'a-frame': loader } }));
vi.mock('../src/wizard/FrameWizard', () => ({ FrameWizard: ({ frame }: { frame: { name: string } }) => <p>{frame.name}</p> }));
vi.spyOn(console, 'error').mockImplementation(() => {});
afterEach(() => cleanup());

class Boundary extends Component<{ children: ReactNode }, { failed?: string }> {
  override state: { failed?: string } = {};
  static getDerivedStateFromError(e: Error) { return { failed: e.message }; }
  override render() { return this.state.failed ? <p>failed: {this.state.failed}</p> : this.props.children; }
}

it('tries again after a load that failed', async () => {
  const { LoadFrame } = await import('../src/wizard/LoadFrame');
  loader.mockRejectedValueOnce(new Error('the chunk did not load')).mockResolvedValue({ frame: { name: 'A frame' }, sim: {} });
  const first = render(<Boundary><LoadFrame id="a-frame" base="/a-frame" /></Boundary>);
  await act(() => new Promise((r) => setTimeout(r, 0)));
  expect(first.container.textContent).toBe('failed: the chunk did not load');
  first.unmount();
  const again = render(<Boundary><LoadFrame id="a-frame" base="/a-frame" /></Boundary>);
  await act(() => new Promise((r) => setTimeout(r, 0)));
  expect(again.container.textContent).toBe('A frame');
  expect(loader).toHaveBeenCalledTimes(2);
});
