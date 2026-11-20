#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# CISTUS (광운대학교 교수질문센터) FE/BE 일괄 빌드 및 서비스 반영 스크립트
# - 1985 코나미 버블 시스템(Konami Bubble System) 부팅 화면 및 Morning Music 재현
# - 일반 유저 권한으로 Git/Vite/Gradle 빌드 수행 (필요한 시스템/권한 명령에만 sudo 적용)
# - 코드 동기화(git) -> FE(Vite) 빌드 & Nginx 리로드 -> BE(Ktor) 빌드 & systemd 재시작
# - .gradle 권한/캐시 꼬임 자동 복구, uploads/ 및 .admin-ctl.secret 권한 보호
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${REPO_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
PROJECT_DIR="${PROJECT_DIR:-proj}"
BRANCH="${BRANCH:-main}"
BACKEND_SERVICE="${BACKEND_SERVICE:-}"
SERVICE_USER="${SERVICE_USER:-www-data}"
BACKEND_PORT="${BACKEND_PORT:-}"

DO_PULL=1
DO_FE=1
DO_BE=1
DO_RESTART=1
WITH_TEST=0
CLEAN_GRADLE=0
FORCE_NPM_CI=0
PLAIN_OUTPUT="${PLAIN_OUTPUT:-${NO_BUBBLE_UI:-0}}"
BUBBLE_DEMO=0
MUSIC_MODE="${BUBBLE_MUSIC:-auto}"

usage() {
  cat <<'EOF'
사용법: ./scripts/update-all.sh [옵션]
  * 일반 계정으로 실행하면 빌드/Git은 현재 계정으로, 시스템 제어/권한 변경 등
    일부 필요한 명령에만 내부적으로 sudo를 적용합니다.

옵션:
  --repo-dir <경로>       저장소 루트 경로 지정 (기본값: 스크립트 상위 폴더)
  --project-dir <폴더>    프로젝트 하위 폴더 지정 (기본값: proj)
  --mockup                목업 프로젝트(proj_mockup) 배포 (기본 서비스 cistus-mockup-be, 포트 8081)
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
  --music <모드>          Morning Music 출력 방식 (auto|decps|audio|console|off)
  --no-music              Morning Music 재생 끄기 (--music off 와 동일)
  --plain                 ANSI 애니메이션 없이 버블 시스템 일반 텍스트 로그 출력
  --bubble-demo           실제 배포 없이 버블 시스템 부팅 진행 화면 미리보기
  -h, --help              도움말 출력

* SSH 환경 소리 출력: Windows Terminal, Mintty, Contour 등 DECPS(VT520 Play Sound)
  지원 터미널에서는 --music auto 또는 --music decps 로 SSH 창에서 바로 소리가 납니다.
  로컬/PulseAudio/PipeWire 포워딩 환경에서는 2성부 칩튠 신시사이저가 자동 선택됩니다.

환경변수로도 지정 가능:
  REPO_DIR, BRANCH, BACKEND_SERVICE, SERVICE_USER, BACKEND_PORT,
  PLAIN_OUTPUT, NO_BUBBLE_UI, BUBBLE_MUSIC
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo-dir)
      [[ $# -ge 2 ]] || { echo "[ERROR] --repo-dir 값이 필요합니다." >&2; exit 1; }
      REPO_DIR="$2"; shift 2 ;;
    --project-dir)
      [[ $# -ge 2 ]] || { echo "[ERROR] --project-dir 값이 필요합니다." >&2; exit 1; }
      PROJECT_DIR="$2"; shift 2 ;;
    --mockup)
      PROJECT_DIR="proj_mockup"; shift ;;
    --branch)
      [[ $# -ge 2 ]] || { echo "[ERROR] --branch 값이 필요합니다." >&2; exit 1; }
      BRANCH="$2"; shift 2 ;;
    --service)
      [[ $# -ge 2 ]] || { echo "[ERROR] --service 값이 필요합니다." >&2; exit 1; }
      BACKEND_SERVICE="$2"; shift 2 ;;
    --service-user)
      [[ $# -ge 2 ]] || { echo "[ERROR] --service-user 값이 필요합니다." >&2; exit 1; }
      SERVICE_USER="$2"; shift 2 ;;
    --port)
      [[ $# -ge 2 ]] || { echo "[ERROR] --port 값이 필요합니다." >&2; exit 1; }
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
    --music)
      [[ $# -ge 2 ]] || { echo "[ERROR] --music 값이 필요합니다." >&2; exit 1; }
      MUSIC_MODE="$2"; shift 2 ;;
    --no-music)
      MUSIC_MODE="off"; shift ;;
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

case "$MUSIC_MODE" in
  auto|decps|audio|console|off) ;;
  *)
    echo "[ERROR] 알 수 없는 --music 모드입니다: $MUSIC_MODE (auto|decps|audio|console|off)" >&2
    usage
    exit 1 ;;
esac

if [[ -z "$BACKEND_PORT" ]]; then
  if [[ "$PROJECT_DIR" == "proj_mockup" ]]; then
    BACKEND_PORT="8081"
  else
    BACKEND_PORT="8080"
  fi
fi

# ------------------------------------------------------------------------------
# 터미널 / 로그 / 버블 시스템 UI & Morning Music 상태 관리
# ------------------------------------------------------------------------------
BUBBLE_UI=0
if [[ "$PLAIN_OUTPUT" != "1" ]] && [[ -t 1 ]] && [[ "${TERM:-dumb}" != "dumb" ]]; then
  BUBBLE_UI=1
fi

HAS_256COLOR=0
if [[ "${TERM:-}" == *256color* ]] || [[ -n "${COLORTERM:-}" ]] || [[ -n "${WT_SESSION:-}" ]]; then
  HAS_256COLOR=1
fi

UPDATE_LOG_FILE="$(mktemp /tmp/cistus-update.XXXXXX.log 2>/dev/null || echo "/tmp/cistus-update-$$.log")"
STEP_LOG="$(mktemp /tmp/cistus-step.XXXXXX.log 2>/dev/null || echo "/tmp/cistus-step-$$.log")"
TTY_LOCK_FILE="$(mktemp /tmp/cistus-tty.XXXXXX.lock 2>/dev/null || echo "/tmp/cistus-tty-$$.lock")"
MUSIC_STATE_FILE="$(mktemp /tmp/cistus-music.XXXXXX.state 2>/dev/null || echo "/tmp/cistus-music-$$.state")"
: > "$UPDATE_LOG_FILE" 2>/dev/null || true
: > "$STEP_LOG" 2>/dev/null || true
: > "$TTY_LOCK_FILE" 2>/dev/null || true
printf -- '-- 0\n' > "$MUSIC_STATE_FILE" 2>/dev/null || true

CURRENT_CMD_PID=""
MUSIC_PID=""
MUSIC_BACKEND="off"
BUBBLE_STARTED=0
BUBBLE_TICK=0
CURRENT_PCT=0
LAST_COUNTDOWN_VAL=99
COUNT13_STUTTER_DONE=0
UPLOADS_CHOWNED_FOR_PULL=0
UPDATE_SUCCEEDED=0

TERM_ROWS=24
TERM_COLS=80
CRT_WIDTH=64
CRT_PAD_X=0
CRT_PAD_Y=0

STAGE_NAMES=("GIT SOURCE SYNC" "FRONTEND BUILD" "BACKEND BUILD" "SERVICE STARTUP")
STAGE_SHORT_NAMES=("GIT" "FE" "BE" "SVC")
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
  stop_morning_music
  if [[ "$BUBBLE_STARTED" -eq 1 && "$BUBBLE_UI" -eq 1 ]]; then
    bubble_end_screen
  fi
  printf '[ERROR] %s\n' "$*" | tee -a "$UPDATE_LOG_FILE" >&2
  printf '        전체 로그: %s\n' "$UPDATE_LOG_FILE" >&2
  exit 1
}

tty_write_atomic() {
  local payload="${1:-}"
  if [[ -z "$payload" ]]; then
    return 0
  fi
  if command -v flock >/dev/null 2>&1 && [[ -n "${TTY_LOCK_FILE:-}" ]] && [[ -e "$TTY_LOCK_FILE" ]]; then
    {
      flock -x 9 2>/dev/null || true
      printf '%s' "$payload"
    } 9>>"$TTY_LOCK_FILE" 2>/dev/null || printf '%s' "$payload"
  else
    printf '%s' "$payload"
  fi
}

get_term_size() {
  local sz=""
  if [[ -t 1 ]]; then
    sz="$(stty size 2>/dev/null || true)"
  fi
  if [[ "$sz" =~ ^([0-9]+)[[:space:]]+([0-9]+)$ ]]; then
    TERM_ROWS="${BASH_REMATCH[1]}"
    TERM_COLS="${BASH_REMATCH[2]}"
  else
    TERM_ROWS="$(tput lines 2>/dev/null || echo 24)"
    TERM_COLS="$(tput cols 2>/dev/null || echo 80)"
  fi
  [[ "$TERM_ROWS" =~ ^[0-9]+$ ]] || TERM_ROWS=24
  [[ "$TERM_COLS" =~ ^[0-9]+$ ]] || TERM_COLS=80
  if (( TERM_ROWS < 20 )); then TERM_ROWS=20; fi
  if (( TERM_COLS < 64 )); then TERM_COLS=64; fi
  CRT_PAD_X=$(( (TERM_COLS - CRT_WIDTH) / 2 ))
  if (( CRT_PAD_X < 0 )); then CRT_PAD_X=0; fi
}

# ------------------------------------------------------------------------------
# Konami Bubble System "Morning Music" (1985) SSH 오디오 & 신시사이저 엔진
# ------------------------------------------------------------------------------
# VT520 DECPS 음표 번호: 1=C5 .. 13=C6 .. 25=C7 (0=쉼표)
# 형식: "<decps_note>:<decps_dur32>:<sleep_sec>:<note_label>:<psg_lvl>"
MORNING_MUSIC_SEQ=(
  # Phrase A (G -> D -> Em -> D)
  "8:7:0.22:G5:4"   "12:7:0.22:B5:5"  "15:7:0.22:D6:6"  "20:7:0.22:G6:7"
  "19:7:0.22:F#6:6" "20:4:0.12:G6:7"  "22:4:0.12:A6:8"  "15:14:0.44:D6:6"
  "17:7:0.22:E6:7"  "13:7:0.22:C6:5"  "10:7:0.22:A5:4"  "7:7:0.22:F#5:3"
  "8:10:0.32:G5:5"  "10:4:0.12:A5:5"  "12:7:0.22:B5:6"  "8:7:0.22:G5:4"
  # Phrase B (C -> G -> Am -> D7 -> G)
  "13:7:0.22:C6:6"  "17:7:0.22:E6:7"  "20:7:0.22:G6:8"  "17:7:0.22:E6:7"
  "15:7:0.22:D6:6"  "12:7:0.22:B5:5"  "8:14:0.44:G5:4"
  "10:7:0.22:A5:4"  "12:7:0.22:B5:5"  "13:7:0.22:C6:6"  "15:7:0.22:D6:7"
  "12:10:0.32:B5:6" "10:4:0.12:A5:5"  "8:14:0.44:G5:5"
  # Phrase C (D7 -> G -> A7 -> D)
  "15:7:0.22:D6:6"  "19:7:0.22:F#6:7" "22:7:0.22:A6:8"  "19:7:0.22:F#6:7"
  "20:7:0.22:G6:8"  "15:7:0.22:D6:6"  "12:14:0.44:B5:5"
  "17:7:0.22:E6:7"  "14:7:0.22:C#6:6" "10:7:0.22:A5:5"  "17:7:0.22:E6:7"
  "15:10:0.32:D6:6" "12:4:0.12:B5:5"  "10:14:0.44:A5:5"
  # Phrase D (G -> C -> D7 -> G cadence)
  "8:7:0.22:G5:4"   "10:7:0.22:A5:5"  "12:7:0.22:B5:6"  "15:7:0.22:D6:7"
  "13:7:0.22:C6:6"  "12:7:0.22:B5:5"  "10:7:0.22:A5:4"  "8:7:0.22:G5:4"
  "7:7:0.22:F#5:3"  "8:7:0.22:G5:4"   "10:7:0.22:A5:5"  "7:7:0.22:F#5:4"
  "8:10:0.32:G5:6"  "12:4:0.12:B5:7"  "8:14:0.44:G5:6"  "0:4:0.14:--:0"
)

decps_note_to_hz() {
  local n="${1:-0}"
  local hz_table=(
    0
    523 554 587 622 659 698 740 784 831 880 932 988
    1047 1109 1175 1245 1319 1397 1480 1568 1661 1760 1865 1976 2093
  )
  if (( n >= 0 && n < ${#hz_table[@]} )); then
    printf '%s' "${hz_table[$n]}"
  else
    printf '0'
  fi
}

AUDIO_PLAYER_BIN=""

probe_local_audio_player() {
  AUDIO_PLAYER_BIN=""
  command -v python3 >/dev/null 2>&1 && python3 --version >/dev/null 2>&1 || return 1
  if command -v pw-play >/dev/null 2>&1 && pw-cli info >/dev/null 2>&1; then
    AUDIO_PLAYER_BIN="pw-play"
    return 0
  fi
  if command -v paplay >/dev/null 2>&1 && pactl info >/dev/null 2>&1; then
    AUDIO_PLAYER_BIN="paplay"
    return 0
  fi
  if [[ -z "${SSH_CONNECTION:-}" ]]; then
    if command -v aplay >/dev/null 2>&1 && aplay -l >/dev/null 2>&1; then
      AUDIO_PLAYER_BIN="aplay"
      return 0
    fi
    if [[ "$(uname -s 2>/dev/null || echo '')" == "Darwin" ]] && command -v afplay >/dev/null 2>&1; then
      AUDIO_PLAYER_BIN="afplay"
      return 0
    fi
  fi
  return 1
}

resolve_music_backend() {
  if [[ "$BUBBLE_UI" -ne 1 ]] || [[ "$MUSIC_MODE" == "off" ]]; then
    MUSIC_BACKEND="off"
    return 0
  fi
  case "$MUSIC_MODE" in
    audio)
      if probe_local_audio_player; then
        MUSIC_BACKEND="audio"
      else
        MUSIC_BACKEND="decps"
      fi
      ;;
    decps)
      MUSIC_BACKEND="decps"
      ;;
    console)
      MUSIC_BACKEND="console"
      ;;
    auto)
      if probe_local_audio_player; then
        MUSIC_BACKEND="audio"
      elif [[ "${TERM:-}" == "linux" ]]; then
        MUSIC_BACKEND="console"
      else
        MUSIC_BACKEND="decps"
      fi
      ;;
    *)
      MUSIC_BACKEND="off"
      ;;
  esac
}

morning_music_decps_loop() {
  trap 'exit 0' TERM INT
  local entry note dur slp label lvl hz ms
  while true; do
    for entry in "${MORNING_MUSIC_SEQ[@]}"; do
      IFS=':' read -r note dur slp label lvl <<< "$entry"
      printf '%s %s\n' "$label" "$lvl" > "$MUSIC_STATE_FILE" 2>/dev/null || true
      if (( note > 0 )); then
        if [[ "$MUSIC_BACKEND" == "console" ]]; then
          hz="$(decps_note_to_hz "$note")"
          ms=$(( dur * 31 ))
          tty_write_atomic $'\033[10;'"${hz}"$']\033[11;'"${ms}"$']\a'
        else
          tty_write_atomic $'\033[3;'"${dur};${note}"',~'
        fi
      fi
      sleep "$slp" 2>/dev/null || break
    done
  done
}

morning_music_audio_loop() {
  exec python3 - "$MUSIC_STATE_FILE" "$AUDIO_PLAYER_BIN" <<'PYEOF'
import math
import os
import signal
import struct
import subprocess
import sys
import tempfile
import time
import wave

state_file = sys.argv[1]
player = sys.argv[2]
sample_rate = 22050
proc = None
wav_tmp = None

def cleanup(_signum=None, _frame=None):
    if proc is not None and proc.poll() is None:
        try:
            proc.terminate()
            proc.wait(timeout=0.4)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
    if wav_tmp and os.path.exists(wav_tmp):
        try:
            os.remove(wav_tmp)
        except Exception:
            pass
    sys.exit(0)

signal.signal(signal.SIGTERM, cleanup)
signal.signal(signal.SIGINT, cleanup)

# Lead (MIDI, dur_s, label, level) + Baroque Bass MIDI
SEQ = [
    (79, 0.22, "G5", 4, 55), (83, 0.22, "B5", 5, 59), (86, 0.22, "D6", 6, 62), (91, 0.22, "G6", 7, 55),
    (90, 0.22, "F#6", 6, 54), (91, 0.12, "G6", 7, 57), (93, 0.12, "A6", 8, 57), (86, 0.44, "D6", 6, 50),
    (88, 0.22, "E6", 7, 52), (84, 0.22, "C6", 5, 55), (81, 0.22, "A5", 4, 57), (78, 0.22, "F#5", 3, 50),
    (79, 0.32, "G5", 5, 55), (81, 0.12, "A5", 5, 54), (83, 0.22, "B5", 6, 59), (79, 0.22, "G5", 4, 55),

    (84, 0.22, "C6", 6, 48), (88, 0.22, "E6", 7, 52), (91, 0.22, "G6", 8, 55), (88, 0.22, "E6", 7, 52),
    (86, 0.22, "D6", 6, 59), (83, 0.22, "B5", 5, 55), (79, 0.44, "G5", 4, 43),
    (81, 0.22, "A5", 4, 57), (83, 0.22, "B5", 5, 55), (84, 0.22, "C6", 6, 48), (86, 0.22, "D6", 7, 50),
    (83, 0.32, "B5", 6, 55), (81, 0.12, "A5", 5, 50), (79, 0.44, "G5", 5, 43),

    (86, 0.22, "D6", 6, 50), (90, 0.22, "F#6", 7, 54), (93, 0.22, "A6", 8, 57), (90, 0.22, "F#6", 7, 54),
    (91, 0.22, "G6", 8, 55), (86, 0.22, "D6", 6, 59), (83, 0.44, "B5", 5, 47),
    (88, 0.22, "E6", 7, 52), (85, 0.22, "C#6", 6, 57), (81, 0.22, "A5", 5, 52), (88, 0.22, "E6", 7, 45),
    (86, 0.32, "D6", 6, 50), (83, 0.12, "B5", 5, 47), (81, 0.44, "A5", 5, 50),

    (79, 0.22, "G5", 4, 55), (81, 0.22, "A5", 5, 54), (83, 0.22, "B5", 6, 59), (86, 0.22, "D6", 7, 62),
    (84, 0.22, "C6", 6, 48), (83, 0.22, "B5", 5, 55), (81, 0.22, "A5", 4, 57), (79, 0.22, "G5", 4, 52),
    (78, 0.22, "F#5", 3, 50), (79, 0.22, "G5", 4, 55), (81, 0.22, "A5", 5, 45), (78, 0.22, "F#5", 4, 50),
    (79, 0.32, "G5", 6, 43), (83, 0.12, "B5", 7, 47), (79, 0.44, "G5", 6, 55), (0, 0.14, "--", 0, 0),
]

def midi_hz(m):
    return 440.0 * (2.0 ** ((m - 69) / 12.0)) if m > 0 else 0.0

def render_note(lead_midi, bass_midi, dur):
    n_samples = max(1, int(sample_rate * dur))
    f1 = midi_hz(lead_midi)
    f2 = midi_hz(bass_midi)
    frames = bytearray(n_samples * 2)
    tau = 2.0 * math.pi
    for i in range(n_samples):
        t = i / sample_rate
        # Fast baroque chiptune pluck envelope (Konami 005289 wavetable feel)
        atk = min(1.0, i / 160.0)
        rel = min(1.0, (n_samples - i) / 320.0)
        decay = math.exp(-3.0 * t)
        env1 = atk * rel * (0.45 + 0.55 * decay)
        env2 = atk * rel * 0.6
        val = 0.0
        if f1 > 0.0:
            p1 = tau * f1 * t
            val += env1 * 0.52 * (0.65 * math.sin(p1) + 0.25 * math.sin(2.0 * p1) + 0.12 * math.sin(3.0 * p1))
        if f2 > 0.0:
            p2 = tau * f2 * t
            tri = (2.0 / math.pi) * math.asin(math.sin(p2))
            val += env2 * 0.24 * tri
        pcm = max(-32767, min(32767, int(val * 24000)))
        struct.pack_into("<h", frames, i * 2, pcm)
    return bytes(frames)

def write_state(label, lvl):
    try:
        with open(state_file, "w", encoding="utf-8") as fp:
            fp.write(f"{label} {lvl}\n")
    except Exception:
        pass

if player == "afplay":
    fd, wav_tmp = tempfile.mkstemp(prefix="cistus-mm-", suffix=".wav")
    os.close(fd)
    with wave.open(wav_tmp, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        for lm, dur, _lbl, _lvl, bm in SEQ:
            wf.writeframes(render_note(lm, bm, dur))
    while True:
        proc = subprocess.Popen(["afplay", wav_tmp], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _lm, dur, lbl, lvl, _bm in SEQ:
            if proc.poll() is not None:
                break
            write_state(lbl, lvl)
            time.sleep(dur)
        if proc.poll() is None:
            proc.wait()

cmd_map = {
    "pw-play": ["pw-play", "--raw", "--rate", str(sample_rate), "--channels", "1", "--format", "s16", "-"],
    "paplay": ["paplay", "--raw", f"--rate={sample_rate}", "--channels=1", "--format=s16le", "--latency-msec=80"],
    "aplay": ["aplay", "-q", "-t", "raw", "-f", "S16_LE", "-r", str(sample_rate), "-c", "1"],
}
cmd = cmd_map.get(player)
if not cmd:
    sys.exit(0)

proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
cached = [(render_note(lm, bm, dur), dur, lbl, lvl) for lm, dur, lbl, lvl, bm in SEQ]

while True:
    for pcm_bytes, dur, lbl, lvl in cached:
        if proc.poll() is not None:
            cleanup()
        write_state(lbl, lvl)
        t0 = time.monotonic()
        try:
            proc.stdin.write(pcm_bytes)
            proc.stdin.flush()
        except Exception:
            cleanup()
        elapsed = time.monotonic() - t0
        rem = dur - elapsed - 0.005
        if rem > 0:
            time.sleep(rem)
PYEOF
}

start_morning_music() {
  resolve_music_backend
  if [[ "$MUSIC_BACKEND" == "off" || "$BUBBLE_UI" -ne 1 ]]; then
    return 0
  fi
  if [[ -n "${MUSIC_PID:-}" ]] && kill -0 "$MUSIC_PID" 2>/dev/null; then
    return 0
  fi
  if [[ "$MUSIC_BACKEND" == "audio" ]]; then
    morning_music_audio_loop 2>/dev/null &
    MUSIC_PID=$!
  else
    morning_music_decps_loop &
    MUSIC_PID=$!
  fi
}

stop_morning_music() {
  if [[ -n "${MUSIC_PID:-}" ]]; then
    if kill -0 "$MUSIC_PID" 2>/dev/null; then
      kill "$MUSIC_PID" 2>/dev/null || true
      wait "$MUSIC_PID" 2>/dev/null || true
    fi
    MUSIC_PID=""
  fi
  if [[ "${MUSIC_BACKEND:-off}" == "decps" && "${BUBBLE_UI:-0}" -eq 1 ]]; then
    tty_write_atomic $'\033[0;1;0,~'
  fi
}

bubble_digit_row() {
  local d="${1:-0}"
  local r="${2:-0}"
  case "$d:$r" in
    0:0|0:4|2:0|2:2|2:4|3:0|3:2|3:4|4:2|5:0|5:2|5:4|6:0|6:2|6:4|7:0|8:0|8:2|8:4|9:0|9:2|9:4|1:4)
      printf '██████' ;;
    0:1|0:2|0:3|4:0|4:1|6:3|8:1|8:3|9:1)
      printf '██  ██' ;;
    1:0)
      printf '  ██  ' ;;
    1:1)
      printf '████  ' ;;
    1:2|1:3|7:3|7:4)
      printf '  ██  ' ;;
    2:1|3:1|3:3|4:3|4:4|5:3|7:1|9:3)
      printf '    ██' ;;
    2:3|5:1|6:1)
      printf '██    ' ;;
    7:2)
      printf '   ██ ' ;;
    *)
      printf '      ' ;;
  esac
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
  local width="${2:-28}"
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
  if (( ${#clean} > 50 )); then
    clean="${clean:0:47}..."
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
    tty_write_atomic $'\033[?1049h\033[?25l\033[40m\033[2J\033[H'
  fi
}

bubble_end_screen() {
  if [[ "${BUBBLE_STARTED:-0}" -eq 1 ]]; then
    tty_write_atomic $'\033[0m\033[?25h\033[?1049l'
    printf '\n'
    BUBBLE_STARTED=0
  fi
}

bubble_crt_line() {
  local vis_len="$1"
  local styled="$2"
  local inner_pad=$(( (CRT_WIDTH - vis_len) / 2 ))
  if (( inner_pad < 0 )); then inner_pad=0; fi
  local lp=""
  printf -v lp '%*s' $(( CRT_PAD_X + inner_pad )) ""
  CRT_OUT+="${CRT_BG}${lp}${styled}${CRT_RESET}${CRT_BG}"$'\033[K\n'
}

bubble_crt_blank() {
  CRT_OUT+="${CRT_BG}"$'\033[K\n'
}

bubble_render_frame() {
  local mode="${1:-warming}"
  local stage_label="${2:-INITIALIZING}"
  local pct="${3:-0}"
  local ticker="${4:-...}"
  local cnt_override="${5:-}"

  local cnt
  if [[ -n "$cnt_override" ]]; then
    cnt="$cnt_override"
  else
    cnt="$(bubble_count_from_pct "$pct")"
  fi

  get_term_size
  BUBBLE_TICK=$(( BUBBLE_TICK + 1 ))

  local c_reset=$'\033[0m'
  local c_bg=$'\033[40m'
  local c_red c_orange c_gold c_yellow c_green c_cyan c_blue c_dcyan c_white c_dim
  if [[ "$HAS_256COLOR" -eq 1 ]]; then
    c_red=$'\033[1;38;5;196m'
    c_orange=$'\033[1;38;5;208m'
    c_gold=$'\033[1;38;5;220m'
    c_yellow=$'\033[1;38;5;226m'
    c_green=$'\033[1;38;5;46m'
    c_cyan=$'\033[1;38;5;51m'
    c_blue=$'\033[1;38;5;33m'
    c_dcyan=$'\033[38;5;37m'
    c_white=$'\033[1;37m'
    c_dim=$'\033[38;5;244m'
  else
    c_red=$'\033[1;31m'
    c_orange=$'\033[1;33m'
    c_gold=$'\033[1;33m'
    c_yellow=$'\033[1;33m'
    c_green=$'\033[1;32m'
    c_cyan=$'\033[1;36m'
    c_blue=$'\033[1;34m'
    c_dcyan=$'\033[0;36m'
    c_white=$'\033[1;37m'
    c_dim=$'\033[2;37m'
  fi

  CRT_BG="$c_bg"
  CRT_RESET="$c_reset"
  CRT_OUT=$'\033[H'

  local content_rows=19
  case "$mode" in
    check) content_rows=10 ;;
    getting_ready) content_rows=8 ;;
  esac
  CRT_PAD_Y=$(( (TERM_ROWS - content_rows) / 2 ))
  if (( CRT_PAD_Y < 0 )); then CRT_PAD_Y=0; fi
  if (( CRT_PAD_Y > 8 )); then CRT_PAD_Y=8; fi

  local yi=0
  while (( yi < CRT_PAD_Y )); do
    bubble_crt_blank
    yi=$(( yi + 1 ))
  done

  if [[ "$mode" == "check" ]]; then
    local l_rb="██████████████████████████████████████"
    local l_t1="B U B B L E   S Y S T E M"
    local l_t2="GX400 HARDWARE SELF TEST"
    local l_c1="WORK RAM CHECK   ..........  OK"
    local l_c2="VIDEO RAM CHECK  ..........  OK"
    local l_c3="SOUND CPU CHECK  ..........  OK"
    local l_c4="BUBBLE CASSETTE  ..........  OK"
    local l_pr="P R E S E N T E D   B Y   K O N A M I"
    bubble_crt_line "${#l_rb}" "${c_red}██████  ${c_orange}██████  ${c_gold}██████  ${c_green}██████  ${c_cyan}██████${c_reset}"
    bubble_crt_blank
    bubble_crt_line "${#l_t1}" "${c_white}${l_t1}${c_reset}"
    bubble_crt_line "${#l_t2}" "${c_cyan}${l_t2}${c_reset}"
    bubble_crt_blank
    bubble_crt_line "${#l_c1}" "${c_white}WORK RAM CHECK   ..........  ${c_green}OK${c_reset}"
    bubble_crt_line "${#l_c2}" "${c_white}VIDEO RAM CHECK  ..........  ${c_green}OK${c_reset}"
    bubble_crt_line "${#l_c3}" "${c_white}SOUND CPU CHECK  ..........  ${c_green}OK${c_reset}"
    bubble_crt_line "${#l_c4}" "${c_white}BUBBLE CASSETTE  ..........  ${c_green}OK${c_reset}"
    bubble_crt_blank
    bubble_crt_line "${#l_pr}" "${c_orange}${l_pr}${c_reset}"
    CRT_OUT+="${c_bg}"$'\033[J'
    tty_write_atomic "$CRT_OUT"
    return 0
  fi

  if [[ "$mode" == "getting_ready" ]]; then
    local g_pr="P R E S E N T E D   B Y   K O N A M I"
    local g_rd="G E T T I N G   R E A D Y . . ."
    local g_ht="BUBBLE MEMORY HEATER : 30°C -> 40°C"
    local g_vc="VLM5030 SPEECH SYNTH : READY"
    local g_bt="CISTUS DEPLOY CASSETTE INITIALIZING"
    bubble_crt_line "${#g_pr}" "${c_white}${g_pr}${c_reset}"
    bubble_crt_blank
    bubble_crt_line "${#g_rd}" "${c_orange}${g_rd}${c_reset}"
    bubble_crt_blank
    bubble_crt_line "${#g_ht}" "${c_red}${g_ht}${c_reset}"
    bubble_crt_line "${#g_vc}" "${c_dcyan}${g_vc}${c_reset}"
    bubble_crt_blank
    bubble_crt_line "${#g_bt}" "${c_dim}${g_bt}${c_reset}"
    CRT_OUT+="${c_bg}"$'\033[J'
    tty_write_atomic "$CRT_OUT"
    return 0
  fi

  # ASCII 1-column bubble glyphs (strictly width 1 so digits never shift horizontally)
  local glyphs=('o' 'O' '.' '*' 'o' '.' ' ' ' ')
  local n_glyphs=${#glyphs[@]}
  local b0="${glyphs[$(( BUBBLE_TICK % n_glyphs ))]}"
  local b1="${glyphs[$(( (BUBBLE_TICK + 2) % n_glyphs ))]}"
  local b2="${glyphs[$(( (BUBBLE_TICK + 4) % n_glyphs ))]}"
  local b3="${glyphs[$(( (BUBBLE_TICK + 6) % n_glyphs ))]}"

  local konami_logo=""
  local banner_plain=""
  local banner_line=""
  local sub_plain=""
  local sub_msg=""
  local digit_color="$c_orange"
  case "$mode" in
    ready)
      konami_logo="${c_green}▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀${c_reset}"
      banner_plain="W A R M I N G   U P   C O M P L E T E D"
      banner_line="${c_green}${banner_plain}${c_reset}"
      sub_plain="A L L   S Y S T E M S   O K   -   R E A D Y !"
      sub_msg="${c_cyan}${sub_plain}${c_reset}"
      digit_color="$c_green"
      ;;
    error)
      konami_logo="${c_red}▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀${c_reset}"
      banner_plain="B U B B L E   S Y S T E M   E R R O R"
      banner_line="${c_red}${banner_plain}${c_reset}"
      sub_plain="C H E C K   F A I L E D   -   H A L T E D"
      sub_msg="${c_red}${sub_plain}${c_reset}"
      digit_color="$c_red"
      ;;
    *)
      konami_logo="${c_red}▄▄▄▄▄▄▄▄▄▄▄▄${c_orange}▄▄▄▄▄▄▄▄▄▄▄▄▄${c_gold}▄▄▄▄▄▄▄▄▄▄▄▄▄${c_reset}"
      banner_plain="W A R M I N G   U P   N O W"
      if (( BUBBLE_TICK % 2 == 0 )); then
        banner_line="${c_orange}${banner_plain}${c_reset}"
        digit_color="$c_orange"
      else
        banner_line="${c_gold}${banner_plain}${c_reset}"
        digit_color="$c_gold"
      fi
      sub_plain="LOADING CISTUS SYSTEM TO MAIN DRAM..."
      sub_msg="${c_dcyan}${sub_plain}${c_reset}"
      ;;
  esac

  local d1="${cnt:0:1}"
  local d2="${cnt:1:1}"
  local temp_c=$(( 30 + (pct * 10 / 100) ))

  local title_plain="${b0}  B U B B L E   S Y S T E M  ${b1}"
  bubble_crt_line 38 "$konami_logo"
  bubble_crt_line "${#title_plain}" "${c_blue}${b0}${c_reset}  ${c_red}B U B B L E   ${c_orange}S Y S T E M${c_reset}  ${c_cyan}${b1}${c_reset}"
  bubble_crt_blank
  bubble_crt_line "${#banner_plain}" "$banner_line"
  bubble_crt_blank

  # Giant 5-row block digits (total visible width 30: bubble + 7sp + 6d1 + 2sp + 6d2 + 7sp + bubble)
  local r=0
  local r1 r2 row_lb row_rb
  while (( r < 5 )); do
    r1="$(bubble_digit_row "$d1" "$r")"
    r2="$(bubble_digit_row "$d2" "$r")"
    case "$r" in
      0) row_lb="${c_cyan}${b2}${c_reset}";  row_rb="${c_blue}${b0}${c_reset}" ;;
      2) row_lb="${c_blue}${b1}${c_reset}";  row_rb="${c_cyan}${b3}${c_reset}" ;;
      4) row_lb="${c_dcyan}${b3}${c_reset}"; row_rb="${c_dcyan}${b1}${c_reset}" ;;
      *) row_lb=" ";                         row_rb=" " ;;
    esac
    bubble_crt_line 30 "${row_lb}       ${digit_color}${r1}  ${r2}${c_reset}       ${row_rb}"
    r=$(( r + 1 ))
  done

  local presented_plain="P R E S E N T E D   B Y   K O N A M I"
  bubble_crt_blank
  bubble_crt_line "${#presented_plain}" "${c_white}P R E S E N T E D   B Y   ${c_orange}K O N A M I${c_reset}"
  bubble_crt_line "${#sub_plain}" "$sub_msg"
  bubble_crt_blank

  # Compact BIOS Transfer Bar & ROM Banks
  local bar
  bar="$(bubble_bar "$pct" 28)"
  local pct_str
  pct_str="$(printf '%3d%%' "$pct")"
  local bar_plain="[${bar}] ${pct_str}  ${temp_c}°C"
  bubble_crt_line "${#bar_plain}" "${c_cyan}[${bar}]${c_reset} ${c_white}${pct_str}${c_reset}  ${c_orange}${temp_c}°C${c_reset}"

  local banks_plain=""
  local banks_styled=""
  local idx=0
  while (( idx < 4 )); do
    local sn="${STAGE_SHORT_NAMES[$idx]}"
    local st="${STAGE_STATUS[$idx]}"
    local tag="" stag=""
    case "$st" in
      OK)      tag="[OK]"; stag="${c_green}[OK]${c_reset}" ;;
      WARMING) tag="[>>]"; stag="${c_gold}[>>]${c_reset}" ;;
      NG)      tag="[NG]"; stag="${c_red}[NG]${c_reset}" ;;
      SKIP)    tag="[--]"; stag="${c_dim}[--]${c_reset}" ;;
      *)       tag="[..]"; stag="${c_dcyan}[..]${c_reset}" ;;
    esac
    if (( idx > 0 )); then
      banks_plain+="   "
      banks_styled+="   "
    fi
    banks_plain+="${sn} ${tag}"
    banks_styled+="${c_white}${sn}${c_reset} ${stag}"
    idx=$(( idx + 1 ))
  done
  bubble_crt_line "${#banks_plain}" "$banks_styled"

  # Live stage & ticker
  local clean_ticker
  clean_ticker="$(sanitize_ticker_line "$ticker")"
  if [[ -z "$clean_ticker" ]]; then
    clean_ticker="$stage_label"
  fi
  local line_stage="> ${stage_label}"
  if (( ${#line_stage} > 58 )); then
    line_stage="${line_stage:0:55}..."
  fi
  bubble_crt_line "${#line_stage}" "${c_white}${line_stage}${c_reset}"
  bubble_crt_line "${#clean_ticker}" "${c_dcyan}${clean_ticker}${c_reset}"

  # Live Morning Music PSG bar
  local cur_note="--"
  local cur_lvl=0
  if [[ -r "${MUSIC_STATE_FILE:-}" ]]; then
    read -r cur_note cur_lvl < "$MUSIC_STATE_FILE" 2>/dev/null || true
  fi
  [[ "$cur_lvl" =~ ^[0-9]+$ ]] || cur_lvl=0
  if (( cur_lvl > 8 )); then cur_lvl=8; fi
  local psg_meter=""
  local mi=1
  while (( mi <= 8 )); do
    if (( mi <= cur_lvl )); then
      psg_meter+="#"
    else
      psg_meter+="."
    fi
    mi=$(( mi + 1 ))
  done
  local m_tag="OFF"
  case "${MUSIC_BACKEND:-off}" in
    decps)   m_tag="SSH-DECPS" ;;
    audio)   m_tag="CHIPTUNE" ;;
    console) m_tag="PC-SPK" ;;
    off)     m_tag="MUTED" ;;
  esac
  local note_disp
  note_disp="$(printf '%-3s' "${cur_note:-"--"}")"
  local m_plain="J MORNING MUSIC [${m_tag}]  ${note_disp}  [${psg_meter}]"
  bubble_crt_line "${#m_plain}" "${c_cyan}♪${c_reset} ${c_dim}MORNING MUSIC${c_reset} ${c_dcyan}[${m_tag}]${c_reset}  ${c_gold}${note_disp}${c_reset}  ${c_cyan}[${psg_meter}]${c_reset}"

  CRT_OUT+="${c_bg}"$'\033[J'
  tty_write_atomic "$CRT_OUT"
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

  local cnt_num=$(( 10#$cnt ))
  if [[ "$mode" == "warming" && "$COUNT13_STUTTER_DONE" -eq 0 && "$LAST_COUNTDOWN_VAL" -gt 13 && "$cnt_num" -le 13 ]]; then
    COUNT13_STUTTER_DONE=1
    bubble_render_frame "warming" "$stage_label" "$pct" "$ticker" "13"
    sleep 0.25
    if (( cnt_num < 13 )); then
      bubble_render_frame "$mode" "$stage_label" "$pct" "$ticker"
    fi
  else
    bubble_render_frame "$mode" "$stage_label" "$pct" "$ticker"
  fi
  LAST_COUNTDOWN_VAL="$cnt_num"
}

run_bubble_intro() {
  if [[ "$BUBBLE_UI" -ne 1 ]]; then
    return 0
  fi
  local check_delay="${1:-0.35}"
  local ready_delay="${2:-0.55}"
  bubble_render "check" "SELF TEST" 0 "GX400 Self Test"
  sleep "$check_delay"
  bubble_render "getting_ready" "GETTING READY" 0 "Heating Bubble Memory..."
  start_morning_music
  sleep "$ready_delay"
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
    stop_morning_music
    local err_line=""
    err_line="$(awk 'NF{line=$0} END{print line}' "$STEP_LOG" 2>/dev/null || true)"
    bubble_render "error" "$stage_label (FAILED)" "$CURRENT_PCT" "${err_line:-exit=$rc}"
    sleep 0.4
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
    "> Task :installDist - BUILD SUCCESSFUL"
    "HTTP 200 OK (127.0.0.1:${BACKEND_PORT})"
  )

  run_bubble_intro 0.45 0.65

  for idx in "${demo_stages[@]}"; do
    if [[ "${STAGE_STATUS[$idx]}" == "SKIP" ]]; then
      continue
    fi
    STAGE_STATUS[idx]="WARMING"
    local s="${STAGE_START[$idx]}"
    local e="${STAGE_END[$idx]}"
    local p="$s"
    local step_inc=2
    local step_delay=0.10
    if [[ "$BUBBLE_UI" -ne 1 ]]; then
      step_inc=$(( (e - s) / 4 ))
      if (( step_inc < 1 )); then step_inc=1; fi
      step_delay=0.02
    fi
    while (( p < e )); do
      bubble_render "warming" "${demo_labels[$idx]}" "$p" "${demo_tickers[$idx]}"
      sleep "$step_delay"
      p=$(( p + step_inc ))
    done
    STAGE_STATUS[idx]="OK"
    STAGE_DETAIL[idx]="${demo_details[$idx]}"
    CURRENT_PCT="$e"
    bubble_render "warming" "${demo_labels[$idx]}" "$e" "DONE: ${demo_labels[$idx]}"
    d_step=$(( d_step + 1 ))
  done

  stop_morning_music
  if [[ "$BUBBLE_UI" -eq 1 && "${MUSIC_BACKEND:-off}" == "decps" ]]; then
    tty_write_atomic $'\033[4;3;8,~\033[4;3;12,~\033[4;3;15,~\033[4;6;20,~'
  fi
  bubble_render "ready" "ALL SYSTEMS READY" 100 "WARMING UP COMPLETED (DEMO · ${d_step} STAGES)"
  if [[ "$BUBBLE_UI" -eq 1 ]]; then
    sleep 0.9
    bubble_end_screen
  fi
  printf '[CISTUS] Bubble System demo finished (music: %s, log: %s)\n' "${MUSIC_BACKEND:-off}" "$UPDATE_LOG_FILE"
  UPDATE_SUCCEEDED=1
  exit 0
}

cleanup_on_exit() {
  stop_morning_music
  if [[ -n "${CURRENT_CMD_PID:-}" ]] && kill -0 "$CURRENT_CMD_PID" 2>/dev/null; then
    kill "$CURRENT_CMD_PID" 2>/dev/null || true
    wait "$CURRENT_CMD_PID" 2>/dev/null || true
    CURRENT_CMD_PID=""
  fi
  if [[ "${BUBBLE_STARTED:-0}" -eq 1 ]]; then
    bubble_end_screen
  fi
  rm -f "${STEP_LOG:-}" "${TTY_LOCK_FILE:-}" "${MUSIC_STATE_FILE:-}" 2>/dev/null || true
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

if [[ ! -d "$REPO_DIR/$PROJECT_DIR/frontend" || ! -d "$REPO_DIR/$PROJECT_DIR/backend" ]]; then
  die "유효한 프로젝트 경로가 아닙니다: $REPO_DIR ($PROJECT_DIR/frontend, $PROJECT_DIR/backend 없음)"
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
  if [[ "$PROJECT_DIR" == "proj_mockup" ]]; then
    if command -v systemctl >/dev/null 2>&1; then
      for candidate in cistus-mockup-be gonghak-mockup-backend; do
        if systemctl list-unit-files "${candidate}.service" --no-legend 2>/dev/null | grep -q "$candidate"; then
          echo "$candidate"
          return 0
        fi
      done
    fi
    echo "cistus-mockup-be"
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
FE_DIR="$REPO_DIR/$PROJECT_DIR/frontend"
BE_DIR="$REPO_DIR/$PROJECT_DIR/backend"
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
run_bubble_intro 0.35 0.55
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
      stop_morning_music
      bubble_render "error" "SERVICE HEALTHCHECK TIMEOUT" "$CURRENT_PCT" "$BACKEND_SERVICE failed healthcheck"
      if [[ "$BUBBLE_UI" -eq 1 ]]; then
        sleep 0.4
      fi
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
stop_morning_music
if [[ "$BUBBLE_UI" -eq 1 && "${MUSIC_BACKEND:-off}" == "decps" ]]; then
  tty_write_atomic $'\033[4;3;8,~\033[4;3;12,~\033[4;3;15,~\033[4;6;20,~'
fi
bubble_render "ready" "ALL SYSTEMS READY" 100 "UPDATE COMPLETED ($NEW_COMMIT)"
if [[ "$BUBBLE_UI" -eq 1 ]]; then
  sleep 0.8
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
