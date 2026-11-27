import AVFoundation
import Network
import UIKit
import WebRTC

private final class NoOpVideoEncoderFactory: NSObject, RTCVideoEncoderFactory {
  func createEncoder(_ info: RTCVideoCodecInfo) -> (any RTCVideoEncoder)? { nil }
  func supportedCodecs() -> [RTCVideoCodecInfo] { [] }
}

@MainActor
final class CameraCall: NSObject, RTCPeerConnectionDelegate {
  let bell: Doorbell
  private let home: HomeAssistant
  private let audioReady: () -> Bool
  private static let factory = RTCPeerConnectionFactory(
    encoderFactory: NoOpVideoEncoderFactory(),
    decoderFactory: RTCDefaultVideoDecoderFactory()
  )
  private var connection: RTCPeerConnection?
  private var socket: HASocket?
  private var connectionTask: Task<Void, Never>?
  private var connectionTimeout: Task<Void, Never>?
  private var disconnectTimeout: Task<Void, Never>?
  private var retryTask: Task<Void, Never>?
  private var snapshotTask: Task<Void, Never>?
  private var attempt = 0
  private var automaticRetryUsed = false
  private var iceConnected = false
  private var sessionID: String?
  private var localCandidates: [RTCIceCandidate] = []
  private var remoteCandidates: [RTCIceCandidate] = []
  private var remoteDescriptionSet = false
  private var audioSender: RTCRtpSender?
  private var dataChannel: RTCDataChannel?
  private var microphone: RTCAudioTrack?
  private var acceptsAudio = false
  private var answered = false
  private(set) var muted = false
  private let startedAt = ProcessInfo.processInfo.systemUptime
  private var firstFrameLogged = false
  private(set) var video: RTCVideoTrack?
  private(set) var snapshot: UIImage?
  private(set) var status = "Connecting camera…"
  private(set) var canMute = false
  private(set) var canRetry = false
  var didChange: (() -> Void)?
  /// Called once with the bytes of the first snapshot that loads and decodes.
  var snapshotReady: ((Data) -> Void)?

  static func warmUp() {
    DispatchQueue.global(qos: .utility).async {
      _ = Self.factory
    }
  }

  init(
    bell: Doorbell,
    home: HomeAssistant,
    audioReady: @escaping () -> Bool,
    workerTimeMs: Int64? = nil,
    eventTimeMs: Int64? = nil
  ) {
    self.bell = bell
    self.home = home
    self.audioReady = audioReady
    super.init()
    let wallMs = Int64(Date().timeIntervalSince1970 * 1000)
    NSLog("Doorbell Companion timing: push_received elapsed_ms=0 wall_ms=%lld event_ms=%lld worker_ms=%lld",
          wallMs, eventTimeMs ?? 0, workerTimeMs ?? 0)
  }

  func start() {
    guard connectionTask == nil else { return }
    let current = attempt
    connectionTask = Task { [weak self] in
      guard let self else { return }
      do {
        try await self.connect(attempt: current)
      } catch {
        guard !Task.isCancelled, self.attempt == current else { return }
        NSLog("Doorbell Companion camera: %@", error.localizedDescription)
        // ICE drives teardown when an established peer loses media connectivity.
        guard !self.iceConnected else { return }
        let requiresSignIn = error is HARefreshRejectedError || error is HAAuthRejectedError
        self.connectionFailed(requiresSignIn ? "Sign in to Home Assistant again" : error.localizedDescription,
                              retryable: Self.isConnectionError(error), requiresSignIn: requiresSignIn)
      }
    }
  }

  private static func isConnectionError(_ error: Error) -> Bool {
    if error is HAWebSocketUnavailableError { return true }
    if let failure = error as? HARefreshFailedError { return failure.retryable }
    return isHomeAssistantTransportError(error)
  }

  private func connectionFailed(_ message: String, retryable: Bool, requiresSignIn: Bool = false) {
    mark("camera_connection_failed")
    resetConnection()
    guard retryable, !automaticRetryUsed else {
      showFailure(message, canRetry: !requiresSignIn)
      return
    }
    automaticRetryUsed = true
    status = "Reconnecting camera…"
    retryTask = Task { [weak self] in
      let monitor = NWPathMonitor()
      monitor.start(queue: .global(qos: .utility))
      defer { monitor.cancel() }
      let deadline = ProcessInfo.processInfo.systemUptime + 15
      try? await Task.sleep(nanoseconds: 1_000_000_000)
      while !Task.isCancelled {
        if monitor.currentPath.status == .satisfied {
          self?.reconnect()
          return
        }
        if ProcessInfo.processInfo.systemUptime >= deadline {
          self?.showFailure("Could not reach Home Assistant")
          return
        }
        try? await Task.sleep(nanoseconds: 500_000_000)
      }
    }
    didChange?()
  }

  private func showFailure(_ message: String, canRetry: Bool = true) {
    retryTask = nil
    status = message
    self.canRetry = canRetry
    didChange?()
  }

  func retry() {
    guard canRetry else { return }
    automaticRetryUsed = false
    reconnect()
  }

  private func reconnect() {
    retryTask = nil
    canRetry = false
    status = "Reconnecting camera…"
    mark("camera_reconnect_start")
    didChange?()
    start()
  }

  private func buildPeerAndOffer(iceServers serverData: [JSON],
                                 dataChannel dataChannelLabel: String?,
                                 attempt current: Int) async throws -> (RTCPeerConnection, String) {
    try requireAttempt(current)
    let configuration = RTCConfiguration()
    configuration.sdpSemantics = .unifiedPlan
    configuration.bundlePolicy = .maxBundle
    configuration.rtcpMuxPolicy = .require
    configuration.continualGatheringPolicy = .gatherContinually
    configuration.iceCandidatePoolSize = 1
    configuration.iceServers = serverData.compactMap {
      let urls = $0["urls"] as? [String] ?? ($0["urls"] as? String).map { [$0] } ?? []
      guard !urls.isEmpty else { return nil }
      return RTCIceServer(urlStrings: urls, username: $0["username"] as? String,
                          credential: $0["credential"] as? String)
    }
    if configuration.iceServers.isEmpty {
      configuration.iceServers = [
        RTCIceServer(urlStrings: ["stun:stun.home-assistant.io:3478", "stun:stun.cloudflare.com:3478"]),
      ]
    }
    let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
    guard let connection = Self.factory.peerConnection(with: configuration, constraints: constraints,
                                                        delegate: self) else {
      throw DoorbellCompanionError("Could not start WebRTC")
    }
    self.connection = connection
    let video = RTCRtpTransceiverInit()
    video.direction = .recvOnly
    let audio = RTCRtpTransceiverInit()
    audio.direction = bell.talkback ? .sendRecv : .recvOnly
    if let dataChannelLabel {
      // Nest WebRTC cameras need audio, video and data channel sections, in that order.
      // libwebrtc always puts the data channel section last in the first offer.
      audioSender = connection.addTransceiver(of: .audio, init: audio)?.sender
      connection.addTransceiver(of: .video, init: video)
      dataChannel = connection.dataChannel(forLabel: dataChannelLabel, configuration: RTCDataChannelConfiguration())
    } else {
      connection.addTransceiver(of: .video, init: video)
      audioSender = connection.addTransceiver(of: .audio, init: audio)?.sender
    }

    let offer = try await connection.offer(for: constraints)
    try requireAttempt(current)
    try await connection.setLocalDescription(offer)
    try requireAttempt(current)
    mark("local_sdp_ready")
    return (connection, offer.sdp)
  }

  private func requireAttempt(_ current: Int) throws {
    try Task.checkCancellation()
    guard attempt == current else { throw CancellationError() }
  }

  private func connect(attempt current: Int) async throws {
    try requireAttempt(current)
    let endpoints = home.endpoints
    guard !endpoints.isEmpty else { throw DoorbellCompanionError("Sign in to Home Assistant first") }
    let tokenTask = Task { [home] in try await home.accessToken() }
    defer { tokenTask.cancel() }

    snapshotTask = Task { [weak self] in
      guard let self, let token = try? await tokenTask.value, !Task.isCancelled else { return }
      let data = await withTaskGroup(of: Data?.self, returning: Data?.self) { group in
        for endpoint in endpoints {
          group.addTask { @MainActor [home = self.home, bell = self.bell] in
            try? await home.snapshot(at: endpoint, camera: bell.camera, token: token)
          }
        }
        for await candidate in group {
          if let candidate {
            group.cancelAll()
            return candidate
          }
        }
        return nil
      }
      guard !Task.isCancelled, let data, let image = UIImage(data: data) else { return }
      let ready = snapshotReady
      snapshotReady = nil
      ready?(data)
      guard !firstFrameLogged else { return }
      snapshot = image
      mark("snapshot_ready")
      didChange?()
    }

    // Overlap PeerConnection + offer creation with WebSocket connect when ICE servers are cached.
    let cachedIce = home.cachedICEServers()
    let prebuiltOffer: Task<(RTCPeerConnection, String), Error>? = cachedIce.map { servers in
      Task {
        let camera = self.home.selectedDoorbell(camera: self.bell.camera) ?? self.bell
        return try await self.buildPeerAndOffer(iceServers: servers, dataChannel: camera.dataChannel, attempt: current)
      }
    }
    defer { prebuiltOffer?.cancel() }

    let socket = try await home.firstAuthorizedSocket(endpoints: endpoints, tokenTask: tokenTask)
    do { try requireAttempt(current) } catch {
      socket.close()
      throw error
    }
    self.socket = socket
    mark("ha_auth_ok")

    // Authentication and token requests have their own deadlines.
    connectionTimeout = Task { [weak self] in
      try? await Task.sleep(nanoseconds: 12_000_000_000)
      guard !Task.isCancelled, let self, self.attempt == current, !self.iceConnected else { return }
      self.connectionFailed("Camera connection timed out", retryable: true)
    }

    let connection: RTCPeerConnection
    let offerSdp: String
    var configCommandID: Int?
    if let prebuiltOffer {
      (connection, offerSdp) = try await prebuiltOffer.value
      // Refresh ICE cache in the background while the offer is already in flight.
      configCommandID = try? await socket.sendCommand("camera/webrtc/get_client_config", fields: [
        "entity_id": bell.camera,
      ])
    } else {
      let config = try await socket.command("camera/webrtc/get_client_config", fields: [
        "entity_id": bell.camera,
      ]) as? JSON
      try requireAttempt(current)
      let serverData = (config?["configuration"] as? JSON)?["iceServers"] as? [JSON] ?? []
      home.saveICEServers(serverData)
      let dataChannelLabel = config?["dataChannel"] as? String
      home.saveDataChannel(dataChannelLabel, camera: bell.camera)
      (connection, offerSdp) = try await buildPeerAndOffer(iceServers: serverData, dataChannel: dataChannelLabel,
                                                           attempt: current)
    }

    try requireAttempt(current)
    let latestSdp = connection.localDescription?.sdp.isEmpty == false ? connection.localDescription!.sdp : offerSdp
    if latestSdp.contains("a=candidate:") {
      localCandidates.removeAll { latestSdp.contains($0.sdp) }
    }
    let offerID = try await socket.sendCommand("camera/webrtc/offer", fields: [
      "entity_id": bell.camera, "offer": latestSdp,
    ])
    mark("offer_sent")

    while !Task.isCancelled {
      let message = try await socket.receive()
      try requireAttempt(current)
      let msgID = message["id"] as? Int
      if let configCommandID, msgID == configCommandID, message["type"] as? String == "result" {
        if let result = message["result"] as? JSON {
          if let servers = (result["configuration"] as? JSON)?["iceServers"] as? [JSON] {
            home.saveICEServers(servers)
          }
          // The offer is already in flight, so a changed data channel applies from the next call.
          home.saveDataChannel(result["dataChannel"] as? String, camera: bell.camera)
        }
        continue
      }
      guard msgID == offerID else { continue }
      if message["type"] as? String == "result" && message["success"] as? Bool == false {
        try handleSignalingError(DoorbellCompanionError("Home Assistant rejected the WebRTC offer"))
      }
      guard let event = message["event"] as? JSON else { continue }
      switch event["type"] as? String {
      case "session":
        sessionID = event["session_id"] as? String
        mark("ha_session")
        let candidates = localCandidates
        localCandidates.removeAll()
        for candidate in candidates {
          try requireAttempt(current)
          try await sendCandidate(candidate)
        }
      case "answer":
        guard let answer = event["answer"] as? String, !remoteDescriptionSet else { continue }
        acceptsAudio = Self.acceptsMicrophone(answer)
        try await connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: answer))
        try requireAttempt(current)
        remoteDescriptionSet = true
        for candidate in remoteCandidates { try await connection.add(candidate) }
        try requireAttempt(current)
        remoteCandidates.removeAll()
        mark("remote_description_set")
        updateMicrophone()
      case "candidate":
        guard let data = event["candidate"] as? JSON, let sdp = data["candidate"] as? String,
              !sdp.isEmpty else { continue }
        let candidate = RTCIceCandidate(sdp: sdp,
                                        sdpMLineIndex: data["sdpMLineIndex"] as? Int32 ?? 0,
                                        sdpMid: data["sdpMid"] as? String ?? "0")
        if remoteDescriptionSet {
          do { try await connection.add(candidate) } catch {
            try requireAttempt(current)
            try handleSignalingError(error)
          }
        } else {
          remoteCandidates.append(candidate)
        }
      case "error":
        try handleSignalingError(DoorbellCompanionError(event["message"] as? String ?? "Home Assistant WebRTC error"))
      default:
        break
      }
    }
  }

  private func handleSignalingError(_ error: Error) throws {
    guard iceConnected else { throw error }
    NSLog("Doorbell Companion camera: %@", error.localizedDescription)
  }

  private func sendCandidate(_ candidate: RTCIceCandidate) async throws {
    guard let socket, let sessionID else {
      localCandidates.append(candidate)
      return
    }
    _ = try await socket.sendCommand("camera/webrtc/candidate", fields: [
      "entity_id": bell.camera, "session_id": sessionID,
      "candidate": ["candidate": candidate.sdp, "sdpMid": candidate.sdpMid ?? "0",
                    "sdpMLineIndex": candidate.sdpMLineIndex],
    ])
  }

  private static func acceptsMicrophone(_ sdp: String) -> Bool {
    var audio = false
    for line in sdp.components(separatedBy: .newlines) {
      if line.hasPrefix("m=") { audio = line.hasPrefix("m=audio ") && !line.hasPrefix("m=audio 0 ") }
      if audio && ["a=sendrecv", "a=recvonly"].contains(line) { return true }
    }
    return false
  }

  func answer() {
    answered = true
    mark("answered")
    updateMicrophone()
  }

  func updateMicrophone() {
    let authorized = AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
    canMute = answered && bell.talkback && acceptsAudio && authorized && audioReady()
    if canMute && microphone == nil {
      let source = Self.factory.audioSource(with: RTCMediaConstraints(
        mandatoryConstraints: nil, optionalConstraints: nil
      ))
      let track = Self.factory.audioTrack(with: source, trackId: "ha-talkback-mic")
      track.isEnabled = false
      audioSender?.track = track
      microphone = track
    }
    microphone?.isEnabled = canMute && !muted
    didChange?()
  }

  func setMuted(_ next: Bool) {
    guard muted != next else { return }
    muted = next
    updateMicrophone()
  }

  func close() {
    mark("camera_closed")
    retryTask?.cancel()
    retryTask = nil
    resetConnection()
    snapshot = nil
    canRetry = false
    didChange = nil
    snapshotReady = nil
  }

  private func resetConnection() {
    attempt += 1
    connectionTask?.cancel()
    connectionTask = nil
    connectionTimeout?.cancel()
    connectionTimeout = nil
    disconnectTimeout?.cancel()
    disconnectTimeout = nil
    snapshotTask?.cancel()
    snapshotTask = nil
    socket?.close()
    dataChannel?.close()
    microphone?.isEnabled = false
    let previous = connection
    connection = nil
    previous?.close()
    socket = nil
    sessionID = nil
    localCandidates.removeAll()
    remoteCandidates.removeAll()
    remoteDescriptionSet = false
    iceConnected = false
    audioSender = nil
    dataChannel = nil
    microphone = nil
    acceptsAudio = false
    canMute = false
    video = nil
    firstFrameLogged = false
  }

  func renderedFirstFrame() {
    guard !firstFrameLogged else { return }
    firstFrameLogged = true
    snapshotTask?.cancel()
    snapshotTask = nil
    mark("first_frame")
  }

  private func mark(_ stage: String) {
    let elapsed = Int((ProcessInfo.processInfo.systemUptime - startedAt) * 1000)
    NSLog("Doorbell Companion timing: %@ elapsed_ms=%d", stage, elapsed)
  }

  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection,
                                  didStartReceivingOn transceiver: RTCRtpTransceiver) {
    guard let track = transceiver.receiver.track as? RTCVideoTrack else { return }
    Task { @MainActor [weak self] in
      guard let self, self.connection === peerConnection else { return }
      self.video = track
      self.didChange?()
    }
  }

  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
    Task { @MainActor [weak self] in
      guard let self, self.connection === peerConnection else { return }
      try? await self.sendCandidate(candidate)
    }
  }

  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
    Task { @MainActor [weak self] in
      guard let self, self.connection === peerConnection else { return }
      self.disconnectTimeout?.cancel()
      self.disconnectTimeout = nil
      switch newState {
      case .connected, .completed:
        self.iceConnected = true
        self.connectionTimeout?.cancel()
        self.connectionTimeout = nil
        self.status = "Live"
        self.mark("ice_connected")
      case .disconnected:
        self.disconnectTimeout = Task { [weak self] in
          try? await Task.sleep(nanoseconds: 2_000_000_000)
          guard !Task.isCancelled, let self, self.connection === peerConnection else { return }
          self.connectionFailed("Camera connection interrupted", retryable: true)
        }
        return
      case .failed:
        self.connectionFailed("Remote media connection failed (NAT/ICE)", retryable: true)
        return
      default:
        return
      }
      self.didChange?()
    }
  }

  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
  nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
  nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

@MainActor
final class CameraCallViewController: UIViewController, RTCVideoViewDelegate {
  private let call: CameraCall
  private let toggleMuteAction: () -> Void
  private let endCall: () -> Void
  private var video: RTCMTLVideoView?
  private let snapshot = UIImageView()
  private let statusLabel = UILabel()
  private let statusDot = UIView()
  private let muteButton = CallControl(symbol: "mic.fill", title: "Mute", color: UIColor(white: 1, alpha: 0.22))
  private let retryButton = UIButton(type: .system)
  private let topShade = GradientView(colors: [UIColor(white: 0, alpha: 0.7), .clear])
  private let bottomShade = GradientView(colors: [.clear, UIColor(white: 0, alpha: 0.75)])
  private var track: RTCVideoTrack?
  private var fillsScreen = true
  private var live = false

  init(call: CameraCall, toggleMute: @escaping () -> Void, endCall: @escaping () -> Void) {
    self.call = call
    self.toggleMuteAction = toggleMute
    self.endCall = endCall
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .fullScreen
  }

  required init?(coder: NSCoder) { nil }

  override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .black
    snapshot.clipsToBounds = true
    applyContentMode()
    for child in [snapshot, topShade, bottomShade] {
      view.addSubview(child)
      child.translatesAutoresizingMaskIntoConstraints = false
    }
    pin(snapshot, to: view)
    view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(toggleContentMode)))

    let title = UILabel()
    title.text = call.bell.name
    title.font = UIFontMetrics(forTextStyle: .title1).scaledFont(for: .systemFont(ofSize: 28, weight: .semibold))
    title.adjustsFontForContentSizeCategory = true
    title.textColor = .white
    title.textAlignment = .center
    title.numberOfLines = 2

    statusLabel.font = .preferredFont(forTextStyle: .subheadline)
    statusLabel.adjustsFontForContentSizeCategory = true
    statusLabel.textColor = UIColor(white: 1, alpha: 0.92)
    statusLabel.numberOfLines = 2
    statusDot.layer.cornerRadius = 4
    statusDot.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
      statusDot.widthAnchor.constraint(equalToConstant: 8),
      statusDot.heightAnchor.constraint(equalToConstant: 8),
    ])
    let statusRow = UIStackView(arrangedSubviews: [statusDot, statusLabel])
    statusRow.spacing = 8
    statusRow.alignment = .center
    let chip = UIView()
    chip.backgroundColor = UIColor(white: 0.06, alpha: 0.55)
    chip.layer.cornerRadius = 15
    chip.layer.cornerCurve = .continuous
    chip.addSubview(statusRow)
    statusRow.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
      statusRow.leadingAnchor.constraint(equalTo: chip.leadingAnchor, constant: 14),
      statusRow.trailingAnchor.constraint(equalTo: chip.trailingAnchor, constant: -14),
      statusRow.topAnchor.constraint(equalTo: chip.topAnchor, constant: 6),
      statusRow.bottomAnchor.constraint(equalTo: chip.bottomAnchor, constant: -6),
    ])
    var retryStyle = UIButton.Configuration.filled()
    retryStyle.title = "Retry"
    retryButton.configuration = retryStyle
    retryButton.addAction(UIAction { [weak self] _ in self?.call.retry() }, for: .touchUpInside)
    let header = UIStackView(arrangedSubviews: [title, chip, retryButton])
    header.axis = .vertical
    header.alignment = .center
    header.spacing = 10

    muteButton.addAction(UIAction { [weak self] _ in
      self?.toggleMuteAction()
    }, for: .touchUpInside)
    let endButton = CallControl(symbol: "phone.down.fill", title: "End", color: .systemRed)
    endButton.addAction(UIAction { [weak self] _ in self?.endCall() }, for: .touchUpInside)
    let buttons = UIStackView(arrangedSubviews: [muteButton, endButton])
    buttons.spacing = 56
    buttons.alignment = .top

    for child in [header, buttons] {
      view.addSubview(child)
      child.translatesAutoresizingMaskIntoConstraints = false
    }
    let guide = view.safeAreaLayoutGuide
    NSLayoutConstraint.activate([
      header.topAnchor.constraint(equalTo: guide.topAnchor, constant: 16),
      header.leadingAnchor.constraint(greaterThanOrEqualTo: guide.leadingAnchor, constant: 20),
      header.trailingAnchor.constraint(lessThanOrEqualTo: guide.trailingAnchor, constant: -20),
      header.centerXAnchor.constraint(equalTo: guide.centerXAnchor),
      buttons.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -24),
      buttons.centerXAnchor.constraint(equalTo: guide.centerXAnchor),
      topShade.topAnchor.constraint(equalTo: view.topAnchor),
      topShade.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      topShade.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      topShade.bottomAnchor.constraint(equalTo: header.bottomAnchor, constant: 40),
      bottomShade.topAnchor.constraint(equalTo: buttons.topAnchor, constant: -56),
      bottomShade.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      bottomShade.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      bottomShade.bottomAnchor.constraint(equalTo: view.bottomAnchor),
    ])
    call.didChange = { [weak self] in self?.update() }
    update()
  }

  func detach() {
    if let video {
      track?.remove(video)
      video.removeFromSuperview()
    }
    track = nil
    video = nil
  }

  private func pin(_ child: UIView, to parent: UIView) {
    NSLayoutConstraint.activate([
      child.leadingAnchor.constraint(equalTo: parent.leadingAnchor),
      child.trailingAnchor.constraint(equalTo: parent.trailingAnchor),
      child.topAnchor.constraint(equalTo: parent.topAnchor),
      child.bottomAnchor.constraint(equalTo: parent.bottomAnchor),
    ])
  }

  @objc private func toggleContentMode() {
    fillsScreen.toggle()
    UIView.animate(withDuration: 0.2) { self.applyContentMode() }
  }

  private func applyContentMode() {
    video?.videoContentMode = fillsScreen ? .scaleAspectFill : .scaleAspectFit
    snapshot.contentMode = fillsScreen ? .scaleAspectFill : .scaleAspectFit
  }

  private func update() {
    statusLabel.text = call.status
    live = call.status == "Live"
    statusDot.backgroundColor = live ? .systemGreen : .systemOrange
    retryButton.isHidden = !call.canRetry
    muteButton.isHidden = !call.canMute
    muteButton.update(symbol: call.muted ? "mic.slash.fill" : "mic.fill",
                      title: call.muted ? "Unmute" : "Mute")
    if track !== call.video {
      detach()
      snapshot.isHidden = false
      if let track = call.video {
        // A new renderer reports its first video size even when the retry keeps the old resolution.
        let video = RTCMTLVideoView()
        self.video = video
        self.track = track
        video.delegate = self
        view.insertSubview(video, belowSubview: snapshot)
        video.translatesAutoresizingMaskIntoConstraints = false
        pin(video, to: view)
        applyContentMode()
        track.add(video)
      }
    }
    snapshot.image = snapshot.isHidden ? nil : call.snapshot
  }

  nonisolated func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) {
    guard size.width > 0, size.height > 0 else { return }
    Task { @MainActor [weak self] in
      guard let self, self.video === videoView else { return }
      self.snapshot.isHidden = true
      self.snapshot.image = nil
      self.call.renderedFirstFrame()
    }
  }
}

/// A round call-screen button with a caption, matching the system call UI.
private final class CallControl: UIControl {
  private let circle = UIView()
  private let icon = UIImageView()
  private let caption = UILabel()

  init(symbol: String, title: String, color: UIColor) {
    super.init(frame: .zero)
    circle.backgroundColor = color
    circle.layer.cornerRadius = 36
    circle.isUserInteractionEnabled = false
    icon.tintColor = .white
    icon.contentMode = .center
    icon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 26, weight: .semibold)
    caption.font = .preferredFont(forTextStyle: .footnote)
    caption.textColor = .white
    caption.textAlignment = .center
    for child in [circle, icon, caption] {
      addSubview(child)
      child.translatesAutoresizingMaskIntoConstraints = false
    }
    NSLayoutConstraint.activate([
      circle.widthAnchor.constraint(equalToConstant: 72),
      circle.heightAnchor.constraint(equalToConstant: 72),
      circle.topAnchor.constraint(equalTo: topAnchor),
      circle.centerXAnchor.constraint(equalTo: centerXAnchor),
      circle.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor),
      icon.centerXAnchor.constraint(equalTo: circle.centerXAnchor),
      icon.centerYAnchor.constraint(equalTo: circle.centerYAnchor),
      caption.topAnchor.constraint(equalTo: circle.bottomAnchor, constant: 8),
      caption.leadingAnchor.constraint(equalTo: leadingAnchor),
      caption.trailingAnchor.constraint(equalTo: trailingAnchor),
      caption.bottomAnchor.constraint(equalTo: bottomAnchor),
    ])
    isAccessibilityElement = true
    accessibilityTraits = .button
    update(symbol: symbol, title: title)
  }

  required init?(coder: NSCoder) { nil }

  func update(symbol: String, title: String) {
    icon.image = UIImage(systemName: symbol)
    caption.text = title
    accessibilityLabel = title
  }

  override var isHighlighted: Bool {
    didSet { circle.alpha = isHighlighted ? 0.7 : 1 }
  }

  // Keep the parent's tap-to-toggle-fill gesture from swallowing button taps.
  override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
    !(gestureRecognizer is UITapGestureRecognizer && gestureRecognizer.view !== self)
  }
}

private final class GradientView: UIView {
  override class var layerClass: AnyClass { CAGradientLayer.self }

  init(colors: [UIColor]) {
    super.init(frame: .zero)
    isUserInteractionEnabled = false
    (layer as? CAGradientLayer)?.colors = colors.map(\.cgColor)
  }

  required init?(coder: NSCoder) { nil }
}
