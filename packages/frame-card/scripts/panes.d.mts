// Types for scripts/panes.mjs, for the tests that import it.
import type { PaneInfo } from '../src/panes';

export const REPO: string;
export const OUT: string;
export const MODULE_ROOTS: readonly string[];
export function modules(repo?: string): string[];
export function paneFiles(repo?: string): string[];
export function readPanes(repo?: string): PaneInfo[];
export function render(panes: readonly PaneInfo[]): string;
