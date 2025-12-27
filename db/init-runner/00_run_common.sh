#!/usr/bin/env bash
set -euo pipefail

# Script that kicks off the execution of all initialization scripts common to Citus coordinator and workers
# Script mounted to /docker-entrypoint-initdb.d, which PG container runs automatically on initialization

echo "[init-runner] Running init-common scripts..."

# Run .sh scripts
for f in /init-common/*.sh; do
  [ -e "$f" ] || continue
  echo "[init-runner] exec $f"
  bash "$f"
done

# Run .sql scripts
for f in /init-common/*.sql; do
  [ -e "$f" ] || continue
  echo "[init-runner] psql $f"
  psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -f "$f"
done