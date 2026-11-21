import {Page} from '../Chrome.jsx';
import {VStack} from '@astryxdesign/core/VStack';
import {Card} from '@astryxdesign/core/Card';
import {Heading} from '@astryxdesign/core/Heading';
import {Text} from '@astryxdesign/core/Text';
import {Link} from '@astryxdesign/core/Link';

function P({children, ...rest}) {
  return <Text as="p" display="block" {...rest}>{children}</Text>;
}

function Block({title, children}) {
  return (
    <VStack gap={3} as="section" style={{paddingBlock: 0}}>
      <Heading level={2}>{title}</Heading>
      {children}
    </VStack>
  );
}

export function Privacy() {
  return (
    <Page>
      <main id="main" className="wrap doc">
        <Card padding={8}>
          <VStack gap={8} as="article">
            <VStack gap={2}>
              <Heading level={1}>Privacy policy</Heading>
              <Text type="supporting">Last updated 21 November 2026</Text>
            </VStack>

            <P>LinkBridge is an app for Mac, Android and iPhone that sends files between your own devices over your local Wi-Fi network. This policy covers the apps and this website.</P>

            <Block title="The apps collect no data">
              <P>LinkBridge has no account system and no servers. The apps don't collect, store or share any personal data with us or anyone else. They contain no analytics, advertising or crash-reporting tools.</P>
            </Block>

            <Block title="What stays on your devices">
              <ul>
                <li><Text><strong>Files you send and receive</strong> go directly from one of your devices to another over your local network. They are never uploaded to the internet. Received files are saved in Downloads › LinkBridge on your device.</Text></li>
                <li><Text><strong>Pairing details</strong>: each device keeps a name, an ID and a shared secret for every device you pair with, so they can recognise each other and encrypt transfers. These stay on the device.</Text></li>
                <li><Text><strong>Transfer history</strong>: the apps keep a list of recent transfers on the device so you can see what was sent.</Text></li>
              </ul>
              <P>Uninstalling LinkBridge removes this information from that device, except for files you received.</P>
            </Block>

            <Block title="Permissions">
              <ul>
                <li><Text><strong>Local network</strong>: to find your other devices and send files to them.</Text></li>
                <li><Text><strong>Notifications</strong> (Android): to let you accept files while the app is in the background.</Text></li>
              </ul>
            </Block>

            <Block title="This website">
              <P>This site uses no cookies, analytics or trackers. The company hosting it may keep standard server logs, such as IP addresses and the pages requested, to run and secure the service.</P>
              <P>If you sign up as a tester, the signup form is run by Google Forms and your answers, including your email address, are handled under <Link href="https://policies.google.com/privacy">Google's privacy policy</Link>. We use that email only to add you to the LinkBridge test and to contact you about it, and delete it when testing ends or when you ask.</P>
            </Block>

            <Block title="Children">
              <P>LinkBridge doesn't collect data from anyone, including children.</P>
            </Block>

            <Block title="Changes">
              <P>If this policy changes, the new version will be posted on this page with a new date.</P>
            </Block>

            <Block title="Contact">
              {/* PLACEHOLDER: confirm the contact address before publishing. */}
              <P>Questions about privacy: <Link href="mailto:privacy@pratham.si">privacy@pratham.si</Link></P>
            </Block>
          </VStack>
        </Card>
      </main>
    </Page>
  );
}
