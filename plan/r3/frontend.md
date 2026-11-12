# 프론트엔드

<br>

# 사용할 기술
- **React (v19) + Vite (v8)**
- **Lucide React**: UI 아이콘 시스템.
- **Web Crypto API (`PBKDF2-HMAC-SHA256`)**: 오프라인/로컬 폴백 시에도 단방향 솔트 해싱을 수행하는 암호화 모듈.
- **HTML5 Canvas API & FileReader/DataView**: 업로드 전 클라이언트 측 이미지 다운스케일링(최대 1920px, JPEG 0.82 압축) 및 파일 시그니처(매직 바이트) 사전 검증.
- **Web Notification API**: 브라우저 푸시 알림.
- **LocalStorage 오프라인 폴백 엔진**: 백엔드 미연결 시에도 회원가입·로그인·관리모드·학과/과목 동적 추가·이메일 발송 로그가 동작하도록 지원.

# 플랫폼 및 3단계 반응형 레이아웃
- **데스크톱 (`>= 1025px`)**: 좌측 서양식 포럼 토픽 보드 + 우측 스레드 포스트 스트림이 나란히 배치되는 와이드 스플릿 레이아웃 (`forum-desktop-split`).
- **태블릿 + 스마트폰 가로모드 + Galaxy Z Fold 시리즈 (`641px ~ 1024px`)**: 폴더블 내부 대화면 및 태블릿 가로/세로 뷰포트에 맞춘 적응형 포럼 테이블 및 스레드 레이아웃 (`tier-tablet`).
- **스마트폰 (`<= 640px`)**: 단일 컬럼 토픽 카드 + 상단 프로필 압축형 포스트 스트림 + 하단 내비게이션 바(`BottomNav`) 자동 전환 (`tier-smartphone`).

# 기능
## 공용 (R3 체계개편)
### 질문 체계 (서양식 포럼 UI)
- **포럼 토픽 보드 (`QuestionList.jsx`, `ProfessorDashboard.jsx`, `QuestionCard.jsx`)**:
  - `Topic (질문 스레드)` / `Participants (참여자 아바타 스택)` / `Replies / Posts` / `Activity` 컬럼 구조의 서양식 포럼 테이블.
- **시간순 포스트 스트림 (`QuestionDetail.jsx`)**:
  - `#1 OP (Topic Starter)` 포스트와 `#2, #3...` 교수 답변 포스트가 시간순으로 나열되는 스레드 뷰.

### 테마 및 글자 크기
- **3종 테마 (`ThemeContext.jsx`, `SettingsModal.jsx`, `index.css`)**:
  - `light` (라이트 모드), `green` (**눈이 편한 초록색조 테마**: 세이지·포레스트 그린 배색), `dark` (다크 모드) 지원.
- **4단계 글자 크기 조절**: `sm`, `md`, `lg`, `xl`.

### 비로그인 블러 게이트 & 전시용 회원가입 (`AuthContext.jsx`, `AuthGateOverlay.jsx`)
- 사전 생성 계정 드롭다운을 제거하고 비로그인(`currentUser = null`)과 로그인 상태를 구분.
- **비로그인 블러 처리**: 비로그인 시 헤더 아래 포럼 영역(`.forum-main-stage.is-blurred-locked`)에 `filter: blur(9px)` 및 클릭/드래그 방지를 적용하고, 상단에 로그인/회원가입 유도 카드(`AuthGateOverlay.jsx`)를 표시.
- **전시용 회원가입**:
  - **학생 가입**: 학번(6~12자리 숫자, 중복 가입 차단), 학과, 이름, ID, 비밀번호 입력.
  - **교수 가입**: 학과, 담당 과목(다중 선택), 이메일(중복 가입 차단 및 질문 알림 발송 연동), 이름, ID, 비밀번호 입력.

### 관리 모드 (`AdminModal.jsx`)
- **브라우저 관리자 권한 부여 차단**: 보안 정책상 브라우저 콘솔(`window.grantAdmin`) 및 HTTP API를 통한 관리자 권한 부여는 완전히 불가능하며, 오직 백엔드 서버 터미널 콘솔(`admin <아이디>`)에서만 부여할 수 있습니다.
- **관리자 컨트롤 센터 (`AdminModal.jsx`)**:
  1. **학과 · 과목 동적 관리**: 학과(`createDepartment`) 및 교과목(`createCourse`) 실시간 추가.
  2. **계정 관리 · 제재**: 가입 계정 목록 조회, 강제 탈퇴(`WITHDRAW`)/복구(`RESTORE`), 기간 설정(1시간~30일) 기반 질문 제한(`BAN_QUESTION`) 및 답변 제한(`BAN_ANSWER`).
  3. **익명 실명 확인 · 게시물 가리기**: 익명 질문의 실제 작성자(학번·학과·이름) 확인 및 질문/답변 가리기(`toggleHideQuestion`, `toggleHideAnswer`).
  4. **이메일 알림 로그**: 교수에게 발송된 질문 알림 이메일 내역 열람.

## 교수용
- **질문 알림 및 이메일 발송 설정 (`NotificationSettingsModal.jsx`)**: 알림 수신 이메일 주소 등록/수정, 이메일 발송 ON/OFF 토글, 방해금지 시간대 설정, 공동 답변 알림 설정, 카테고리별 질문 활성화 제어.

## 학생용
- **AI 질문 도우미 (`AiRefineModal.jsx`)**, **유사 질답 보기 (`SimilarQuestionsModal.jsx`)**, **파일·이미지 보안 첨부 (`QuestionForm.jsx`)**, **익명 질문**(성적 제외) 지원.

<sub>r3 26. 11. 12. 23:45</sub>
