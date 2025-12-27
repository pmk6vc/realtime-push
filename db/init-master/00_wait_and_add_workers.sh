#!/usr/bin/env bash
set -euo pipefail

echo "[init-master] Waiting for Citus workers to accept connections..."
for host in citus_worker_1 citus_worker_2 citus_worker_3; do
  until pg_isready -h "$host" -p 5432 -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" >/dev/null 2>&1; do
    sleep 1
  done
  echo "  - $host is ready"
done

echo "[init-master] Registering workers with coordinator..."

psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM master_get_active_worker_nodes()
    WHERE node_name='citus_worker_1' AND node_port=5432
  ) THEN
    PERFORM master_add_node('citus_worker_1', 5432);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM master_get_active_worker_nodes()
    WHERE node_name='citus_worker_2' AND node_port=5432
  ) THEN
    PERFORM master_add_node('citus_worker_2', 5432);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM master_get_active_worker_nodes()
    WHERE node_name='citus_worker_3' AND node_port=5432
  ) THEN
    PERFORM master_add_node('citus_worker_3', 5432);
  END IF;
END $$;
SQL