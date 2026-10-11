#!/usr/bin/env bash
set -euo pipefail

APP_IMAGE="miniivr:latest"
CONTAINER="miniivr"
ESL_PASSWORD="ClueCon"
SIP_PASSWORD="1234"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: Docker is not installed."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker Desktop is not running."
  exit 1
fi

PRIMARY_IF="$(route get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
HOST_IP=""
if [[ -n "${PRIMARY_IF:-}" ]]; then
  HOST_IP="$(ipconfig getifaddr "$PRIMARY_IF" 2>/dev/null || true)"
fi
[[ -n "$HOST_IP" ]] || HOST_IP="127.0.0.1"

echo "Stopping/removing previous MiniIVR..."
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker image rm -f "$APP_IMAGE" >/dev/null 2>&1 || true

echo "Building fresh image..."
docker build --pull --no-cache --build-arg HOST_IP="$HOST_IP" -t "$APP_IMAGE" .

echo "Starting MiniIVR..."
docker run -d \
  --name "$CONTAINER" \
  -p 5060:5060/tcp \
  -p 5060:5060/udp \
  -p 8021:8021/tcp \
  -p 16384-16484:16384-16484/udp \
  -e FREESWITCH_ESL_PASSWORD="$ESL_PASSWORD" \
  -e FREESWITCH_DEFAULT_PASSWORD="$SIP_PASSWORD" \
  -e FREESWITCH_DOMAIN="$HOST_IP" \
  -e FREESWITCH_EXTERNAL_IP="$HOST_IP" \
  -e FREESWITCH_RTP_START="16384" \
  -e FREESWITCH_RTP_END="16484" \
  "$APP_IMAGE" >/dev/null

echo
echo "MiniIVR is starting."
echo "Linphone:"
echo "  Username: 1000"
echo "  Password: $SIP_PASSWORD"
echo "  Domain:   $HOST_IP"
echo "  Port:     5060"
echo
echo "Dial 9199"
