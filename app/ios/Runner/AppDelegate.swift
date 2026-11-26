import AuthenticationServices
import AVFoundation
import CryptoKit
import Darwin
import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate,
                         ASWebAuthenticationPresentationContextProviding {
  private let home = HomeAssistant()
  private lazy var calls = DoorbellCalls(home: home)
  private let discovery = HomeAssistantDiscovery()
  private var channel: FlutterMethodChannel?
  private var authentication: ASWebAuthenticationSession?
  private var oauthState: String?
  private var syncTask: Task<Void, Never>?
  private var debouncedSyncTask: Task<Void, Never>?
  private var disconnecting = false
  private var testPushTask: Task<Void, Never>?
  private var pushSetupError: String?
  private var pushReady = false
  private var didRefreshOnLaunch = false

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    let launched = super.application(application, didFinishLaunchingWithOptions: launchOptions)
    CameraCall.warmUp()
    if home.pendingDisconnect {
      pushSetupError = "Run Disconnect again to finish signing out of Home Assistant"
    }
    calls.tokenChanged = { [weak self] in self?.syncPush() }
    calls.registerForVoIP()
    discovery.didChange = { [weak self] in self?.channel?.invokeMethod("onDiscoveredInstances", arguments: $0) }
    return launched
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    let channel = FlutterMethodChannel(name: "doorbell_companion",
                                       binaryMessenger: engineBridge.applicationRegistrar.messenger())
    self.channel = channel
    channel.setMethodCallHandler { [weak self] call, result in
      guard let self else { result(FlutterMethodNotImplemented); return }
      self.handle(call, result: result)
    }
  }

  func sceneBecameActive() {
    calls.showCameraIfReady()
    publishStatus()
    if !didRefreshOnLaunch, home.url != nil, !home.pendingDisconnect {
      didRefreshOnLaunch = true
      Task { [weak self] in
        guard let self else { return }
        try? await self.home.refreshDoorbells()
        self.publishStatus()
      }
    }
  }

  private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let arguments = call.arguments as? JSON ?? [:]
    switch call.method {
    case "getStatus":
      result(status())
    case "startDiscovery":
      discovery.start()
      result(nil)
    case "startOAuth":
      do {
        try startAuthentication(arguments["url"] as? String ?? "")
        result(nil)
      } catch { result(flutterError(error)) }
    case "cancelOAuth":
      oauthState = nil
      authentication?.cancel()
      authentication = nil
      result(nil)
    case "refreshDoorbells":
      Task {
        do {
          try await home.refreshDoorbells()
          syncPush(force: true)
          await syncTask?.value
          if let pushSetupError { throw DoorbellCompanionError(pushSetupError) }
          result(status())
        } catch { result(flutterError(error)) }
      }
    case "setDoorbellEnabled":
      if let camera = arguments["cameraEntityId"] as? String, let enabled = arguments["enabled"] as? Bool {
        home.setEnabled(camera, enabled)
        debouncedSyncTask?.cancel()
        debouncedSyncTask = Task { [weak self] in
          try? await Task.sleep(nanoseconds: 400_000_000)
          guard !Task.isCancelled else { return }
          self?.syncPush()
        }
      }
      result(status())
    case "disconnect":
      guard !disconnecting else {
        result(flutterError(DoorbellCompanionError("Already disconnecting from Home Assistant")))
        return
      }
      disconnecting = true
      Task {
        defer { disconnecting = false }
        do {
          debouncedSyncTask?.cancel()
          testPushTask?.cancel()
          await syncTask?.value
          try await home.disconnect()
          pushReady = false
          pushSetupError = nil
          result(status())
        } catch {
          if home.pendingDisconnect {
            pushReady = false
            pushSetupError = "Run Disconnect again to finish signing out of Home Assistant"
            publishStatus()
          }
          result(flutterError(error))
        }
      }
    case "testCall":
      guard let bell = home.selectedDoorbell(camera: arguments["camera"] as? String) else {
        result(flutterError(DoorbellCompanionError("This doorbell is no longer available")))
        return
      }
      calls.test(bell)
      result(nil)
    case "testRemotePush":
      guard calls.pushToken != nil,
            let bell = home.selectedDoorbell(camera: arguments["camera"] as? String) else {
        result(flutterError(DoorbellCompanionError("A registered iPhone and an enabled doorbell are required")))
        return
      }
      sendTestPush(bell: bell, result: result)
    case "openUrl":
      if let raw = arguments["url"] as? String,
         let url = URL(string: raw),
         let scheme = url.scheme?.lowercased(),
         scheme == "https" || scheme == "http" {
        UIApplication.shared.open(url)
      }
      result(nil)
    case "requestPermissions":
      if arguments["type"] as? String == "microphone" {
        requestMicrophone()
      } else {
        calls.registerForVoIP()
        syncPush()
      }
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func sendTestPush(bell: Doorbell, result: @escaping FlutterResult) {
    guard testPushTask == nil else {
      result(flutterError(DoorbellCompanionError("A test call is already scheduled")))
      return
    }
    let application = UIApplication.shared
    let background = application.beginBackgroundTask(withName: "Test doorbell push") { [weak self] in
      Task { @MainActor in self?.testPushTask?.cancel() }
    }
    testPushTask = Task {
      defer {
        testPushTask = nil
        if background != .invalid { application.endBackgroundTask(background) }
      }
      do {
        try await Task.sleep(nanoseconds: 3_000_000_000)
        await syncTask?.value
        try Task.checkCancellation()
        try await home.testPush(bell: bell)
        result(nil)
      } catch { result(flutterError(error)) }
    }
  }

  private func status() -> JSON {
    var value: JSON = [
      "haConnected": home.url != nil, "haUrl": home.url?.absoluteString ?? "",
      "doorbells": home.doorbells.map(\.flutterValue),
      "microphoneGranted": AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
      "pushReady": pushReady,
    ]
    if let pushSetupError { value["pushError"] = pushSetupError }
    return value
  }

  private func publishStatus() {
    channel?.invokeMethod("onStatusChanged", arguments: status())
  }

  private func syncPush(force: Bool = false) {
    let previous = syncTask
    syncTask = Task { [weak self] in
      await previous?.value
      guard let self, !disconnecting, !home.pendingDisconnect else { return }
      guard home.url != nil, let token = calls.pushToken else {
        pushReady = false
        publishStatus()
        return
      }
      do {
        try await home.setupPush(token: token, force: force)
        pushReady = true
        pushSetupError = nil
      } catch {
        pushReady = false
        pushSetupError = error.localizedDescription
      }
      publishStatus()
    }
  }

  private func startAuthentication(_ raw: String) throws {
    let endpoint = try HomeAssistant.serverURL(raw)
    let state = UUID().uuidString
    let verifier = UUID().uuidString + UUID().uuidString
    let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
      .replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "=", with: "")
    oauthState = state
    authentication?.cancel()
    var authorize = URLComponents(url: endpoint.appendingPathComponent("auth/authorize"),
                                  resolvingAgainstBaseURL: false)!
    authorize.queryItems = [
      URLQueryItem(name: "response_type", value: "code"),
      URLQueryItem(name: "client_id", value: HomeAssistant.clientID),
      URLQueryItem(name: "redirect_uri", value: HomeAssistant.callback),
      URLQueryItem(name: "state", value: state),
      URLQueryItem(name: "code_challenge", value: challenge),
      URLQueryItem(name: "code_challenge_method", value: "S256"),
    ]
    let session = ASWebAuthenticationSession(url: authorize.url!, callbackURLScheme: "doorbell-companion") {
      [weak self] callback, error in
      Task { @MainActor in
        guard let self, self.oauthState == state else { return }
        self.oauthState = nil
        self.authentication = nil
        if let error {
          self.channel?.invokeMethod("onOAuthError", arguments: error.localizedDescription)
          return
        }
        let parameters = callback.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }
        guard parameters?.host == "auth-callback",
              parameters?.queryItems?.first(where: { $0.name == "state" })?.value == state,
              let code = parameters?.queryItems?.first(where: { $0.name == "code" })?.value else {
          self.channel?.invokeMethod("onOAuthError", arguments: "Invalid Home Assistant sign-in response")
          return
        }
        await self.finishAuthentication(at: endpoint, code: code, verifier: verifier)
      }
    }
    session.presentationContextProvider = self
    authentication = session
    guard session.start() else {
      authentication = nil
      oauthState = nil
      throw DoorbellCompanionError("Could not open Home Assistant sign-in")
    }
  }

  private func finishAuthentication(at endpoint: URL, code: String, verifier: String) async {
    channel?.invokeMethod("onOAuthProgress", arguments: "Finding Home Assistant doorbells…")
    let discoveredLocalURL = discovery.internalURL(for: endpoint.absoluteString)
    do {
      try await home.signIn(at: endpoint, with: code, verifier: verifier)
    } catch {
      channel?.invokeMethod("onOAuthError", arguments: error.localizedDescription)
      return
    }
    home.saveLocalURL(discoveredLocalURL)
    discovery.stop()
    pushSetupError = nil
    do { try await home.refreshDoorbells() }
    catch { pushSetupError = error.localizedDescription }
    channel?.invokeMethod("onOAuthComplete", arguments: status())
    if home.doorbells.contains(where: \.talkback),
       AVCaptureDevice.authorizationStatus(for: .audio) == .notDetermined {
      requestMicrophone()
    }
    if pushSetupError == nil { syncPush() }
  }

  func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
    calls.currentWindow ?? window ?? ASPresentationAnchor()
  }

  private func requestMicrophone() {
    if AVCaptureDevice.authorizationStatus(for: .audio) == .denied,
       let url = URL(string: UIApplication.openSettingsURLString) {
      UIApplication.shared.open(url)
    } else {
      AVCaptureDevice.requestAccess(for: .audio) { [weak self] _ in
        Task { @MainActor in self?.publishStatus() }
      }
    }
  }

  private func flutterError(_ error: Error) -> FlutterError {
    FlutterError(code: "DOORBELL_COMPANION_IOS", message: error.localizedDescription, details: nil)
  }
}

final class HomeAssistantDiscovery: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
  private struct Entry {
    let name: String
    let url: String
    let version: String
    let internalURL: URL?
  }

  private let browser = NetServiceBrowser()
  private var services: [NetService] = []
  private var entriesByService: [String: Entry] = [:]
  private var isBrowsing = false
  var didChange: (([JSON]) -> Void)?

  func internalURL(for primaryURL: String) -> URL? {
    entriesByService.values.first { $0.url.caseInsensitiveCompare(primaryURL) == .orderedSame }?.internalURL
  }

  func start() {
    publish()
    guard !isBrowsing else { return }
    isBrowsing = true
    browser.delegate = self
    browser.searchForServices(ofType: "_home-assistant._tcp.", inDomain: "local.")
  }

  func stop() {
    browser.stop()
    for service in services { service.stop() }
    services.removeAll()
    entriesByService.removeAll()
    isBrowsing = false
  }

  func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
    services.append(service)
    service.delegate = self
    service.resolve(withTimeout: 5)
  }

  func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
    services.removeAll { $0 == service }
    if entriesByService.removeValue(forKey: service.name) != nil {
      publish()
    }
  }

  func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
    isBrowsing = false
  }

  func netServiceDidResolveAddress(_ service: NetService) {
    guard service.port > 0 else { return }
    let values = service.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
    func value(_ name: String) -> String? {
      values[name].flatMap { String(data: $0, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) }
        .flatMap { $0.isEmpty ? nil : $0 }
    }

    let resolvedIP = Self.ipv4Address(from: service.addresses)
    let fallbackHost = resolvedIP ?? service.hostName?.trimmingCharacters(in: CharacterSet(charactersIn: "."))
    let fallbackLocal = fallbackHost.flatMap { URL(string: "http://\($0):\(service.port)") }

    let externalURL = value("external_url").flatMap { try? HomeAssistant.serverURL($0) }
    let txtInternal = value("internal_url").flatMap { try? HomeAssistant.serverURL($0) }
    let baseURL = value("base_url").flatMap { try? HomeAssistant.serverURL($0) }

    // Prefer resolved numeric IP over http://*.local to skip per-connection mDNS lookup latency.
    let internalURL: URL?
    if let txtInternal, txtInternal.scheme?.lowercased() == "http",
       txtInternal.host?.lowercased().hasSuffix(".local") == true, let fallbackLocal {
      internalURL = fallbackLocal
    } else {
      internalURL = txtInternal ?? fallbackLocal
    }

    // Prefer external_url as primary so doorbell calls keep working remotely on cellular,
    // while keeping internalURL for low-latency LAN racing on Wi-Fi.
    guard let chosenURL = externalURL ?? baseURL ?? internalURL else { return }
    entriesByService[service.name] = Entry(
      name: value("location_name") ?? service.name,
      url: chosenURL.absoluteString,
      version: value("version") ?? "",
      internalURL: internalURL
    )
    publish()
  }

  private func publish() {
    var seen = Set<String>()
    let list: [JSON] = entriesByService.values.compactMap { entry in
      guard seen.insert(entry.url.lowercased()).inserted else { return nil }
      return ["name": entry.name, "url": entry.url, "version": entry.version]
    }
    didChange?(list)
  }

  private static func ipv4Address(from addresses: [Data]?) -> String? {
    guard let addresses else { return nil }
    for data in addresses {
      var ip: String?
      data.withUnsafeBytes { raw in
        guard let addr = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self),
              addr.pointee.sa_family == sa_family_t(AF_INET) else { return }
        var sin = raw.load(as: sockaddr_in.self)
        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        if inet_ntop(AF_INET, &sin.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil {
          ip = String(cString: buffer)
        }
      }
      if let ip { return ip }
    }
    return nil
  }
}
