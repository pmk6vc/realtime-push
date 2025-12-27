#!/usr/bin/env bash
set -euo pipefail

echo "[init-common] Installing .pgpass for inter-node auth..."

PGPASS="/var/lib/postgresql/.pgpass"

cat > "${PGPASS}" <<EOF
citus_master:5432:*:${POSTGRES_USER}:${POSTGRES_PASSWORD}
citus_worker_1:5432:*:${POSTGRES_USER}:${POSTGRES_PASSWORD}
citus_worker_2:5432:*:${POSTGRES_USER}:${POSTGRES_PASSWORD}
citus_worker_3:5432:*:${POSTGRES_USER}:${POSTGRES_PASSWORD}
EOF

chown postgres:postgres "${PGPASS}"
chmod 600 "${PGPASS}"