# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-11-04
- Primary product surfaces: 공유방 웹 시연, Android 네이티브 프로토타입, 후속 iPhone 앱.
- Evidence reviewed: 사용자 원음·잠금 수신 요구, 상위 폴더의 DESIGN.md·GLOSSARY.md·development/implementation-plan.md·prototype/public/. 상위 원본은 변경하지 않는다.

## Brand
- Personality: 차분하고 명확한 동행 도구.
- Trust signals: 연결 상태, 발신자, 수신 시각, 녹음 시간과 실제 재생 상태.
- Avoid: 내비 실행 요청이나 음성 전송 접수를 실제 안내·재생 완료로 표시하지 않는다.

## Product goals
- Goals: 공유방에서 목적지와 최대 15초 원음을 주고받는다. 최종 휴대폰 수신은 내비 사용·화면 잠금 중에도 지원하는 네이티브 경로를 검증한다.
- Non-goals: 자체 지도 엔진, 위치 궤적 추적, 음성 합성, 공개 배포, Pleos 미검증 이벤트를 실제 연동으로 표시하는 동작.
- Success signals: 별도 참여자의 목적지 수신과 인증된 원음 바이트 전달·재생. 브라우저 결과는 잠금 수신의 증거가 아니다.

## Personas and jobs
- Primary personas: 여러 차량으로 이동하는 가족·친구와 동승자.
- User jobs: 목적지 변경과 짧은 말을 전화 없이 공유한다.
- Key contexts of use: 정차 또는 동승자 조작. 녹음은 전경 화면에서 명시적으로 시작한다.

## Information architecture
- Primary navigation: 공유방 생성·참여, 연결된 공유방.
- Core routes/screens: 연결 전 시작 화면, 연결 후 목적지·원음·초대·나가기 영역.
- Content hierarchy: 연결 상태, 받은 목적지, 말하기, 새 원음, 공유방 관리.

## Design principles
- 실제 상태와 조작 결과를 짧은 한국어로 표시한다.
- 자동 수신·재생은 사용자가 시작하고 언제든 중지할 수 있다.
- Tradeoffs: 웹은 열린 화면의 기능 검증용이다. 잠금·백그라운드 요구를 알림 클릭으로 대체해 통과시키지 않는다.

## Visual language
- Color: 밝은 회색 바탕, 짙은 글자, 청록색 기본 조작, 텍스트를 동반한 상태색.
- Typography: 시스템 글꼴, 본문 16px 이상.
- Spacing/layout rhythm: 8px 간격 단위, 모바일 한 열 중심.
- Shape/radius/elevation: 16px 카드, 10px 버튼, 약한 테두리.
- Motion: 최소 전환, 점멸 없음.
- Imagery/iconography: 외부 이미지 없이 텍스트와 간단한 아이콘을 사용한다.

## Components
- Existing components to reuse: 상위 시제품의 공유방·목적지 흐름을 참고한다.
- New/changed components: 연결 상태, 목적지 카드, 대표 차량 폼, 녹음 타이머·취소, 원음 목록과 재생 조작.
- Variants and states: 연결/재연결/종료, 녹음/전송/실패, 자동 재생 꺼짐/켜짐/거절, 최신/오래된 원음.
- Token/component ownership: 웹은 prototype/public/의 CSS 변수, Android는 prototype/android/의 공통 스타일과 리소스로 관리한다.

## Accessibility
- Target standard: WCAG 2.2 AA를 목표로 기본 접근성을 검토한다. 인증을 의미하지 않는다.
- Keyboard/focus behavior: 모든 입력에 label, 키보드 조작과 명확한 포커스.
- Contrast/readability: 고대비 본문과 상태 텍스트.
- Screen-reader semantics: 제목 구조, 상태 aria-live, 의미 있는 버튼 이름.
- Reduced motion and sensory considerations: 자동 소리는 명시적 선택 후에만 재생하고 즉시 중지할 수 있다.

## Responsive behavior
- Supported breakpoints/devices: 320px 이상 웹 화면, iPhone Safari·Android Chrome의 전경 검증.
- Layout adaptations: 작은 화면에서 입력과 버튼을 한 열로 배치한다.
- Touch/hover differences: 주요 조작 높이 48px 이상, hover에만 의존하지 않는다.

## Interaction states
- Loading: 요청 중 중복 전송을 막고 진행 상태를 표시한다.
- Empty: 목적지 없음과 받은 원음 없음을 구분한다.
- Error: 마이크·재생 권한 거절, 입력 오류, 연결 오류를 구분한다.
- Success: 전송 접수·수신·재생을 각각 표시한다.
- Disabled: 녹음 중 공유방 변경과 중복 녹음을 차단한다.
- Offline/slow network: 마지막 목적지를 유지하고 재연결 상태를 표시한다. 오래된 원음을 자동 재생하지 않는다.

## Content voice
- Tone: 짧고 구체적인 한국어.
- Terminology: 공유방, 대표 차량, 참여자, 받은 목적지, 원음. GLOSSARY.md를 따른다.
- Microcopy rules: 제품 조작을 우선하고 서버·SDK 설명은 개발 문서로 둔다.

## Implementation constraints
- Framework/styling system: 웹은 Node 표준 HTTP·이벤트 연결과 HTML/CSS/JavaScript다. Android는 Kotlin·플랫폼 Activity·AudioRecord/AudioTrack·사용자가 시작하는 수신 서비스를 사용한다.
- Design-token constraints: CSS 변수로 색·간격을 관리한다.
- Performance constraints: 원음은 1–15초, PCM16 mono 16kHz WAV를 공통 검증 형식으로 사용한다. 15초 약 480KB이며 서버에서 실제 길이를 검사한다.
- Compatibility constraints: 브라우저 마이크는 localhost 또는 신뢰된 HTTPS가 필요하다. 초기 Android 기준은 API 26 이상·compile/target 35다. iPhone 배경 수신은 PushToTalk/APNs, Android는 네이티브 서비스로 실기기 검증하며 모든 구형 기종 지원을 보장하지 않는다.
- Test/screenshot expectations: 실제 HTTP 바이트 전달, 두 독립 브라우저 참여와 목적지 전달, 모바일 화면·오류 상태를 확인한다. 실마이크·실기기 미실행을 명시한다.

## Open questions
- [ ] iPhone 기종·iOS와 Apple 서명/PTT capability / 사용자·개발 / 실제 배경 원음 검증.
- [ ] Galaxy 기종·OS 및 장시간 무음 후 수신 / 사용자·개발 / 실기기 첫 소리 검증.
- [ ] Pleos 계정·SDK·에뮬레이터 / 사용자·개발 / 순정 내비 목적지 이벤트 실증.
