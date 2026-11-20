// /android's step routes, from its frame's data (routesOf, as every frame's): its install's steps and the
// standard flow's. Each renders the same page; what it shows comes from the session.
import type { Metadata } from 'next';
import { INSTALLERS } from '@glassworks/frames/catalog';
import { routesOf } from '../../[frame]/frames';
import { AndroidPage } from '../AndroidPage';

type StepParams = { params: Promise<{ step: string[] }> };

/** The frame whose installer.json route this page is: its data in the catalog. */
const ID = Object.keys(INSTALLERS).find((id) => INSTALLERS[id]!.route === '/android')!;

export const dynamicParams = false;

export function generateStaticParams() {
  return Object.keys(routesOf(ID)).map((s) => ({ step: s.split('/') }));
}

export async function generateMetadata({ params }: StepParams): Promise<Metadata> {
  return { title: `${routesOf(ID)[(await params).step.join('/')]} · Android device` };
}

export default function Page() {
  return <AndroidPage />;
}
