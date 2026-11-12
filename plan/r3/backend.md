# 백엔드

<br>

# 사용할 기술
- **Ktor (v2.3.12, Netty)**: 백엔드 비동기 HTTP API 처리 및 Ktor Client(CIO) 외부 API 호출 담당.
  - **Exposed (v0.52.0)**: 백엔드에서의 데이터베이스와의 통신(ORM/DSL) 담당.
  - **HikariCP (v5.1.0)**: 데이터베이스 커넥션 풀(Connection Pool) 관리 (`maximumPoolSize = 10`, `TRANSACTION_REPEATABLE_READ`).
- **암호화 및 보안 (`PasswordSecurity`)**:
  - `PBKDF2WithHmacSHA256` (65,536회 반복 + `SecureRandom` 16바이트 솔트) 기반 비밀번호 단방향 해싱 및 `MessageDigest.isEqual` 상수 시간 검증.
  - `HmacSHA256` 서명 기반 인증 토큰 발급 및 검증(`RouteUtils.getCurrentUserId()`).
- **이메일 발송 및 콘솔 관리 (`EmailService`, `AdminConsoleManager`)**:
  - 교수 질문 알림 이메일 발송(SMTP 연동 지원 + `EmailLogsTable` 영구 로깅).
  - 서버 표준 입력(`stdin`) 데몬 스레드에서 `admin <id>`, `revoke-admin <id>`, `users`, `help` 명령어를 실시간 수신하여 관리자 권한 제어.
- **PostgreSQL / H2**: 데이터베이스 (`DB_URL` 미지정 시 PostgreSQL 호환 모드의 인메모리 H2 자동 폴백).
- **Java AWT / ImageIO**: 서버 측 이미지 리사이징(최대 1920px), JPEG 0.82 재인코딩 압축 및 EXIF 제거.

# 기반 (R3 스키마 확장)
- **`UsersTable`**: `username` (고유 ID), `passwordHash` (`PBKDF2` 해시), `email`, `isAdmin`, `isWithdrawn` (탈퇴 여부), `questionBanUntil` (질문 제한 만료 시각), `answerBanUntil` (답변 제한 만료 시각) 컬럼 추가.
- **`DepartmentsTable`**: 학과 정식 명칭(`name`), 약칭(`shortName`), 영문 코드(`code`) 동적 관리 테이블 추가.
- **`QuestionsTable` & `AnswersTable`**: 관리자 게시물 가리기 상태 컬럼(`isHidden`) 추가.
- **`ProfessorNotificationSettingsTable` & `EmailLogsTable`**: 교수 이메일 알림 활성화(`emailEnabled`), 수신 이메일 주소(`notificationEmail`), 이메일 발송 내역(`EmailLogsTable`) 추가.

# 기능
## 인증 및 전시용 회원가입 (`AuthRoutes`, `UserService`)
- **`POST /api/auth/register`**:
  - **학생 계정**: 학번(`identifier`), 학과(`department`), 이름(`name`), ID(`username`), 비밀번호(`password`)를 검증하고 학번·ID 중복 가입을 차단한 뒤 `PBKDF2` 해시로 저장 및 전체 개설 강좌 자동 수강 연결.
  - **교수 계정**: 학과(`department`), 담당 과목(`courseIds` / `courseNames`), 이메일(`email`), 이름(`name`), ID(`username`), 비밀번호(`password`)를 검증하고 이메일·ID 중복 가입을 차단한 뒤 담당 강좌 및 이메일 알림 설정 자동 생성.
- **`POST /api/auth/login`**:
  - ID/비밀번호 상수 시간 해시 검증 및 탈퇴(`isWithdrawn`) 계정 로그인 차단(`403 Forbidden`).

## 관리 모드 (`AdminRoutes`, `AdminConsoleManager`)
- **서버 터미널 콘솔 전용 관리자 권한 부여**:
  - 브라우저·HTTP API(`POST /api/admin/console-grant`, `sanction` 내 권한 부여 등)를 통한 관리자 권한 부여는 완전히 차단되며, 오직 백엔드 서버 표준 입력 터미널 콘솔(`admin <id>`, `revoke-admin <id>`)에서만 권한을 부여/회수할 수 있습니다.
  - 모든 관리자 HTTP 엔드포인트(`/api/admin/**`)는 HMAC-SHA256 서명 기반 `Authorization: Bearer <token>` 검증(`getVerifiedTokenUserId()`) 및 DB 관리자·탈퇴 여부 확인을 필수로 요구합니다.
- **학과 및 과목 동적 추가**:
  - `POST /api/admin/departments`, `POST /api/admin/courses` (관리자 권한 검증 필수).
- **익명 질문의 실제 질문자 표시 (`QuestionService`)**:
  - 조회 요청자가 관리자(`isAdmin == true`)인 경우 익명 질문에서도 `realStudentName`, `realStudentTag`, `realStudentIdentifier`에 실제 작성자 정보를 담아 반환 (일반 사용자에게는 `null`).
- **질문 및 답변 가리기 (`PATCH /api/admin/questions/{id}/hide`, `PATCH /api/admin/answers/{id}/hide`)**:
  - `isHidden = true` 설정 시 일반 사용자에게는 제목과 본문을 비표시 마스킹 문구로 치환하여 반환하고, 관리자에게는 원본과 가림 배지를 함께 반환.
- **일반 계정 탈퇴 및 기간별 질문·답변 제한 (`POST /api/admin/users/{id}/sanction`)**:
  - `WITHDRAW` / `RESTORE` (탈퇴 및 복구).
  - `BAN_QUESTION` / `UNBAN_QUESTION` (`durationHours` / `durationDays` 기간을 받아 `questionBanUntil` 설정, 만료 전 질문 작성 시 `403 Forbidden` 차단).
  - `BAN_ANSWER` / `UNBAN_ANSWER` (`durationHours` / `durationDays` 기간을 받아 `answerBanUntil` 설정, 만료 전 답변 작성 시 `403 Forbidden` 차단).

## 교수용 질문 알림 및 이메일 발송 (`NotificationService`, `EmailService`)
- 학생이 질문을 등록하면 담당 교수(및 강의 카테고리의 동일 과목 교수)의 연동 이메일 주소(`notificationEmail` 또는 계정 `email`)로 질문 제목·카테고리·작성자·본문이 포함된 알림 이메일을 즉시 발송하고 `EmailLogsTable`에 기록(`GET /api/notifications/emails`로 조회 가능).

## 학생용 및 공통 기능
- **AI 질문 도우미 (`AiRefineService`)**, **유사 질답 보기 (`SimilarityService`)**, **파일·이미지 보안 첨부 (`AttachmentService`)**, **초록색조 테마(`green`)를 포함한 계정별 설정 저장 (`UserSettingRoutes`)** 지원.

<sub>r3 26. 11. 12. 23:45</sub>
