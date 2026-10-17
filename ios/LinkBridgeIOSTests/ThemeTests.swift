@testable import LinkBridgeIOS
import Testing

@Test func accentHexValuesMatchSpec() {
    #expect(Accent.action.lightHex == 0x3E62E8 && Accent.action.darkHex == 0x7C95FF)
    #expect(Accent.signal.lightHex == 0x35C98B && Accent.signal.darkHex == 0x4ADBA0)
    #expect(Accent.attention.lightHex == 0xF08A5D && Accent.attention.darkHex == 0xFF9F78)
}
