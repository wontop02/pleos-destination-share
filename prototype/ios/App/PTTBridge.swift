import Foundation
import PushToTalk
import AVFoundation
import UIKit
import RoadCore

enum PTTEvent: Sendable {
    case ready, joined(UUID), left(UUID), token(Data), activated, deactivated, pushAccepted
    case failedJoin(UUID), failedLeave(UUID), unavailable
}
/// Delegate methods may run off main. Cached descriptors and gate reads never wait on main/network/storage.
final class PTTBridge: NSObject, PTChannelManagerDelegate, PTChannelRestorationDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var manager: PTChannelManager?, cached: (UUID, PTChannelDescriptor)?
    private let image = UIImage(systemName: "car.2.fill")
    let gate: ReceiveGate
    let onEvent: @Sendable (PTTEvent) -> Void
    init(gate: ReceiveGate, cached: CachedChannel?, onEvent: @escaping @Sendable (PTTEvent) -> Void) {
        self.gate = gate; self.onEvent = onEvent; super.init()
        if let cached { self.cached = (cached.id, PTChannelDescriptor(name: cached.name, image: image)) }
        // Required at application launch, including a system PTT relaunch.
        PTChannelManager.channelManager(delegate: self, restorationDelegate: self) { [weak self] manager, _ in
            guard let self, let manager else { self?.onEvent(.unavailable); return }
            self.lock.lock(); self.manager = manager; self.lock.unlock(); self.onEvent(.ready)
            if let channel = manager.activeChannelUUID { self.listenOnly(manager, channel: channel) }
        }
    }
    var activeChannel: UUID? { lock.lock(); defer { lock.unlock() }; return manager?.activeChannelUUID }
    func cacheDescriptor(id: UUID, name: String) { let descriptor = PTChannelDescriptor(name: name, image: image); lock.lock(); cached = (id, descriptor); lock.unlock() }
    func join(_ id: UUID, name: String) {
        cacheDescriptor(id: id, name: name); lock.lock(); let manager = manager, descriptor = cached!.1; lock.unlock()
        guard let manager else { onEvent(.unavailable); return }; manager.requestJoinChannel(channelUUID: id, descriptor: descriptor)
    }
    func leave(_ id: UUID) { gate.invalidate(channelUUID: id); lock.lock(); let manager = manager; lock.unlock(); manager?.setActiveRemoteParticipant(nil, channelUUID: id, completionHandler: nil); manager?.leaveChannel(channelUUID: id) }
    func finishRemote(_ id: UUID) { lock.lock(); let manager = manager; lock.unlock(); manager?.setActiveRemoteParticipant(nil, channelUUID: id, completionHandler: nil) }
    func remote(_ name: String, channel: UUID) { lock.lock(); let manager = manager; lock.unlock(); manager?.setActiveRemoteParticipant(PTParticipant(name: name, image: nil), channelUUID: channel, completionHandler: nil) }
    func channelDescriptor(restoredChannelUUID channelUUID: UUID) -> PTChannelDescriptor {
        lock.lock(); defer { lock.unlock() }
        return cached?.0 == channelUUID ? cached!.1 : PTChannelDescriptor(name: "종료된 공유방", image: image)
    }
    private func listenOnly(_ manager: PTChannelManager, channel: UUID) {
        manager.setTransmissionMode(.listenOnly, channelUUID: channel) { [weak self] error in
            guard let self else { return }; if error != nil { self.gate.invalidate(channelUUID: channel); manager.leaveChannel(channelUUID: channel); self.onEvent(.failedJoin(channel)); return }
            self.onEvent(.joined(channel))
        }
    }
    func channelManager(_ channelManager: PTChannelManager, didJoinChannel channelUUID: UUID, reason: PTChannelJoinReason) { listenOnly(channelManager, channel: channelUUID) }
    func channelManager(_ channelManager: PTChannelManager, didLeaveChannel channelUUID: UUID, reason: PTChannelLeaveReason) { gate.invalidate(channelUUID: channelUUID); onEvent(.left(channelUUID)) }
    func channelManager(_ channelManager: PTChannelManager, failedToJoinChannel channelUUID: UUID, error: Error) { gate.invalidate(channelUUID: channelUUID); onEvent(.failedJoin(channelUUID)) }
    func channelManager(_ channelManager: PTChannelManager, failedToLeaveChannel channelUUID: UUID, error: Error) { onEvent(.failedLeave(channelUUID)) }
    func channelManager(_ channelManager: PTChannelManager, receivedEphemeralPushToken pushToken: Data) { onEvent(.token(pushToken)) }
    func channelManager(_ channelManager: PTChannelManager, didActivate audioSession: AVAudioSession) { gate.setAudioActive(true); onEvent(.activated) }
    func channelManager(_ channelManager: PTChannelManager, didDeactivate audioSession: AVAudioSession) { gate.setAudioActive(false); onEvent(.deactivated) }
    func channelManager(_ channelManager: PTChannelManager, channelUUID: UUID, didBeginTransmittingFrom source: PTChannelTransmitRequestSource) { channelManager.stopTransmitting(channelUUID: channelUUID) }
    func channelManager(_ channelManager: PTChannelManager, channelUUID: UUID, didEndTransmittingFrom source: PTChannelTransmitRequestSource) {}
    func channelManager(_ channelManager: PTChannelManager, failedToBeginTransmittingInChannel channelUUID: UUID, error: Error) {}
    func channelManager(_ channelManager: PTChannelManager, failedToStopTransmittingInChannel channelUUID: UUID, error: Error) { channelManager.leaveChannel(channelUUID: channelUUID) }
    func incomingPushResult(channelManager: PTChannelManager, channelUUID: UUID, pushPayload: [String: Any]) -> PTPushResult {
        // Strictly bounded synchronous parsing only. No network, await, Keychain or dispatch-to-main wait.
        guard JSONSerialization.isValidJSONObject(pushPayload), let bytes = try? JSONSerialization.data(withJSONObject: pushPayload), bytes.count <= 4096,
              let payload = try? JSONDecoder().decode(PTTPayload.self, from: bytes) else { gate.invalidate(channelUUID: channelUUID); return .leaveChannel }
        switch gate.receive(payload, channelUUID: channelUUID) {
        case .accept(let voice, _): onEvent(.pushAccepted); return .activeRemoteParticipant(PTParticipant(name: voice.sender.name, image: nil))
        case .leave: gate.invalidate(channelUUID: channelUUID); return .leaveChannel
        }
    }
}
