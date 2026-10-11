// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "LinkBridge",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "LinkBridge", targets: ["LinkBridge"]),
        .executable(name: "linkbridge-cli", targets: ["linkbridge-cli"])
    ],
    targets: [
        // Protocol, crypto, pairing, discovery and transfer. No UI.
        // Screen sharing will live in a sibling target that depends on this one.
        .target(
            name: "LinkBridgeCore",
            path: "Sources/LinkBridgeCore"
        ),
        // The Mac app that ships in the App Store bundle.
        .executableTarget(
            name: "LinkBridge",
            dependencies: ["LinkBridgeCore"],
            path: "Sources/LinkBridge"
        ),
        // Developer tool: headless server and command-line sends. Not shipped.
        .executableTarget(
            name: "linkbridge-cli",
            dependencies: ["LinkBridgeCore"],
            path: "Sources/linkbridge-cli"
        ),
        // Test runner. XCTest/Swift Testing need full Xcode; this runs with the
        // Command Line Tools: `swift run LinkBridgeCoreChecks`.
        .executableTarget(
            name: "LinkBridgeCoreChecks",
            dependencies: ["LinkBridgeCore"],
            path: "Sources/LinkBridgeCoreChecks"
        )
    ]
)
