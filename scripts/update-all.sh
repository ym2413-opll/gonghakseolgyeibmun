#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# CISTUS (광운대학교 교수질문센터) FE/BE 일괄 빌드 및 서비스 반영 스크립트
# - 일반 유저 권한으로 Git/Vite/Gradle 빌드 수행 (필요한 시스템/권한 명령에만 sudo 적용)
# - 코드 동기화(git) -> FE(Vite) 빌드 & Nginx 리로드 -> BE(Ktor) 빌드 & systemd 재시작
# - .gradle 권한/캐시 꼬임 자동 복구, uploads/ 및 .admin-ctl.secret 권한 보호
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${REPO_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
BRANCH="${BRANCH:-main}"
BACKEND_SERVICE="${BACKEND_SERVICE:-}"
SERVICE_USER="${SERVICE_USER:-www-data}"
BACKEND_PORT="${BACKEND_PORT:-8080}"

DO_PULL=1
DO_FE=1
DO_BE=1
DO_RESTART=1
WITH_TEST=0
CLEAN_GRADLE=0
FORCE_NPM_CI=0

usage() {
  cat <<'EOF'
사용법: ./scripts/update-all.sh [옵션]
  * 일반 계정으로 실행하면 빌드/Git은 현재 계정으로, 시스템 제어/권한 변경 등
    일부 필요한 명령에만 내부적으로 sudo를 적용합니다.

옵션:
  --repo-dir <경로>       저장소 루트 경로 지정 (기본값: 스크립트 상위 폴더)
  --branch <브랜치>       배포할 Git 브랜치 지정 (기본값: main)
  --service <이름>        백엔드 systemd 서비스명 (기본값: cistus-be 자동 감지)
  --service-user <계정>   백엔드 실행 리눅스 계정 (기본값: www-data)
  --port <포트>           백엔드 헬스체크 포트 (기본값: 8080)
  --fe-only               프론트엔드(FE)만 업데이트
  --be-only               백엔드(BE)만 업데이트
  --with-test             빌드 전 lint / Gradle test 함께 실행
  --clean-gradle          .gradle 및 build 캐시를 완전히 삭제 후 클린 빌드
  --npm-ci                package-lock 변경 여부와 무관하게 npm ci 강제 실행
  --no-pull               git fetch / reset 단계 생략 (로컬 수정본 배포 시)
  --no-restart            빌드만 수행하고 nginx / systemd 재시작 생략
  -h, --help              도움말 출력

환경변수로도 지정 가능: REPO_DIR, BRANCH, BACKEND_SERVICE, SERVICE_USER, BACKEND_PORT
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo-dir)
      REPO_DIR="$2"; shift 2 ;;
    --branch)
      BRANCH="$2"; shift 2 ;;
    --service)
      BACKEND_SERVICE="$2"; shift 2 ;;
    --service-user)
      SERVICE_USER="$2"; shift 2 ;;
    --port)
      BACKEND_PORT="$2"; shift 2 ;;
    --fe-only)
      DO_FE=1; DO_BE=0; shift ;;
    --be-only)
      DO_FE=0; DO_BE=1; shift ;;
    --with-test)
      WITH_TEST=1; shift ;;
    --clean-gradle)
      CLEAN_GRADLE=1; shift ;;
    --npm-ci)
      FORCE_NPM_CI=1; shift ;;
    --no-pull)
      DO_PULL=0; shift ;;
    --no-restart)
      DO_RESTART=0; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "[ERROR] 알 수 없는 옵션입니다: $1" >&2
      usage
      exit 1 ;;
  esac
done

log() {
  printf '\n[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

warn() {
  printf '[WARN] %s\n' "$*" >&2
}

die() {
  printf '[ERROR] %s\n' "$*" >&2
  exit 1
}

if [[ ! -d "$REPO_DIR/proj/frontend" || ! -d "$REPO_DIR/proj/backend" ]]; then
  die "유효한 프로젝트 경로가 아닙니다: $REPO_DIR (proj/frontend, proj/backend 없음)"
fi
REPO_DIR="$(cd "$REPO_DIR" && pwd)"

if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  if [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]]; then
    DEPLOY_USER="$SUDO_USER"
  else
    DEPLOY_USER="$(stat -c '%U' "$REPO_DIR" 2>/dev/null || echo root)"
  fi
else
  DEPLOY_USER="$(id -un)"
fi

DEPLOY_GROUP="$(id -gn "$DEPLOY_USER" 2>/dev/null || echo "$DEPLOY_USER")"

run_priv() {
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    "$@"
  else
    command -v sudo >/dev/null 2>&1 || die "특권이 필요한 명령('$*')을 실행하려면 sudo가 필요합니다."
    sudo "$@"
  fi
}

prime_sudo() {
  if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    command -v sudo >/dev/null 2>&1 || die "서비스 제어 및 권한 설정을 위해 sudo가 필요합니다."
    if ! sudo -n true 2>/dev/null; then
      log "서비스 제어 및 권한 설정 등 일부 명령을 위해 sudo 인증을 진행합니다."
      sudo -v
    fi
  fi
}

run_deploy() {
  if [[ "${EUID:-$(id -u)}" -eq 0 && "$DEPLOY_USER" != "root" ]]; then
    sudo -u "$DEPLOY_USER" -H "$@"
  else
    "$@"
  fi
}

ensure_single_writable_for_deploy() {
  local target="$1"
  if [[ -e "$target" ]]; then
    local owner
    owner="$(stat -c '%U' "$target" 2>/dev/null || echo "")"
    if [[ "$owner" != "$DEPLOY_USER" ]] || [[ ! -w "$target" ]]; then
      run_priv chown -h "$DEPLOY_USER:$DEPLOY_GROUP" "$target"
      run_priv chmod u+rwX "$target"
    fi
  fi
}

ensure_writable_for_deploy() {
  local target="$1"
  if [[ -e "$target" ]]; then
    local needs_fix=0
    local bad_entry=""
    if [[ ! -w "$target" ]]; then
      needs_fix=1
    else
      if ! bad_entry="$(find "$target" ! -type l \( ! -user "$DEPLOY_USER" -o ! -writable \) -print -quit 2>/dev/null)"; then
        needs_fix=1
      elif [[ -n "$bad_entry" ]]; then
        needs_fix=1
      fi
    fi
    if [[ "$needs_fix" -eq 1 ]]; then
      run_priv chown -hR "$DEPLOY_USER:$DEPLOY_GROUP" "$target"
      run_priv chmod -R u+rwX "$target"
    fi
  fi
}

detect_backend_service() {
  if [[ -n "$BACKEND_SERVICE" ]]; then
    echo "$BACKEND_SERVICE"
    return 0
  fi
  if command -v systemctl >/dev/null 2>&1; then
    for candidate in cistus-be gonghak-backend; do
      if systemctl list-unit-files "${candidate}.service" --no-legend 2>/dev/null | grep -q "$candidate"; then
        echo "$candidate"
        return 0
      fi
    done
  fi
  echo "cistus-be"
}

sha256_or_empty() {
  local f="$1"
  if [[ -f "$f" ]]; then
    sha256sum "$f" | awk '{print $1}'
  else
    echo ""
  fi
}

# ------------------------------------------------------------------------------
# 1. Git 소스 동기화
# ------------------------------------------------------------------------------
FE_DIR="$REPO_DIR/proj/frontend"
BE_DIR="$REPO_DIR/proj/backend"
LOCK_FILE="$FE_DIR/package-lock.json"
PREV_LOCK_HASH="$(sha256_or_empty "$LOCK_FILE")"
NEW_LOCK_HASH="$PREV_LOCK_HASH"

if [[ "${EUID:-$(id -u)}" -ne 0 ]] && [[ "$DO_BE" -eq 1 || "$DO_RESTART" -eq 1 ]]; then
  prime_sudo
fi

if [[ "$DO_PULL" -eq 1 ]]; then
  log "1/3 Git 최신 코드 동기화 중 (branch: origin/$BRANCH)..."
  command -v git >/dev/null 2>&1 || die "git 명령어를 찾을 수 없습니다."

  # 이전 sudo/www-data 작업으로 소유권이 꼬인 추적/빌드 디렉터리 정리 (업로드/시크릿은 제외)
  ensure_single_writable_for_deploy "$REPO_DIR"
  ensure_single_writable_for_deploy "$REPO_DIR/proj"
  ensure_single_writable_for_deploy "$BE_DIR"
  for f in "$REPO_DIR"/.gitattributes "$REPO_DIR"/.gitignore "$REPO_DIR"/README.md \
           "$BE_DIR"/build.gradle.kts "$BE_DIR"/settings.gradle.kts "$BE_DIR"/gradle.properties \
           "$BE_DIR"/gradlew "$BE_DIR"/gradlew.bat "$BE_DIR"/admin-ctl.sh "$BE_DIR"/README.md; do
    ensure_single_writable_for_deploy "$f"
  done
  ensure_writable_for_deploy "$REPO_DIR/.git"
  ensure_writable_for_deploy "$FE_DIR"
  ensure_writable_for_deploy "$BE_DIR/src"
  ensure_writable_for_deploy "$BE_DIR/gradle"
  ensure_writable_for_deploy "$REPO_DIR/scripts"

  cd "$REPO_DIR"
  OLD_COMMIT="$(run_deploy git rev-parse --short HEAD 2>/dev/null || echo 'unknown')"
  run_deploy git fetch --all --prune
  run_deploy git rev-parse --verify "origin/$BRANCH" >/dev/null 2>&1 || die "원격 브랜치 'origin/$BRANCH'를 찾을 수 없습니다."
  run_deploy git checkout -f "$BRANCH" >/dev/null 2>&1 || run_deploy git checkout -B "$BRANCH" "origin/$BRANCH"
  run_deploy git reset --hard "origin/$BRANCH"
  NEW_COMMIT="$(run_deploy git rev-parse --short HEAD)"
  COMMIT_MSG="$(run_deploy git log -n 1 --pretty=format:'%s')"
  NEW_LOCK_HASH="$(sha256_or_empty "$LOCK_FILE")"

  echo "  • 커밋 반영: $OLD_COMMIT -> $NEW_COMMIT ($COMMIT_MSG)"
else
  log "1/3 --no-pull 옵션으로 Git 동기화를 건너뜁니다."
fi

# ------------------------------------------------------------------------------
# 2. 프론트엔드 (FE) 빌드 및 Nginx 반영
# ------------------------------------------------------------------------------
if [[ "$DO_FE" -eq 1 ]]; then
  log "2/3 프론트엔드(FE) 빌드 중..."
  command -v node >/dev/null 2>&1 || die "node가 설치되어 있지 않습니다."
  command -v npm >/dev/null 2>&1 || die "npm이 설치되어 있지 않습니다."

  cd "$FE_DIR"
  ensure_writable_for_deploy "$FE_DIR"

  if [[ "$FORCE_NPM_CI" -eq 1 ]] || [[ ! -d "$FE_DIR/node_modules" ]] || [[ "$PREV_LOCK_HASH" != "$NEW_LOCK_HASH" ]]; then
    echo "  • npm ci 실행 (의존성 동기화)..."
    run_deploy npm ci
  else
    echo "  • package-lock.json 변경 없음 — npm ci 생략"
  fi

  if [[ "$WITH_TEST" -eq 1 ]]; then
    echo "  • 프론트엔드 린트(npm run lint) 검증..."
    run_deploy npm run lint
  fi

  echo "  • Vite 프로덕션 번들 빌드..."
  if [[ -e "$FE_DIR/dist" ]]; then
    ensure_writable_for_deploy "$FE_DIR/dist"
  fi
  rm -rf "$FE_DIR/dist" 2>/dev/null || run_priv rm -rf "$FE_DIR/dist"
  run_deploy npm run build
  chmod -R a+rX "$FE_DIR/dist" 2>/dev/null || run_priv chmod -R a+rX "$FE_DIR/dist"

  if [[ "$DO_RESTART" -eq 1 ]] && command -v systemctl >/dev/null 2>&1; then
    if systemctl is-active --quiet nginx 2>/dev/null; then
      echo "  • Nginx 설정 검증 및 리로드 (sudo)..."
      run_priv nginx -t
      run_priv systemctl reload nginx
    fi
  fi
  echo "  • FE 빌드 완료: $FE_DIR/dist"
else
  log "2/3 --be-only 옵션으로 프론트엔드 빌드를 건너뜁니다."
fi

# ------------------------------------------------------------------------------
# 3. 백엔드 (BE) 클린 빌드 및 systemd 서비스 재시작
# ------------------------------------------------------------------------------
if [[ "$DO_BE" -eq 1 ]]; then
  BACKEND_SERVICE="$(detect_backend_service)"
  log "3/3 백엔드(BE) 빌드 및 '$BACKEND_SERVICE' 적용 중..."
  command -v java >/dev/null 2>&1 || die "java가 설치되어 있지 않습니다."

  cd "$BE_DIR"
  ensure_single_writable_for_deploy "$BE_DIR"
  for f in "$BE_DIR"/build.gradle.kts "$BE_DIR"/settings.gradle.kts "$BE_DIR"/gradle.properties "$BE_DIR"/gradlew "$BE_DIR"/admin-ctl.sh; do
    ensure_single_writable_for_deploy "$f"
  done
  ensure_writable_for_deploy "$BE_DIR/src"
  ensure_writable_for_deploy "$BE_DIR/gradle"
  ensure_writable_for_deploy "$BE_DIR/build"
  ensure_writable_for_deploy "$BE_DIR/.gradle"
  chmod +x "$BE_DIR/gradlew" 2>/dev/null || run_priv chmod +x "$BE_DIR/gradlew"
  if [[ -f "$BE_DIR/admin-ctl.sh" ]]; then
    chmod +x "$BE_DIR/admin-ctl.sh" 2>/dev/null || run_priv chmod +x "$BE_DIR/admin-ctl.sh"
  fi

  run_deploy ./gradlew --stop >/dev/null 2>&1 || true

  if [[ "$CLEAN_GRADLE" -eq 1 ]]; then
    echo "  • --clean-gradle: .gradle 및 build 캐시 삭제..."
    rm -rf "$BE_DIR/.gradle" "$BE_DIR/build" 2>/dev/null || run_priv rm -rf "$BE_DIR/.gradle" "$BE_DIR/build"
  fi

  GRADLE_TASKS="clean installDist"
  if [[ "$WITH_TEST" -eq 1 ]]; then
    GRADLE_TASKS="clean test installDist"
    echo "  • 백엔드 단위/보안 테스트 포함 빌드 실행 ($GRADLE_TASKS)..."
  else
    echo "  • 백엔드 클린 빌드 실행 ($GRADLE_TASKS)..."
  fi

  # shellcheck disable=SC2086
  run_deploy ./gradlew $GRADLE_TASKS --no-daemon

  BACKEND_BIN="$BE_DIR/build/install/backend/bin/backend"
  [[ -f "$BACKEND_BIN" ]] || die "백엔드 실행 파일이 생성되지 않았습니다: $BACKEND_BIN"
  chmod +x "$BACKEND_BIN" 2>/dev/null || run_priv chmod +x "$BACKEND_BIN"

  # 런타임 데이터 및 제어 소켓 시크릿 파일 소유권 보장 (서비스 계정: $SERVICE_USER)
  run_priv mkdir -p "$BE_DIR/uploads/attachments"
  if id "$SERVICE_USER" >/dev/null 2>&1; then
    SERVICE_GROUP="$(id -gn "$SERVICE_USER" 2>/dev/null || echo "$SERVICE_USER")"
    echo "  • 런타임 소유권 및 시크릿 권한 설정 (sudo -> $SERVICE_USER:$SERVICE_GROUP)..."
    run_priv chown -R "$SERVICE_USER:$SERVICE_GROUP" "$BE_DIR/build/install" "$BE_DIR/uploads"
    if ! run_priv test -f "$BE_DIR/.admin-ctl.secret"; then
      run_priv touch "$BE_DIR/.admin-ctl.secret"
    fi
    run_priv chown "$SERVICE_USER:$SERVICE_GROUP" "$BE_DIR/.admin-ctl.secret"
    run_priv chmod 600 "$BE_DIR/.admin-ctl.secret"
  else
    warn "서비스 계정 '$SERVICE_USER'를 찾을 수 없어 chown 단계를 건너뜁니다."
  fi

  if [[ "$DO_RESTART" -eq 1 ]]; then
    command -v systemctl >/dev/null 2>&1 || die "systemctl을 찾을 수 없습니다. (--no-restart 사용 가능)"
    echo "  • systemd 서비스 재시작 (sudo): $BACKEND_SERVICE"
    run_priv systemctl restart "$BACKEND_SERVICE"

    echo "  • 백엔드 기동 및 헬스체크 확인 중 (http://127.0.0.1:${BACKEND_PORT}/)..."
    HEALTH_OK=0
    for _ in $(seq 1 25); do
      if systemctl is-active --quiet "$BACKEND_SERVICE" 2>/dev/null || run_priv systemctl is-active --quiet "$BACKEND_SERVICE"; then
        if command -v curl >/dev/null 2>&1; then
          if curl -fsS --max-time 2 "http://127.0.0.1:${BACKEND_PORT}/" >/dev/null 2>&1; then
            HEALTH_OK=1
            break
          fi
        else
          HEALTH_OK=1
          break
        fi
      fi
      sleep 1
    done

    if [[ "$HEALTH_OK" -ne 1 ]]; then
      warn "'$BACKEND_SERVICE' 서비스 상태 확인에 실패했습니다. 최근 로그를 출력합니다:"
      run_priv systemctl status "$BACKEND_SERVICE" --no-pager || true
      run_priv journalctl -u "$BACKEND_SERVICE" -n 50 --no-pager || true
      exit 1
    fi

    echo "  • 서비스 정상 기동 확인 완료 ($BACKEND_SERVICE)"
    run_priv systemctl status "$BACKEND_SERVICE" --no-pager --lines=8 || true
    echo "--- 최근 로그 (15줄) ---"
    run_priv journalctl -u "$BACKEND_SERVICE" -n 15 --no-pager || true
  else
    echo "  • --no-restart 옵션으로 서비스 재시작을 건너뛰었습니다."
  fi
else
  log "3/3 --fe-only 옵션으로 백엔드 빌드를 건너뜁니다."
fi

log "FE/BE 일괄 업데이트가 완료되었습니다."
