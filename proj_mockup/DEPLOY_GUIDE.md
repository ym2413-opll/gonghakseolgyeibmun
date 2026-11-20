# `proj_mockup` 서버 병행 배포 완벽 가이드

기존 서비스(`proj`)와 **같은 서버에서 충돌 없이 병행 운영**하기 위한 전체 설정 가이드입니다.  
PostgreSQL 별도 DB 또는 파일 기반 H2 DB 연결, 도메인/HTTPS(Nginx), systemd 서비스, `.env` 설정, 일괄 배포 스크립트 실행까지 단계별로 정리되어 있습니다.

---

## 1. 기존 서버(`proj`)와 목업(`proj_mockup`) 분리 구조

| 구분 | 기존 서비스 (`proj`) | 목업 서비스 (`proj_mockup`) |
| --- | --- | --- |
| 프로젝트 경로 | `<REPO_DIR>/proj` | `<REPO_DIR>/proj_mockup` |
| 백엔드 포트 | `8080` | `8081` |
| systemd 서비스명 | `cistus-be` | `cistus-mockup-be` |
| 도메인 예시 | `qna.your-domain.com` | `mockup.your-domain.com` (또는 `mockup.<서버IP>.nip.io`) |
| 프론트엔드 정적 경로 | `proj/frontend/dist` | `proj_mockup/frontend/dist` |
| 데이터베이스 | `cistus` (PostgreSQL) | `cistus_mockup` (PostgreSQL) 또는 `data/mockup_db` (파일 H2) |
| 배포 명령 | `./scripts/update-all.sh` | `./scripts/update-all.sh --mockup` |

> **참고**: 아래 예시에서는 저장소 루트 경로를 `/opt/gonghak`으로 가정합니다. 실제 서버 경로가 다르면 `/opt/gonghak` 부분을 서버 경로로 바꿔 적용하세요.

---

## 2. 데이터베이스(DB) 선택 및 준비

목업 백엔드([DatabaseFactory.kt](backend/src/main/kotlin/com/gonghak/config/DatabaseFactory.kt))는 `.env`의 `DB_URL` 설정에 따라 아래 3가지 DB 모드를 지원합니다.

### 옵션 A. PostgreSQL 별도 DB 사용 (기존 서버와 동일 방식, 권장)
기존 `proj` DB와 완전히 분리된 DB(`cistus_mockup`)와 전용 계정을 생성합니다.

```bash
sudo -u postgres psql
```
```sql
CREATE USER cistus_mockup WITH PASSWORD '여기에_강력한_비밀번호_입력';
CREATE DATABASE cistus_mockup OWNER cistus_mockup ENCODING 'UTF8';
\q
```
- 테이블과 초기 시드 데이터(`인공지능융합대학` 5개 학과, 24개 교과목, 학생 4명·교수 4명)는 백엔드 최초 기동 시 자동 생성됩니다.

### 옵션 B. 파일 기반 H2 DB 사용 (PostgreSQL 설정 없이 영구 저장)
별도 DB 서버나 계정 생성 없이 로컬 파일에 데이터를 영구 저장합니다.
```bash
sudo mkdir -p /opt/gonghak/proj_mockup/backend/data
sudo chown -R www-data:www-data /opt/gonghak/proj_mockup/backend/data
```
- `.env`에서 `DB_URL=jdbc:h2:file:./data/mockup_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`로 지정합니다.
- **주의**: `PRODUCTION_MODE=true`일 때는 `DB_PASSWORD`가 비어 있거나 `postgres`이면 기동이 거부되므로, 파일 H2 사용 시 `DB_PASSWORD`에 임의의 비밀번호를 넣거나 `PRODUCTION_MODE=false`로 두세요.

### 옵션 C. 인메모리 H2 DB 사용 (초기화 데모용)
`DB_URL`을 설정하지 않으면 인메모리 H2로 동작합니다. 서버를 재시작할 때마다 데이터가 초기 시드 상태로 깨끗하게 리셋됩니다.

---

## 3. 백엔드 `.env` 설정 (`proj_mockup/backend/.env`)

[`.env.example`](backend/.env.example)을 복사해 `.env`를 생성합니다.

```bash
cp /opt/gonghak/proj_mockup/backend/.env.example /opt/gonghak/proj_mockup/backend/.env
chmod 600 /opt/gonghak/proj_mockup/backend/.env
```

### 실서버 권장 `.env` 작성 예시 (HTTPS + PostgreSQL 기준)

```dotenv
# === 1. 서버 포트 및 목업 모드 ===
PORT=8081
ENABLE_DEMO_USERS=true

# === 2. 데이터베이스 (옵션 A: PostgreSQL) ===
DB_DRIVER=org.postgresql.Driver
DB_URL=jdbc:postgresql://localhost:5432/cistus_mockup
DB_USER=cistus_mockup
DB_PASSWORD=여기에_강력한_비밀번호_입력

# (옵션 B: 파일 기반 H2를 쓸 경우 위 4줄 대신 아래 4줄 사용)
# DB_DRIVER=org.h2.Driver
# DB_URL=jdbc:h2:file:./data/mockup_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
# DB_USER=sa
# DB_PASSWORD=mockup-local-h2-pass

# === 3. 보안 및 도메인 / HTTPS ===
# HTTPS 도메인 적용 시 true 권장 (HTTP 전용 테스트 서버라면 false로 설정)
PRODUCTION_MODE=true
FORCE_HTTPS_COOKIES=true

# 32자 이상의 랜덤 문자열 (생성 예: openssl rand -hex 32)
AUTH_TOKEN_SECRET=여기에_32자_이상_랜덤_시크릿_문자열_입력

# 목업 프론트엔드 접속 도메인 (여러 개면 쉼표로 구분)
CORS_ALLOWED_ORIGINS=https://mockup.your-domain.com

# === 4. 업로드 및 선택 기능 ===
UPLOAD_DIR=uploads/attachments
# GEMINI_API_KEY=필요한_경우_입력
```

### 주요 환경변수 설명

| 변수명 | 필수 여부 | 설명 |
| --- | --- | --- |
| `PORT` | 필수 | 백엔드 수신 포트. 기존 `proj`(8080)과 겹치지 않게 `8081` 사용 |
| `ENABLE_DEMO_USERS` | 권장 | 목업 계정 드롭다운/즉시 로그인 허용 (`proj_mockup` 기본값 `true`) |
| `DB_URL` / `DB_DRIVER` / `DB_USER` / `DB_PASSWORD` | 선택 | 미설정 시 인메모리 H2. 영구 저장 시 PostgreSQL 또는 파일 H2 지정 |
| `PRODUCTION_MODE` | 선택 | `true` 시 `AUTH_TOKEN_SECRET`(32자+) 및 non-default `DB_PASSWORD` 필수 |
| `FORCE_HTTPS_COOKIES` | 조건부 | HTTPS 사용 시 `true`. **HTTP(SSL 미적용)로 접속하면 `false`로 두어야 쿠키가 동작** |
| `AUTH_TOKEN_SECRET` | 조건부 | `PRODUCTION_MODE=true`일 때 32자 이상 필수 (`openssl rand -hex 32`로 생성) |
| `CORS_ALLOWED_ORIGINS` | 권장 | 프론트엔드 접속 URL (`https://mockup.your-domain.com`) |

---

## 4. 도메인, Nginx 및 HTTPS(SSL) 설정

### 4-1. 도메인(DNS) 준비
- **자체 도메인 보유 시**: DNS에 `A` 레코드 추가  
  - 예: `mockup.your-domain.com` → `<서버_공인_IP>`
- **별도 도메인 없이 IP만 있는 경우 (`nip.io` 활용)**:  
  - DNS 설정 없이 `mockup.<서버_공인_IP>.nip.io` (예: `mockup.203.0.113.10.nip.io`)를 `server_name`으로 바로 사용할 수 있습니다.

> **서브도메인 방식을 권장하는 이유**: 프론트엔드 라우팅(`/`, `/sw.js`, `/manifest.webmanifest`)과 `/api/` 프록시가 루트(`/`) 기준으로 설정되어 있어, 하위 경로(`/mockup/`)보다 서브도메인(`mockup.your-domain.com`) 분리가 가장 안정적입니다.

### 4-2. Nginx 서버 블록 등록
템플릿([`deploy/nginx-mockup.conf`](deploy/nginx-mockup.conf))을 서버에 복사하고 도메인과 경로를 수정합니다.

```bash
sudo cp /opt/gonghak/proj_mockup/deploy/nginx-mockup.conf /etc/nginx/sites-available/cistus-mockup
sudo nano /etc/nginx/sites-available/cistus-mockup
```

확인할 항목:
- `server_name mockup.your-domain.com;` (실제 도메인 또는 `nip.io` 주소)
- `root /opt/gonghak/proj_mockup/frontend/dist;` (실제 저장소 경로)
- `proxy_pass http://127.0.0.1:8081/api/;` (`.env`의 `PORT`와 일치)

사이트 활성화 및 문법 검사:
```bash
sudo ln -s /etc/nginx/sites-available/cistus-mockup /etc/nginx/sites-enabled/cistus-mockup
sudo nginx -t
sudo systemctl reload nginx
```

### 4-3. HTTPS (Let's Encrypt SSL) 적용
```bash
sudo certbot --nginx -d mockup.your-domain.com
```
- 발급이 끝나면 `.env`의 `PRODUCTION_MODE=true`, `FORCE_HTTPS_COOKIES=true`, `CORS_ALLOWED_ORIGINS=https://mockup.your-domain.com`과 일치하는지 확인합니다.
- 방화벽(`ufw` 등)을 사용 중이라면 `80`, `443` 포트가 열려 있어야 합니다 (`8081` 포트는 외부에 직접 열 필요 없이 Nginx가 내부 프록시합니다).

---

## 5. systemd 서비스 등록 (`cistus-mockup-be`)

백엔드가 서버 재부팅 후에도 자동 실행되도록 systemd 유닛을 등록합니다.

```bash
sudo cp /opt/gonghak/proj_mockup/deploy/cistus-mockup-be.service /etc/systemd/system/cistus-mockup-be.service
sudo nano /etc/systemd/system/cistus-mockup-be.service
```

확인할 항목:
- `WorkingDirectory=/opt/gonghak/proj_mockup/backend`
- `EnvironmentFile=-/opt/gonghak/proj_mockup/backend/.env`
- `ExecStart=/opt/gonghak/proj_mockup/backend/build/install/backend/bin/backend`
- `User=www-data`, `Group=www-data`

권한 설정 및 서비스 등록:
```bash
sudo mkdir -p /opt/gonghak/proj_mockup/backend/uploads/attachments
sudo chown -R www-data:www-data /opt/gonghak/proj_mockup/backend/uploads
sudo chown www-data:www-data /opt/gonghak/proj_mockup/backend/.env

sudo systemctl daemon-reload
sudo systemctl enable cistus-mockup-be
```

---

## 6. 일괄 빌드 및 배포 실행

루트 저장소의 [`update-all.sh`](../scripts/update-all.sh)에 `--mockup` 옵션이 추가되어 있어 기존 서버와 동일한 방식으로 배포할 수 있습니다.

```bash
cd /opt/gonghak
chmod +x scripts/update-all.sh

# 전체 배포 (Git pull → FE Vite 빌드 & Nginx 리로드 → BE Gradle 빌드 & cistus-mockup-be 재시작)
./scripts/update-all.sh --mockup
```

자주 쓰는 옵션 조합:
- **로컬 수정본을 Git pull 없이 바로 배포**: `./scripts/update-all.sh --mockup --no-pull`
- **테스트(lint + Gradle test) 통과 후 배포**: `./scripts/update-all.sh --mockup --with-test`
- **프론트엔드만 배포**: `./scripts/update-all.sh --mockup --fe-only`
- **백엔드만 배포**: `./scripts/update-all.sh --mockup --be-only`

---

## 7. 배포 후 검증 체크리스트

1. **백엔드 서비스 및 헬스체크**
   ```bash
   sudo systemctl status cistus-mockup-be
   curl -s http://127.0.0.1:8081/api/auth/users | head -c 300
   ```
   - 8개 데모 계정(`이서준`, `김민지`, `박준형`, `정하은`, `김교수`, `이교수`, `박교수`, `최교수`) JSON이 응답되면 정상입니다.
2. **외부 도메인 / Nginx 프록시 확인**
   ```bash
   curl -I https://mockup.your-domain.com
   curl -s https://mockup.your-domain.com/api/courses/summary
   ```
   - `courses` 요약 응답에 `"인공지능융합대학"` 및 24개 교과목이 표시되는지 확인합니다.
3. **기존 `proj` 서비스 정상 여부 확인**
   ```bash
   sudo systemctl status cistus-be
   curl -s http://127.0.0.1:8080/api/courses/summary | head -c 200
   ```

---

## 8. 트러블슈팅

- **`cistus-mockup-be` 기동 직후 종료됨 (`status=1`)**
  - `sudo journalctl -u cistus-mockup-be -n 100 --no-pager`로 원인 확인
  - `PRODUCTION_MODE=true`인데 `AUTH_TOKEN_SECRET`이 32자 미만이거나 `DB_PASSWORD`가 기본값(`postgres`/빈값)인 경우 예외로 중단됩니다.
  - PostgreSQL 접속 오류라면 `pg_hba.conf` 또는 `cistus_mockup` DB/계정 권한을 확인하세요.
- **HTTP 테스트 환경에서 계정 전환/쿠키가 안 먹힘**
  - `.env`에서 `PRODUCTION_MODE=false`, `FORCE_HTTPS_COOKIES=false`로 설정한 뒤 `sudo systemctl restart cistus-mockup-be`를 실행하세요.
- **Java / Gradle 경로 오류 (`JAVA_HOME`)**
  - 서버에 JDK 17 또는 21이 설치되어 있어야 하며, `JAVA_HOME`은 `.../bin`이 아닌 **JDK 루트 디렉터리**를 가리켜야 합니다.
