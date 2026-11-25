import AVFoundation
import UIKit
import WebRTC

@MainActor
final class CameraCall: NSObject, RTCPeerConnectionDelegate {
  let bell: Doorbell
  private let home: HomeAssistant
  private let audioReady: () -> Bool
  private static let factory = RTCPeerConnectionFactory(
    encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory()
  )
  private var connection: RTCPeerConnection?
  private var socket: HASocket?
  private var connectionTask: Task<Void, Never>?
  private var snapshotTask: Task<Void, Never>?
  private var sessionID: String?
  private var localCandidates: [RTCIceCandidate] = []
  private var remoteCandidates: [RTCIceCandidate] = []
  private var remoteDescriptionSet = false
  private var audioSender: RTCRtpSender?
  private var microphone: RTCAudioTrack?
  private var acceptsAudio = false
  private var answered = false
  private var muted = false
  private let startedAt = ProcessInfo.processInfo.systemUptime
  private var firstFrameLogged = false
  private(set) var video: RTCVideoTrack?
  private(set) var snapshot: UIImage?
  private(set) var status = "Connecting camera…"
  private(set) var canMute = false
  var isNegotiated: Bool { remoteDescriptionSet }
  var didChange: (() -> Void)?
  var didFail: ((Error) -> Void)?
  var didNegotiate: (() -> Void)?

  init(bell: Doorbell, home: HomeAssistant, audioReady: @escaping () -> Bool) {
    self.bell = bell
    self.home = home
    self.audioReady = audioReady
  }

  func start() {
    guard connectionTask == nil else { return }
    connectionTask = Task { [weak self] in
      guard let self else { return }
      do {
        try await self.connect()
      } catch {
        guard !Task.isCancelled else { return }
        self.status = "Could not connect to the camera"
        NSLog("Doorbell Companion camera: %@", error.localizedDescription)
        self.didChange?()
        self.didFail?(error)
      }
    }
  }

  private func connect() async throws {
    let token = try await home.accessToken()
    try Task.checkCancellation()
    let endpoints = home.endpoints
    snapshotTask = Task { [weak self] in
      guard let self else { return }
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
      snapshot = image
      mark("snapshot_ready")
      didChange?()
    }
    let socket = try await firstAuthorizedSocket(endpoints: endpoints, token: token)
    self.socket = socket
    try Task.checkCancellation()

    let config = try await socket.command("camera/webrtc/get_client_config", fields: [
      "entity_id": bell.camera,
    ]) as? JSON
    let configuration = RTCConfiguration()
    configuration.sdpSemantics = .unifiedPlan
    let serverData = (config?["configuration"] as? JSON)?["iceServers"] as? [JSON] ?? []
    configuration.iceServers = serverData.compactMap {
      let urls = $0["urls"] as? [String] ?? ($0["urls"] as? String).map { [$0] } ?? []
      guard !urls.isEmpty else { return nil }
      return RTCIceServer(urlStrings: urls, username: $0["username"] as? String,
                          credential: $0["credential"] as? String)
    }
    if configuration.iceServers.isEmpty {
      configuration.iceServers = [RTCIceServer(urlStrings: ["stun:stun.home-assistant.io:3478"])]
    }
    let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
    guard let connection = Self.factory.peerConnection(with: configuration, constraints: constraints,
                                                        delegate: self) else {
      throw ProtectFastError("Could not start WebRTC")
    }
    self.connection = connection
    let video = RTCRtpTransceiverInit()
    video.direction = .recvOnly
    connection.addTransceiver(of: .video, init: video)
    let audio = RTCRtpTransceiverInit()
    audio.direction = bell.talkback ? .sendRecv : .recvOnly
    audioSender = connection.addTransceiver(of: .audio, init: audio)?.sender

    let offer = try await connection.offer(for: constraints)
    try Task.checkCancellation()
    try await connection.setLocalDescription(offer)
    try Task.checkCancellation()
    let id = try await socket.sendCommand("camera/webrtc/offer", fields: [
      "entity_id": bell.camera, "offer": offer.sdp,
    ])
    mark("offer_sent")
    while !Task.isCancelled {
      let message = try await socket.receive()
      guard message["id"] as? Int == id else { continue }
      if message["type"] as? String == "result" && message["success"] as? Bool == false {
        throw ProtectFastError("Home Assistant rejected the WebRTC offer")
      }
      guard let event = message["event"] as? JSON else { continue }
      switch event["type"] as? String {
      case "session":
        sessionID = event["session_id"] as? String
        let candidates = localCandidates
        localCandidates.removeAll()
        for candidate in candidates { try await sendCandidate(candidate) }
      case "answer":
        guard let answer = event["answer"] as? String, !remoteDescriptionSet else { continue }
        acceptsAudio = Self.acceptsMicrophone(answer)
        try await connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: answer))
        try Task.checkCancellation()
        remoteDescriptionSet = true
        for candidate in remoteCandidates { try await connection.add(candidate) }
        remoteCandidates.removeAll()
        mark("remote_description_set")
        didNegotiate?()
        updateMicrophone()
      case "candidate":
        guard let data = event["candidate"] as? JSON, let sdp = data["candidate"] as? String,
              !sdp.isEmpty else { continue }
        let candidate = RTCIceCandidate(sdp: sdp,
                                        sdpMLineIndex: data["sdpMLineIndex"] as? Int32 ?? 0,
                                        sdpMid: data["sdpMid"] as? String ?? "0")
        if remoteDescriptionSet { try await connection.add(candidate) }
        else { remoteCandidates.append(candidate) }
      case "error":
        throw ProtectFastError(event["message"] as? String ?? "Home Assistant WebRTC error")
      default:
        break
      }
    }
  }

  private func firstAuthorizedSocket(endpoints: [URL], token: String) async throws -> HASocket {
    enum Attempt {
      case endpoint(HASocket?)
      case timeout
    }
    let socket = await withTaskGroup(of: Attempt.self, returning: HASocket?.self) { group in
      for endpoint in endpoints {
        group.addTask { @MainActor [home] in
          let socket = try? await home.authorizedSocket(at: endpoint, token: token)
          if Task.isCancelled {
            socket?.close()
            return .endpoint(nil)
          }
          return .endpoint(socket)
        }
      }
      group.addTask {
        try? await Task.sleep(nanoseconds: 8_000_000_000)
        return .timeout
      }
      var pending = endpoints.count
      while let result = await group.next() {
        switch result {
        case .endpoint(.some(let socket)):
          group.cancelAll()
          // Close any other authenticated socket whose result was already queued.
          for await other in group {
            if case .endpoint(let loser) = other { loser?.close() }
          }
          return socket
        case .endpoint(nil):
          pending -= 1
          if pending > 0 { continue }
        case .timeout:
          break
        }
        group.cancelAll()
        for await other in group {
          if case .endpoint(let loser) = other { loser?.close() }
        }
        break
      }
      return nil
    }
    if Task.isCancelled {
      socket?.close()
      throw CancellationError()
    }
    guard let socket else { throw ProtectFastError("Home Assistant WebSocket is unreachable") }
    return socket
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

  func toggleMute() -> Bool {
    muted.toggle()
    updateMicrophone()
    return muted
  }

  func close() {
    connectionTask?.cancel()
    snapshotTask?.cancel()
    socket?.close()
    connection?.close()
    microphone?.isEnabled = false
    connection = nil
    socket = nil
    didChange = nil
    didFail = nil
    didNegotiate = nil
  }

  func renderedFirstFrame() {
    guard !firstFrameLogged else { return }
    firstFrameLogged = true
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
      switch newState {
      case .connected, .completed:
        self.status = "Live"
      case .failed:
        self.status = "Camera connection failed"
        self.didFail?(ProtectFastError(self.status))
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
  private let endCall: () -> Void
  private let video = RTCMTLVideoView()
  private let snapshot = UIImageView()
  private let statusLabel = UILabel()
  private let muteButton = UIButton(type: .system)
  private var track: RTCVideoTrack?

  init(call: CameraCall, endCall: @escaping () -> Void) {
    self.call = call
    self.endCall = endCall
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .fullScreen
  }

  required init?(coder: NSCoder) { nil }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .black
    video.videoContentMode = .scaleAspectFit
    video.delegate = self
    snapshot.contentMode = .scaleAspectFit
    let title = UILabel()
    title.text = call.bell.name
    title.font = .preferredFont(forTextStyle: .title2)
    title.textColor = .white
    title.textAlignment = .center
    title.adjustsFontForContentSizeCategory = true
    statusLabel.textAlignment = .center
    statusLabel.textColor = .white
    statusLabel.numberOfLines = 0

    muteButton.setTitle("Mute", for: .normal)
    muteButton.addAction(UIAction { [weak self] _ in
      guard let self else { return }
      muteButton.setTitle(call.toggleMute() ? "Unmute" : "Mute", for: .normal)
    }, for: .touchUpInside)
    var endConfig = UIButton.Configuration.filled()
    endConfig.title = "End call"
    endConfig.baseBackgroundColor = .systemRed
    let endButton = UIButton(configuration: endConfig, primaryAction: UIAction { [weak self] _ in
      self?.endCall()
    })
    let buttons = UIStackView(arrangedSubviews: [muteButton, endButton])
    buttons.spacing = 24
    let header = UIStackView(arrangedSubviews: [title, statusLabel])
    header.axis = .vertical
    header.spacing = 4
    let preview = UIView()
    for child in [video, snapshot] {
      preview.addSubview(child)
      child.translatesAutoresizingMaskIntoConstraints = false
      child.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
      NSLayoutConstraint.activate([
        child.leadingAnchor.constraint(equalTo: preview.leadingAnchor),
        child.trailingAnchor.constraint(equalTo: preview.trailingAnchor),
        child.topAnchor.constraint(equalTo: preview.topAnchor),
        child.bottomAnchor.constraint(equalTo: preview.bottomAnchor),
      ])
    }
    let content = UIStackView(arrangedSubviews: [header, preview, buttons])
    content.axis = .vertical
    content.spacing = 12
    view.addSubview(content)
    content.translatesAutoresizingMaskIntoConstraints = false
    let guide = view.safeAreaLayoutGuide
    NSLayoutConstraint.activate([
      content.topAnchor.constraint(equalTo: guide.topAnchor, constant: 12),
      content.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -12),
      content.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 16),
      content.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -16),
    ])
    call.didChange = { [weak self] in self?.update() }
    update()
  }

  func detach() {
    track?.remove(video)
    track = nil
  }

  private func update() {
    statusLabel.text = call.status
    muteButton.isHidden = !call.canMute
    snapshot.image = call.snapshot
    if track !== call.video {
      track?.remove(video)
      track = call.video
      track?.add(video)
    }
  }

  nonisolated func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) {
    guard size.width > 0, size.height > 0 else { return }
    Task { @MainActor [weak self] in
      self?.snapshot.isHidden = true
      self?.call.renderedFirstFrame()
    }
  }
}
