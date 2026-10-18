import LinkBridgePresentation
@testable import LinkBridgeIOS
import Testing

@Test func accentHexValuesMatchSpec() {
    #expect(Accent.action.lightHex == 0x3E62E8 && Accent.action.darkHex == 0x7C95FF)
    #expect(Accent.signal.lightHex == 0x35C98B && Accent.signal.darkHex == 0x4ADBA0)
    #expect(Accent.attention.lightHex == 0xF08A5D && Accent.attention.darkHex == 0xFF9F78)
}

@Test func labelsOnAccentFillsMeetContrastInBothModes() {
    for accent in Accent.allCases {
        #expect(contrastRatio(accent.onLightHex, accent.lightHex) >= 4.5, "\(accent) light")
        #expect(contrastRatio(accent.onDarkHex, accent.darkHex) >= 4.5, "\(accent) dark")
    }
}

@Test func contrastRatioMatchesKnownValues() {
    #expect(abs(contrastRatio(0xFFFFFF, 0x000000) - 21) < 0.01)
    #expect(abs(contrastRatio(0xFFFFFF, 0xFFFFFF) - 1) < 0.01)
}
