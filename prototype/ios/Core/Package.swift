// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "RoadCore", platforms: [.macOS(.v13), .iOS(.v16)], products: [.library(name: "RoadCore", targets: ["RoadCore"]), .executable(name: "RoadCoreChecks", targets: ["RoadCoreChecks"])], targets: [.target(name: "RoadCore"), .target(name: "RoadScenarios", dependencies: ["RoadCore"], path: "Checks/Scenarios"), .executableTarget(name: "RoadCoreChecks", dependencies: ["RoadScenarios"], path: "Checks/Runner"), .testTarget(name: "RoadCoreTests", dependencies: ["RoadScenarios"])])
