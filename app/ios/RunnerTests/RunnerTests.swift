import XCTest
@testable import Runner

final class RunnerTests: XCTestCase {
  @MainActor
  func testDoorbellDiscoveryPrefersEnabledMediumCameraAndBinaryTrigger() {
    let devices: [JSON] = [["id": "door", "name_by_user": "Front Door"]]
    let entities: [JSON] = [
      ["device_id": "door", "entity_id": "camera.high", "unique_id": "front_0"],
      ["device_id": "door", "entity_id": "camera.medium", "unique_id": "front_1"],
      ["device_id": "door", "entity_id": "camera.medium_disabled", "disabled_by": "user"],
      ["device_id": "door", "entity_id": "binary_sensor.nvr_doorbell"],
      ["device_id": "door", "entity_id": "event.front_doorbell", "original_device_class": "doorbell"],
    ]
    let bells = HomeAssistant.discoverDoorbells(devices: devices, entities: entities, states: [])
    XCTAssertEqual(bells.count, 1)
    XCTAssertEqual(bells.first?.camera, "camera.medium")
    XCTAssertEqual(bells.first?.name, "Front Door")
    XCTAssertEqual(bells.first?.triggers, ["binary_sensor.nvr_doorbell"])
  }

  @MainActor
  func testEventDoorbellWithNoBinaryTrigger() {
    let entities: [JSON] = [
      ["device_id": "door", "entity_id": "camera.front"],
      ["device_id": "door", "entity_id": "event.front", "original_device_class": "doorbell"],
    ]
    let states: [JSON] = [
      ["entity_id": "camera.front", "attributes": ["friendly_name": "Front Door"]],
    ]
    let bells = HomeAssistant.discoverDoorbells(devices: [], entities: entities, states: states)
    XCTAssertEqual(bells.first?.name, "Front Door")
    XCTAssertEqual(bells.first?.triggers, ["event.front"])
  }
}
