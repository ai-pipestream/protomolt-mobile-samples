// swift-tools-version: 6.2

import PackageDescription

let package = Package(
    name: "CourtSearchKit",
    platforms: [.iOS(.v18)],
    products: [
        .library(name: "CourtSearchKit", targets: ["CourtSearchKit"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", exact: "1.38.1")
    ],
    targets: [
        // Built by scripts/build-engine.sh from the pinned protomolt-search revision.
        .binaryTarget(
            name: "ProtomoltSearch",
            path: "../Frameworks/ProtomoltSearch.xcframework"
        ),
        // Built by scripts/build-embedder.sh from embedder-ffi/.
        .binaryTarget(
            name: "CourtEmbedder",
            path: "../Frameworks/CourtEmbedder.xcframework"
        ),
        .target(
            name: "CourtSearchKit",
            dependencies: [
                "ProtomoltSearch",
                "CourtEmbedder",
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ],
            exclude: ["Generated/ENGINE_REV"],
            resources: [.copy("Resources/court.desc"), .copy("Resources/court_opinions_potion512.ndjson")]
        ),
        .testTarget(name: "CourtSearchKitTests", dependencies: ["CourtSearchKit"]),
    ]
)
