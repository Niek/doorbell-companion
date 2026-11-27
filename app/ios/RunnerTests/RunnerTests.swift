import XCTest
@testable import Runner

final class RunnerTests: XCTestCase {
  @MainActor
  func testSharedDoorbellAutomationIsBundled() throws {
    let automation = try HomeAssistant.sharedAutomationTemplate()
    XCTAssertEqual(automation["id"] as? String, "doorbell_companion_doorbell_call")
    XCTAssertEqual(automation["mode"] as? String, "parallel")
  }

  @MainActor
  func testDoorbellDiscoveryPrefersEnabledMediumCameraAndEventTrigger() {
    let devices: [JSON] = [["id": "door", "name_by_user": "Front Door"]]
    let entities: [JSON] = [
      ["device_id": "door", "entity_id": "camera.high", "unique_id": "front_0"],
      ["device_id": "door", "entity_id": "camera.medium", "unique_id": "front_1"],
      ["device_id": "door", "entity_id": "camera.medium_disabled", "disabled_by": "user"],
      ["device_id": "door", "entity_id": "binary_sensor.nvr_doorbell"],
      ["device_id": "door", "entity_id": "event.front_doorbell", "original_device_class": "doorbell",
       "platform": "unifiprotect"],
    ]
    let bells = HomeAssistant.discoverDoorbells(devices: devices, entities: entities, states: [], version: "2026.11.0")
    XCTAssertEqual(bells.count, 1)
    XCTAssertEqual(bells.first?.camera, "camera.medium")
    XCTAssertEqual(bells.first?.name, "Front Door")
    XCTAssertEqual(bells.first?.triggers, ["event.front_doorbell"])
    let trigger = bells.first?.automationTrigger(for: "event.front_doorbell", id: "front")
    XCTAssertEqual(trigger?["trigger"] as? String, "event.received")
    XCTAssertEqual(trigger?["id"] as? String, "front")
    XCTAssertEqual(trigger?["target"] as? [String: String], ["entity_id": "event.front_doorbell"])
    XCTAssertEqual(trigger?["options"] as? [String: [String]], ["event_type": ["ring"]])

    let older = HomeAssistant.discoverDoorbells(devices: devices, entities: entities, states: [], version: "2026.6.3")
    XCTAssertEqual(older.first?.triggers, ["event.front_doorbell"])
    XCTAssertEqual(older.first?.automationTrigger(for: "event.front_doorbell", id: "front")["trigger"] as? String, "state")

    let binaryOnly = HomeAssistant.discoverDoorbells(devices: devices, entities: Array(entities.dropLast()), states: [],
                                                    version: "2026.11.0")
    XCTAssertEqual(binaryOnly.first?.triggers, ["binary_sensor.nvr_doorbell"])
    XCTAssertEqual(binaryOnly.first?.automationTrigger(for: "binary_sensor.nvr_doorbell", id: "front")["to"] as? String, "on")
  }

  @MainActor
  func testDingAndChimeUseDoorbellClassFromState() {
    for event in ["ding", "chime"] {
      let ring = "event.front_\(event)"
      let entities: [JSON] = [
        ["device_id": "door", "entity_id": "camera.front"],
        ["device_id": "door", "entity_id": ring, "translation_key": event],
        ["device_id": "door", "entity_id": "event.front_motion", "translation_key": "motion"],
      ]
      let states: [JSON] = [
        ["entity_id": ring, "attributes": ["device_class": "doorbell", "event_types": ["ring"]]],
        ["entity_id": "event.front_motion", "attributes": ["device_class": "motion", "event_types": ["motion"]]],
      ]
      let bells = HomeAssistant.discoverDoorbells(devices: [], entities: entities, states: states,
                                                  version: "2026.11.3")
      XCTAssertEqual(bells.first?.camera, "camera.front")
      XCTAssertEqual(bells.first?.triggers, [ring])
      XCTAssertEqual(bells.first?.automationTrigger(for: ring, id: ring)["trigger"] as? String, "event.received")
    }
  }

  @MainActor
  func testReolinkVisitorRingsWithDefaultEntityID() {
    let visitor = "binary_sensor.front_visitor"
    let entities: [JSON] = [
      ["device_id": "door", "entity_id": "camera.front_fluent", "platform": "reolink"],
      ["device_id": "door", "entity_id": visitor, "platform": "reolink", "translation_key": "visitor"],
      ["device_id": "door", "entity_id": "binary_sensor.front_person", "platform": "reolink",
       "translation_key": "person"],
      ["device_id": "other", "entity_id": "camera.other"],
      ["device_id": "other", "entity_id": "binary_sensor.other_visitor", "platform": "template",
       "translation_key": "visitor"],
    ]
    let bells = HomeAssistant.discoverDoorbells(devices: [], entities: entities, states: [], version: "2026.11.3")
    XCTAssertEqual(bells.count, 1)
    XCTAssertEqual(bells.first?.camera, "camera.front_fluent")
    XCTAssertEqual(bells.first?.triggers, [visitor])
    XCTAssertEqual(bells.first?.automationTrigger(for: visitor, id: "front")["to"] as? String, "on")
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
