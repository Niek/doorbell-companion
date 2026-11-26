// swift-tools-version: 5.9
import PackageDescription

let package = Package(
  name: "WebRTC",
  platforms: [.iOS(.v15)],
  products: [.library(name: "WebRTC", targets: ["WebRTC"])],
  targets: [
    .binaryTarget(
      name: "WebRTC",
      url: "https://github.com/webrtc-sdk/Specs/releases/download/150.7871.04/WebRTC.xcframework.zip",
      checksum: "9532fe3ce47507c304f37a4355b907be1e89967971d614a04603005a546249be"
    ),
  ]
)
