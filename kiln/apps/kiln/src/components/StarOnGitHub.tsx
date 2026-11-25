// Done's last line on an install: a quiet ask to star Glassworks on GitHub, in the credit line's look
// (Credit.tsx). The same on every frame, since every frame runs the same Glassworks.
import s from './Credit.module.css';

export const REPO = 'https://github.com/GlassworksHQ/glassworks';

const Star = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="m12 3.5 2.6 5.3 5.9.9-4.25 4.1 1 5.8L12 16.9l-5.25 2.7 1-5.8L3.5 9.7l5.9-.9z" />
  </svg>
);

/** "Enjoying Glassworks? Star it on GitHub." */
export function StarOnGitHub() {
  return (
    <p className={`small ${s.credit}`}>
      <Star />
      <span>Enjoying Glassworks? <a className="link" href={REPO} target="_blank" rel="noopener">Star it on GitHub</a> to help others find it.</span>
    </p>
  );
}
