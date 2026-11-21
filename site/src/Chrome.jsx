import {Theme} from '@astryxdesign/core/theme';
import {neutralTheme} from '@astryxdesign/theme-neutral/built';
import {Button} from '@astryxdesign/core/Button';
import {Link} from '@astryxdesign/core/Link';
import {Text} from '@astryxdesign/core/Text';

export const MAC_DMG = 'download/LinkBridge-0.2.0-macOS-arm64.dmg';
export const MAC_ZIP = 'download/LinkBridge-0.2.0-macOS-arm64.zip';
export const ANDROID_APK = 'download/LinkBridge-0.2.0-android.apk';
// PLACEHOLDER: replace with the Google Form link.
export const TESTER_FORM = 'https://forms.gle/REPLACE-ME';

function Logo() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <rect x="2" y="7" width="17" height="13" rx="3.5" fill="none" stroke="currentColor" strokeWidth="2.4" />
      <rect x="19" y="11" width="11" height="17" rx="3" fill="#3657d9" />
    </svg>
  );
}

// Shared page frame: Astryx Neutral theme in light mode, header, footer.
export function Page({home, children}) {
  const h = home ? '' : './';
  return (
    <Theme theme={neutralTheme} mode="light">
      <a className="skip" href="#main">Skip to content</a>
      <header className="site-head">
        <div className="wrap">
          <a className="brand" href="./" aria-label="LinkBridge home">
            <Logo />
            LinkBridge
          </a>
          <nav className="nav" aria-label="Main">
            {home && (
              <div className="nav-links">
                <Link href="#how" color="secondary">How it works</Link>
                <Link href="#privacy" color="secondary">Privacy</Link>
                <Link href="#faq" color="secondary">FAQ</Link>
              </div>
            )}
            <Button label="Download" variant="primary" size="sm" href={`${h}#install`} />
          </nav>
        </div>
      </header>
      {children}
      <footer className="site-foot">
        <div className="wrap">
          <Text type="supporting">© 2026 LinkBridge</Text>
          <nav aria-label="Footer">
            {home ? (
              <>
                <Link href="privacy.html" color="secondary">Privacy policy</Link>
                <Link href="#install" color="secondary">Download</Link>
                <Link href="#testers" color="secondary">Become a tester</Link>
              </>
            ) : (
              <>
                <Link href="./" color="secondary">Home</Link>
                <Link href="./#install" color="secondary">Download</Link>
              </>
            )}
          </nav>
        </div>
      </footer>
    </Theme>
  );
}
