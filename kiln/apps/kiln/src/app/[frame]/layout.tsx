import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { FrameWizardHost } from './FrameWizardHost';
import { frameAt, segments, type FrameParams } from './frames';

export const dynamicParams = false;
export const generateStaticParams = segments;

export async function generateMetadata({ params }: FrameParams): Promise<Metadata> {
  return { title: frameAt((await params).frame).name };
}

/**
 * The wizard lives in the layout, so it stays mounted while the step routes below change; each
 * page only names its route. What a route shows comes from the session (wizard/routes.ts).
 */
export default async function Layout({ children, params }: FrameParams & { children: ReactNode }) {
  const f = frameAt((await params).frame);
  return (
    <>
      <FrameWizardHost id={f.id} base={f.route} />
      {children}
    </>
  );
}
