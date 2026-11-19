#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# CISTUS (광운대학교 교수질문센터) FE/BE 일괄 빌드 및 서비스 반영 스크립트
# - 코나미 버블 시스템(Konami Bubble System, 1985) 스타일 부팅/워밍업 진행률 표시
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
PLAIN_OUTPUT="${PLAIN_OUTPUT:-${NO_BUBBLE_UI:-0}}"
BUBBLE_DEMO=0

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
  --plain                 ANSI 애니메이션 없이 버블 시스템 일반 텍스트 로그 출력
  --bubble-demo           실제 배포 없이 버블 시스템 부팅 진행 화면 미리보기
  -h, --help              도움말 출력

환경변수로도 지정 가능: REPO_DIR, BRANCH, BACKEND_SERVICE, SERVICE_USER, BACKEND_PORT, PLAIN_OUTPUT, NO_BUBBLE_UI
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
    --plain)
      PLAIN_OUTPUT=1; shift ;;
    --bubble-demo)
      BUBBLE_DEMO=1; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "[ERROR] 알 수 없는 옵션입니다: $1" >&2
      usage
      exit 1 ;;
  esac
done

# ------------------------------------------------------------------------------
# 터미널 / 로그 / 버블 시스템 UI 상태 관리
# ------------------------------------------------------------------------------
BUBBLE_UI=0
if [[ "$PLAIN_OUTPUT" != "1" ]] && [[ -t 1 ]] && [[ "${TERM:-dumb}" != "dumb" ]]; then
  BUBBLE_UI=1
fi

UPDATE_LOG_FILE="$(mktemp /tmp/cistus-update.XXXXXX.log 2>/dev/null || echo "/tmp/cistus-update-$$.log")"
STEP_LOG="$(mktemp /tmp/cistus-step.XXXXXX.log 2>/dev/null || echo "/tmp/cistus-step-$$.log")"
: > "$UPDATE_LOG_FILE" 2>/dev/null || true
: > "$STEP_LOG" 2>/dev/null || true

CURRENT_CMD_PID=""
BUBBLE_STARTED=0
BUBBLE_TICK=0
CURRENT_PCT=0
UPLOADS_CHOWNED_FOR_PULL=0
UPDATE_SUCCEEDED=0

STAGE_NAMES=("GIT SOURCE SYNC" "FRONTEND BUILD" "BACKEND BUILD" "SERVICE STARTUP")
STAGE_STATUS=("WAIT" "WAIT" "WAIT" "WAIT")
STAGE_DETAIL=("" "" "" "")
STAGE_START=(0 0 0 0)
STAGE_END=(0 0 0 0)

log() {
  if [[ "$BUBBLE_UI" -eq 1 ]]; then
    printf '\n[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$UPDATE_LOG_FILE"
  else
    printf '\n[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$UPDATE_LOG_FILE"
  fi
}

warn() {
  printf '[WARN] %s\n' "$*" | tee -a "$UPDATE_LOG_FILE" >&2
}

die() {
  if [[ "$BUBBLE_STARTED" -eq 1 && "$BUBBLE_UI" -eq 1 ]]; then
    bubble_end_screen
  fi
  printf '[ERROR] %s\n' "$*" | tee -a "$UPDATE_LOG_FILE" >&2
  printf '        전체 로그: %s\n' "$UPDATE_LOG_FILE" >&2
  exit 1
}

bubble_count_from_pct() {
  local pct="${1:-0}"
  if (( pct >= 100 )); then
    printf '00'
    return 0
  fi
  if (( pct < 0 )); then
    pct=0
  fi
  local cnt=$(( 99 - (pct * 99 / 100) ))
  if (( cnt < 1 && pct < 100 )); then
    cnt=1
  fi
  printf '%02d' "$cnt"
}

bubble_bar() {
  local pct="${1:-0}"
  local width="${2:-34}"
  if (( pct < 0 )); then pct=0; fi
  if (( pct > 100 )); then pct=100; fi
  local filled=$(( pct * width / 100 ))
  local empty=$(( width - filled ))
  local bar_fill=""
  local bar_empty=""
  local i=0
  while (( i < filled )); do
    bar_fill="${bar_fill}█"
    i=$(( i + 1 ))
  done
  i=0
  while (( i < empty )); do
    bar_empty="${bar_empty}░"
    i=$(( i + 1 ))
  done
  printf '%s%s' "$bar_fill" "$bar_empty"
}

sanitize_ticker_line() {
  local raw="${1:-}"
  local clean
  clean="$(printf '%s' "$raw" \
    | sed -E $'s/\x1B\\[[0-9;?]*[ -/]*[@-~]//g' \
    | tr '\r\n\t' '   ' \
    | tr -d '\000-\037\177' \
    | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/  +/ /g')"
  if (( ${#clean} > 62 )); then
    clean="${clean:0:59}..."
  fi
  printf '%s' "$clean"
}

compute_stage_ranges() {
  local weights=(0 0 0 0)
  if [[ "$DO_PULL" -eq 1 ]]; then
    weights[0]=20
    STAGE_STATUS[0]="WAIT"
    STAGE_DETAIL[0]="($BRANCH)"
  else
    STAGE_STATUS[0]="SKIP"
    STAGE_DETAIL[0]="(skipped)"
  fi

  if [[ "$DO_FE" -eq 1 ]]; then
    weights[1]=30
    STAGE_STATUS[1]="WAIT"
    STAGE_DETAIL[1]="(Vite)"
  else
    STAGE_STATUS[1]="SKIP"
    STAGE_DETAIL[1]="(skipped)"
  fi

  if [[ "$DO_BE" -eq 1 ]]; then
    weights[2]=40
    STAGE_STATUS[2]="WAIT"
    STAGE_DETAIL[2]="(Gradle)"
  else
    STAGE_STATUS[2]="SKIP"
    STAGE_DETAIL[2]="(skipped)"
  fi

  if [[ "$DO_BE" -eq 1 && "$DO_RESTART" -eq 1 ]]; then
    weights[3]=10
    STAGE_STATUS[3]="WAIT"
    STAGE_DETAIL[3]="(systemd)"
  else
    STAGE_STATUS[3]="SKIP"
    STAGE_DETAIL[3]="(skipped)"
  fi

  local total=$(( weights[0] + weights[1] + weights[2] + weights[3] ))
  if (( total <= 0 )); then
    STAGE_START=(0 0 0 0)
    STAGE_END=(100 100 100 100)
    return 0
  fi

  local acc=0
  local prev_end=0
  local idx=0
  local last_active=-1
  while (( idx < 4 )); do
    if (( weights[idx] > 0 )); then
      last_active=$idx
    fi
    idx=$(( idx + 1 ))
  done

  idx=0
  while (( idx < 4 )); do
    if (( weights[idx] == 0 )); then
      STAGE_START[idx]=$prev_end
      STAGE_END[idx]=$prev_end
    else
      STAGE_START[idx]=$prev_end
      acc=$(( acc + weights[idx] ))
      if (( idx == last_active )); then
        STAGE_END[idx]=100
      else
        STAGE_END[idx]=$(( acc * 100 / total ))
      fi
      prev_end="${STAGE_END[idx]}"
    fi
    idx=$(( idx + 1 ))
  done
}

bubble_start_screen() {
  if [[ "$BUBBLE_UI" -eq 1 && "$BUBBLE_STARTED" -eq 0 ]]; then
    BUBBLE_STARTED=1
    printf '\033[?25l\033[2J\033[H'
  fi
}

bubble_end_screen() {
  if [[ "$BUBBLE_STARTED" -eq 1 ]]; then
    printf '\033[?25h\033[0m\n'
    BUBBLE_STARTED=0
  fi
}

bubble_render() {
  local mode="${1:-warming}"
  local stage_label="${2:-INITIALIZING}"
  local pct="${3:-0}"
  local ticker="${4:-...}"

  if (( pct < 0 )); then pct=0; fi
  if (( pct > 100 )); then pct=100; fi
  CURRENT_PCT="$pct"

  local cnt
  cnt="$(bubble_count_from_pct "$pct")"

  if [[ "$BUBBLE_UI" -ne 1 ]]; then
    printf '[BUBBLE %s | %3d%%] %-24s | %s\n' "$cnt" "$pct" "$stage_label" "$ticker"
    return 0
  fi

  bubble_start_screen
  BUBBLE_TICK=$(( BUBBLE_TICK + 1 ))

  local c_reset=$'\033[0m'
  local c_gold=$'\033[1;33m'
  local c_cyan=$'\033[1;36m'
  local c_dcyan=$'\033[0;36m'
  local c_white=$'\033[1;37m'
  local c_green=$'\033[1;32m'
  local c_red=$'\033[1;31m'
  local c_dim=$'\033[2;37m'
  local c_blue=$'\033[1;34m'

  local glyphs=('°' 'o' 'O' '◯' 'O' 'o' '·' ' ')
  local n_glyphs=${#glyphs[@]}
  local b0="${glyphs[$(( BUBBLE_TICK % n_glyphs ))]}"
  local b1="${glyphs[$(( (BUBBLE_TICK + 2) % n_glyphs ))]}"
  local b2="${glyphs[$(( (BUBBLE_TICK + 4) % n_glyphs ))]}"
  local b3="${glyphs[$(( (BUBBLE_TICK + 6) % n_glyphs ))]}"

  local banner_line=""
  local sub_banner=""
  case "$mode" in
    ready)
      banner_line="${c_green}***  W A R M I N G   U P   C O M P L E T E D  ***${c_reset}"
      sub_banner="${c_cyan}ALL SYSTEMS OK  —  READY FOR OPERATION!${c_reset}"
      ;;
    error)
      banner_line="${c_red}***  B U B B L E   S Y S T E M   E R R O R  ***${c_reset}"
      sub_banner="${c_red}CHECK FAILED  —  UPDATE HALTED${c_reset}"
      ;;
    *)
      if (( BUBBLE_TICK % 2 == 0 )); then
        banner_line="${c_gold}***  W A R M I N G   U P   N O W  ***${c_reset}"
      else
        banner_line="${c_white}***  W A R M I N G   U P   N O W  ***${c_reset}"
      fi
      sub_banner="${c_cyan}B U B B L E   M E M O R Y   L O A D I N G . . .${c_reset}"
      ;;
  esac

  local bar
  bar="$(bubble_bar "$pct" 34)"
  local clean_ticker
  clean_ticker="$(sanitize_ticker_line "$ticker")"
  if [[ -z "$clean_ticker" ]]; then
    clean_ticker="$stage_label"
  fi

  local out=""
  local hr="══════════════════════════════════════════════════════════════════════"
  out+=$'\033[H'
  out+="${c_cyan}${hr}${c_reset}"$'\033[K\n'
  out+="  ${c_blue}${b0} ${b2}${c_reset}   ${c_red}C   I   S   T   U   S${c_reset}     ${c_gold}B U B B L E   S Y S T E M${c_reset}     ${c_cyan}${b1} ${b3}${c_reset}"$'\033[K\n'
  out+="  ${c_dcyan}${b3}${c_reset}     ${c_dim}PRESENTED BY KWANGWOON UNIV. Q&A PORTAL  //  AUTO DEPLOYER${c_reset}   ${c_blue}${b0}${c_reset}"$'\033[K\n'
  out+="${c_cyan}${hr}${c_reset}"$'\033[K\n'
  out+=$'\033[K\n'
  out+="      ${banner_line}"$'\033[K\n'
  out+="      ${sub_banner}"$'\033[K\n'
  out+=$'\033[K\n'
  out+="    ${c_white}WARM-UP COUNT :${c_reset} ${c_gold}[ ${cnt} ]${c_reset}   ${c_cyan}[${bar}]${c_reset} ${c_white}$(printf '%3d%%' "$pct")${c_reset}"$'\033[K\n'
  out+=$'\033[K\n'
  out+="${c_dcyan}──────────────────────────────────────────────────────────────────────${c_reset}"$'\033[K\n'

  local idx=0
  while (( idx < 4 )); do
    local s_name="${STAGE_NAMES[$idx]}"
    local s_stat="${STAGE_STATUS[$idx]}"
    local s_det="${STAGE_DETAIL[$idx]}"
    local badge=""
    local lcolor="$c_dim"
    case "$s_stat" in
      OK)
        badge="${c_green}[ OK ]${c_reset}"
        lcolor="$c_white"
        ;;
      WARMING)
        if (( BUBBLE_TICK % 2 == 0 )); then
          badge="${c_gold}[ >> ]${c_reset}"
        else
          badge="${c_cyan}[ >> ]${c_reset}"
        fi
        lcolor="$c_gold"
        ;;
      NG)
        badge="${c_red}[ NG ]${c_reset}"
        lcolor="$c_red"
        ;;
      SKIP)
        badge="${c_dim}[ -- ]${c_reset}"
        lcolor="$c_dim"
        ;;
      *)
        badge="${c_dcyan}[ .. ]${c_reset}"
        lcolor="$c_dim"
        ;;
    esac
    local pad_dots=".................."
    local pad_len=$(( 18 - ${#s_name} ))
    if (( pad_len < 2 )); then pad_len=2; fi
    local dots="${pad_dots:0:$pad_len}"
    out+="    ${c_blue}${b1}${c_reset}  ${lcolor}${s_name}${c_reset} ${c_dim}${dots}${c_reset} ${badge}  ${c_dim}${s_det}${c_reset}"$'\033[K\n'
    idx=$(( idx + 1 ))
  done

  out+="${c_dcyan}──────────────────────────────────────────────────────────────────────${c_reset}"$'\033[K\n'
  out+="    ${c_cyan}STAGE  :${c_reset} ${c_white}${stage_label}${c_reset}"$'\033[K\n'
  out+="    ${c_cyan}STATUS :${c_reset} ${c_dcyan}${clean_ticker}${c_reset}"$'\033[K\n'
  out+=$'\033[K\n'
  out+="  ${c_dim}♪ MORNING MUSIC  ·  BUBBLE MEMORY CHECK  ·  LOG: ${UPDATE_LOG_FILE}${c_reset}"$'\033[K\n'
  out+="${c_cyan}${hr}${c_reset}"$'\033[K\033[J'

  printf '%s' "$out"
}

run_bubble_cmd() {
  local stage_idx="$1"
  local stage_label="$2"
  local start_pct="$3"
  local end_pct="$4"
  shift 4

  if (( CURRENT_PCT < start_pct )); then
    CURRENT_PCT="$start_pct"
  fi

  if [[ "$BUBBLE_UI" -ne 1 ]]; then
    bubble_render "warming" "$stage_label" "$CURRENT_PCT" "$stage_label..."
    printf '\n=== [%s] %s ===\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$stage_label" >> "$UPDATE_LOG_FILE"
    set +e
    "$@" 2>&1 | tee -a "$UPDATE_LOG_FILE"
    local rc="${PIPESTATUS[0]}"
    set -e
    if (( rc != 0 )); then
      STAGE_STATUS[stage_idx]="NG"
      die "단계 실패 ($stage_label, exit=$rc)"
    fi
    CURRENT_PCT="$end_pct"
    return 0
  fi

  : > "$STEP_LOG"
  printf '\n=== [%s] %s ===\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$stage_label" >> "$UPDATE_LOG_FILE"
  bubble_render "warming" "$stage_label" "$CURRENT_PCT" "$stage_label..."

  "$@" >"$STEP_LOG" 2>&1 &
  CURRENT_CMD_PID=$!

  local tick_elapsed=0
  local ceiling=$(( end_pct > start_pct ? end_pct - 1 : start_pct ))
  while kill -0 "$CURRENT_CMD_PID" 2>/dev/null; do
    tick_elapsed=$(( tick_elapsed + 1 ))
    if (( CURRENT_PCT < ceiling )); then
      if (( tick_elapsed % 3 == 0 )); then
        CURRENT_PCT=$(( CURRENT_PCT + 1 ))
      fi
    fi
    local last_line=""
    last_line="$(awk 'NF{line=$0} END{print line}' "$STEP_LOG" 2>/dev/null || true)"
    bubble_render "warming" "$stage_label" "$CURRENT_PCT" "${last_line:-$stage_label...}"
    sleep 0.18
  done

  local rc=0
  wait "$CURRENT_CMD_PID" || rc=$?
  CURRENT_CMD_PID=""

  if [[ -s "$STEP_LOG" ]]; then
    cat "$STEP_LOG" >> "$UPDATE_LOG_FILE"
  fi

  if (( rc != 0 )); then
    STAGE_STATUS[stage_idx]="NG"
    local err_line=""
    err_line="$(awk 'NF{line=$0} END{print line}' "$STEP_LOG" 2>/dev/null || true)"
    bubble_render "error" "$stage_label (FAILED)" "$CURRENT_PCT" "${err_line:-exit=$rc}"
    bubble_end_screen
    printf '\n[ERROR] 단계 실패: %s (exit=%d)\n' "$stage_label" "$rc" >&2
    printf '--- 마지막 출력 (최대 40줄) ---\n' >&2
    tail -n 40 "$STEP_LOG" >&2 || true
    printf '\n전체 상세 로그: %s\n' "$UPDATE_LOG_FILE" >&2
    exit "$rc"
  fi

  CURRENT_PCT="$end_pct"
  bubble_render "warming" "$stage_label" "$CURRENT_PCT" "DONE: $stage_label"
  return 0
}

run_bubble_demo() {
  compute_stage_ranges
  local d_step=0
  local demo_stages=(0 1 2 3)
  local demo_labels=(
    "GIT FETCH & SYNC (origin/$BRANCH)"
    "VITE PRODUCTION BUNDLE BUILD"
    "GRADLE BACKEND CLEAN DIST BUILD"
    "SYSTEMD RESTART & HEALTHCHECK"
  )
  local demo_details=(
    "(a1b2c3d -> e4f5a6b)"
    "(Vite dist ready)"
    "(clean installDist)"
    "(cistus-be active)"
  )
  local demo_tickers=(
    "From origin: main -> FETCH_HEAD updated"
    "dist/assets/index.js 62.6 kB | built in 1.12s"
    "> Task :installDist — BUILD SUCCESSFUL"
    "HTTP 200 OK (127.0.0.1:${BACKEND_PORT})"
  )

  for idx in "${demo_stages[@]}"; do
    if [[ "${STAGE_STATUS[$idx]}" == "SKIP" ]]; then
      continue
    fi
    STAGE_STATUS[idx]="WARMING"
    local s="${STAGE_START[$idx]}"
    local e="${STAGE_END[$idx]}"
    local p="$s"
    local step_inc=$(( (e - s) / 4 ))
    if (( step_inc < 1 )); then step_inc=1; fi
    while (( p < e )); do
      bubble_render "warming" "${demo_labels[$idx]}" "$p" "${demo_tickers[$idx]}"
      if [[ "$BUBBLE_UI" -eq 1 ]]; then
        sleep 0.16
      else
        sleep 0.03
      fi
      p=$(( p + step_inc ))
    done
    STAGE_STATUS[idx]="OK"
    STAGE_DETAIL[idx]="${demo_details[$idx]}"
    CURRENT_PCT="$e"
    bubble_render "warming" "${demo_labels[$idx]}" "$e" "DONE: ${demo_labels[$idx]}"
    d_step=$(( d_step + 1 ))
  done

  bubble_render "ready" "ALL SYSTEMS READY" 100 "WARMING UP COMPLETED (DEMO · ${d_step} STAGES)"
  if [[ "$BUBBLE_UI" -eq 1 ]]; then
    sleep 0.8
    bubble_end_screen
  fi
  printf '[CISTUS] Bubble System demo finished (log: %s)\n' "$UPDATE_LOG_FILE"
  UPDATE_SUCCEEDED=1
  exit 0
}

cleanup_on_exit() {
  if [[ -n "${CURRENT_CMD_PID:-}" ]] && kill -0 "$CURRENT_CMD_PID" 2>/dev/null; then
    kill "$CURRENT_CMD_PID" 2>/dev/null || true
    wait "$CURRENT_CMD_PID" 2>/dev/null || true
    CURRENT_CMD_PID=""
  fi
  if [[ "${BUBBLE_STARTED:-0}" -eq 1 ]]; then
    bubble_end_screen
  fi
  rm -f "${STEP_LOG:-}" 2>/dev/null || true
  if [[ "${UPDATE_SUCCEEDED:-0}" -eq 1 ]]; then
    rm -f "${UPDATE_LOG_FILE:-}" 2>/dev/null || true
  fi
  if [[ "${UPLOADS_CHOWNED_FOR_PULL:-0}" -eq 1 ]]; then
    restore_uploads_owner || true
  fi
}
trap cleanup_on_exit EXIT INT TERM

if [[ "$BUBBLE_DEMO" -eq 1 ]]; then
  run_bubble_demo
fi

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

restore_uploads_owner() {
  if [[ -d "$BE_DIR/uploads" ]] && id "$SERVICE_USER" >/dev/null 2>&1; then
    local service_group
    service_group="$(id -gn "$SERVICE_USER" 2>/dev/null || echo "$SERVICE_USER")"
    run_priv chown -R "$SERVICE_USER:$service_group" "$BE_DIR/uploads"
  fi
  UPLOADS_CHOWNED_FOR_PULL=0
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
# 사전 sudo 인증 (애니메이션 시작 전 대화형 프롬프트 처리)
# ------------------------------------------------------------------------------
FE_DIR="$REPO_DIR/proj/frontend"
BE_DIR="$REPO_DIR/proj/backend"
LOCK_FILE="$FE_DIR/package-lock.json"
PREV_LOCK_HASH="$(sha256_or_empty "$LOCK_FILE")"
NEW_LOCK_HASH="$PREV_LOCK_HASH"
OLD_COMMIT="local"
NEW_COMMIT="local"
COMMIT_MSG=""

if [[ "${EUID:-$(id -u)}" -ne 0 ]] && [[ "$DO_BE" -eq 1 || "$DO_RESTART" -eq 1 ]]; then
  prime_sudo
fi

compute_stage_ranges
bubble_render "warming" "BUBBLE MEMORY INITIALIZE" 0 "Checking modules and environment..."

# ------------------------------------------------------------------------------
# 1. Git 소스 동기화 (Stage 0)
# ------------------------------------------------------------------------------
if [[ "$DO_PULL" -eq 1 ]]; then
  command -v git >/dev/null 2>&1 || die "git 명령어를 찾을 수 없습니다."
  STAGE_STATUS[0]="WARMING"
  local_start="${STAGE_START[0]}"
  local_end="${STAGE_END[0]}"
  local_mid=$(( local_start + (local_end - local_start) / 2 ))

  bubble_render "warming" "GIT PERMISSION CHECK" "$local_start" "Checking repository ownership..."

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

  run_bubble_cmd 0 "GIT FETCH (origin/$BRANCH)" "$local_start" "$local_mid" \
    run_deploy git fetch --all --prune

  run_deploy git rev-parse --verify "origin/$BRANCH" >/dev/null 2>&1 || die "원격 브랜치 'origin/$BRANCH'를 찾을 수 없습니다."

  # uploads 디렉터리가 www-data 등 서비스 계정 소유일 때 Git 추적 파일 갱신이 Permission denied로 실패하지 않도록
  # 동기화 동안만 임시로 배포 계정 쓰기 권한을 부여하고, 완료/종료 시 즉시 서비스 계정으로 원복한다.
  if [[ -d "$BE_DIR/uploads" ]]; then
    if [[ "$DO_BE" -eq 1 ]] \
       || ! run_deploy git diff --quiet HEAD "origin/$BRANCH" -- "$BE_DIR/uploads" 2>/dev/null \
       || [[ -n "$(run_deploy git status --porcelain -- "$BE_DIR/uploads" 2>/dev/null)" ]]; then
      ensure_writable_for_deploy "$BE_DIR/uploads"
      UPLOADS_CHOWNED_FOR_PULL=1
    fi
  fi

  sync_repo_to_remote() {
    run_deploy git checkout -f "$BRANCH" >/dev/null 2>&1 || run_deploy git checkout -B "$BRANCH" "origin/$BRANCH"
    run_deploy git reset --hard "origin/$BRANCH"
  }
  run_bubble_cmd 0 "GIT RESET (origin/$BRANCH)" "$local_mid" "$local_end" sync_repo_to_remote

  if [[ "$UPLOADS_CHOWNED_FOR_PULL" -eq 1 ]]; then
    restore_uploads_owner
  fi

  NEW_COMMIT="$(run_deploy git rev-parse --short HEAD)"
  COMMIT_MSG="$(run_deploy git log -n 1 --pretty=format:'%s')"
  NEW_LOCK_HASH="$(sha256_or_empty "$LOCK_FILE")"

  STAGE_STATUS[0]="OK"
  STAGE_DETAIL[0]="($OLD_COMMIT -> $NEW_COMMIT)"
  bubble_render "warming" "GIT SOURCE SYNCED" "$local_end" "$OLD_COMMIT -> $NEW_COMMIT ($COMMIT_MSG)"
else
  OLD_COMMIT="$(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo 'local')"
  NEW_COMMIT="$OLD_COMMIT"
fi

# ------------------------------------------------------------------------------
# 2. 프론트엔드 (FE) 빌드 및 Nginx 반영 (Stage 1)
# ------------------------------------------------------------------------------
if [[ "$DO_FE" -eq 1 ]]; then
  command -v node >/dev/null 2>&1 || die "node가 설치되어 있지 않습니다."
  command -v npm >/dev/null 2>&1 || die "npm이 설치되어 있지 않습니다."
  STAGE_STATUS[1]="WARMING"

  fe_start="${STAGE_START[1]}"
  fe_end="${STAGE_END[1]}"
  fe_span=$(( fe_end - fe_start ))
  fe_after_ci=$(( fe_start + fe_span * 30 / 100 ))
  fe_after_lint=$(( fe_start + fe_span * 45 / 100 ))
  fe_after_build=$(( fe_start + fe_span * 90 / 100 ))

  cd "$FE_DIR"
  ensure_writable_for_deploy "$FE_DIR"

  if [[ "$FORCE_NPM_CI" -eq 1 ]] || [[ ! -d "$FE_DIR/node_modules" ]] || [[ "$PREV_LOCK_HASH" != "$NEW_LOCK_HASH" ]]; then
    run_bubble_cmd 1 "FRONTEND NPM CI" "$fe_start" "$fe_after_ci" run_deploy npm ci
  else
    CURRENT_PCT="$fe_after_ci"
    bubble_render "warming" "FRONTEND DEPENDENCIES OK" "$CURRENT_PCT" "package-lock.json unchanged"
  fi

  if [[ "$WITH_TEST" -eq 1 ]]; then
    run_bubble_cmd 1 "FRONTEND LINT (oxlint)" "$fe_after_ci" "$fe_after_lint" run_deploy npm run lint
  else
    CURRENT_PCT="$fe_after_lint"
  fi

  if [[ -e "$FE_DIR/dist" ]]; then
    ensure_writable_for_deploy "$FE_DIR/dist"
  fi
  rm -rf "$FE_DIR/dist" 2>/dev/null || run_priv rm -rf "$FE_DIR/dist"

  run_bubble_cmd 1 "VITE PRODUCTION BUILD" "$fe_after_lint" "$fe_after_build" run_deploy npm run build
  chmod -R a+rX "$FE_DIR/dist" 2>/dev/null || run_priv chmod -R a+rX "$FE_DIR/dist"

  if [[ "$DO_RESTART" -eq 1 ]] && command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet nginx 2>/dev/null; then
    reload_nginx_fn() {
      run_priv nginx -t
      run_priv systemctl reload nginx
    }
    run_bubble_cmd 1 "NGINX RELOAD" "$fe_after_build" "$fe_end" reload_nginx_fn
  else
    CURRENT_PCT="$fe_end"
  fi

  STAGE_STATUS[1]="OK"
  STAGE_DETAIL[1]="(Vite dist ready)"
  bubble_render "warming" "FRONTEND BUILD COMPLETED" "$fe_end" "dist ready ($FE_DIR/dist)"
fi

# ------------------------------------------------------------------------------
# 3. 백엔드 (BE) 클린 빌드 (Stage 2)
# ------------------------------------------------------------------------------
if [[ "$DO_BE" -eq 1 ]]; then
  BACKEND_SERVICE="$(detect_backend_service)"
  command -v java >/dev/null 2>&1 || die "java가 설치되어 있지 않습니다."
  STAGE_STATUS[2]="WARMING"

  be_start="${STAGE_START[2]}"
  be_end="${STAGE_END[2]}"
  be_span=$(( be_end - be_start ))
  be_after_prep=$(( be_start + be_span * 15 / 100 ))
  be_after_gradle=$(( be_start + be_span * 92 / 100 ))

  bubble_render "warming" "BACKEND PREPARE ($BACKEND_SERVICE)" "$be_start" "Checking Gradle & permissions..."

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
    bubble_render "warming" "BACKEND CLEAN CACHE" "$be_start" "Removing .gradle & build caches..."
    rm -rf "$BE_DIR/.gradle" "$BE_DIR/build" 2>/dev/null || run_priv rm -rf "$BE_DIR/.gradle" "$BE_DIR/build"
  fi
  CURRENT_PCT="$be_after_prep"

  GRADLE_TASKS="clean installDist"
  if [[ "$WITH_TEST" -eq 1 ]]; then
    GRADLE_TASKS="clean test installDist"
  fi

  # shellcheck disable=SC2086
  run_bubble_cmd 2 "GRADLE ($GRADLE_TASKS)" "$be_after_prep" "$be_after_gradle" \
    run_deploy ./gradlew $GRADLE_TASKS --no-daemon

  BACKEND_BIN="$BE_DIR/build/install/backend/bin/backend"
  [[ -f "$BACKEND_BIN" ]] || die "백엔드 실행 파일이 생성되지 않았습니다: $BACKEND_BIN"
  chmod +x "$BACKEND_BIN" 2>/dev/null || run_priv chmod +x "$BACKEND_BIN"

  # 런타임 데이터 및 제어 소켓 시크릿 파일 소유권 보장 (서비스 계정: $SERVICE_USER)
  fix_be_ownership_fn() {
    run_priv mkdir -p "$BE_DIR/uploads/attachments"
    if id "$SERVICE_USER" >/dev/null 2>&1; then
      local service_group
      service_group="$(id -gn "$SERVICE_USER" 2>/dev/null || echo "$SERVICE_USER")"
      run_priv chown -R "$SERVICE_USER:$service_group" "$BE_DIR/build/install" "$BE_DIR/uploads"
      if ! run_priv test -f "$BE_DIR/.admin-ctl.secret"; then
        run_priv touch "$BE_DIR/.admin-ctl.secret"
      fi
      run_priv chown "$SERVICE_USER:$service_group" "$BE_DIR/.admin-ctl.secret"
      run_priv chmod 600 "$BE_DIR/.admin-ctl.secret"
    else
      warn "서비스 계정 '$SERVICE_USER'를 찾을 수 없어 chown 단계를 건너뜁니다."
    fi
  }
  run_bubble_cmd 2 "BACKEND RUNTIME PERMISSIONS" "$be_after_gradle" "$be_end" fix_be_ownership_fn

  STAGE_STATUS[2]="OK"
  STAGE_DETAIL[2]="($GRADLE_TASKS)"
  bubble_render "warming" "BACKEND BUILD COMPLETED" "$be_end" "Distribution ready ($BACKEND_BIN)"

  # ----------------------------------------------------------------------------
  # 4. systemd 서비스 재시작 및 헬스체크 (Stage 3)
  # ----------------------------------------------------------------------------
  if [[ "$DO_RESTART" -eq 1 ]]; then
    command -v systemctl >/dev/null 2>&1 || die "systemctl을 찾을 수 없습니다. (--no-restart 사용 가능)"
    STAGE_STATUS[3]="WARMING"

    svc_start="${STAGE_START[3]}"
    svc_end="${STAGE_END[3]}"
    svc_mid=$(( svc_start + (svc_end - svc_start) * 40 / 100 ))

    run_bubble_cmd 3 "SYSTEMD RESTART ($BACKEND_SERVICE)" "$svc_start" "$svc_mid" \
      run_priv systemctl restart "$BACKEND_SERVICE"

    HEALTH_OK=0
    hc_pct="$svc_mid"
    hc_ceiling=$(( svc_end > svc_mid ? svc_end - 1 : svc_mid ))
    for hc_i in $(seq 1 25); do
      hc_pct=$(( svc_mid + (hc_ceiling - svc_mid) * hc_i / 25 ))
      bubble_render "warming" "SERVICE HEALTHCHECK ($hc_i/25)" "$hc_pct" "Probing http://127.0.0.1:${BACKEND_PORT}/ ..."
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
      STAGE_STATUS[3]="NG"
      bubble_render "error" "SERVICE HEALTHCHECK TIMEOUT" "$CURRENT_PCT" "$BACKEND_SERVICE failed healthcheck"
      bubble_end_screen
      warn "'$BACKEND_SERVICE' 서비스 상태 확인에 실패했습니다. 최근 로그를 출력합니다:"
      run_priv systemctl status "$BACKEND_SERVICE" --no-pager || true
      run_priv journalctl -u "$BACKEND_SERVICE" -n 50 --no-pager || true
      exit 1
    fi

    STAGE_STATUS[3]="OK"
    STAGE_DETAIL[3]="($BACKEND_SERVICE active)"
    bubble_render "warming" "SERVICE HEALTHCHECK OK" "$svc_end" "HTTP 200 OK (127.0.0.1:${BACKEND_PORT})"
  fi
fi

# ------------------------------------------------------------------------------
# 최종 완료 화면 및 요약 출력
# ------------------------------------------------------------------------------
bubble_render "ready" "ALL SYSTEMS READY" 100 "UPDATE COMPLETED ($NEW_COMMIT)"
if [[ "$BUBBLE_UI" -eq 1 ]]; then
  sleep 0.6
  bubble_end_screen
fi

UPDATE_SUCCEEDED=1
printf '\n[%s] CISTUS FE/BE 일괄 업데이트 완료 (commit: %s -> %s)\n' \
  "$(date '+%Y-%m-%d %H:%M:%S')" "$OLD_COMMIT" "$NEW_COMMIT"
if [[ -n "$COMMIT_MSG" ]]; then
  printf '  • 최신 커밋: %s\n' "$COMMIT_MSG"
fi

if [[ "$DO_BE" -eq 1 && "$DO_RESTART" -eq 1 ]]; then
  printf '\n--- %s 상태 ---\n' "$BACKEND_SERVICE"
  run_priv systemctl status "$BACKEND_SERVICE" --no-pager --lines=8 || true
  printf '\n--- 최근 로그 (15줄) ---\n'
  run_priv journalctl -u "$BACKEND_SERVICE" -n 15 --no-pager || true
fi
