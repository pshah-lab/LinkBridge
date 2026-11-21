# LinkBridge landing page — short design

**Why:** give early testers a place to download the test builds, learn the install steps,
and sign up for the Google Play closed test (12 testers for 14 days). Later it carries the
store links and the privacy policy Play requires.

**Approved by the user (2026-11-21):** section outline (hero, how it works, what works today,
privacy, coming, install steps, tester signup, FAQ, footer, privacy page). Hosted on the
user's domain `pratham.si`; no GitHub link anywhere; no "end-to-end encryption" claim;
no store badges until the app is in the stores; never say "AirDrop".

**Approaches considered:** plain static HTML/CSS (picked: fast, no build, any host);
Astro/11ty (nicer templating, adds a build for two pages); a Vercel template (heavier, generic).

**Design (builder's calls):**
- Static files in `site/`; relative links so it works at the root of a subdomain
  (proposed `linkbridge.pratham.si`) or anywhere else.
- Look: Liquid Glass to match the Apple apps — translucent panels with backdrop blur over a
  soft cool backdrop, system font (SF Pro on Apple), SF Rounded digits. Blue accent taken from
  the app's send button. Light and dark via `prefers-color-scheme`.
- Memorable element: the hero shows the same six-digit code on a Mac pane and a phone pane.
- Downloads point at placeholder paths `download/LinkBridge-<ver>-mac.zip` and `-android.apk`
  until the release-test session confirms the builds.
- Tester signup: a link to a Google Form (placeholder URL) collecting the Google account email.
- Real iPhone and Android screenshots from `docs/screenshots`; no Mac screenshot exists yet,
  so the Mac pane is a drawn mock, labelled as such in a comment.
