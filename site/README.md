# LinkBridge website

Built with Meta's Astryx design system (`@astryxdesign/core`, Neutral theme, light mode only), React 19 and Vite.
Both pages are pre-rendered to static HTML at build time (`scripts/prerender.mjs`), so they work without JavaScript.

- Develop: `npm install`, then `npm run dev`
- Build: `npm run build` (output in `dist/`), preview with `npm run preview`
- Deploy: `vercel deploy --prod --yes` from this folder (Vercel runs the build, see `vercel.json`)

Downloads go in `public/download/` (git-ignored, but deployed). The pages link
`download/LinkBridge-0.2.0-macOS-arm64.dmg`, `...-arm64.zip` and `LinkBridge-0.2.0-android.apk`.

Placeholders to replace before going live (search for `PLACEHOLDER`):
- `https://forms.gle/REPLACE-ME`: the Google Form for Android testers (`src/Chrome.jsx`).
- `privacy@pratham.si`: confirm the privacy contact address (`src/pages/Privacy.jsx`).
- A Mac app screenshot (none exists yet).

Rules: no store badges until the app is in the stores, no GitHub link, no "end-to-end" claim, never "AirDrop". Mark planned features "Coming".
