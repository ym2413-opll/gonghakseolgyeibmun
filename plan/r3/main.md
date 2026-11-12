# 교수질문센터 - 교수와 학생 간 질답을 위한 서비스

<br>

# 사용할 기술

## 프론트엔드
- **React (v19) + Vite (v8)**: SPA 기반 서양식 포럼(Western-Style Forum) UI 구축 및 번들링.
  - **Lucide React**: 아이콘 시스템.
  - **Web Crypto API (`PBKDF2-HMAC-SHA256`) & LocalStorage 폴백 엔진**: 백엔드 미연결 시에도 단방향 솔트 해싱 및 관리 모드가 동작하는 독립 폴백 지원.
  - **HTML5 Canvas API & Web Notification API**: 클라이언트 측 이미지 압축 및 브라우저 푸시 알림.

## 백엔드
- **Ktor (v2.3.12, Netty)**: 비동기 HTTP 서버 및 Ktor Client(CIO) 기반 외부 API 호출 담당.
  - **Exposed (v0.52.0)**: 백엔드에서의 데이터베이스와의 통신 담당.
  - **HikariCP (v5.1.0)**: 데이터베이스 커넥션 풀(Connection Pool) 관리.
- **PBKDF2WithHmacSHA256 & HmacSHA256 토큰 서명**: 65,536회 반복 + 16바이트 난수 솔트 기반 비밀번호 해싱 및 상수 시간(`MessageDigest.isEqual`) 검증.
- **SMTP Email Dispatcher & Admin Console Listener**: 교수 질문 알림 이메일 발송(`EmailService`) 및 서버 표준 입력 콘솔(`admin <id>`) 실시간 명령 리스너(`AdminConsoleManager`).
- **PostgreSQL / H2 (PostgreSQL 호환 모드)**: 메인 데이터베이스 (`DB_URL` 미설정 시 인메모리 H2 자동 폴백).
- **Java AWT / ImageIO**: 서버 측 이미지 리사이징·압축 및 EXIF 메타데이터 제거.

## 데이터 및 배포
- **Python (`convert_xlsx_to_json.py`)**: 학사 개설과목 및 강의시간표 엑셀(`.xlsx`)을 `courses_data.json`으로 변환.
- **GCP (Nginx + Ktor) / Netlify**: 프로덕션 및 데모 배포 환경 대응.

# 기능
## 공용 (R3 체계개편)
### 질문 체계 (서양식 포럼 개편)
- 기존 단순 게시글 형태에서 **서양식 포럼(Western-Style Forum: Discourse / GitHub Discussions 스타일)** 형태로 전면 개편.
- **포럼 토픽 보드(Topic Table)**: 토픽 상태 아이콘(Solved/Open), 카테고리·교과목 배지, 참여자 아바타 스택(Participants), 답글/포스트 수(Replies/Posts), 최근 활동 시각(Activity) 표시.
- **시간순 스레드 포스트 스트림(Chronological Post Stream)**: `#1 OP(Topic Starter)` 포스트와 `#2, #3...` 교수 답변 포스트가 수직 타임라인으로 이어지는 포럼 스레드 구조.

### 레이아웃 & 테마
- **3단계 반응형 레이아웃 구현**:
  - **스마트폰 (`<= 640px`)**: 단일 컬럼 컴팩트 토픽 카드 및 하단 내비게이션 바(`BottomNav`).
  - **태블릿 + 스마트폰 가로모드 + Galaxy Z Fold 시리즈 (`641px ~ 1024px`)**: 폴더블 펼침 화면 및 태블릿에 최적화된 적응형 포럼 테이블/스레드 레이아웃.
  - **데스크톱 (`>= 1025px`)**: 좌측 토픽 보드와 우측 스레드 스트림을 동시에 탐색하는 와이드 스플릿 뷰.
- **눈이 편한 초록색조 테마 (`green`) 추가**:
  - 라이트(`light`), 다크(`dark`) 외에 세이지·포레스트 그린 톤의 눈 보호 테마(`data-theme="green"`) 및 4단계 글자 크기 조절 지원.

### 전시용 계정 전환 개편 (비로그인 블러 게이트 & 회원가입)
- 사전 생성 계정 전환 방식을 제거하고, **비로그인(Unauthenticated)** 과 **로그인(Authenticated)** 상태를 엄격히 구분.
- **비로그인 블러 게이트**: 비로그인 시 헤더 아래 포럼 질문 영역을 블러(`filter: blur(9px)`) 처리하여 읽지 못하게 보호하고, 그 위에 로그인/전시용 회원가입 유도 오버레이(`AuthGateOverlay`)를 표시.
- **전시용 회원가입 절차**:
  - **학생 계정**: 학번(`identifier`), 학과(`department`), 이름(`name`), ID(`username`), 비밀번호(`password`) 입력.
  - **교수 계정**: 학과(`department`), 담당 과목(`courseIds`), 이메일(`email`), 이름(`name`), ID(`username`), 비밀번호(`password`) 입력.
  - **암호화 해싱 및 중복 가입 차단**: 비밀번호는 `PBKDF2WithHmacSHA256`(솔트 + 65,536회 반복)으로 해싱 저장하며, 동일 ID·동일 학번·동일 이메일의 중복 가입을 원천 차단.

### 관리 모드 (Admin Mode)
- **콘솔 기반 관리자 권한 부여**:
  - 백엔드 터미널 콘솔에서 `admin <id>` (해제 시 `revoke-admin <id>`) 명령어 입력 또는 브라우저 개발자 콘솔(F12)에서 `await grantAdmin("<id>")` 호출 시 특정 ID에 즉시 관리자 권한 부여.
- **과목 및 학과 동적 추가**: 관리자 모달(`AdminModal`)에서 새로운 학과(`DepartmentsTable`)와 교과목(`CoursesTable`)을 실시간 추가.
- **익명 질문의 실제 질문자 표시**: 관리자 계정에는 익명 질문의 실제 작성자(학번·학과·이름) 배지가 토픽 목록과 상세 스레드, 관리 테이블에 표시됨.
- **질문 및 답변 가리기 (내용 비표시)**: 부적절한 질문 또는 답변을 가리기(`isHidden = true`) 처리하여 일반 사용자에게 내용을 비표시 마스킹.
- **일반 계정 제재 (탈퇴 · 기간별 질문 제한 · 기간별 답변 제한)**:
  - 일반 계정의 강제 탈퇴(`WITHDRAW`) 및 복구(`RESTORE`).
  - 기간(1시간, 6시간, 1일, 3일, 7일, 30일 등)을 지정하여 질문 작성 제한(`questionBanUntil`) 및 답변 작성 제한(`answerBanUntil`) 설정/해제.

## 교수용
### 질문 알림 및 이메일 발송 연동
- 교수 계정에 이메일 주소를 등록·변경하고, 학생이 담당 과목에 새 질문을 작성하면 연동된 이메일 주소로 즉시 질문 알림 메일을 발송(`EmailService`) 및 발송 로그(`EmailLogsTable`) 기록.
- 방해 금지 시간대(야간 알림 보류 후 일괄 전달) 및 **같은 과목 다른 교수** 공동 답변 알림 설정 지원.

### 같은 과목 다른 교수 & 카테고리별 질문 활성화
- 동일 과목 담당 교수 모두가 **강의** 카테고리 미답변 질문에 공동 답변 가능 (담당교수 답변 완료 시 마감).
- 과목·카테고리별 질문 접수 활성화/비활성화 설정 가능.

## 학생용
- **답변 알림**, **익명 질문**(성적 제외), **질문 제목 자동 요약**, **파일·이미지 보안 첨부 및 자동 압축**, **Gemini 기반 AI 질문 도우미**, **Word2Vec(문자 2-gram + 64차원 해시 투영 코사인 유사도) 기반 유사 질답 보기** 지원.

<sub>r3 26. 11. 12. 23:45</sub>
