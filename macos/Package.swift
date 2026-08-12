// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "LinkBridgeMac",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "LinkBridgeMac", targets: ["LinkBridgeMac"])
    ],
    targets: [
        .executableTarget(
            name: "LinkBridgeMac",
            path: "Sources/LinkBridgeMac"
        )
    ]
)

