import AVFoundation
import CallKit
import PushKit
import UIKit
import UserNotifications
import WebRTC

@MainActor
final class DoorbellCalls: NSObject, @preconcurrency PKPushRegistryDelegate, @preconcurrency CXProviderDelegate,
                           UNUserNotificationCenterDelegate {
  private struct Incoming {
    let id: UUID
    let media: CameraCall
    let usesCallKit: Bool
    var answered = false
    var snapshotData: Data?
    var notificationTask: Task<Void, Never>?
  }

  @MainActor
  private final class BackgroundTaskToken {
    private var id: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
      id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
        MainActor.assumeIsolated { self?.end() }
      }
    }

    func end() {
      guard id != .invalid else { return }
      UIApplication.shared.endBackgroundTask(id)
      id = .invalid
    }
  }

  private static let ringTimeoutNanoseconds: UInt64 = 30_000_000_000
  private static let missedFallbackDelaySeconds: TimeInterval = 31

  private static func missedFallbackIdentifier(for callID: UUID) -> String {
    "\(callID.uuidString)-missed"
  }

  private let home: HomeAssistant
  private let provider: CXProvider
  private let controller = CXCallController()
  private var registry: PKPushRegistry?
  private var active: Incoming?
  private var timeout: Task<Void, Never>?
  private var prewarmBackgroundTask: UIBackgroundTaskIdentifier = .invalid
  private weak var cameraView: CameraCallViewController?
  private var audioActive = false
  private(set) var pushToken: String?
  var tokenChanged: (() -> Void)?

  init(home: HomeAssistant) {
    self.home = home
    let configuration = CXProviderConfiguration()
    configuration.supportsVideo = true
    configuration.supportedHandleTypes = [.generic]
    configuration.maximumCallGroups = 1
    configuration.maximumCallsPerCallGroup = 1
    configuration.includesCallsInRecents = false
    configuration.iconTemplateImageData = UIImage(named: "CallKitIcon")?.pngData()
    provider = CXProvider(configuration: configuration)
    super.init()
    UNUserNotificationCenter.current().delegate = self
    provider.setDelegate(self, queue: .main)
    let rtcAudio = RTCAudioSession.sharedInstance()
    rtcAudio.useManualAudio = true
    rtcAudio.isAudioEnabled = false
    let rtcConfiguration = RTCAudioSessionConfiguration.webRTC()
    rtcConfiguration.categoryOptions = [.defaultToSpeaker, .allowBluetoothHFP]
    RTCAudioSessionConfiguration.setWebRTC(rtcConfiguration)
  }

  func registerForVoIP() {
    guard registry == nil else { return }
    let registry = PKPushRegistry(queue: .main)
    registry.delegate = self
    registry.desiredPushTypes = [.voIP]
    self.registry = registry
  }

  func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials,
                    for type: PKPushType) {
    guard type == .voIP else { return }
    let value = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
    let entitlement = Bundle.main.object(forInfoDictionaryKey: "DoorbellCompanionAPNSEnvironment") as? String
    let environment = entitlement == "production" ? "production" : "sandbox"
    pushToken = "\(environment):\(value)"
    tokenChanged?()
  }

  func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
    guard type == .voIP else { return }
    pushToken = nil
    tokenChanged?()
  }

  func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
                    for type: PKPushType, completion: @escaping () -> Void) {
    guard type == .voIP else { completion(); return }
    receiveIncomingCall(payload.dictionaryPayload, completion: completion)
  }

  func receiveIncomingCall(_ value: [AnyHashable: Any], completion: @escaping () -> Void) {
    let id = (value["call_id"] as? String).flatMap(UUID.init(uuidString:)) ?? UUID()
    let name = value["doorbell_name"] as? String ?? "Doorbell"
    let camera = value["camera_entity_id"] as? String
    let workerTimeMs = (value["received_at"] as? NSNumber)?.int64Value
      ?? (value["received_at"] as? String).flatMap(Int64.init)
    let eventTimeMs = (value["event_ms"] as? NSNumber)?.int64Value
      ?? (value["event_ms"] as? String).flatMap(Int64.init)
    let bell = camera.flatMap { home.selectedDoorbell(camera: $0) }.flatMap { $0.enabled ? $0 : nil }
    let update = CXCallUpdate()
    update.localizedCallerName = bell?.name ?? name
    update.remoteHandle = CXHandle(type: .generic, value: bell?.name ?? name)
    update.hasVideo = true
    update.supportsHolding = false
    update.supportsGrouping = false
    update.supportsUngrouping = false
    update.supportsDTMF = false

    // Report every VoIP invite immediately, even if the local configuration changed.
    let available = active == nil && bell != nil && home.url != nil
    if available, let bell {
      let media = makeMedia(bell, workerTimeMs: workerTimeMs, eventTimeMs: eventTimeMs)
      media.snapshotReady = { [weak self] data in
        self?.handleSnapshot(data, callID: id, doorbellName: bell.name)
      }
      active = Incoming(id: id, media: media, usesCallKit: true)
    }
    provider.reportNewIncomingCall(with: id, update: update) { [weak self] error in
      completion()
      Task { @MainActor in
        guard let self else { return }
        if let error {
          NSLog("Doorbell Companion CallKit: %@", error.localizedDescription)
          if available {
            let previousTask = self.active?.id == id ? self.active?.notificationTask : nil
            previousTask?.cancel()
            self.clearNotification(for: id, after: previousTask)
            self.finish(id)
          }
        } else if !available {
          self.provider.reportCall(with: id, endedAt: Date(), reason: .failed)
        }
      }
    }
    if available {
      NSLog("Doorbell Companion CallKit: VoIP invite reported")
      prewarmBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Connect doorbell camera") {
        [weak self] in
        MainActor.assumeIsolated { self?.endPrewarmBackgroundTask() }
      }
      // Schedule an OS-timed fallback so a missed alert still fires if iOS suspends the app before 30s.
      if let doorbellName = active?.media.bell.name {
        active?.notificationTask = scheduleMissedFallbackNotification(callID: id, doorbellName: doorbellName)
      }
      active?.media.start()
      timeout = Task { [weak self] in
        try? await Task.sleep(nanoseconds: Self.ringTimeoutNanoseconds)
        guard !Task.isCancelled, let self, let active = self.active, active.id == id, !active.answered else { return }
        let doorbellName = active.media.bell.name
        let snapshotData = active.snapshotData
        let previousTask = active.notificationTask
        previousTask?.cancel()
        self.provider.reportCall(with: id, endedAt: Date(), reason: .unanswered)
        // Acquire the missed-call background task before finish() releases prewarmBackgroundTask.
        self.postDoorbellNotification(
          callID: id,
          doorbellName: doorbellName,
          body: "Missed doorbell",
          snapshot: snapshotData,
          onlyWhileRinging: false,
          backgroundTaskName: "Post missed doorbell notification",
          after: previousTask,
          removeFallback: true
        )
        self.finish(id)
      }
    }
  }

  func test(_ bell: Doorbell) {
    guard active == nil else { return }
    let id = UUID()
    let media = makeMedia(bell)
    active = Incoming(id: id, media: media, usesCallKit: false, answered: true)
    configureAudioSession()
    do {
      try AVAudioSession.sharedInstance().setActive(true)
      setAudioActive(true)
    } catch {
      NSLog("Doorbell Companion test audio: %@", error.localizedDescription)
    }
    if bell.talkback, AVCaptureDevice.authorizationStatus(for: .audio) == .notDetermined {
      AVCaptureDevice.requestAccess(for: .audio) { [weak self] _ in
        Task { @MainActor in self?.active?.media.updateMicrophone() }
      }
    }
    media.answer()
    media.start()
    showCameraIfReady()
  }

  private func makeMedia(_ bell: Doorbell, workerTimeMs: Int64? = nil, eventTimeMs: Int64? = nil) -> CameraCall {
    CameraCall(bell: bell, home: home, audioReady: { [weak self] in self?.audioActive == true },
               workerTimeMs: workerTimeMs, eventTimeMs: eventTimeMs)
  }

  func showCameraIfReady() {
    guard let active, active.answered, cameraView == nil,
          let root = currentWindow?.rootViewController else { return }
    var presenter = root
    while let presented = presenter.presentedViewController { presenter = presented }
    guard presenter.viewIfLoaded?.window != nil, !presenter.isBeingDismissed else { return }
    let call = CameraCallViewController(
      call: active.media,
      toggleMute: { [weak self] in self?.toggleActiveMute() },
      endCall: { [weak self] in self?.endActiveCall() }
    )
    cameraView = call
    presenter.present(call, animated: true)
  }

  var currentWindow: UIWindow? {
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
      .first { $0.activationState == .foregroundActive }?.windows.first(where: \.isKeyWindow)
  }

  func providerDidReset(_ provider: CXProvider) {
    if let active {
      let previousTask = active.notificationTask
      previousTask?.cancel()
      clearNotification(for: active.id, after: previousTask)
      finish(active.id)
    }
  }

  func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
    guard active?.id == action.callUUID else { action.fail(); return }
    timeout?.cancel()
    let previousTask = active?.notificationTask
    previousTask?.cancel()
    active?.notificationTask = nil
    active?.answered = true
    // The live camera replaces the snapshot, so drop the ring notification and missed fallback.
    clearNotification(for: action.callUUID, after: previousTask)
    configureAudioSession()
    active?.media.answer()
    action.fulfill()
    endPrewarmBackgroundTask()
    showCameraIfReady()
  }

  func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
    guard active?.id == action.callUUID else { action.fail(); return }
    active?.media.setMuted(action.isMuted)
    action.fulfill()
  }

  func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
    let previousTask = active?.id == action.callUUID ? active?.notificationTask : nil
    previousTask?.cancel()
    clearNotification(for: action.callUUID, after: previousTask)
    finish(action.callUUID)
    action.fulfill()
  }

  func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
    RTCAudioSession.sharedInstance().audioSessionDidActivate(audioSession)
    setAudioActive(true)
  }

  func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
    setAudioActive(false)
    RTCAudioSession.sharedInstance().audioSessionDidDeactivate(audioSession)
  }

  private func configureAudioSession() {
    do {
      try AVAudioSession.sharedInstance().setCategory(
        .playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetoothHFP]
      )
    } catch {
      NSLog("Doorbell Companion audio: %@", error.localizedDescription)
    }
  }

  private func setAudioActive(_ active: Bool) {
    audioActive = active
    RTCAudioSession.sharedInstance().isAudioEnabled = active
    self.active?.media.updateMicrophone()
  }

  private func toggleActiveMute() {
    guard let active else { return }
    let next = !active.media.muted
    guard active.usesCallKit else {
      active.media.setMuted(next)
      return
    }
    let action = CXSetMutedCallAction(call: active.id, muted: next)
    controller.request(CXTransaction(action: action)) { [weak self] error in
      guard error != nil else { return }
      Task { @MainActor in self?.active?.media.setMuted(next) }
    }
  }

  private func endActiveCall() {
    guard let active else { return }
    guard active.usesCallKit else { finish(active.id); return }
    controller.request(CXTransaction(action: CXEndCallAction(call: active.id))) { [weak self] error in
      guard error != nil else { return }
      Task { @MainActor in
        self?.provider.reportCall(with: active.id, endedAt: Date(), reason: .failed)
        self?.finish(active.id)
      }
    }
  }

  private func finish(_ id: UUID) {
    guard let active, active.id == id else { return }
    timeout?.cancel()
    timeout = nil
    active.notificationTask?.cancel()
    endPrewarmBackgroundTask()
    cameraView?.detach()
    active.media.close()
    cameraView?.dismiss(animated: true)
    cameraView = nil
    self.active = nil
    setAudioActive(false)
    if !active.usesCallKit {
      try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
  }

  /// CallKit can't show images, so a ringing call also posts the doorbell snapshot as a notification.
  /// Answered or declined calls clear it; unanswered calls replace it with a "Missed doorbell" alert.
  private func handleSnapshot(_ data: Data, callID: UUID, doorbellName: String) {
    guard active?.id == callID, active?.answered == false else { return }
    active?.snapshotData = data
    let previousTask = active?.notificationTask
    active?.notificationTask = postDoorbellNotification(
      callID: callID,
      doorbellName: doorbellName,
      body: "Someone is at the door",
      snapshot: data,
      onlyWhileRinging: true,
      backgroundTaskName: "Post doorbell snapshot",
      after: previousTask
    )
  }

  private func scheduleMissedFallbackNotification(callID: UUID, doorbellName: String) -> Task<Void, Never> {
    let center = UNUserNotificationCenter.current()
    let token = BackgroundTaskToken(name: "Schedule missed doorbell fallback")
    let fallbackID = Self.missedFallbackIdentifier(for: callID)
    return Task { [weak self] in
      defer { token.end() }
      let status = await center.notificationSettings().authorizationStatus
      guard !Task.isCancelled,
            [.authorized, .provisional, .ephemeral].contains(status),
            self?.active?.id == callID,
            self?.active?.answered == false else { return }
      let content = UNMutableNotificationContent()
      content.title = doorbellName
      content.body = "Missed doorbell"
      content.threadIdentifier = "doorbell"
      let trigger = UNTimeIntervalNotificationTrigger(timeInterval: Self.missedFallbackDelaySeconds, repeats: false)
      do {
        try await center.add(UNNotificationRequest(identifier: fallbackID, content: content, trigger: trigger))
      } catch {
        NSLog("Doorbell Companion missed fallback notification: %@", error.localizedDescription)
      }
    }
  }

  @discardableResult
  private func postDoorbellNotification(
    callID: UUID,
    doorbellName: String,
    body: String,
    snapshot: Data?,
    onlyWhileRinging: Bool,
    backgroundTaskName: String,
    after previousTask: Task<Void, Never>? = nil,
    removeFallback: Bool = false
  ) -> Task<Void, Never> {
    let center = UNUserNotificationCenter.current()
    let fallbackID = Self.missedFallbackIdentifier(for: callID)
    if removeFallback {
      center.removePendingNotificationRequests(withIdentifiers: [fallbackID])
      center.removeDeliveredNotifications(withIdentifiers: [fallbackID])
    }
    // Keep the app awake until the notification request is handed off to the system.
    let token = BackgroundTaskToken(name: backgroundTaskName)
    return Task { [weak self] in
      defer { token.end() }
      await previousTask?.value
      if removeFallback {
        center.removePendingNotificationRequests(withIdentifiers: [fallbackID])
        center.removeDeliveredNotifications(withIdentifiers: [fallbackID])
      }
      guard !Task.isCancelled else { return }
      let status = await center.notificationSettings().authorizationStatus
      guard !Task.isCancelled, [.authorized, .provisional, .ephemeral].contains(status) else { return }
      if onlyWhileRinging {
        guard self?.active?.id == callID, self?.active?.answered == false else { return }
      }
      var tempFile: URL?
      do {
        let content = UNMutableNotificationContent()
        content.title = doorbellName
        content.body = body
        content.threadIdentifier = "doorbell"
        if let snapshot {
          let isPNG = snapshot.starts(with: [0x89, 0x50, 0x4E, 0x47])
          let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathExtension(isPNG ? "png" : "jpg")
          try snapshot.write(to: file)
          tempFile = file
          content.attachments = [try UNNotificationAttachment(identifier: "snapshot", url: file)]
        }
        guard !Task.isCancelled else {
          if let tempFile { try? FileManager.default.removeItem(at: tempFile) }
          return
        }
        if onlyWhileRinging {
          guard self?.active?.id == callID, self?.active?.answered == false else {
            if let tempFile { try? FileManager.default.removeItem(at: tempFile) }
            return
          }
        }
        try await center.add(UNNotificationRequest(identifier: callID.uuidString, content: content, trigger: nil))
      } catch {
        NSLog("Doorbell Companion notification: %@", error.localizedDescription)
      }
      // A delivered attachment has already been moved out of tmp, so this only cleans up failures.
      if let tempFile { try? FileManager.default.removeItem(at: tempFile) }
    }
  }

  private func clearNotification(for callID: UUID, after previousTask: Task<Void, Never>?) {
    let identifiers = [callID.uuidString, Self.missedFallbackIdentifier(for: callID)]
    let center = UNUserNotificationCenter.current()
    center.removePendingNotificationRequests(withIdentifiers: identifiers)
    center.removeDeliveredNotifications(withIdentifiers: identifiers)
    guard let previousTask else { return }
    let token = BackgroundTaskToken(name: "Clear doorbell snapshot")
    Task {
      defer { token.end() }
      await previousTask.value
      center.removePendingNotificationRequests(withIdentifiers: identifiers)
      center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }
  }

  // UserNotifications calls this off the main queue.
  nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                          withCompletionHandler completionHandler:
                                            @escaping (UNNotificationPresentationOptions) -> Void) {
    let id = notification.request.identifier
    Task { @MainActor [weak self] in
      let answered = self?.active.map { $0.answered && $0.id.uuidString == id } ?? false
      completionHandler(answered ? [] : [.banner, .list])
    }
  }

  private func endPrewarmBackgroundTask() {
    guard prewarmBackgroundTask != .invalid else { return }
    UIApplication.shared.endBackgroundTask(prewarmBackgroundTask)
    prewarmBackgroundTask = .invalid
  }
}
