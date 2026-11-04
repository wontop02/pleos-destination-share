import SwiftUI
import RoadCore
import UIKit

@MainActor final class RoomStore: ObservableObject {
    @Published private(set) var session: Session?
    @Published private(set) var snapshot: Snapshot?
    @Published private(set) var destination: Destination?
    @Published private(set) var voices: [Voice] = []
    @Published var serverText = Bundle.main.object(forInfoDictionaryKey: "RoadAPIBaseURL") as? String ?? "https://localhost.invalid"
    @Published var name = ""
    @Published var invitation = ""
    @Published var destinationName = "올림픽공원"
    @Published var latitude = "37.5209436"
    @Published var longitude = "127.1230074"
    @Published private(set) var status = "공유방을 만들거나 초대 링크로 참여해 주세요."
    @Published private(set) var connection = "연결 전"
    @Published private(set) var recordingStatus = "마이크로 1–15초 녹음해 보내요."
    @Published private(set) var playbackStatus = "재생 중인 원음이 없어요."
    @Published private(set) var receiveStatus = "자동 수신 꺼짐"
    @Published private(set) var busy = false
    @Published private(set) var isRecording = false
    @Published private(set) var uploading = false
    @Published private(set) var hasRetry = false
    @Published private(set) var receiving = false
    @Published private(set) var joining = false
    @Published private(set) var channelLeaving = false
    @Published private(set) var elapsed = 0.0
    private var foreground = false, pttReady = false, pttResolved = false, joined = false
    private var requestedChannel: UUID?, pushToken: Data?, epoch = UUID(), playEpoch = UUID()
    private var cache = KeychainStore.load() ?? DeviceCache(session: nil, channel: nil)
    private let clock = ServerClock(), registrations = RegistrationCoordinator()
    private var gate: ReceiveGate!, bridge: PTTBridge!
    private let audio = NativeAudio()
    private var api: APIClient?, eventsTask: Task<Void, Never>?, actionTask: Task<Void, Never>?, registrationTask: Task<Void, Never>?, playbackTask: Task<Void, Never>?, expiryTask: Task<Void, Never>?, retryTask: Task<Void, Never>?, timerTask: Task<Void, Never>?
    private var clip: RetryClip?, revision: Int64 = -1, playingPTT = false
    private var recordingTask: Task<Void, Never>?
    private var recordingEpoch = UUID()
    private var leaseTask: Task<Void, Never>?
    private struct ChannelCleanupKey: Hashable { let epoch: UUID, channel: UUID }
    private var registrationCleanups: [ChannelCleanupKey: Task<Void, Never>] = [:]
    var canUseForegroundAudio: Bool { foreground && pttResolved && requestedChannel == nil && !channelLeaving && bridge?.activeChannel == nil && !receiving }
    private static var debugHTTP: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
    init() {
        if let saved = cache.session, let valid = try? saved.validated(debugLocalHTTP: Self.debugHTTP), let endpoint = try? Endpoint(valid.server, debugLocalHTTP: Self.debugHTTP) { session = valid; api = APIClient(endpoint: endpoint); serverText = valid.server }
        else { cache = DeviceCache(session: nil, channel: nil); KeychainStore.clear() }
        gate = ReceiveGate(clock: clock)
        if var channel = cache.channel {
            if ProcessInfo.processInfo.systemUptime >= channel.savedUptime {
                clock.update(channel.serverEstimate + Int64((ProcessInfo.processInfo.systemUptime - channel.savedUptime) * 1000))
                if let binding = channel.binding, let member = session, binding.roomID == member.roomID, binding.memberID == member.memberID,
                   binding.channelUUID == channel.id, (try? checkedID(binding.registrationID)) != nil,
                   binding.registrationExpiresAt > clock.now(), binding.registrationExpiresAt <= binding.roomExpiresAt,
                   binding.roomExpiresAt > clock.now(), channel.receivingSelected {
                    channel.binding = PTTBinding(roomID: binding.roomID, memberID: binding.memberID, channelUUID: binding.channelUUID, registrationID: binding.registrationID, registrationExpiresAt: binding.registrationExpiresAt, roomExpiresAt: binding.roomExpiresAt, epoch: epoch)
                    requestedChannel = channel.id; joining = true; receiveStatus = "이전 수신 채널을 확인하고 있어요."
                }
                else { channel.receivingSelected = false; channel.binding = nil }
            } else { channel.receivingSelected = false; channel.binding = nil }
            cache.channel = channel
        }
        bridge = PTTBridge(gate: gate, cached: cache.channel) { [weak self] event in Task { @MainActor in self?.handlePTT(event) } }
        audio.mayUseForegroundSession = { [weak self] in self?.canUseForegroundAudio == true }
        audio.onRecorded = { [weak self] result in self?.recorded(result) }
        audio.onPlaybackEnd = { [weak self] success in self?.playbackEnded(success) }
        audio.onInterruption = { [weak self] in self?.interrupted() }
        if cache.channel?.receivingSelected == true, let binding = cache.channel?.binding { scheduleLeaseExpiry(binding) }
    }
    func sceneActive(_ active: Bool) {
        foreground = active
        if active {
            if let binding = cache.channel?.binding, binding.registrationExpiresAt <= clock.now() || binding.roomExpiresAt <= clock.now() { status = "자동 수신 등록이 만료됐어요. 직접 다시 시작해 주세요."; stopReceiving() }
            connect()
        }
        else {
            cancelRecording(); eventsTask?.cancel(); eventsTask = nil
            if !uploading { discardClip() }
            if !receiving && requestedChannel == nil { stopPlayback(); playbackStatus = "화면이 숨겨져 재생을 중지했어요." }
        }
    }
    func create() { entry(join: false) }
    func joinRoom() { entry(join: true) }
    private func entry(join: Bool) {
        guard !busy, session == nil, requestedChannel == nil, !channelLeaving else { return }
        do {
            let client = APIClient(endpoint: try Endpoint(serverText, debugLocalHTTP: Self.debugHTTP)), displayName = name.trimmingCharacters(in: .whitespacesAndNewlines), inviteText = invitation
            guard !displayName.isEmpty, displayName.utf16.count <= 40 else { throw RoadError.invalid("이름을 1–40자로 입력해 주세요.") }
            busy = true
            actionTask = Task {
                defer { busy = false }
                do {
                    let reply: APIResponse<CreatedRoom>
                    if join { let invite = try client.endpoint.invitation(inviteText); reply = try await client.join(room: invite.room, invite: invite.secret, name: displayName) }
                    else { reply = try await client.create(name: displayName) }
                    try Task.checkCancellation(); let member = try reply.value.session(endpoint: client.endpoint)
                    stopTasks(cancelAction: false); session = member; api = client; epoch = UUID(); cache = DeviceCache(session: member, channel: nil); try persist(); invitation = ""
                    apply(reply.value.state, rtt: reply.roundTripMS); status = "공유방에 연결됐어요."; connect()
                } catch { status = text(error) }
            }
        } catch { status = text(error) }
    }
    private func connect() {
        guard foreground, eventsTask == nil, let member = session, let api else { return }; let captured = epoch
        eventsTask = Task {
            var wait = 1
            while !Task.isCancelled && captured == epoch && foreground {
                do {
                    connection = "연결 중"
                    for try await event in api.events(member) { guard captured == epoch, !Task.isCancelled else { return }; try consume(event); wait = 1 }
                } catch is CancellationError { return }
                catch RoadError.http(let code, _, _) where [401,403,404].contains(code) { if captured == epoch { invalidate("공유방이 종료됐거나 참여 권한이 없어요.") }; return }
                catch { connection = "재연결 중"; status = "다시 연결하고 있어요. 마지막 목적지는 유지해요." }
                do { try await Task.sleep(for: .seconds(wait)) } catch { return }; wait = min(wait * 2, 10)
            }
        }
    }
    private func consume(_ event: SSEEvent) throws {
        switch event.type {
        case "snapshot": let state = try JSONDecoder().decode(Snapshot.self, from: event.data); try state.validate(); apply(state); connection = "연결됨"
        case "destination": let update = try JSONDecoder().decode(DestinationUpdate.self, from: event.data); clock.update(update.serverTime); if update.revision >= revision { revision = update.revision; destination = update.destination; status = "새 목적지를 받았어요." }
        case "voice": struct Event: Decodable { let message: Voice, serverTime: Int64 }; let value = try JSONDecoder().decode(Event.self, from: event.data); try value.message.validate(); clock.update(value.serverTime); addVoice(value.message)
        case "closed": invalidate("공유방이 종료됐거나 만료됐어요.")
        default: break
        }
    }
    private func apply(_ value: Snapshot, rtt: Int64 = 0) {
        snapshot = value; clock.update(value.serverTime, roundTripMS: rtt)
        if value.revision >= revision { revision = value.revision; destination = value.destination }
        for voice in value.voiceMessages { addVoice(voice) }
        let captured = epoch; expiryTask?.cancel()
        expiryTask = Task { let delay = max(0, value.room.expiresAt - clock.now()); do { try await Task.sleep(for: .milliseconds(delay)) } catch { return }; if captured == epoch { invalidate("공유방이 만료되어 수신과 원음 접근을 종료했어요.") } }
    }
    private func addVoice(_ value: Voice) { if !voices.contains(where: { $0.id == value.id }) { voices.append(value); voices.sort { $0.sequence < $1.sequence } } }
    func sendDestination() {
        guard !busy, let member = session, let api, let lat = Double(latitude), let lng = Double(longitude) else { status = "위도·경도를 확인해 주세요."; return }
        let captured = epoch; busy = true
        actionTask = Task { defer { busy = false }; do { let result = try await api.destination(member, name: destinationName, latitude: lat, longitude: lng); guard captured == epoch else { return }; clock.update(result.value.serverTime, roundTripMS: result.roundTripMS); if result.value.revision >= revision { revision = result.value.revision; destination = result.value.destination }; status = "목적지를 전달했어요." } catch { if captured == epoch { status = text(error) } } }
    }
    func copyInvitation() { guard let value = session?.invitation else { return }; UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: value]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(300)]); status = "초대 링크를 복사했어요." }
    func requestMap() {
        guard let destination else { return }
        guard let url = NaverNavigation.url(destination, bundleID: Bundle.main.bundleIdentifier ?? "kr.together.road.ios") else { status = "이 좌표는 네이버 지도 경로 요청 범위 밖이에요."; return }
        guard UIApplication.shared.canOpenURL(url) else { status = "네이버 지도 앱이 없거나 이 경로 요청을 처리할 수 없어요."; return }
        UIApplication.shared.open(url) { [weak self] accepted in Task { @MainActor in self?.status = accepted ? "네이버 지도에 경로를 요청했어요. 실제 길 안내 시작은 지도 앱에서 확인해 주세요." : "네이버 지도 경로 요청을 열지 못했어요." } }
    }
    func beginRecording() {
        guard canUseForegroundAudio, !isRecording, !uploading, clip == nil else { status = "자동 수신을 종료하고 열린 화면에서 녹음해 주세요."; return }
        let capturedRecording = UUID(); recordingEpoch = capturedRecording
        isRecording = true; recordingStatus = "마이크 권한을 확인하고 있어요. 취소할 수 있어요."
        recordingTask = Task {
            do { try await audio.beginRecording(); try Task.checkCancellation(); guard capturedRecording == recordingEpoch else { return }; guard canUseForegroundAudio else { audio.cancelRecording(); isRecording = false; return }; elapsed = 0; recordingStatus = "녹음 중 · 중지하고 보내거나 취소해 주세요."
                timerTask = Task { while !Task.isCancelled && audio.isRecording { elapsed = min(15, audio.recordingTime); try? await Task.sleep(for: .milliseconds(200)) } }
            } catch { if capturedRecording == recordingEpoch { isRecording = false; recordingStatus = text(error) } }
        }
    }
    func finishRecording() { audio.finishRecording(); recordingStatus = "녹음을 마무리하고 있어요." }
    func cancelRecording() { recordingEpoch = UUID(); recordingTask?.cancel(); recordingTask = nil; audio.cancelRecording(); isRecording = false; timerTask?.cancel(); elapsed = 0; recordingStatus = "녹음을 취소했어요. 원음을 보내지 않았어요." }
    private func recorded(_ result: Result<Data, Error>) {
        isRecording = false; timerTask?.cancel()
        guard canUseForegroundAudio else { return }
        switch result { case .failure(let error): recordingStatus = text(error)
        case .success(let bytes): clip = RetryClip(bytes: bytes); hasRetry = true; elapsed = Double((try? WAV.decode(bytes).sampleCount) ?? 0)/16000; let captured = epoch
            retryTask?.cancel(); retryTask = Task { try? await Task.sleep(for: .seconds(30)); guard !Task.isCancelled, captured == epoch, !uploading else { return }; discardClip(); recordingStatus = "30초가 지나 전송하지 못한 원음을 지웠어요." }; sendClip()
        }
    }
    func discardClip() { clip = nil; hasRetry = false; retryTask?.cancel() }
    func sendClip() {
        guard !uploading, let clip, let member = session, let api else { return }
        guard foreground, clip.fresh(uptime: ProcessInfo.processInfo.systemUptime) else { discardClip(); recordingStatus = "오래된 녹음은 보내지 않았어요. 다시 녹음해 주세요."; return }
        uploading = true; recordingStatus = "원음을 전송하고 있어요."; let captured = epoch
        actionTask = Task {
            do { let reply = try await api.upload(member, clip: clip); guard captured == epoch else { return }; uploading = false; clock.update(reply.value.serverTime, roundTripMS: reply.roundTripMS); addVoice(reply.value.message); discardClip(); recordingStatus = reply.value.duplicate ? "이미 접수된 원음이에요. 중복으로 전달하지 않았어요." : "서버가 원음을 접수했어요. 상대 재생 여부는 상대 화면에서 확인해 주세요." }
            catch { guard captured == epoch else { return }; uploading = false; if !foreground || !clip.fresh(uptime: ProcessInfo.processInfo.systemUptime) { discardClip() }; recordingStatus = "전송 실패 · \(text(error))" }
        }
    }
    func playManually(_ voice: Voice) {
        guard canUseForegroundAudio, !isRecording, let member = session, let api else { status = "자동 수신 채널 탈퇴를 확인한 뒤 열린 화면에서 재생해 주세요."; return }
        stopPlayback(); let playback = playEpoch, captured = epoch; playbackStatus = "원음을 불러오고 있어요."
        playbackTask = Task {
            var startedPlayback = false
            defer { if !startedPlayback, playback == playEpoch { playbackTask = nil } }
            do { let bytes = try await api.download(member, voice: voice); guard captured == epoch, playback == playEpoch, canUseForegroundAudio, snapshot?.room.expiresAt ?? 0 > clock.now() else { return }; try audio.playManually(bytes); startedPlayback = true; playbackStatus = "\(voice.sender.name)의 원음을 재생하고 있어요." }
            catch { if playback == playEpoch { playbackStatus = "재생 실패 · \(text(error))" } }
        }
    }
    func stopPlayback() { let wasPTT = playingPTT; playEpoch = UUID(); playbackTask?.cancel(); playbackTask = nil; audio.stopPlayback(); playingPTT = false; gate.cancelPending(); if wasPTT { finishRemote() }; playbackStatus = "재생을 중지했어요." }
    private func playbackEnded(_ success: Bool) { playbackTask = nil; playbackStatus = success ? "원음 재생을 마쳤어요." : "원음 재생이 중단됐어요."; let wasPTT = playingPTT; playingPTT = false; if wasPTT { if success { drainPTT() } else { finishRemote() } } }
    private func interrupted() { stopPlayback(); gate.setAudioActive(false); finishRemote(); status = "오디오가 중단되어 재생을 중지했어요." }
    func startReceiving() {
        guard foreground, pttReady, requestedChannel == nil, !channelLeaving, !busy, !isRecording, !uploading, let member = session, let api else { status = "열린 공유방에서 자동 수신 준비 상태를 확인해 주세요."; return }
        let captured = epoch; busy = true
        actionTask = Task { defer { busy = false }; do {
            let state = try await api.ptt(member); guard captured == epoch, foreground, !Task.isCancelled else { return }
            guard state.configured else { receiveStatus = "자동 수신 서버 설정이 필요해요. 수신을 시작하지 않았어요."; return }
            stopPlayback()
            try audio.prepareForPTT(); let channel = UUID(); requestedChannel = channel; joining = true; joined = false; receiveStatus = "수신 채널에 참여하고 있어요."
            cache.channel = CachedChannel(id: channel, name: "함께 가는 길", receivingSelected: false, binding: nil, seenIDs: [], lastSequence: -1, serverEstimate: clock.now(), savedUptime: ProcessInfo.processInfo.systemUptime); try persist(); bridge.join(channel, name: "함께 가는 길")
        } catch { if captured == epoch { receiveStatus = text(error) } } }
    }
    func stopReceiving() {
        let channel = requestedChannel ?? bridge.activeChannel
        receiving = false; joining = false; joined = false; gate.invalidate(); stopPlayback(); leaseTask?.cancel(); pushToken = nil
        if let channel { retireChannelRegistration(channel) } else { registrationTask?.cancel(); registrationTask = nil }
        cache.channel?.receivingSelected = false; cache.channel?.binding = nil; try? persist()
        guard let channel else { requestedChannel = nil; channelLeaving = false; receiveStatus = "자동 수신 꺼짐"; return }
        requestedChannel = channel; channelLeaving = true; receiveStatus = "수신 종료와 채널 탈퇴를 확인하고 있어요."; bridge.leave(channel)
    }
    private func handlePTT(_ event: PTTEvent) {
        switch event {
        case .ready:
            pttReady = true; pttResolved = true
            if let active = bridge.activeChannel { if active != requestedChannel { channelLeaving = true; requestedChannel = active; bridge.leave(active) } }
            else if let pending = requestedChannel { confirmLeft(pending); receiveStatus = "이전 수신 채널이 복원되지 않았어요. 직접 다시 시작해 주세요." }
        case .unavailable: pttReady = false; pttResolved = true; receiveStatus = "자동 수신을 준비하지 못했어요. 수신 채널 상태를 확인해 주세요."
        case .joined(let channel):
            guard requestedChannel == channel, !channelLeaving, session != nil else { bridge.leave(channel); return }; joined = true
            if let saved = cache.channel, saved.receivingSelected, let binding = saved.binding, binding.roomExpiresAt > clock.now(), binding.registrationExpiresAt > clock.now() { gate.configure(binding, joined: true, seenIDs: saved.seenIDs, lastSequence: saved.lastSequence); receiving = true; receiveStatus = "이전 수신 채널을 복원했어요." }
            registerIfPossible()
        case .token(let token): pushToken = token; registerIfPossible()
        case .left(let channel): confirmLeft(channel)
        case .failedJoin(let channel):
            guard requestedChannel == channel else { return }; retireChannelRegistration(channel); receiving = false; joining = false; joined = false; gate.invalidate(); receiveStatus = "수신 채널 참여에 실패했어요."; cache.channel = nil
            if bridge.activeChannel == channel { channelLeaving = true } else { requestedChannel = nil; channelLeaving = false }; try? persist()
        case .failedLeave(let channel): guard requestedChannel == channel else { return }; if bridge.activeChannel == nil { confirmLeft(channel) } else { channelLeaving = true; receiveStatus = "채널 탈퇴를 확인하지 못했어요. 수신 종료를 다시 눌러 주세요." }
        case .activated: drainPTT()
        case .deactivated: stopPlayback(); finishRemote()
        case .pushAccepted: drainPTT()
        }
    }
    private func confirmLeft(_ channel: UUID) {
        guard requestedChannel == channel else { return }
        retireChannelRegistration(channel)
        requestedChannel = nil; joining = false; channelLeaving = false; joined = false; receiving = false; pushToken = nil
        gate.invalidate(); stopPlayback(); leaseTask?.cancel(); cache.channel = nil; try? persist(); receiveStatus = "자동 수신 꺼짐 · 채널 탈퇴 확인"
    }
    private func retireChannelRegistration(_ channel: UUID) {
        registrationTask?.cancel(); registrationTask = nil
        // Explicit stop and the later system confirmation share the same retirement task.
        guard !registrationCleanups.keys.contains(where: { $0.channel == channel }) else { return }
        guard let member = session, let api else { status = "채널은 종료됐지만 서버 등록 해제를 확인할 공유방 인증이 없어요."; return }
        let captured = epoch, key = ChannelCleanupKey(epoch: epoch, channel: channel)
        registrationCleanups[key] = Task {
            do { try await registrations.leave(client: api, member: member, channel: channel, scopeID: captured) }
            catch { if epoch == captured { status = "종료한 수신의 서버 등록 해제를 확인하지 못했어요. 연결과 공유방 권한을 확인해 주세요." } }
        }
    }
    private func registerIfPossible() {
        guard joined, !channelLeaving, let channel = requestedChannel, let token = pushToken, let member = session, let api else { return }
        let captured = epoch; registrationTask?.cancel()
        registrationTask = Task {
            do {
                guard let state = try await registrations.join(client: api, member: member, channel: channel, token: token, scopeID: captured), captured == epoch, requestedChannel == channel, joined, !channelLeaving, !Task.isCancelled else { return }
                clock.update(state.serverTime)
                guard state.configured, state.status == "registered", let registration = state.registration, registration.channelUUID == channel, registration.expiresAt > clock.now() else { status = "자동 수신 서버 설정이 필요해요. 수신을 시작하지 않았어요."; stopReceiving(); return }
                _ = try checkedID(registration.id)
                let roomExpires = snapshot?.room.expiresAt ?? cache.channel?.binding?.roomExpiresAt ?? 0
                guard roomExpires > clock.now(), registration.expiresAt <= roomExpires else { stopReceiving(); return }
                let binding = PTTBinding(roomID: member.roomID, memberID: member.memberID, channelUUID: channel, registrationID: registration.id, registrationExpiresAt: registration.expiresAt, roomExpiresAt: roomExpires, epoch: captured)
                let previous = gate.snapshot(); gate.configure(binding, joined: true, seenIDs: previous.seen, lastSequence: previous.lastSequence)
                cache.channel?.binding = binding; cache.channel?.receivingSelected = true; try persist(); scheduleLeaseExpiry(binding); receiving = true; joining = false; receiveStatus = "자동 수신 켜짐 · 새 원음을 기다려요."
            } catch { if captured == epoch, !Task.isCancelled { status = "수신 등록을 확인하지 못했어요."; stopReceiving() } }
        }
    }
    private func drainPTT() {
        guard !playingPTT, playbackTask == nil, gate.audioIsActive else { return }
        guard let member = session, let api, let (voice, binding) = gate.next(), binding.epoch == epoch else { finishRemote(); return }
        let playback = UUID(); playEpoch = playback; playingPTT = true; playbackStatus = "새 원음을 불러오고 있어요."
        playbackTask = Task {
            do { try persist(); let bytes = try await api.download(member, voice: voice)
                guard playEpoch == playback, epoch == binding.epoch, !Task.isCancelled, gate.mayStart(voice, binding: binding) else { if playEpoch == playback { playingPTT = false; playbackTask = nil; drainPTT() }; return }
                try persist(); bridge.remote(voice.sender.name, channel: binding.channelUUID); try audio.playSystemActivatedPTT(bytes); addVoice(voice); playbackStatus = "\(voice.sender.name)의 원음을 재생하고 있어요."
            } catch { if playEpoch == playback { playingPTT = false; playbackTask = nil; gate.cancelPending(); finishRemote(); playbackStatus = "새 원음을 재생하지 못했어요." } }
        }
    }
    private func finishRemote() { if let channel = requestedChannel { bridge.finishRemote(channel) } }
    private func scheduleLeaseExpiry(_ binding: PTTBinding) {
        leaseTask?.cancel()
        leaseTask = Task {
            do { try await Task.sleep(for: .milliseconds(max(0, min(binding.registrationExpiresAt, binding.roomExpiresAt) - clock.now()))) } catch { return }
            guard epoch == binding.epoch, cache.channel?.binding?.registrationID == binding.registrationID else { return }
            status = "자동 수신 등록이 만료됐어요. 직접 다시 시작해 주세요."; stopReceiving()
        }
    }
    private func persist() throws { let state = gate?.snapshot(); if state?.binding != nil { cache.channel?.binding = state?.binding; cache.channel?.seenIDs = state?.seen ?? []; cache.channel?.lastSequence = state?.lastSequence ?? -1 }; cache.session = session; cache.channel?.serverEstimate = clock.now(); cache.channel?.savedUptime = ProcessInfo.processInfo.systemUptime; try KeychainStore.save(cache) }
    func leaveRoom() { guard let member = session, let api, !busy else { return }; stopReceiving(); let captured = epoch; busy = true; actionTask = Task { defer { busy = false }; do { try await api.leave(member); if captured == epoch { invalidate("공유방 연결을 종료했어요.") } } catch { if captured == epoch { status = text(error) } } } }
    private func invalidate(_ text: String) { stopReceiving(); stopTasks(); epoch = UUID(); session = nil; api = nil; snapshot = nil; destination = nil; voices = []; revision = -1; cache.session = nil; try? persist(); connection = "연결 종료"; status = text }
    private func stopTasks(cancelAction: Bool = true) { if cancelAction { actionTask?.cancel() }; recordingEpoch = UUID(); recordingTask?.cancel(); eventsTask?.cancel(); eventsTask = nil; playbackTask?.cancel(); playbackTask = nil; expiryTask?.cancel(); leaseTask?.cancel(); registrationTask?.cancel(); retryTask?.cancel(); timerTask?.cancel(); clip = nil; hasRetry = false; audio.cancelRecording(); isRecording = false; uploading = false }
    private func text(_ error: Error) -> String { if let error = error as? RoadError { return error.localizedDescription }; if error is CancellationError { return "작업을 취소했어요." }; return "연결이나 오디오 처리가 중단됐어요. 직접 다시 시도해 주세요." }
}
