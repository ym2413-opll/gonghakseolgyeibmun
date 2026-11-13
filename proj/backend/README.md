# 교수질문센터 백엔드 (Professor Q&A Platform Backend)

공학설계입문 프로젝트의 백엔드 시스템입니다.  
`plan/r2/backend.md`, `plan/r2/frontend.md`, `plan/r2/main.md` 명세를 기반으로 구현되었습니다.

---

## 🛠️ 기술 스택

- **언어 및 런타임**: Kotlin (JVM 17 / 21)
- **웹 프레임워크**: Ktor 2.3.12 (Netty Engine)
- **ORM / 데이터베이스**: Exposed 0.52.0 (PostgreSQL / H2 in-memory auto-fallback)
- **커넥션 풀**: HikariCP 5.1.0
- **직렬화**: Kotlinx.serialization (JSON)
- **인공지능**: Google Gemini API (1.5 Flash) + 지능형 규칙 기반 윤문 폴백 엔진
- **텍스트 유사도**: Word2Vec / 토큰 임베딩 기반 코사인 유사도(Cosine Similarity) 연산 엔진
- **로깅**: Logback (SLF4J)

---

## 📂 프로젝트 구조

```
proj/backend/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
├── src/
│   ├── main/
│   │   ├── kotlin/com/gonghak/
│   │   │   ├── Application.kt              # Ktor 메인 모듈, 플러그인(CORS, JSON, 상태 코드) 설정
│   │   │   ├── config/
│   │   │   │   └── DatabaseFactory.kt      # HikariCP, Exposed 테이블 생성 및 전시용 시드 데이터 자동 초기화
│   │   │   ├── models/
│   │   │   │   ├── Enums.kt                # 역할(STUDENT/PROFESSOR), 카테고리(GRADE/ASSIGNMENT/LECTURE) 등
│   │   │   │   └── Tables.kt               # Exposed 테이블 정의 (Users, Questions, Answers, Notifications 등)
│   │   │   ├── dto/
│   │   │   │   └── Dtos.kt                 # 클라이언트 통신용 직렬화 DTO 클래스
│   │   │   ├── service/
│   │   │   │   ├── UserService.kt          # 사용자 계정 조회, 과목 수강/강의 조회, 테마 설정
│   │   │   │   ├── QuestionService.kt     # 질문/답변 CRUD, 권한 규칙, 익명 마스킹, 카테고리 검증
│   │   │   │   ├── AiRefineService.kt      # Gemini API 및 정중한 격식체 윤문 엔진
│   │   │   │   ├── SimilarityService.kt    # Word2Vec 토큰 임베딩 및 코사인 유사도 검색
│   │   │   │   └── NotificationService.kt  # 알림 생성, 교수 방해금지 시간대 큐잉/몰아서 보내기
│   │   │   └── routes/
│   │   │       ├── RouteUtils.kt           # X-User-Id / Bearer 인증 식별 유틸
│   │   │       ├── AuthRoutes.kt           # 계정 목록 및 현재 사용자 프로필
│   │   │       ├── UserSettingRoutes.kt    # 테마 설정 & 교수 알림 설정
│   │   │       ├── CourseRoutes.kt         # 과목 및 교수 카테고리 ON/OFF 설정
│   │   │       ├── AiRoutes.kt             # AI 질문 도우미 엔드포인트
│   │   │       ├── QuestionRoutes.kt       # 질문 및 답변, 유사 질답 조회 엔드포인트
│   │   │       └── NotificationRoutes.kt   # 알림 조회 및 읽음 처리
│   │   └── resources/
│   │       ├── application.conf            # 포트, DB, Gemini 키 설정
│   │       └── logback.xml                 # 콘솔 로깅 설정
│   └── test/
│       └── kotlin/com/gonghak/
│           └── ApplicationTest.kt          # 기획서 명세 전체 시나리오 통합 테스트
```

---

## 🚀 실행 방법

### 1. 테스트 실행
```bash
./gradlew test
```

### 2. 로컬 서버 실행
```bash
./gradlew run
```
기본적으로 `http://localhost:8080` 포트로 실행됩니다.  
DB_URL이 지정되지 않으면 **PostgreSQL 호환 인메모리 H2 데이터베이스**로 즉시 초기화되며 전시용 샘플 데이터가 자동 시딩됩니다.

### 3. 환경 변수 (선택 사항)
- `PORT`: 실행 포트 (기본 `8080`)
- `DB_URL`: PostgreSQL JDBC URL (예: `jdbc:postgresql://localhost:5432/qnadb`)
- `DB_USER`: DB 사용자명 (기본 `postgres`)
- `DB_PASSWORD`: DB 비밀번호 (기본 `postgres`)
- `GEMINI_API_KEY`: Google Gemini API 키 (미설정 시 자체 지능형 규칙 기반 윤문 엔진으로 자동 동작)

---

## 🎯 핵심 기능 및 비즈니스 규칙 구현 상세

### 1. 계정 및 전시용(데모) 환경
- 계정 식별: 모든 사용자는 UUID로 관리되며, 요청 시 `X-User-Id: <UUID>` 헤더를 통해 사용자를 식별합니다.
- 사전 시드 계정:
  - **학생**: `서영민` (26_소프트_서영민), `김민지` (25_소프트_김민지), `박준형` (24_컴공_박준형)
  - **교수**: `김교수` (공학설계입문 01분반), `이교수` (공학설계입문 02분반), `박교수` (자료구조)

### 2. 카테고리별 제약 조건
| 카테고리 | 익명 질문 가능 여부 | 유사 질답 보기 | 같은 과목 다른 교수 답변 |
| :--- | :---: | :---: | :---: |
| **성적 (GRADE)** | ❌ 불가 (실명 필수) | ❌ 불가 | ❌ 불가 (오직 담당 교수만) |
| **과제 (ASSIGNMENT)** | ⭕ 가능 | ⭕ 가능 | ❌ 불가 (오직 담당 교수만) |
| **강의 (LECTURE)** | ⭕ 가능 | ⭕ 가능 | ⭕ 가능 (담당교수 답변 전까지) |

### 3. 같은 과목 다른 교수 기능
- 공학설계입문과 같이 여러 교수가 개설한 과목의 경우, **강의(LECTURE)** 카테고리 질문은 해당 과목의 다른 교수도 답변할 수 있습니다.
- 단, **질문자의 담당 교수가 이미 답변을 작성한 경우**, 다른 교수는 답변 작성이 차단됩니다 (`canAnswer: false`, 403 Forbidden).

### 4. 교수 알림 및 방해금지 시간대 (몰아서 보내기)
- 교수는 알림을 받지 않을 시간대(`quietHoursStart` ~ `quietHoursEnd`, 예: 22:00 ~ 08:00)를 설정할 수 있습니다.
- 방해금지 시간대에 등록된 질문 알림은 `isHeld: true` 상태로 서버 큐에 적재됩니다.
- 방해금지 시간대가 지나면(`releaseAt` 경과 시) 알림 조회 시점에 자동으로 일괄 해제되어 **몰아서 한 번에 전달**됩니다.
- 또한 **같은 과목 다른 교수 알림 수신 여부**(`notifyOtherProfessorQuestions`) 옵션을 제공합니다.

### 5. 익명 질문 정보 보호
- 학생이 익명으로 질문을 등록하더라도 **서버 DB에는 실제 질문자 정보가 온전히 저장**됩니다.
- 질문 목록 및 상세 조회 시:
  - 타인에게는 `공학설계입문 수강생`과 같이 마스킹된 이름으로 노출됩니다.
  - 작성자 본인이 조회할 때는 본인의 글임을 식별할 수 있도록 `isMyQuestion: true`와 표시 태그가 제공됩니다.

### 6. AI 질문 도우미
- `POST /api/ai/refine`
- 학생이 거친 표현이나 반말로 질문을 작성하더라도, 교수님께 예의를 갖춘 격식체 이메일/문의문 형식으로 교정·윤문합니다.
- Gemini 1.5 Flash API 연동 및 API Key 부재 시에도 안정적인 룰 기반 폴백 엔진을 탑재했습니다.

### 7. 유사 질답 보기 (Word2Vec / Cosine Similarity)
- `GET /api/questions/similar?courseId=...&category=...&query=...`
- 질문 등록 시 AI 처리/본문 텍스트에 대해 단어 토큰 임베딩 벡터를 미리 계산하여 DB에 저장합니다.
- 학생이 새 질문을 작성할 때 코사인 유사도를 계산하여 유사도가 높은 기존 질문 및 답변 목록을 추천합니다.

---

## 📡 주요 REST API 명세 요약

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/auth/users` | 전시용 계정 목록 조회 (계정 스위칭용) |
| `GET` | `/api/auth/me` | 현재 사용자 프로필 |
| `GET` | `/api/user/settings` | 테마(light/dark), 폰트 크기 조회 |
| `PUT` | `/api/user/settings` | 테마 및 폰트 크기 변경 |
| `GET` | `/api/professor/settings` | 교수 방해금지 시간대 및 알림 옵션 조회 |
| `PUT` | `/api/professor/settings` | 교수 방해금지 시간대 및 알림 옵션 변경 |
| `GET` | `/api/courses` | 수강/강의 과목 및 활성 카테고리 목록 조회 |
| `GET` | `/api/courses/{courseId}/categories` | 교수의 카테고리 활성화/비활성화 목록 |
| `PUT` | `/api/courses/{courseId}/categories` | 교수의 카테고리 활성화/비활성화 변경 |
| `POST` | `/api/ai/refine` | 질문 원문 AI 순화 및 격식체 윤문 |
| `GET` | `/api/questions` | 질문 목록 조회 (과목/카테고리 필터링) |
| `POST` | `/api/questions` | 새 질문 등록 |
| `GET` | `/api/questions/{id}` | 질문 상세 조회 (답변 목록, canAnswer 여부 포함) |
| `POST` | `/api/questions/{id}/answers` | 교수 답변 등록 (권한 규칙 적용) |
| `GET` | `/api/questions/similar` | 유사 질답 검색 (Word2Vec 코사인 유사도) |
| `GET` | `/api/notifications` | 알림 목록 조회 (방해금지 해제 자동 반영) |
| `POST` | `/api/notifications/{id}/read` | 알림 읽음 처리 |
| `POST` | `/api/webhooks/email-reply` | Cloudflare Email Worker 인바운드 답변 등록 웹훅 (`X-Email-Webhook-Secret`) |

### 8. 이메일 답장 자동 등록 (Cloudflare Email Worker 연동)
- 아웃바운드 알림 메일에 `Reply-To: reply+<token>@<EMAIL_REPLY_DOMAIN>` 헤더를 포함합니다.
- Cloudflare Email Worker([proj/cloudflare-email-worker](file:///d:/gonghakseolgyeibmun/proj/cloudflare-email-worker/README.md))가 답장을 파싱해 `/api/webhooks/email-reply`로 전달하면, 백엔드가 시크릿·SPF/DKIM·토큰·교수 이메일 일치·`Message-ID` 중복 여부를 검증한 뒤 답변을 등록합니다.
- 주요 환경변수: `EMAIL_REPLY_DOMAIN`, `EMAIL_REPLY_PREFIX`(기본 `reply`), `EMAIL_WEBHOOK_SECRET`, `EMAIL_ENFORCE_SENDER_AUTH`(기본 `true`).
