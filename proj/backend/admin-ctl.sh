#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_PATH="$SCRIPT_DIR/build/install/backend/bin/backend"

if [ -x "$BIN_PATH" ]; then
  exec "$BIN_PATH" --admin "$@"
fi

PORT="${ADMIN_CONSOLE_PORT:-18089}"
SECRET_FILE="${ADMIN_CTL_SECRET_FILE:-$SCRIPT_DIR/.admin-ctl.secret}"
TOKEN="${ADMIN_CTL_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -r "$SECRET_FILE" ]; then
  TOKEN="$(tr -d '\r\n' < "$SECRET_FILE")"
fi

if [ "$#" -gt 0 ]; then
  CMD="$*"
  if [ -z "$TOKEN" ]; then
    echo "[ADMIN CLI] 제어 소켓 인증 토큰을 찾을 수 없습니다 ($SECRET_FILE)." >&2
    exit 1
  fi
  if command -v python3 >/dev/null 2>&1; then
    exec python3 - "$PORT" "$TOKEN" "$CMD" <<'PY'
import socket, sys
port = int(sys.argv[1])
token = sys.argv[2]
cmd = sys.argv[3]
with socket.create_connection(("127.0.0.1", port), timeout=5) as s:
    f = s.makefile("rw", encoding="utf-8", newline="\n")
    def send_and_read(line_to_send, print_ok=True):
        f.write(line_to_send + "\n")
        f.flush()
        has_err = False
        for line in f:
            line = line.rstrip("\r\n")
            if line == "__END_ADMIN_RESPONSE__":
                break
            if line.startswith("[ADMIN CONSOLE] 오류:"):
                has_err = True
            if print_ok or has_err:
                print(line)
        return not has_err

    if not send_and_read("AUTH " + token, print_ok=False):
        sys.exit(1)
    ok = send_and_read(cmd, print_ok=True)
    sys.exit(0 if ok else 1)
PY
  fi
fi

echo "[ADMIN CLI] 빌드된 바이너리가 없습니다. 먼저 './gradlew installDist'를 실행하거나 다음 명령을 사용하세요:" >&2
echo "  ./build/install/backend/bin/backend --admin admin <아이디_또는_학번>" >&2
exit 1