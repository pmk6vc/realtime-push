#!/usr/bin/env bash
set -euo pipefail

# Script that kicks off the execution of all initialization scripts unique to Citus coordinator
# Script mounted to /docker-entrypoint-initdb.d, which PG container runs automatically on initialization

if [[ "${HOSTNAME}" != "citus_master" ]]; then
  echo "[init-runner] Not coordinator (${HOSTNAME}); skipping init-master."
  exit 0
fi

echo "[init-runner] Running init-master scripts on coordinator..."

for f in /init-master/*.sh; do
  [ -e "$f" ] || continue
  echo "[init-runner] exec $f"
  bash "$f"
done

for f in /init-master/*.sql; do
  [ -e "$f" ] || continue
  echo "[init-runner] psql $f"
  psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -f "$f"
done