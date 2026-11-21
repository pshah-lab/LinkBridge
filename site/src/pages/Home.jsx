import {Page, MAC_DMG, MAC_ZIP, ANDROID_APK, TESTER_FORM} from '../Chrome.jsx';
import {VStack} from '@astryxdesign/core/VStack';
import {HStack} from '@astryxdesign/core/HStack';
import {Grid} from '@astryxdesign/core/Grid';
import {Card} from '@astryxdesign/core/Card';
import {Badge} from '@astryxdesign/core/Badge';
import {Button} from '@astryxdesign/core/Button';
import {Heading} from '@astryxdesign/core/Heading';
import {Text} from '@astryxdesign/core/Text';
import {Link} from '@astryxdesign/core/Link';
import {Collapsible, CollapsibleGroup} from '@astryxdesign/core/Collapsible';
import {Table, TableHeader, TableBody, TableRow, TableCell, TableHeaderCell} from '@astryxdesign/core/Table';
import {VisuallyHidden} from '@astryxdesign/core/VisuallyHidden';

const CODE = ['4', '1', '8', '2', '0', '7'];

function Code() {
  return (
    <div className="code" aria-hidden="true">
      {CODE.map((d, i) => <span key={i}>{d}</span>)}
    </div>
  );
}

function SectionHead({id, title, intro}) {
  return (
    <VStack gap={2} className="section-head">
      <Heading level={2} id={id}>{title}</Heading>
      {intro && <Text type="large" color="secondary" as="p">{intro}</Text>}
    </VStack>
  );
}

function P({children, color, type = 'body'}) {
  return <Text as="p" type={type} color={color} display="block">{children}</Text>;
}

const steps = [
  ['Install on both', 'Get LinkBridge on your Mac and your phone, and open it on each.'],
  ['Pair once', 'Start pairing on the phone. Check the six-digit code matches on both screens, then approve it on the Mac.'],
  ['Send', 'Pick up to 50 files on one device. Accept them on the other, and they land in Downloads › LinkBridge.'],
];

const matrix = [
  ['Find nearby devices', 'Yes', 'Yes', 'Yes'],
  ['Pair with a six-digit code', 'Approves pairing', 'Starts pairing with a Mac', 'Starts pairing with a Mac'],
  ['Send files', 'Yes', 'Yes', 'Yes'],
  ['Receive files', 'Yes', 'Yes, also in the background', 'Not yet'],
];

const facts = [
  ['Direct, over local Wi-Fi', 'Files go straight from one device to the other. Nothing is uploaded to the internet.'],
  ['No account', "There's nothing to sign up for or log in to."],
  ['Encrypted on the way', 'Transfers use TLS with a key made from the secret your devices agree on when you pair.'],
  ['Only paired devices', "A device you haven't paired with can't send you files, and you accept each transfer before it's saved."],
  ['No tracking', 'The apps contain no analytics, ads or crash reporters.'],
];

const faq = [
  ['Is LinkBridge free?', 'Yes.'],
  ['Does it use the internet?', 'No. Your devices need to be on the same Wi-Fi network, and files go directly between them.'],
  ['Where do received files go?', 'Into a LinkBridge folder inside Downloads, on both the Mac and Android.'],
  ['How many files can I send at once?', 'Up to 50 in one go.'],
  ['Which devices does it run on?', "Apple silicon Macs with macOS 26 or later (Intel Macs aren't supported), and Android phones with Android 12 or later. An iPhone app (iOS 26 or later) that can send to a Mac is built but not available to download yet."],
  ['Can an iPhone and an Android phone send to each other?', 'Not yet. Both pair with a Mac today.'],
  ['Why does my Mac warn me when I open it?', "The test build isn't notarized by Apple yet. Follow the Mac install steps above to open it. The App Store version won't show this warning."],
  ['When will it be in the App Store and Google Play?', <>After testing. The Google Play closed test comes first, which is where <Link href="#testers">testers</Link> help.</>],
];

function Cell({v}) {
  const tone = v.startsWith('Yes') ? 'success' : v === 'Not yet' ? 'neutral' : null;
  return <TableCell>{tone ? <Badge variant={tone} label={v} /> : <Text>{v}</Text>}</TableCell>;
}

export function Home() {
  return (
    <Page home>
      <main id="main">
        <div className="wrap hero">
          <VStack gap={6}>
            <VStack gap={4}>
              <Heading level={1} type="display-2">Send files between your Mac and phone over your own Wi-Fi.</Heading>
              <Text type="large" color="secondary" as="p" textWrap="pretty">
                LinkBridge moves files straight from one device to the other on your network. No account, no cloud, no cable.
              </Text>
            </VStack>
            {/* Release 0.2.0, verified on device 2026-11-21. */}
            <div className="ctas">
              <div className="cta">
                <Button label="Download for Mac" variant="primary" size="lg" href={MAC_DMG} />
                <Text type="supporting">Apple silicon, macOS 26 or later</Text>
              </div>
              <div className="cta">
                <Button label="Download for Android" variant="secondary" size="lg" href={ANDROID_APK} />
                <Text type="supporting">Android 12 or later</Text>
              </div>
            </div>
            <P type="supporting" color="secondary">
              Free test builds, version 0.2.0. Not in the App Store or Google Play yet, so <Link href="#install" type="inherit">a couple of install steps</Link> apply.
            </P>
          </VStack>

          {/* Illustration of pairing, drawn in HTML (not a screenshot). */}
          <figure className="pairing" aria-label="The same six-digit code shown on a Mac and on a phone">
            <Card elevation="med" padding={5}>
              <VStack gap={3}>
                <span className="who">Mac</span>
                <Heading level={3} accessibilityLevel={2}>Pair with Android phone?</Heading>
                <Text color="secondary">Check that the phone shows the same code.</Text>
                <Code />
                <div className="pills" aria-hidden="true"><span className="pill">Decline</span><span className="pill primary">Pair</span></div>
              </VStack>
            </Card>
            <Card elevation="med" padding={5}>
              <VStack gap={3}>
                <span className="who">Phone</span>
                <Heading level={3} accessibilityLevel={2}>Pairing with Mac</Heading>
                <Code />
                <div className="pills" aria-hidden="true"><span className="pill primary">Codes match</span></div>
              </VStack>
            </Card>
            <figcaption><Text type="supporting">Pair once: if the code matches on both screens, you're set.</Text></figcaption>
          </figure>
        </div>

        <section id="how" className="wrap" aria-labelledby="how-title">
          <SectionHead id="how-title" title="How it works" intro="Both devices need to be on the same Wi-Fi network." />
          <Grid columns={{minWidth: 240, repeat: 'fit'}} gap={4}>
            {steps.map(([t, d], i) => (
              <Card key={t} padding={5}>
                <VStack gap={3} as="article">
                  <span className="step-num" aria-hidden="true">{i + 1}</span>
                  <Heading level={3}><VisuallyHidden>Step {i + 1}: </VisuallyHidden>{t}</Heading>
                  <Text color="secondary">{d}</Text>
                </VStack>
              </Card>
            ))}
          </Grid>
          <div className="screens" aria-label="App screenshots" role="group" style={{marginTop: 48}}>
            <figure className="shot">
              <img src="img/android-send-light.jpg" width="454" height="1000" loading="lazy" alt="LinkBridge on Android: the Send screen with a PDF ready to send to a laptop." />
              <figcaption><Text type="label">Android</Text></figcaption>
            </figure>
            <figure className="shot">
              <img src="img/ios-light-send.jpg" width="687" height="1000" loading="lazy" alt="LinkBridge on iPhone: the Send screen with a drop area to choose a file for a paired Mac." />
              <figcaption><Text type="label">iPhone</Text></figcaption>
            </figure>
            {/* PLACEHOLDER: add a Mac app screenshot here once one is captured. */}
          </div>
        </section>

        <section className="wrap" aria-labelledby="today-title">
          <SectionHead id="today-title" title="What works today" />
          <div className="matrix">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHeaderCell scope="col"><VisuallyHidden>Feature</VisuallyHidden></TableHeaderCell>
                  <TableHeaderCell scope="col">Mac</TableHeaderCell>
                  <TableHeaderCell scope="col">Android</TableHeaderCell>
                  <TableHeaderCell scope="col">iPhone</TableHeaderCell>
                </TableRow>
              </TableHeader>
              <TableBody>
                {matrix.map(([f, ...v]) => (
                  <TableRow key={f}>
                    <TableHeaderCell scope="row">{f}</TableHeaderCell>
                    {v.map((x, i) => <Cell key={i} v={x} />)}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div style={{marginTop: 16}}>
            <P type="supporting" color="secondary">iPhone and Android can't pair with each other yet. The iPhone app isn't available to download yet.</P>
          </div>
        </section>

        <section id="privacy" className="wrap" aria-labelledby="privacy-title">
          <SectionHead id="privacy-title" title="Your files stay on your network" intro="LinkBridge has no servers. Files travel between your devices and nowhere else." />
          <Grid columns={{minWidth: 260, repeat: 'fit'}} gap={4}>
            {facts.map(([t, d]) => (
              <Card key={t} variant="muted" padding={5}>
                <VStack gap={2}><Heading level={3}>{t}</Heading><Text color="secondary">{d}</Text></VStack>
              </Card>
            ))}
            <Card variant="muted" padding={5}>
              <VStack gap={2}>
                <Heading level={3}>Read the policy</Heading>
                <Text color="secondary">The <Link href="privacy.html">privacy policy</Link> explains what the apps and this website do with data.</Text>
              </VStack>
            </Card>
          </Grid>
        </section>

        <section className="wrap" aria-labelledby="coming-title">
          <SectionHead id="coming-title" title="Coming" intro="Planned, not in the app yet. Both will use the pairing you already have." />
          <Grid columns={{minWidth: 260, repeat: 'fit'}} gap={4}>
            {[
              ['Screen mirroring', 'See your Android screen on your Mac first, then your Mac screen on your phone.'],
              ['No Wi-Fi needed', "Send files when there's no shared Wi-Fi network around."],
            ].map(([t, d]) => (
              <Card key={t} padding={5}>
                <VStack gap={3} as="article" hAlign="start">
                  <Badge variant="info" label="Coming" />
                  <Heading level={3}>{t}</Heading>
                  <Text color="secondary">{d}</Text>
                </VStack>
              </Card>
            ))}
          </Grid>
        </section>

        <section id="install" className="wrap install" aria-labelledby="install-title">
          <SectionHead id="install-title" title="Download and install" intro="These are test builds from outside the app stores, so your devices ask you to confirm before the first launch." />
          <Grid columns={{minWidth: 300, repeat: 'fit'}} gap={4} align="start">
            <Card padding={6}>
              <VStack gap={4} as="article" hAlign="start">
                <Heading level={3}>Mac</Heading>
                <Text color="secondary">Apple silicon Mac with macOS 26 or later. Intel Macs aren't supported.</Text>
                <ol>
                  <li>Download the DMG and open it, then drag LinkBridge into the Applications folder.</li>
                  <li>Open LinkBridge. macOS says it can't verify the app. Click <span className="ui">Done</span>.</li>
                  <li>Open <span className="ui">System Settings › Privacy &amp; Security</span>, scroll to Security, and click <span className="ui">Open Anyway</span>. The button shows for about an hour after you tried to open the app.</li>
                  <li>Enter your Mac password, then open LinkBridge again.</li>
                  <li>Allow LinkBridge to find devices on your local network when asked.</li>
                </ol>
                <P color="secondary">LinkBridge lives in the menu bar. Click its icon there to open it.</P>
                <P color="secondary">The Open Anyway step is needed because the test build isn't notarized by Apple. The Mac App Store version won't need it.</P>
                <Button label="Download for Mac" variant="primary" href={MAC_DMG} />
                <P type="supporting" color="secondary">Prefer a zip? <Link href={MAC_ZIP} type="inherit">Download the zip</Link> and move LinkBridge to Applications.</P>
              </VStack>
            </Card>
            <Card padding={6}>
              <VStack gap={4} as="article" hAlign="start">
                <Heading level={3}>Android</Heading>
                <Text color="secondary">Android 12 or later</Text>
                <P><strong>Had an earlier test build?</strong> Uninstall it first. This version is signed with a new key, so it can't install over the old one, and you'll need to pair again.</P>
                <ol>
                  <li>Download the APK on your phone.</li>
                  <li>Open the file. If Android asks, allow your browser or Files app to <span className="ui">install unknown apps</span>, then go back.</li>
                  <li>Tap <span className="ui">Install</span>, then open LinkBridge.</li>
                  <li>Allow notifications, so you can accept files while the app is in the background.</li>
                </ol>
                <P color="secondary">You can turn the unknown-apps setting off again after installing. The Google Play version won't need it.</P>
                <Button label="Download for Android" variant="primary" href={ANDROID_APK} />
              </VStack>
            </Card>
          </Grid>
        </section>

        <section id="testers" className="wrap" aria-labelledby="testers-title">
          <Card variant="muted" padding={8}>
            <div className="testers">
              <VStack gap={3}>
                <Heading level={2} id="testers-title">Help bring LinkBridge to Google Play</Heading>
                <Text color="secondary" as="p">Before a new app can launch, Google Play needs at least 12 testers to stay in a closed test for 14 days in a row. If you have an Android phone, join with the email of your Google account.</Text>
              </VStack>
              <Button label="Join the Android test" variant="primary" size="lg" href={TESTER_FORM} rel="noopener" />
            </div>
          </Card>
        </section>

        <section id="faq" className="wrap" aria-labelledby="faq-title">
          <SectionHead id="faq-title" title="Questions" />
          <div className="faq">
            <CollapsibleGroup>
              {faq.map(([q, a]) => (
                <Collapsible key={q} value={q} trigger={<Text type="label" size="base">{q}</Text>}>
                  <div style={{paddingBlockEnd: 12}}><Text color="secondary">{a}</Text></div>
                </Collapsible>
              ))}
            </CollapsibleGroup>
          </div>
        </section>
      </main>
    </Page>
  );
}
