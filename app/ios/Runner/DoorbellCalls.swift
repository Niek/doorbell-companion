import AVFoundation
import CallKit
import PushKit
import UIKit
import WebRTC

@MainActor
final class DoorbellCalls: NSObject, @preconcurrency PKPushRegistryDelegate, @preconcurrency CXProviderDelegate {
  private struct Incoming {
    let id: UUID
    let media: CameraCall
    let usesCallKit: Bool
    var answered = false
  }

  private let home: HomeAssistant
  private let provider: CXProvider
  private let controller = CXCallController()
  private var registry: PKPushRegistry?
  private var active: Incoming?
  private var timeout: Task<Void, Never>?
  private var prewarmBackgroundTask: UIBackgroundTaskIdentifier = .invalid
  private var pendingAnswer: CXAnswerCallAction?
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
    provider = CXProvider(configuration: configuration)
    super.init()
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
    let value = payload.dictionaryPayload
    let id = (value["call_id"] as? String).flatMap(UUID.init(uuidString:)) ?? UUID()
    let name = value["doorbell_name"] as? String ?? "Doorbell"
    let camera = value["camera_entity_id"] as? String
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
      let media = makeMedia(bell)
      media.didNegotiate = { [weak self] in self?.completeAnswer(id) }
      media.didFail = { [weak self] _ in self?.failCall(id) }
      active = Incoming(id: id, media: media, usesCallKit: true)
    }
    provider.reportNewIncomingCall(with: id, update: update) { [weak self] error in
      completion()
      Task { @MainActor in
        guard let self else { return }
        if let error {
          NSLog("Doorbell Companion CallKit: %@", error.localizedDescription)
          if available { self.finish(id) }
        } else if !available {
          self.provider.reportCall(with: id, endedAt: Date(), reason: .failed)
        }
      }
    }
    if available {
      NSLog("Doorbell Companion CallKit: VoIP invite reported")
      prewarmBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Connect doorbell camera") {
        [weak self] in
        Task { @MainActor in self?.endPrewarmBackgroundTask() }
      }
      active?.media.start()
      timeout = Task { [weak self] in
        try? await Task.sleep(nanoseconds: 25_000_000_000)
        guard !Task.isCancelled, let self, self.active?.answered == false else { return }
        self.provider.reportCall(with: id, endedAt: Date(), reason: .unanswered)
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
    media.answer()
    media.start()
    showCameraIfReady()
  }

  private func makeMedia(_ bell: Doorbell) -> CameraCall {
    CameraCall(bell: bell, home: home, audioReady: { [weak self] in self?.audioActive == true })
  }

  func showCameraIfReady() {
    guard let active, active.answered, cameraView == nil,
          let root = currentWindow?.rootViewController else { return }
    var presenter = root
    while let presented = presenter.presentedViewController { presenter = presented }
    guard presenter.viewIfLoaded?.window != nil, !presenter.isBeingDismissed else { return }
    let call = CameraCallViewController(call: active.media) { [weak self] in self?.endActiveCall() }
    cameraView = call
    presenter.present(call, animated: true)
  }

  var currentWindow: UIWindow? {
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
      .first { $0.activationState == .foregroundActive }?.windows.first(where: \.isKeyWindow)
  }

  func providerDidReset(_ provider: CXProvider) {
    if let active { finish(active.id) }
  }

  func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
    guard active?.id == action.callUUID else { action.fail(); return }
    timeout?.cancel()
    pendingAnswer = action
    if active?.media.isNegotiated == true { completeAnswer(action.callUUID) }
  }

  private func completeAnswer(_ id: UUID) {
    guard let action = pendingAnswer, action.callUUID == id, active?.id == id else { return }
    pendingAnswer = nil
    active?.answered = true
    configureAudioSession()
    active?.media.answer()
    action.fulfill()
    endPrewarmBackgroundTask()
    showCameraIfReady()
  }

  func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
    finish(action.callUUID)
    action.fulfill()
  }

  func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
    guard let answer = action as? CXAnswerCallAction, pendingAnswer?.callUUID == answer.callUUID else { return }
    pendingAnswer = nil
    failCall(answer.callUUID)
  }

  private func failCall(_ id: UUID) {
    guard active?.id == id else { return }
    // Keep the snapshot and error visible when the user is already in the app.
    if active?.answered == true { return }
    provider.reportCall(with: id, endedAt: Date(), reason: .failed)
    finish(id)
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
    pendingAnswer?.fail()
    pendingAnswer = nil
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

  private func endPrewarmBackgroundTask() {
    guard prewarmBackgroundTask != .invalid else { return }
    UIApplication.shared.endBackgroundTask(prewarmBackgroundTask)
    prewarmBackgroundTask = .invalid
  }
}
