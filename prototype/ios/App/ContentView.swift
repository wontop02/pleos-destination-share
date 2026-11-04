import SwiftUI
import RoadCore

private enum RoadStyle {
    static let teal = Color(red: 8/255, green: 124/255, blue: 120/255)
    static let background = Color(.systemGroupedBackground)
}
@MainActor struct ContentView: View {
    @ObservedObject var store: RoomStore
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("목적지와 짧은 말, 함께").font(.subheadline.weight(.semibold)).foregroundStyle(RoadStyle.teal)
                    Text("정차 중이거나 동승자가 조작해 주세요.").font(.body)
                    Text(store.status).font(.body.weight(.semibold)).accessibilityAddTraits(.updatesFrequently)
                    if store.session == nil { entry } else { room }
                }.padding(16).frame(maxWidth: 640)
            }.frame(maxWidth: .infinity).background(RoadStyle.background).navigationTitle("함께 가는 길").tint(RoadStyle.teal)
        }
    }
    private func card<Body: View>(_ title: String, @ViewBuilder body: () -> Body) -> some View {
        VStack(alignment: .leading, spacing: 16) { Text(title).font(.title3.weight(.bold)); body() }.frame(maxWidth: .infinity, alignment: .leading).padding(16).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }
    private func action(_ title: String, disabled: Bool = false, _ action: @escaping () -> Void) -> some View { Button(title, action: action).buttonStyle(.borderedProminent).frame(minHeight: 48).disabled(disabled) }
    private var entry: some View {
        card("목적지 공유방") {
            TextField("공유 서버 주소", text: $store.serverText).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityLabel("공유 서버 주소")
            TextField("내 이름", text: $store.name).accessibilityLabel("내 이름")
            action("공유방 만들기", disabled: store.busy || store.channelLeaving) { store.create() }
            TextField("초대 링크 붙여넣기", text: $store.invitation).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityLabel("초대 링크")
            action("초대받은 공유방 참여", disabled: store.busy || store.channelLeaving) { store.joinRoom() }
        }.textFieldStyle(.roundedBorder)
    }
    @ViewBuilder private var room: some View {
        card(store.session?.role == "host" ? "대표 차량" : "공유방 참여자") {
            Text(store.connection).font(.headline)
            if let snapshot = store.snapshot { Text("참여자 \(snapshot.members.count)명 · \(Date(timeIntervalSince1970: Double(snapshot.room.expiresAt)/1000).formatted(date: .omitted, time: .standard)) 만료 예정").foregroundStyle(.secondary) }
        }
        card("받은 목적지") {
            if let destination = store.destination {
                Text(destination.name).font(.title2.weight(.bold)); Text("위도 \(destination.latitude) · 경도 \(destination.longitude)").foregroundStyle(.secondary)
                action("네이버 지도에 경로 요청") { store.requestMap() }
                Text("지도 앱 설치가 필요해요. 실제 길 안내 시작은 지도 앱에서 확인해 주세요.").foregroundStyle(.secondary)
            } else { Text("대표 차량이 목적지를 보내면 여기에 표시돼요.").foregroundStyle(.secondary) }
        }
        card("내 목소리 그대로") {
            Text("1–15초 녹음해 보내요. 15초가 되면 녹음을 마치고 전송해요.")
            Text(String(format: "%.1f / 15초", store.elapsed)).font(.title2.monospacedDigit()); Text(store.recordingStatus)
            if store.isRecording { action("중지하고 보내기") { store.finishRecording() }; Button("녹음 취소") { store.cancelRecording() }.frame(minHeight: 48) }
            else { action("마이크로 녹음 시작", disabled: !store.canUseForegroundAudio || store.uploading || store.hasRetry || store.busy) { store.beginRecording() } }
            if store.hasRetry && !store.uploading { action("같은 원음 다시 보내기") { store.sendClip() }; Button("전송하지 못한 원음 지우기") { store.discardClip() }.frame(minHeight: 48) }
            if !store.canUseForegroundAudio { Text("녹음·수동 재생은 자동 수신을 종료하고 채널 탈퇴를 확인한 뒤 사용할 수 있어요.").foregroundStyle(.secondary) }
        }
        card("iPhone 자동 수신") {
            Text(store.receiveStatus)
            if store.receiving || store.joining || store.channelLeaving { action("자동 수신 종료") { store.stopReceiving() } }
            else { action("자동 수신 시작", disabled: store.busy || store.isRecording || store.uploading) { store.startReceiving() } }
            Text("직접 시작한 수신 채널의 새 원음만 30초 안에 재생해요. 언제든 종료할 수 있어요.").foregroundStyle(.secondary)
            Text(store.playbackStatus); Button("현재 재생 중지") { store.stopPlayback() }.frame(minHeight: 48)
        }
        card("원음 목록 · 직접 재생") {
            if store.voices.isEmpty { Text("아직 주고받은 원음이 없어요.").foregroundStyle(.secondary) }
            ForEach(store.voices) { voice in VStack(alignment: .leading, spacing: 8) { Text("\(voice.sender.name)\(voice.sender.id == store.session?.memberID ? " (나)" : "")").font(.headline); Text("\(Date(timeIntervalSince1970: Double(voice.acceptedAt)/1000).formatted(date: .omitted, time: .standard)) · \(String(format: "%.1f", voice.durationMs/1000))초").foregroundStyle(.secondary); Button("\(voice.sender.name) 원음 재생") { store.playManually(voice) }.frame(minHeight: 48).disabled(!store.canUseForegroundAudio || store.isRecording) }; Divider() }
        }
        if store.session?.role == "host" {
            card("목적지 변경") { TextField("장소 이름", text: $store.destinationName); TextField("위도", text: $store.latitude).keyboardType(.numbersAndPunctuation); TextField("경도", text: $store.longitude).keyboardType(.numbersAndPunctuation); action("목적지 전달하기", disabled: store.busy) { store.sendDestination() }; Button("초대 링크 복사") { store.copyInvitation() }.frame(minHeight: 48) }.textFieldStyle(.roundedBorder)
        }
        card("공유방 관리") { Text("만든 뒤 6시간에 만료돼요. 종료하면 보관한 원음도 삭제돼요.").foregroundStyle(.secondary); Button(store.session?.role == "host" ? "공유방 종료하고 나가기" : "공유방 나가기", role: .destructive) { store.leaveRoom() }.frame(minHeight: 48).disabled(store.busy) }
    }
}
