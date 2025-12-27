#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for Citus workers to accept connections..."

# Note - cannot rely on internal health checks alone, as local connections may succeed while external ones fail
for host in citus_worker_1 citus_worker_2 citus_worker_3; do
  until pg_isready -h "$host" -p 5432 -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" >/dev/null 2>&1; do
    sleep 1
  done
  echo "  - $host is ready"
done

echo "Configuring Citus on coordinator..."
psql -v ON_ERROR_STOP=1 \
  -U "${POSTGRES_USER}" \
  -d "${POSTGRES_DB}" <<'SQL'
CREATE EXTENSION IF NOT EXISTS citus;

SELECT master_add_node('citus_worker_1', 5432);
SELECT master_add_node('citus_worker_2', 5432);
SELECT master_add_node('citus_worker_3', 5432);
SQL

echo "Citus coordinator setup complete."