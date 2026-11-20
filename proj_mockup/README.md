# 교수질문센터 목업 프로젝트 (`proj_mockup`)

로그인/회원가입 절차 없이 상단 드롭다운에서 학생/교수 계정(총 8개)을 즉시 전환하며 테스트할 수 있는 FE/BE 목업 프로젝트입니다.

## 주요 구성
- **계정 전환 드롭다운 (`8개 계정`)**
  - 학생 4개: `이서준` (소프트웨어학부), `김민지` (소프트웨어학부), `박준형` (컴퓨터정보공학부), `정하은` (정보융합학부)
  - 교수 4개: `김교수` (소프트웨어학부), `이교수` (컴퓨터정보공학부), `박교수` (정보융합학부), `최교수` (로봇학부)
- **인공지능융합대학 중심 데이터셋**
  - 단과대: `인공지능융합대학`
  - 학부/학과: `소프트웨어학부`, `컴퓨터정보공학부`, `정보융합학부`, `로봇학부`, `지능형로봇학과`
  - 교과목: `courses_data.json`에서 인공지능융합대학 교과목 24개 일부 발췌 및 데모 과목 3개(`공학설계입문`, `자료구조`, `AI로봇실험2`) 시딩

## 실행 방법

### 1. 백엔드 (`proj_mockup/backend`)
```bash
cd proj_mockup/backend
./gradlew run
```
- 기본 포트 `8080`, 인메모리 H2(PostgreSQL 호환 모드) 자동 초기화
- `GET /api/auth/users` 및 `POST /api/auth/demo-login` 기본 활성화

### 2. 프론트엔드 (`proj_mockup/frontend`)
```bash
cd proj_mockup/frontend
npm install
npm run dev
```
- 기본 포트 `5173`, `/api` 요청을 `http://localhost:8080`으로 프록시

## 기존 서버와 병행 배포 (systemd + Nginx)
전체 도메인, HTTPS, DB(PostgreSQL / 파일 H2), `.env` 상세 설정은 [**DEPLOY_GUIDE.md**](DEPLOY_GUIDE.md)를 참고하세요.

1. **DB 선택 (`proj_mockup/backend/.env`)**: [`.env.example`](backend/.env.example) 참고
   - 미설정 시 인메모리 H2 사용 (재시작 시 초기화)
   - 영구 저장이 필요하면 **파일 기반 H2**(`jdbc:h2:file:./data/mockup_db;...`) 또는 **별도 PostgreSQL DB**(`cistus_mockup`) 지정 가능
2. **systemd / Nginx 등록**: [`deploy/`](deploy/)의 템플릿(`cistus-mockup-be.service`, `nginx-mockup.conf`) 경로/도메인을 서버 환경에 맞게 반영 (`8081` 포트 사용)
3. **일괄 배포 실행**:
   ```bash
   ./scripts/update-all.sh --mockup
   ```
   - `proj_mockup/frontend` Vite 빌드 → Nginx 리로드 → `proj_mockup/backend` Gradle 빌드 → `cistus-mockup-be` 재시작(포트 `8081` 헬스체크)을 수행합니다.
