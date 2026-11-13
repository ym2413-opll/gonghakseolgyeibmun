# Cistus Q&A Cloudflare Email Worker

교수님이 질문 알림 이메일에 **바로 답장(Reply)**을 보내면, Cloudflare Email Routing이 수신 메일을 이 Worker로 전달하고, Worker가 본문을 파싱하여 Cistus 백엔드(`POST /api/webhooks/email-reply`)로 답변을 자동 등록합니다.

## 동작 구조

1. **아웃바운드 (`Backend EmailService`)**:
   - 환경변수 `EMAIL_REPLY_DOMAIN`이 설정되면 각 교수 알림 메일마다 32자리 16진수 회신 토큰(`reply+<token>@<EMAIL_REPLY_DOMAIN>`)을 생성해 `Reply-To` 헤더에 담아 발송합니다.
   - 메일 최상단에 구분선(`----- 이 선 위에 답변을 작성하시면 포럼에 자동 등록됩니다 -----`)을 포함합니다.
2. **인바운드 (`Cloudflare Email Worker`)**:
   - Cloudflare Email Routing이 `reply+*@<도메인>` 메일을 수신해 `src/index.js`를 실행합니다.
   - `postal-mime`으로 메일 본문과 `Authentication-Results`(SPF/DKIM/DMARC)를 파싱한 뒤, `X-Email-Webhook-Secret` 헤더와 함께 백엔드로 전달합니다.
3. **검증 및 답변 등록 (`Backend EmailReplyService`)**:
   - `EMAIL_WEBHOOK_SECRET` 상수시간 검증
   - SPF/DKIM/DMARC 발신자 인증 검증
   - `reply+<token>` DB 토큰 조회 + 발신 이메일이 해당 교수의 등록 이메일(`UsersTable.email` / `ProfessorNotificationSettingsTable.notificationEmail`)과 일치하는지 확인
   - `Message-ID` 기반 중복 처리 방지(`processed_inbound_emails` 테이블)
   - 원문 인용구/답장 헤더 제거 후 `QuestionService.createAnswer()` 호출

---

## 설치 및 배포 방법

### 1. Cloudflare Email Routing 활성화
1. Cloudflare 대시보드에서 사용할 도메인(예: `reply.example.com` 또는 루트 도메인)을 선택합니다.
2. **Email -> Email Routing** 메뉴로 이동해 **Enable Email Routing**을 클릭합니다 (Cloudflare가 필요한 MX / SPF DNS 레코드를 자동 제안/추가합니다).

### 2. Worker 배포 및 시크릿 설정
```bash
cd proj/cloudflare-email-worker
npm install
npx wrangler login

# 백엔드 웹훅 URL 및 공유 시크릿 등록
npx wrangler secret put BACKEND_WEBHOOK_URL
# 입력 예: https://your-domain.com/api/webhooks/email-reply

npx wrangler secret put EMAIL_WEBHOOK_SECRET
# 백엔드 서버 환경변수 EMAIL_WEBHOOK_SECRET과 동일한 긴 랜덤 문자열 입력

# 배포
npm run deploy
```

### 3. Email Routing 규칙 연결
1. Cloudflare 대시보드 -> **Email -> Email Routing -> Routing rules**
2. **Catch-all address** 또는 **Custom addresses**에서 Action을 **Send to a Worker**로 설정하고 `cistus-email-reply-worker`를 선택합니다.
   > `reply+<32hex>@도메인` 형태의 동적 subaddressing을 사용하므로 **Catch-all address -> Send to a Worker** 설정이 가장 간편합니다. Worker 코드가 `ALLOWED_REPLY_PREFIX`와 토큰 형식을 확인해 잘못된 주소는 즉시 reject합니다.

### 4. 백엔드 서버 환경변수 설정 (`/etc/systemd/system/cistus-be.service` 또는 `.env`)
```env
EMAIL_REPLY_DOMAIN=reply.your-domain.com
EMAIL_REPLY_PREFIX=reply
EMAIL_WEBHOOK_SECRET=<Worker와 동일한 비밀키>
# 선택: SPF/DKIM 헤더가 없는 사설 릴레이 환경에서만 false로 설정 (기본값 true 권장)
# EMAIL_ENFORCE_SENDER_AUTH=true
```
설정 후 백엔드 서비스를 재시작하세요 (`sudo systemctl daemon-reload && sudo systemctl restart cistus-be`).
