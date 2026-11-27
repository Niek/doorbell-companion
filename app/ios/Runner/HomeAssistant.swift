import Foundation
import Security

typealias JSON = [String: Any]

struct DoorbellCompanionError: LocalizedError {
  var errorDescription: String?
  init(_ message: String) { errorDescription = message }
}

struct HAAuthRejectedError: LocalizedError {
  var errorDescription: String? { "Home Assistant rejected the session" }
}

struct HARefreshRejectedError: LocalizedError {
  var errorDescription: String? { "Sign in to Home Assistant again" }
}

struct HARefreshFailedError: LocalizedError {
  let retryable: Bool
  var errorDescription: String? { "Could not refresh Home Assistant sign-in" }
}

struct HAWebSocketUnavailableError: LocalizedError {
  var errorDescription: String? { "Home Assistant WebSocket is unreachable" }
}

func isHomeAssistantTransportError(_ failure: Error) -> Bool {
  let error = failure as NSError
  if error.domain == NSPOSIXErrorDomain {
    return [ECONNABORTED, ECONNRESET, ENOTCONN, EPIPE, ETIMEDOUT, ENETDOWN, ENETUNREACH, EHOSTUNREACH]
      .contains(Int32(error.code))
  }
  guard error.domain == NSURLErrorDomain else { return false }
  return [URLError.timedOut, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost,
          .dnsLookupFailed, .notConnectedToInternet, .resourceUnavailable, .dataNotAllowed]
    .contains(URLError.Code(rawValue: error.code))
}

struct HAHTTPError: LocalizedError {
  let status: Int
  let path: String
  var errorDescription: String? { "Home Assistant returned HTTP \(status) for \(path)" }
}

struct Doorbell: Codable {
  let camera: String
  let name: String
  let triggers: [String]
  var nativeRingEvents: [String]?
  var talkback = false
  var enabled = true
  /// Data channel label Home Assistant asks for in the WebRTC offer (Nest WebRTC cameras need one).
  var dataChannel: String?

  var flutterValue: JSON { ["cameraEntityId": camera, "name": name, "enabled": enabled] }

  func automationTrigger(for entity: String, id: String) -> JSON {
    if nativeRingEvents?.contains(entity) == true {
      return ["trigger": "event.received", "id": id,
              "target": ["entity_id": entity], "options": ["event_type": ["ring"]]]
    }
    var trigger: JSON = ["trigger": "state", "entity_id": entity, "id": id]
    if entity.hasPrefix("binary_sensor.") {
      trigger["to"] = "on"
    } else {
      trigger["not_from"] = ["unknown", "unavailable"]
      trigger["not_to"] = ["unknown", "unavailable"]
    }
    return trigger
  }
}

struct HACredentials: Codable {
  let url: URL
  let clientID: String
  let refreshToken: String
  var accessToken: String
  var expiration: Date
  var pendingRevocation: URL?
}

@MainActor
final class HomeAssistant {
  static let callback = "doorbell-companion://auth-callback"
  static let relayURL = URL(string: Bundle.main.object(forInfoDictionaryKey: "DoorbellCompanionRelayURL") as? String ?? "")!
  static var clientID: String { relayURL.appendingPathComponent("ios").absoluteString }

  private let defaults = UserDefaults.standard
  private let session: URLSession = {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 8
    configuration.timeoutIntervalForResource = 15
    return URLSession(configuration: configuration)
  }()
  private let webSockets = URLSession(configuration: .ephemeral)
  private var refreshTask: Task<String, Error>?
  private var credentials: HACredentials?
  private(set) var doorbells: [Doorbell]
  private(set) var localURL: URL?
  private(set) var externalURL: URL?
  private var deviceRegistration: String?
  private var registeredPushToken: String?
  private var registrationServer: URL?
  private let deviceID: String

  var url: URL? { credentials?.url }
  var pendingDisconnect: Bool { credentials?.pendingRevocation != nil }
  var endpoints: [URL] {
    var urls = [localURL, externalURL, url].compactMap { $0 }
    var seen = Set<String>()
    urls.removeAll { !seen.insert($0.absoluteString.lowercased()).inserted }
    return urls
  }
  var name: String { "Doorbell Companion iOS \(deviceID)" }
  private var notifyService: String { "mobile_app_doorbell_companion_ios_\(deviceID)" }

  init() {
    credentials = Self.loadCredentials()
    deviceID = defaults.string(forKey: "device_id") ?? String(
      UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12)
    ).lowercased()
    defaults.set(deviceID, forKey: "device_id")
    doorbells = defaults.data(forKey: "doorbells").flatMap {
      try? JSONDecoder().decode([Doorbell].self, from: $0)
    } ?? []
    localURL = defaults.url(forKey: "local_url")
    externalURL = defaults.url(forKey: "external_url")
    deviceRegistration = defaults.string(forKey: "registration")
    registeredPushToken = defaults.string(forKey: "registered_push_token")
    registrationServer = defaults.url(forKey: "registration_server")
  }

  nonisolated static func serverURL(_ raw: String) throws -> URL {
    let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    var components = URLComponents(string: value.contains("://") ? value : "https://\(value)")
    guard let scheme = components?.scheme?.lowercased(), ["http", "https"].contains(scheme),
          let host = components?.host, !host.isEmpty,
          components?.user == nil, components?.password == nil,
          components?.query == nil, components?.fragment == nil else {
      throw DoorbellCompanionError("Enter a valid Home Assistant URL")
    }
    while components?.path.hasSuffix("/") == true { components?.path.removeLast() }
    guard let url = components?.url else { throw DoorbellCompanionError("Invalid Home Assistant URL") }
    return url
  }

  func saveLocalURL(_ candidate: URL?) {
    guard let candidate, candidate.absoluteString.lowercased() != url?.absoluteString.lowercased() else { return }
    localURL = candidate
    defaults.set(candidate, forKey: "local_url")
  }

  func cachedICEServers() -> [JSON]? {
    guard let data = defaults.data(forKey: "ice_servers"),
          let servers = (try? JSONSerialization.jsonObject(with: data)) as? [JSON],
          !servers.isEmpty else { return nil }
    let raw = String(decoding: data, as: UTF8.self)
    let hasTurn = raw.contains("\"credential\"") || raw.contains("turn:") || raw.contains("turns:")
    if hasTurn {
      let savedAt = defaults.double(forKey: "ice_servers_time")
      if Date().timeIntervalSince1970 - savedAt >= 6 * 60 * 60 { return nil }
    }
    return servers
  }

  /// Remembers a camera's data channel from a call's client config so the next prebuilt offer includes it.
  func saveDataChannel(_ label: String?, camera: String) {
    var changed = false
    for index in doorbells.indices where doorbells[index].camera == camera && doorbells[index].dataChannel != label {
      doorbells[index].dataChannel = label
      changed = true
    }
    if changed { saveDoorbells() }
  }

  func saveICEServers(_ servers: [JSON]) {
    guard !servers.isEmpty,
          let data = try? JSONSerialization.data(withJSONObject: servers) else { return }
    defaults.set(data, forKey: "ice_servers")
    defaults.set(Date().timeIntervalSince1970, forKey: "ice_servers_time")
  }

  func signIn(at url: URL, with code: String, verifier: String) async throws {
    let response = try await tokenResponse(url: url, fields: [
      "grant_type": "authorization_code", "code": code, "client_id": Self.clientID,
      "code_verifier": verifier,
    ])
    guard let access = response["access_token"] as? String,
          let refresh = response["refresh_token"] as? String else {
      throw DoorbellCompanionError("Home Assistant did not return sign-in credentials")
    }
    let value = HACredentials(
      url: url, clientID: Self.clientID, refreshToken: refresh, accessToken: access,
      expiration: Date().addingTimeInterval(response["expires_in"] as? Double ?? 1800)
    )
    try Self.saveCredentials(value)
    if credentials?.url != url {
      localURL = nil
      externalURL = nil
      defaults.removeObject(forKey: "local_url")
      defaults.removeObject(forKey: "external_url")
      defaults.removeObject(forKey: "ice_servers")
      defaults.removeObject(forKey: "ice_servers_time")
    }
    credentials = value
    if registrationServer != url {
      deviceRegistration = nil
      registeredPushToken = nil
      defaults.removeObject(forKey: "registration")
      defaults.removeObject(forKey: "registered_push_token")
      defaults.removeObject(forKey: "synced_doorbell_subscription")
    }
  }

  func accessToken(forceRefresh: Bool = false) async throws -> String {
    if let refreshTask { return try await refreshTask.value }
    guard let credentials else { throw DoorbellCompanionError("Sign in to Home Assistant first") }
    if !forceRefresh && credentials.expiration.timeIntervalSinceNow > 120 {
      return credentials.accessToken
    }
    let candidates = endpoints
    let fields = [
      "grant_type": "refresh_token", "refresh_token": credentials.refreshToken,
      "client_id": credentials.clientID,
    ]
    let task = Task {
      let refreshed = try await withThrowingTaskGroup(of: Result<HACredentials, Error>.self) { group in
        for endpoint in candidates {
          group.addTask { @MainActor in
            do {
              let response = try await self.tokenResponse(url: endpoint, fields: fields)
              guard let access = response["access_token"] as? String, !access.isEmpty else {
                throw DoorbellCompanionError("Home Assistant did not return an access token")
              }
              var updated = credentials
              updated.accessToken = access
              updated.expiration = Date().addingTimeInterval(response["expires_in"] as? Double ?? 1800)
              return .success(updated)
            } catch {
              return .failure(error)
            }
          }
        }
        var rejected = false
        var onlyTransportFailures = !candidates.isEmpty
        for try await result in group {
          switch result {
          case .success(let updated):
            group.cancelAll()
            return updated
          case .failure(let error):
            if error is HARefreshRejectedError { rejected = true }
            if !isHomeAssistantTransportError(error) { onlyTransportFailures = false }
          }
        }
        try Task.checkCancellation()
        if rejected { throw HARefreshRejectedError() }
        throw HARefreshFailedError(retryable: onlyTransportFailures)
      }
      try Task.checkCancellation()
      guard self.credentials?.refreshToken == credentials.refreshToken else { throw CancellationError() }
      try Self.saveCredentials(refreshed)
      self.credentials = refreshed
      return refreshed.accessToken
    }
    refreshTask = task
    defer { refreshTask = nil }
    return try await task.value
  }

  func authorizedSocket(at endpoint: URL, token: String, session: URLSession? = nil) async throws -> HASocket {
    try await authorizedSocket(at: endpoint, session: session) { token }
  }

  func authorizedSocket(at endpoint: URL, session: URLSession? = nil, token: () async throws -> String) async throws -> HASocket {
    let socket = HASocket(baseURL: endpoint, session: session ?? webSockets)
    do {
      guard try await socket.receive()["type"] as? String == "auth_required" else {
        throw DoorbellCompanionError("Unexpected Home Assistant WebSocket response")
      }
      let access = try await token()
      try Task.checkCancellation()
      try await socket.send(["type": "auth", "access_token": access])
      let responseType = try await socket.receive()["type"] as? String
      if responseType == "auth_invalid" {
        throw HAAuthRejectedError()
      }
      guard responseType == "auth_ok" else {
        throw DoorbellCompanionError("Home Assistant rejected the camera session")
      }
      return socket
    } catch {
      socket.close()
      throw error
    }
  }

  /// Races all known endpoints in parallel and retries once with a force-refreshed token if HA rejects auth.
  func firstAuthorizedSocket(endpoints: [URL], tokenTask: Task<String, Error>? = nil) async throws -> HASocket {
    do {
      let resolvedTokenTask = tokenTask ?? Task { [self] in try await self.accessToken() }
      return try await raceAuthorizedSockets(endpoints: endpoints, tokenTask: resolvedTokenTask)
    } catch is HAAuthRejectedError {
      let refreshed = try await accessToken(forceRefresh: true)
      let retryTask = Task<String, Error> { refreshed }
      return try await raceAuthorizedSockets(endpoints: endpoints, tokenTask: retryTask)
    }
  }

  private func raceAuthorizedSockets(endpoints: [URL], tokenTask: Task<String, Error>) async throws -> HASocket {
    enum Attempt {
      case connected(HASocket)
      case authRejected
      case refreshRejected
      case refreshFailed(HARefreshFailedError)
      case failed
      case timeout
    }
    let outcome: Attempt = await withTaskGroup(of: Attempt.self) { group in
      for endpoint in endpoints {
        group.addTask { @MainActor [self] in
          do {
            let socket = try await self.authorizedSocket(at: endpoint) { try await tokenTask.value }
            if Task.isCancelled {
              socket.close()
              return .failed
            }
            return .connected(socket)
          } catch is HAAuthRejectedError {
            return .authRejected
          } catch is HARefreshRejectedError {
            return .refreshRejected
          } catch let error as HARefreshFailedError {
            return .refreshFailed(error)
          } catch {
            return .failed
          }
        }
      }
      group.addTask {
        // The socket exchange gets its own deadline after the shared refresh finishes.
        do {
          _ = try await tokenTask.value
        } catch is HARefreshRejectedError {
          return .refreshRejected
        } catch let error as HARefreshFailedError {
          return .refreshFailed(error)
        } catch {
          return .timeout
        }
        try? await Task.sleep(nanoseconds: 8_000_000_000)
        return .timeout
      }
      var pending = endpoints.count
      var failure = Attempt.failed
      while let result = await group.next() {
        switch result {
        case .connected(let socket):
          group.cancelAll()
          for await other in group {
            if case .connected(let loser) = other { loser.close() }
          }
          return .connected(socket)
        case .authRejected:
          failure = .authRejected
          pending -= 1
          if pending > 0 { continue }
        case .refreshRejected, .refreshFailed:
          // Every endpoint awaits the same token task.
          failure = result
        case .failed:
          pending -= 1
          if pending > 0 { continue }
        case .timeout:
          break
        }
        group.cancelAll()
        for await other in group {
          switch other {
          case .connected(let loser): loser.close()
          case .refreshRejected, .refreshFailed: failure = other
          case .authRejected:
            if case .failed = failure { failure = other }
          case .failed, .timeout: break
          }
        }
        return failure
      }
      return failure
    }
    if Task.isCancelled {
      if case .connected(let socket) = outcome { socket.close() }
      throw CancellationError()
    }
    switch outcome {
    case .connected(let socket):
      return socket
    case .authRejected:
      throw HAAuthRejectedError()
    case .refreshRejected:
      throw HARefreshRejectedError()
    case .refreshFailed(let error):
      throw error
    case .failed, .timeout:
      throw HAWebSocketUnavailableError()
    }
  }

  func refreshDoorbells() async throws {
    let socket = try await firstAuthorizedSocket(endpoints: endpoints)
    defer { socket.close() }

    // Phase 1: fetch registries, states, and config in a single pipelined RTT.
    let phase1 = try await socket.pipeline([
      (type: "config/device_registry/list", fields: [:], required: true),
      (type: "config/entity_registry/list", fields: [:], required: true),
      (type: "get_states", fields: [:], required: true),
      (type: "get_config", fields: [:], required: true),
    ])
    let devices = phase1[0] as? [JSON] ?? []
    let entities = phase1[1] as? [JSON] ?? []
    let states = phase1[2] as? [JSON] ?? []
    let config = phase1[3] as? JSON ?? [:]

    let disabled = Set(doorbells.filter { !$0.enabled }.map(\.camera))
    var discovered = Self.discoverDoorbells(devices: devices, entities: entities, states: states,
                                            version: config["version"] as? String)

    // Phase 2: disable preload_stream (faster IDR keyframe on Protect), warm ICE config, and read capabilities.
    if !discovered.isEmpty {
      var phase2Requests: [(type: String, fields: JSON, required: Bool)] = []
      for bell in discovered {
        phase2Requests.append((
          type: "camera/update_prefs",
          fields: ["entity_id": bell.camera, "preload_stream": false],
          required: false
        ))
      }
      let iceIndex = phase2Requests.count
      phase2Requests.append((
        type: "camera/webrtc/get_client_config",
        fields: ["entity_id": discovered[0].camera],
        required: false
      ))
      let capStartIndex = phase2Requests.count
      for bell in discovered {
        phase2Requests.append((
          type: "camera/capabilities",
          fields: ["entity_id": bell.camera],
          required: false
        ))
      }
      let configStartIndex = phase2Requests.count
      for bell in discovered {
        phase2Requests.append((
          type: "camera/webrtc/get_client_config",
          fields: ["entity_id": bell.camera],
          required: false
        ))
      }

      let phase2 = try await socket.pipeline(phase2Requests)
      if let iceConfig = phase2[iceIndex] as? JSON,
         let servers = (iceConfig["configuration"] as? JSON)?["iceServers"] as? [JSON] {
        saveICEServers(servers)
      }
      for index in discovered.indices {
        let cap = phase2[capStartIndex + index] as? JSON
        discovered[index].talkback = cap?["two_way_audio"] as? Bool == true
        let clientConfig = phase2[configStartIndex + index] as? JSON
        discovered[index].dataChannel = clientConfig?["dataChannel"] as? String
        discovered[index].enabled = !disabled.contains(discovered[index].camera)
      }
    }

    doorbells = discovered
    saveDoorbells()
    if let advertisedInternal = (config["internal_url"] as? String).flatMap({ try? Self.serverURL($0) }) {
      let existingIsIP = localURL?.host.map { !$0.lowercased().hasSuffix(".local") } == true
      let advertisedIsLocal = advertisedInternal.host?.lowercased().hasSuffix(".local") == true
      if !(existingIsIP && advertisedIsLocal) {
        localURL = advertisedInternal
      }
    }
    externalURL = (config["external_url"] as? String).flatMap { try? Self.serverURL($0) }
    defaults.set(localURL, forKey: "local_url")
    defaults.set(externalURL, forKey: "external_url")
  }

  static func discoverDoorbells(devices: [JSON], entities: [JSON], states: [JSON],
                                version: String? = nil) -> [Doorbell] {
    var names: [String: String] = [:]
    for device in devices {
      guard let id = device["id"] as? String else { continue }
      names[id] = device["name_by_user"] as? String ?? device["name"] as? String
    }
    var friendlyNames: [String: String] = [:]
    var doorbellStates = Set<String>()
    var ringEvents = Set<String>()
    for state in states {
      guard let id = state["entity_id"] as? String else { continue }
      let attributes = state["attributes"] as? JSON
      friendlyNames[id] = attributes?["friendly_name"] as? String
      if attributes?["device_class"] as? String == "doorbell" {
        doorbellStates.insert(id)
      }
      if (attributes?["event_types"] as? [String])?.contains("ring") == true {
        ringEvents.insert(id)
      }
    }
    // The event.received trigger became available without opting in to Labs in 2026.7.
    let versionParts = version?.split(separator: ".") ?? []
    let supportsEventTrigger = versionParts.count >= 2 &&
      (Int(versionParts[0]) ?? 0, Int(versionParts[1]) ?? 0) >= (2026, 7)
    var cameras: [String: [JSON]] = [:]
    var standalone: [JSON] = []
    var rings: [String: [String]] = [:]
    for entity in entities {
      guard entity["disabled_by"] == nil || entity["disabled_by"] is NSNull,
            let id = entity["entity_id"] as? String else { continue }
      let device = entity["device_id"] as? String
      if id.hasPrefix("camera.") {
        if let device { cameras[device, default: []].append(entity) }
        else { standalone.append(entity) }
      } else if let device, id.hasPrefix("binary_sensor.") || id.hasPrefix("event.") {
        let classes = ["translation_key", "original_device_class", "device_class"]
        // Reolink only creates its Visitor sensor for doorbell channels, and it has no device class.
        let reolinkVisitor = id.hasPrefix("binary_sensor.") && entity["platform"] as? String == "reolink"
          && entity["translation_key"] as? String == "visitor"
        if doorbellStates.contains(id) || reolinkVisitor
            || classes.contains(where: { entity[$0] as? String == "doorbell" })
            || id.hasSuffix("_doorbell") {
          rings[device, default: []].append(id)
          if id.hasPrefix("event."), entity["platform"] as? String == "unifiprotect" {
            ringEvents.insert(id)
          }
        }
      }
    }
    var result: [Doorbell] = []
    for (device, triggers) in rings {
      guard let best = cameras[device]?.min(by: { cameraRank($0) < cameraRank($1) }),
            let camera = best["entity_id"] as? String else { continue }
      let events = triggers.filter { $0.hasPrefix("event.") }
      let selected = events.isEmpty ? triggers : events
      let native = supportsEventTrigger ? events.filter { ringEvents.contains($0) } : []
      result.append(Doorbell(camera: camera, name: names[device] ?? friendlyNames[camera] ?? camera,
                             triggers: selected, nativeRingEvents: native.isEmpty ? nil : native))
    }
    if result.isEmpty {
      result = (cameras.values.flatMap { $0 } + standalone).compactMap { entity in
        guard let camera = entity["entity_id"] as? String else { return nil }
        let device = entity["device_id"] as? String
        let name = device.flatMap { names[$0] } ?? friendlyNames[camera] ?? camera
        return Doorbell(camera: camera, name: name, triggers: [])
      }
    }
    return result.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
  }

  private static func cameraRank(_ entity: JSON) -> Int {
    let id = entity["entity_id"] as? String ?? ""
    let unique = entity["unique_id"] as? String ?? ""
    if unique.hasSuffix("_1") || id.contains("medium") { return 0 }
    if unique.hasSuffix("_0") { return 1 }
    if id.contains("package") || unique.hasSuffix("_3") { return 3 }
    return 2
  }

  func selectedDoorbell(camera: String? = nil) -> Doorbell? {
    if let camera, !camera.isEmpty { return doorbells.first { $0.camera == camera } }
    return doorbells.first { $0.enabled }
  }

  func setEnabled(_ camera: String, _ enabled: Bool) {
    if let index = doorbells.firstIndex(where: { $0.camera == camera }) {
      doorbells[index].enabled = enabled
      saveDoorbells()
    }
  }

  func setupPush(token pushToken: String, force: Bool = false) async throws {
    guard url != nil else { return }
    let syncKey = "synced_doorbell_subscription"
    let enabled = doorbells.filter { $0.enabled && !$0.triggers.isEmpty }
    let encoder = JSONEncoder()
    encoder.outputFormatting = .sortedKeys
    let subscriptionState = try encoder.encode(enabled)
    if !force, deviceRegistration != nil, registeredPushToken == pushToken,
       defaults.data(forKey: syncKey) == subscriptionState {
      return
    }
    let token = try await accessToken()
    let endpoint = try await reachableEndpoint(token: token)
    let pushURL = Self.relayURL.appendingPathComponent("push/ios").absoluteString
    let appData = ["push_url": pushURL, "push_token": pushToken]
    let deviceInfo: JSON = [
      "device_name": name, "manufacturer": "Apple", "model": "iOS",
      "app_version": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") ?? "1.0",
      "os_version": ProcessInfo.processInfo.operatingSystemVersionString, "app_data": appData,
    ]
    if let registration = deviceRegistration, registeredPushToken != pushToken || force {
      do {
        _ = try await request(at: endpoint, path: "api/webhook/\(registration)", json: [
          "type": "update_registration", "data": deviceInfo,
        ])
      } catch let error as HAHTTPError where error.status == 404 || error.status == 410 {
        deviceRegistration = nil
      }
    }
    if deviceRegistration == nil {
      let appID = Bundle.main.bundleIdentifier ?? "nl.niek.doorbellcompanion"
      let response = try await request(at: endpoint, path: "api/mobile_app/registrations",
                                       token: token, json: deviceInfo.merging([
        "device_id": deviceID, "app_id": appID, "app_name": "Doorbell Companion",
        "os_name": "iOS", "supports_encryption": false,
      ]) { _, new in new })
      guard let object = try JSONSerialization.jsonObject(with: response) as? JSON,
            let webhook = object["webhook_id"] as? String else {
        throw DoorbellCompanionError("Home Assistant did not register this iPhone")
      }
      deviceRegistration = webhook
      registrationServer = url
      defaults.set(webhook, forKey: "registration")
      defaults.set(url, forKey: "registration_server")
    }
    registeredPushToken = pushToken
    defaults.set(pushToken, forKey: "registered_push_token")

    try await syncSharedAutomation(at: endpoint, token: token, refreshTriggers: force)
    guard let registration = deviceRegistration else { throw DoorbellCompanionError("Missing phone registration") }
    var routes: [String: [String: String]] = [:]
    for bell in enabled {
      for entity in bell.triggers {
        routes[entity] = ["camera_entity_id": bell.camera, "doorbell_name": bell.name]
      }
    }
    let subscription: JSON = ["service": "notify.\(notifyService)", "doorbells": routes]
    let result = try await request(at: endpoint, path: "api/webhook/\(registration)", json: [
      "type": "register_sensor", "data": [
        "type": "sensor", "unique_id": "doorbell_companion_subscription", "name": "Doorbell subscription",
        "entity_category": "diagnostic", "state": routes.count,
        "attributes": ["doorbell_companion_subscription": subscription],
      ],
    ])
    guard (try JSONSerialization.jsonObject(with: result) as? JSON)?["success"] as? Bool == true else {
      throw DoorbellCompanionError("Home Assistant could not save this phone's doorbell subscriptions")
    }
    defaults.set(subscriptionState, forKey: syncKey)
  }

  static func sharedAutomationTemplate() throws -> JSON {
    guard let assetURL = Bundle.main.privateFrameworksURL?
      .appendingPathComponent("App.framework/flutter_assets/assets/doorbell_automation.json"),
          let config = try JSONSerialization.jsonObject(with: Data(contentsOf: assetURL)) as? JSON else {
      throw DoorbellCompanionError("Doorbell automation template is missing")
    }
    return config
  }

  private func syncSharedAutomation(at endpoint: URL, token: String, refreshTriggers: Bool) async throws {
    var config = try Self.sharedAutomationTemplate()
    guard let id = config["id"] as? String, let dispatchTriggers = config["triggers"] as? [JSON] else {
      throw DoorbellCompanionError("Invalid doorbell automation template")
    }
    let path = "api/config/automation/config/\(id)"
    let data = try await request(at: endpoint, path: path, token: token, allowMissing: true)
    let existing = (try? JSONSerialization.jsonObject(with: data)) as? JSON ?? [:]
    if !refreshTriggers, existing["triggers"] != nil { return }
    var ringTriggers: [String: JSON] = [:]
    for bell in doorbells {
      for entity in bell.triggers {
        ringTriggers[entity] = bell.automationTrigger(for: entity, id: entity)
      }
    }
    if ringTriggers.isEmpty, existing["triggers"] != nil { return }
    config["triggers"] = dispatchTriggers + ringTriggers.keys.sorted().compactMap { ringTriggers[$0] }
    let current = existing as NSDictionary
    if config.allSatisfy({ key, value in
      key == "id" || (value as? NSObject)?.isEqual(current[key]) == true
    }) { return }
    _ = try await request(at: endpoint, path: path, token: token, json: config)
  }

  func testPush(bell: Doorbell) async throws {
    let token = try await accessToken()
    let endpoint = try await reachableEndpoint(token: token)
    var payload = pushPayload(for: bell)
    var data = payload["data"] as? JSON ?? [:]
    data["event_ms"] = String(Int(Date().timeIntervalSince1970 * 1000))
    payload["data"] = data
    _ = try await request(at: endpoint, path: "api/services/notify/\(notifyService)",
                          token: token, json: payload)
  }

  private func pushPayload(for bell: Doorbell) -> JSON {
    ["title": bell.name, "message": "Someone is at the door", "data": [
      "type": "doorbell_ring", "camera_entity_id": bell.camera, "doorbell_name": bell.name,
    ]]
  }

  func disconnect() async throws {
    guard let credentials else { return }
    let endpoint: URL
    if let pending = credentials.pendingRevocation {
      endpoint = pending
    } else {
      let token = try await accessToken(forceRefresh: true)
      endpoint = try await reachableEndpoint(token: token)
      let entryID = try await registrationEntryID(at: endpoint, token: token)
      if let entryID {
        try await deleteRegistration(at: endpoint, token: token, entryID: entryID)
      }
      // The server may already have revoked the token when a retry runs.
      var pending = self.credentials ?? credentials
      pending.pendingRevocation = endpoint
      try Self.saveCredentials(pending)
      self.credentials = pending
    }

    guard await revokeSession(at: [endpoint] + endpoints.filter { $0 != endpoint }, token: credentials.refreshToken) else {
      throw DoorbellCompanionError("Could not revoke the Home Assistant session. Check the connection and try Disconnect again.")
    }
    let status = SecItemDelete(Self.keychainQuery as CFDictionary)
    guard status == errSecSuccess || status == errSecItemNotFound else {
      throw DoorbellCompanionError("Could not clear this phone's Home Assistant sign-in. Try Disconnect again.")
    }
    refreshTask?.cancel()
    self.credentials = nil
    doorbells = []
    localURL = nil
    externalURL = nil
    deviceRegistration = nil
    registeredPushToken = nil
    registrationServer = nil
    for key in ["doorbells", "local_url", "external_url", "ice_servers", "ice_servers_time",
                "synced_doorbell_subscription", "registration", "registered_push_token",
                "registration_server"] {
      defaults.removeObject(forKey: key)
    }
  }

  private func registrationEntryID(at endpoint: URL, token: String) async throws -> String? {
    var hassDeviceID: String?
    if let registration = deviceRegistration {
      do {
        let data = try await request(at: endpoint, path: "api/webhook/\(registration)", json: ["type": "get_config"])
        if !data.isEmpty {
          let config = try? JSONSerialization.jsonObject(with: data) as? JSON
          guard let id = config?["hass_device_id"] as? String, !id.isEmpty else {
            throw DoorbellCompanionError("Home Assistant did not identify the phone registration")
          }
          hassDeviceID = id
        }
      } catch let error as HAHTTPError where error.status == 404 || error.status == 410 {
        // Home Assistant may have removed the registration already.
      }
    }
    let socket = try await authorizedSocket(at: endpoint, token: token, session: session)
    defer { socket.close() }
    guard let devices = try await socket.command("config/device_registry/list") as? [JSON] else {
      throw DoorbellCompanionError("Could not read the Home Assistant device registry")
    }
    let matching = devices.filter { device in
      (device["identifiers"] as? [[String]])?.contains(["mobile_app", deviceID]) == true
    }
    if hassDeviceID == nil, matching.isEmpty { return nil }
    guard let hassDeviceID, let device = matching.first(where: { $0["id"] as? String == hassDeviceID }) else {
      throw DoorbellCompanionError("Could not verify that the Home Assistant registration belongs to this phone")
    }
    let entries = device["config_entries"] as? [String]
    let entryID = entries.map { $0.count == 1 ? $0[0] : "" } ?? device["config_entry_id"] as? String
    guard let entryID, !entryID.isEmpty else {
      throw DoorbellCompanionError("Could not identify the phone's Mobile App entry in Home Assistant")
    }
    return entryID
  }

  private func deleteRegistration(at endpoint: URL, token: String, entryID: String) async throws {
    do {
      _ = try await request(at: endpoint, path: "api/config/config_entries/entry/\(entryID)",
                            token: token, method: "DELETE", allowMissing: true)
    } catch let error as HAHTTPError where error.status == 401 || error.status == 403 {
      throw DoorbellCompanionError("A Home Assistant administrator must remove the phone registration")
    } catch {
      throw DoorbellCompanionError("Could not remove the phone registration. \(error.localizedDescription)")
    }
  }

  private func revokeSession(at endpoints: [URL], token: String) async -> Bool {
    for endpoint in endpoints {
      do {
        let request = formRequest(at: endpoint, path: "auth/revoke", fields: ["token": token])
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode
        if status == 200, data.isEmpty { return true }
        if status == 404 || status == 405 {
          let legacy = formRequest(at: endpoint, path: "auth/token", fields: ["token": token, "action": "revoke"])
          let (data, response) = try await session.data(for: legacy)
          if (response as? HTTPURLResponse)?.statusCode == 200, data.isEmpty { return true }
        }
      } catch {
        if Task.isCancelled { return false }
      }
    }
    return false
  }

  /// Races all deduplicated endpoints in parallel so cellular doesn't wait for a dead LAN timeout.
  private func reachableEndpoint(token: String) async throws -> URL {
    let candidates = endpoints
    guard !candidates.isEmpty else { throw DoorbellCompanionError("Home Assistant is unreachable") }
    if candidates.count == 1 {
      _ = try await request(at: candidates[0], path: "api/", token: token)
      return candidates[0]
    }
    let winner = await withTaskGroup(of: URL?.self, returning: URL?.self) { group in
      for endpoint in candidates {
        group.addTask { @MainActor [self] in
          guard (try? await self.request(at: endpoint, path: "api/", token: token)) != nil else { return nil }
          return endpoint
        }
      }
      for await result in group {
        if let result {
          group.cancelAll()
          return result
        }
      }
      return nil
    }
    try Task.checkCancellation()
    guard let winner else { throw DoorbellCompanionError("Home Assistant is unreachable") }
    return winner
  }

  func snapshot(at endpoint: URL, camera: String, token: String) async throws -> Data {
    try await request(at: endpoint, path: "api/camera_proxy/\(camera)", token: token)
  }

  private func saveDoorbells() {
    defaults.set(try? JSONEncoder().encode(doorbells), forKey: "doorbells")
  }

  private func request(at url: URL, path: String, token: String? = nil, json: JSON? = nil,
                       method: String? = nil, allowMissing: Bool = false) async throws -> Data {
    var request = URLRequest(url: url.appendingPathComponent(path), timeoutInterval: 8)
    request.httpMethod = method ?? (json == nil ? "GET" : "POST")
    if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
    if let json {
      request.setValue("application/json", forHTTPHeaderField: "Content-Type")
      request.httpBody = try JSONSerialization.data(withJSONObject: json)
    }
    let (data, response) = try await session.data(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    // The automation editor returns 400 for an automation that is already absent.
    let missingAutomation = allowMissing && status == 400 && path.hasPrefix("api/config/automation/") &&
      (try? JSONSerialization.jsonObject(with: data) as? JSON)?["message"] as? String == "Resource not found"
    guard (200..<300).contains(status) || (allowMissing && status == 404) || missingAutomation else {
      // Webhook IDs are credentials. Avoid including the request URL in an error.
      throw HAHTTPError(status: status, path: path.hasPrefix("api/webhook/") ? "registration" : path)
    }
    return data
  }

  private func formRequest(at url: URL, path: String, fields: [String: String]) -> URLRequest {
    var request = URLRequest(url: url.appendingPathComponent(path), timeoutInterval: 8)
    request.httpMethod = "POST"
    request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
    request.httpBody = Data(fields.map { key, value in
      "\(key)=\(value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")"
    }.joined(separator: "&").utf8)
    return request
  }

  private func tokenResponse(url: URL, fields: [String: String]) async throws -> JSON {
    let request = formRequest(at: url, path: "auth/token", fields: fields)
    let (data, response) = try await session.data(for: request)
    if fields["grant_type"] == "refresh_token", (response as? HTTPURLResponse)?.statusCode == 400,
       let body = (try? JSONSerialization.jsonObject(with: data)) as? JSON,
       body["error"] as? String == "invalid_grant" {
      throw HARefreshRejectedError()
    }
    guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
          let result = try JSONSerialization.jsonObject(with: data) as? JSON else {
      throw DoorbellCompanionError("Home Assistant sign-in failed")
    }
    return result
  }

  private static let keychainQuery: [String: Any] = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: "nl.niek.doorbellcompanion.home-assistant",
    kSecAttrAccount as String: "oauth",
  ]

  private static func loadCredentials() -> HACredentials? {
    var query = keychainQuery
    query[kSecReturnData as String] = true
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
          let data = item as? Data else { return nil }
    return try? JSONDecoder().decode(HACredentials.self, from: data)
  }

  private static func saveCredentials(_ credentials: HACredentials) throws {
    let data = try JSONEncoder().encode(credentials)
    let attributes: [String: Any] = [
      kSecValueData as String: data,
      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    var status = SecItemUpdate(keychainQuery as CFDictionary, attributes as CFDictionary)
    if status == errSecItemNotFound {
      status = SecItemAdd(keychainQuery.merging(attributes) { _, new in new } as CFDictionary, nil)
    }
    guard status == errSecSuccess else { throw DoorbellCompanionError("Could not save Home Assistant sign-in") }
  }
}

@MainActor
final class HASocket {
  private let task: URLSessionWebSocketTask
  private var nextID = 0

  init(baseURL: URL, session: URLSession) {
    var components = URLComponents(url: baseURL.appendingPathComponent("api/websocket"),
                                   resolvingAgainstBaseURL: false)!
    components.scheme = baseURL.scheme == "http" ? "ws" : "wss"
    task = session.webSocketTask(with: URLRequest(url: components.url!, timeoutInterval: 8))
    task.resume()
  }

  func close() { task.cancel(with: .goingAway, reason: nil) }

  func send(_ object: JSON) async throws {
    let data = try JSONSerialization.data(withJSONObject: object)
    try await task.send(.string(String(decoding: data, as: UTF8.self)))
  }

  func receive() async throws -> JSON {
    try await withTaskCancellationHandler {
      let data: Data
      switch try await task.receive() {
      case .string(let value): data = Data(value.utf8)
      case .data(let value): data = value
      @unknown default: throw DoorbellCompanionError("Unknown Home Assistant WebSocket response")
      }
      guard let result = try JSONSerialization.jsonObject(with: data) as? JSON else {
        throw DoorbellCompanionError("Invalid Home Assistant WebSocket response")
      }
      return result
    } onCancel: {
      task.cancel(with: .goingAway, reason: nil)
    }
  }

  func command(_ type: String, fields: JSON = [:]) async throws -> Any? {
    let results = try await pipeline([(type: type, fields: fields, required: true)])
    return results.first ?? nil
  }

  /// Sends all commands immediately and collects their results in a single RTT.
  func pipeline(_ requests: [(type: String, fields: JSON, required: Bool)]) async throws -> [Any?] {
    guard !requests.isEmpty else { return [] }
    var idToIndex: [Int: Int] = [:]
    for (index, req) in requests.enumerated() {
      let id = try await sendCommand(req.type, fields: req.fields)
      idToIndex[id] = index
    }
    var results = Array<Any?>(repeating: nil, count: requests.count)
    var remaining = idToIndex.count
    while remaining > 0 {
      try Task.checkCancellation()
      let message = try await receive()
      guard message["type"] as? String == "result",
            let id = message["id"] as? Int,
            let index = idToIndex.removeValue(forKey: id) else { continue }
      remaining -= 1
      if message["success"] as? Bool == true {
        results[index] = message["result"]
      } else if requests[index].required {
        throw DoorbellCompanionError("Home Assistant rejected \(requests[index].type)")
      }
    }
    return results
  }

  func sendCommand(_ type: String, fields: JSON) async throws -> Int {
    nextID += 1
    let id = nextID
    var message = fields
    message["type"] = type
    message["id"] = id
    try await send(message)
    return id
  }
}
