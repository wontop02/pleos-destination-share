import AVFoundation
import Foundation
import RoadCore

/// Foreground capture/manual sound and system-activated PTT sound have separate entry points.
@MainActor final class NativeAudio: NSObject, AVAudioRecorderDelegate, AVAudioPlayerDelegate {
    var mayUseForegroundSession: () -> Bool = { false }
    var onRecorded: (Result<Data, Error>) -> Void = { _ in }
    var onPlaybackEnd: (Bool) -> Void = { _ in }
    var onInterruption: () -> Void = {}
    private var recorder: AVAudioRecorder?, player: AVAudioPlayer?, file: URL?
    private var discard = false, ownsForegroundSession = false
    private var observers: [NSObjectProtocol] = []
    var recordingTime: TimeInterval { recorder?.currentTime ?? 0 }
    var isRecording: Bool { recorder != nil }
    override init() {
        super.init()
        for notification in [AVAudioSession.interruptionNotification, AVAudioSession.mediaServicesWereResetNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: notification, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in self?.cancelRecording(); self?.stopPlayback(); self?.onInterruption() } })
        }
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            guard (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
            Task { @MainActor in self?.cancelRecording(); self?.stopPlayback(); self?.onInterruption() }
        })
    }
    func prepareForPTT() throws {
        guard mayUseForegroundSession() else { throw RoadError.invalid("자동 수신을 먼저 종료하고 채널 탈퇴를 확인해 주세요.") }
        cancelRecording(); stopPlayback(); deactivateForeground()
        try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
        // Activation belongs to PTChannelManager; never call setActive here.
    }
    func beginRecording() async throws {
        guard mayUseForegroundSession() else { throw RoadError.invalid("열린 화면에서 자동 수신을 종료한 뒤 녹음해 주세요.") }
        let allowed = await withCheckedContinuation { continuation in AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) } }
        try Task.checkCancellation()
        guard allowed else { throw RoadError.invalid("마이크 권한이 거절되어 녹음하지 않았어요.") }
        guard mayUseForegroundSession() else { throw CancellationError() }
        stopPlayback()
        let session = AVAudioSession.sharedInstance(); try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth]); try session.setActive(true); ownsForegroundSession = true
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).wav")
        do {
            let record = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 16000.0, AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false])
            recorder = record; file = url; discard = false; record.delegate = self
            guard record.prepareToRecord(), record.record(forDuration: 15) else { throw RoadError.invalid("마이크 녹음을 시작하지 못했어요.") }
        } catch { recorder = nil; try? FileManager.default.removeItem(at: url); deactivateForeground(); throw error }
    }
    func finishRecording() { recorder?.stop() }
    func cancelRecording() { discard = true; recorder?.delegate = nil; recorder?.stop(); recorder = nil; removeFile(); deactivateForeground() }
    private func removeFile() { if let file { try? FileManager.default.removeItem(at: file) }; file = nil }
    private func canonicalWAV(_ url: URL) throws -> Data {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        guard size <= 600000 else { throw RoadError.invalid("녹음 크기가 상한을 넘었어요.") }
        let input = try AVAudioFile(forReading: url, commonFormat: .pcmFormatInt16, interleaved: false)
        guard input.processingFormat.sampleRate == 16000, input.processingFormat.channelCount == 1, (16000...240000).contains(input.length), let buffer = AVAudioPCMBuffer(pcmFormat: input.processingFormat, frameCapacity: AVAudioFrameCount(input.length)) else { throw RoadError.invalid("원음은 1–15초 PCM16 mono 16kHz여야 해요.") }
        try input.read(into: buffer)
        guard buffer.frameLength == input.length, let channel = buffer.int16ChannelData?[0] else { throw RoadError.invalid("원음 표본을 읽지 못했어요.") }
        return try WAV.encode(Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength))))
    }
    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        guard self.recorder === recorder else { return }; self.recorder = nil
        defer { removeFile(); deactivateForeground() }
        guard !discard, mayUseForegroundSession(), let file else { return }
        do { guard flag else { throw RoadError.invalid("녹음을 완료하지 못했어요.") }; onRecorded(.success(try canonicalWAV(file))) } catch { onRecorded(.failure(error)) }
    }
    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) { cancelRecording(); onRecorded(.failure(RoadError.invalid("원음 녹음이 중단됐어요."))) }
    func playManually(_ bytes: Data) throws {
        guard mayUseForegroundSession() else { throw RoadError.invalid("자동 수신 채널 탈퇴를 확인한 뒤 열린 화면에서 재생해 주세요.") }
        stopPlayback(); let session = AVAudioSession.sharedInstance(); try session.setCategory(.playback, mode: .spokenAudio); try session.setActive(true); ownsForegroundSession = true
        do { try startPlayer(bytes) } catch { deactivateForeground(); throw error }
    }
    func playSystemActivatedPTT(_ bytes: Data) throws { stopPlayback(); try startPlayer(bytes) }
    private func startPlayer(_ bytes: Data) throws {
        _ = try WAV.decode(bytes); let sound = try AVAudioPlayer(data: bytes); player = sound; sound.delegate = self
        guard sound.prepareToPlay(), sound.play() else { player = nil; throw RoadError.invalid("원음 재생이 허용되지 않았어요.") }
    }
    func stopPlayback() { player?.delegate = nil; player?.stop(); player = nil; deactivateForeground() }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { guard self.player === player else { return }; self.player = nil; deactivateForeground(); onPlaybackEnd(flag) }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) { stopPlayback(); onPlaybackEnd(false) }
    private func deactivateForeground() {
        guard ownsForegroundSession else { return }; ownsForegroundSession = false
        // This marker is set only by explicit foreground activation, never by PTT.
        // Return our own session even after sceneActive becomes false.
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
