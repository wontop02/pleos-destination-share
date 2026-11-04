import Foundation

private final class NoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) { completionHandler(nil) }
}
public struct APIResponse<Value: Sendable>: Sendable { public let value: Value, roundTripMS: Int64 }
public protocol RegistrationAPI: Sendable {
    func ptt(_ member: Session) async throws -> PTTState
    func register(_ member: Session, channel: UUID, token: Data, expectedRevision: Int64) async throws -> PTTState
    func unregister(_ member: Session, channel: UUID, expectedRevision: Int64) async throws -> PTTState
}
private struct APIErrorBody: Decodable { let code: String?, error: String? }
public struct APIClient: Sendable, RegistrationAPI {
    public let endpoint: Endpoint
    public init(endpoint: Endpoint) { self.endpoint = endpoint }
    private func connection(streaming: Bool = false) -> URLSession {
        let config = URLSessionConfiguration.ephemeral; config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCache = nil; config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = streaming ? 6 * 60 * 60 + 60 : 20
        return URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    private func request(_ method: String, _ path: String, token: String?, body: Data? = nil, clientID: String? = nil) throws -> URLRequest {
        var request = URLRequest(url: try endpoint.api(path)); request.httpMethod = method; request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { try Session.checkSecret(token); request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if body != nil { request.setValue(clientID == nil ? "application/json" : "audio/wav", forHTTPHeaderField: "Content-Type") }
        if let clientID { request.setValue(clientID, forHTTPHeaderField: "X-Client-Message-Id") }; return request
    }
    private func bytes(_ request: URLRequest, limit: Int) async throws -> (Data, Int64) {
        let session = connection(); defer { session.invalidateAndCancel() }
        let start = ProcessInfo.processInfo.systemUptime
        let (stream, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw RoadError.invalid("HTTP 응답이 필요해요.") }
        let allowed = (200...299).contains(http.statusCode) ? limit : 8192
        guard response.expectedContentLength <= allowed || response.expectedContentLength == -1 else { throw RoadError.invalid("응답 크기가 상한을 넘었어요.") }
        var body = Data()
        for try await byte in stream { try Task.checkCancellation(); guard body.count < allowed else { throw RoadError.invalid("응답 크기가 상한을 넘었어요.") }; body.append(byte) }
        guard (200...299).contains(http.statusCode) else { let decoded = try? JSONDecoder().decode(APIErrorBody.self, from: body); throw RoadError.http(http.statusCode, decoded?.code ?? "http_error", decoded?.error ?? "서버 요청에 실패했어요.") }
        return (body, Int64((ProcessInfo.processInfo.systemUptime - start) * 1000))
    }
    public func call<Value: Decodable & Sendable>(_ type: Value.Type, method: String, path: String, token: String?, body: Data? = nil, clientID: String? = nil) async throws -> APIResponse<Value> {
        let (data, rtt) = try await bytes(request(method, path, token: token, body: body, clientID: clientID), limit: 262144)
        return APIResponse(value: try JSONDecoder().decode(type, from: data), roundTripMS: rtt)
    }
    public func create(name: String) async throws -> APIResponse<CreatedRoom> { try await call(CreatedRoom.self, method: "POST", path: "/api/rooms", token: nil, body: JSONEncoder().encode(["name": name])) }
    public func join(room: String, invite: String, name: String) async throws -> APIResponse<CreatedRoom> { try await call(CreatedRoom.self, method: "POST", path: "/api/rooms/\(checkedID(room))/members", token: invite, body: JSONEncoder().encode(["name": name])) }
    public func snapshot(_ member: Session) async throws -> APIResponse<Snapshot> { let reply = try await call(Snapshot.self, method: "GET", path: "/api/rooms/\(member.roomID)", token: member.token); try reply.value.validate(); return reply }
    public func destination(_ member: Session, name: String, latitude: Double, longitude: Double) async throws -> APIResponse<DestinationUpdate> {
        struct Body: Encodable { let name: String, latitude: Double, longitude: Double }
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, name.utf16.count <= 100, latitude.isFinite, longitude.isFinite, (-90...90).contains(latitude), (-180...180).contains(longitude) else { throw RoadError.invalid("장소명과 올바른 위도·경도를 입력해 주세요.") }
        return try await call(DestinationUpdate.self, method: "POST", path: "/api/rooms/\(member.roomID)/destination", token: member.token, body: JSONEncoder().encode(Body(name: name, latitude: latitude, longitude: longitude)))
    }
    public func upload(_ member: Session, clip: RetryClip) async throws -> APIResponse<UploadedVoice> { _ = try WAV.decode(clip.bytes); let result = try await call(UploadedVoice.self, method: "POST", path: "/api/rooms/\(member.roomID)/voice", token: member.token, body: clip.bytes, clientID: clip.clientMessageID); try result.value.message.validate(); return result }
    public func download(_ member: Session, voice: Voice) async throws -> Data {
        try voice.validate(); let (data, _) = try await bytes(request("GET", "/api/rooms/\(member.roomID)/voice/\(checkedID(voice.id))/audio", token: member.token), limit: WAV.maximumBytes)
        guard data.count == voice.byteLength, try WAV.decode(data).sampleCount == voice.sampleCount else { throw RoadError.invalid("원음 바이트가 수신 정보와 일치하지 않아요.") }; return data
    }
    public func leave(_ member: Session) async throws {
        struct Reply: Decodable, Sendable { let closed: Bool?, left: Bool? }
        let _: APIResponse<Reply> = try await call(Reply.self, method: "DELETE", path: "/api/rooms/\(member.roomID)" + (member.role == "host" ? "" : "/members/me"), token: member.token)
    }
    public func ptt(_ member: Session) async throws -> PTTState { try await call(PTTState.self, method: "GET", path: "/api/rooms/\(member.roomID)/ptt", token: member.token).value }
    public func register(_ member: Session, channel: UUID, token: Data, expectedRevision: Int64) async throws -> PTTState {
        struct Body: Encodable { let channelUUID: String, token: String, expectedRevision: Int64 }
        guard (1...512).contains(token.count) else { throw RoadError.invalid("임시 수신 토큰 길이가 올바르지 않아요.") }
        let body = Body(channelUUID: channel.uuidString.lowercased(), token: token.map { String(format: "%02x", $0) }.joined(), expectedRevision: expectedRevision)
        return try await call(PTTState.self, method: "POST", path: "/api/rooms/\(member.roomID)/ptt", token: member.token, body: JSONEncoder().encode(body)).value
    }
    public func unregister(_ member: Session, channel: UUID, expectedRevision: Int64) async throws -> PTTState {
        struct Body: Encodable { let channelUUID: String, expectedRevision: Int64 }
        return try await call(PTTState.self, method: "DELETE", path: "/api/rooms/\(member.roomID)/ptt", token: member.token, body: JSONEncoder().encode(Body(channelUUID: channel.uuidString.lowercased(), expectedRevision: expectedRevision))).value
    }
    public func events(_ member: Session) -> AsyncThrowingStream<SSEEvent, Error> {
        AsyncThrowingStream(bufferingPolicy: .bufferingOldest(32)) { continuation in
            let task = Task {
                let session = connection(streaming: true); defer { session.invalidateAndCancel() }
                do {
                    var request = try request("GET", "/api/rooms/\(member.roomID)/events", token: member.token); request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                    let (bytes, response) = try await session.bytes(for: request)
                    guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw RoadError.http((response as? HTTPURLResponse)?.statusCode ?? 500, "events_failed", "실시간 연결 권한이나 공유방 상태를 확인해 주세요.") }
                    var parser = SSEParser(), chunk = Data()
                    for try await byte in bytes {
                        try Task.checkCancellation(); chunk.append(byte)
                        if byte == 10 || byte == 13 || chunk.count >= 2048 {
                            for event in try parser.feed(chunk) { if case .dropped = continuation.yield(event) { throw RoadError.invalid("수신 대기열이 가득 차 다시 연결해야 해요.") } }; chunk.removeAll(keepingCapacity: true)
                        }
                    }; continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { @Sendable _ in task.cancel() }
        }
    }
}
/// CAS and channel checks protect against an earlier POST finishing after leave/new join.
public actor RegistrationCoordinator {
    private var intent = UUID()
    public init() {}
    public func cancelIntent() { intent = UUID() }
    public func join(client: any RegistrationAPI, member: Session, channel: UUID, token: Data) async throws -> PTTState? {
        let operation = UUID(); intent = operation
        for _ in 0..<3 {
            let current = try await client.ptt(member); guard intent == operation, !Task.isCancelled else { return nil }
            do {
                let reply = try await client.register(member, channel: channel, token: token, expectedRevision: current.revision)
                guard intent == operation, !Task.isCancelled else { if let registration = reply.registration { try? await retire(client: client, member: member, channel: channel, onlyRegistrationID: registration.id) }; return nil }; return reply
            } catch RoadError.http(let status, _, _) where status == 409 { continue }
        }; throw RoadError.invalid("수신 등록 상태가 바뀌었어요. 직접 다시 시작해 주세요.")
    }
    public func leave(client: any RegistrationAPI, member: Session, channel: UUID) async throws { let operation = UUID(); intent = operation; try await retire(client: client, member: member, channel: channel, operation: operation) }
    private func retire(client: any RegistrationAPI, member: Session, channel: UUID, onlyRegistrationID: String? = nil, operation: UUID? = nil) async throws {
        let state = try await client.ptt(member)
        if let operation, intent != operation || Task.isCancelled { return }
        guard state.registration == nil || state.registration?.channelUUID == channel else { return }
        if let onlyRegistrationID, state.registration?.id != onlyRegistrationID { return }
        _ = try await client.unregister(member, channel: channel, expectedRevision: state.revision)
    }
}
