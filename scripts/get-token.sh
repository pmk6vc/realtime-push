#!/bin/sh
set -eu

# Resolve the directory this script lives in (works for bash/zsh/sh)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Load .env from parent directory of this script, if present
ENV_FILE="${SCRIPT_DIR}/../.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <username> <password>" >&2
  exit 1
fi

USERNAME="$1"
PASSWORD="$2"

# Defaults (overridable via .env)
KC_HOST_PORT="${KC_HOST_PORT:-8080}"
REALM="${KC_REALM:-chat}"
CLIENT_ID="${KC_CLIENT_ID:-chat-frontend}"

curl -s -X POST "http://localhost:${KC_HOST_PORT}/realms/${REALM}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=${CLIENT_ID}" \
  -d "username=${USERNAME}" \
  -d "password=${PASSWORD}" \
| jq -r '.access_token'
