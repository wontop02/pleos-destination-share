# 휴대폰 원음 수신 검증표

기준일: 2026-11-05. 코드·빌드·실기기 결과를 구분한다. 웹 첫 실행본은 네이티브 배경 수신의 완료 근거가 아니다. 차량 #12는 기존 휴대폰 #7/#8과 별도 승인된 범위다.

| 대상 | 구현 경로 | 실제 통과에 필요한 관찰 | 현재 자원 |
|---|---|---|---|
| 웹 전경 | 공유방 API, 인증 이벤트 연결, 실제 WAV 업로드·다운로드·재생 | 두 독립 참여자의 목적지와 원음, 권한 거절·중복·만료·수신 중지 | 로컬 Node·Chrome 웹 실행본과 자동 검사 제공. #6 PR의 검증 증거 참고 |
| iPhone | Swift·PushToTalk 채널과 전용 APNs 수신, 시스템 오디오 활성화 후 재생 | 내비 전경·화면 잠금·장시간 무음 후 새 원음의 실제 첫 소리, 중단·탈퇴 시 정지 | Xcode 27.2/SDK 27.2 시뮬레이터 빌드·UI 복원 실행. 실제 마이크·청취·서명/PTT/APNs·실기기는 BLOCKED. [Draft PR #11](https://github.com/wontop02/pleos-destination-share/pull/11), main 미병합 |
| Galaxy/Android | 사용자가 시작하는 네이티브 수신 세션, 적절한 서비스 유형·알림·오디오 포커스 | 기종/OS별 내비·잠금·15/30분 무음 후 첫 소리, 서비스 종료·복구와 사용자 중지 | JVM 검사·SDK 빌드·lint·에뮬레이터 UI 실행. 실제 마이크·Galaxy 잠금 수신은 BLOCKED. [Draft PR #10](https://github.com/wontop02/pleos-destination-share/pull/10), main 미병합 |
| Pleos 차량 #12 | 실제 NaviHelper 3.0.5의 조회·변경 이벤트·경로 요청과 공통 서버 | A 순정 목적지 설정/변경·공유, B 수신·직접 적용, B 후속 순정 목적지 일치, 실패·취소·해제 | 공식 Maven AAR/API 대조·전체 APK 빌드 실행. 전용 공식 Pleos 두 대의 실제 실증은 별도 판정. [차량 검증 기록](../../prototype/vehicle/evidence/verification.md) |

## 확인한 OS 계약

iPhone의 PushToTalk는 사용자가 전경에서 참여한 채널의 임시 토큰으로 배경 원음 수신을 깨운다. 서버는 전용 push type과 topic을 사용하며 앱은 시스템의 오디오 활성화 이후 재생한다. 채널 탈퇴와 통화 등 오디오 경합을 존중한다. 이 API의 존재만으로 파일 다운로드·잠금 재생이 실제 검증됐다고 판단하지 않는다. [Apple PushToTalk](https://developer.apple.com/documentation/pushtotalk), [앱 구현 안내](https://developer.apple.com/documentation/pushtotalk/creating-a-push-to-talk-app)

Android 14 이상에서는 서비스 유형과 해당 권한을 실제 작업에 맞게 선언해야 한다. Android 15 이상을 대상으로 하는 앱은 전경 앱이거나 foreground service가 실행 중이어야 오디오 포커스를 요청할 수 있다. 포커스를 잃으면 재생을 멈추거나 적절히 처리한다. 서비스가 시작됐다는 사실과 장시간 무음 후 수신이 유지됐다는 사실은 별도 검증한다. [서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types), [오디오 포커스](https://developer.android.com/media/optimize/audio-focus)

## 자원 부족 시 재개 조건

계정 가입·결제·보안 권한 확대를 자동으로 수행하지 않는다. 각 이슈에는 작성한 코드와 실행 가능한 검사 결과를 먼저 남긴다. iOS 전체 빌드는 Xcode와 SDK가 준비된 환경, APNs 실증은 적절한 서명/PTT capability·서버 자격증명·연결 iPhone이 필요하다. Android 실제 수신 판정은 대상 Galaxy의 기종·OS를 기록한 실기기 관찰이 필요하다.
