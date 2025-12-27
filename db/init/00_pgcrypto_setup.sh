#!/usr/bin/env bash
set -euo pipefail

echo "Configuring pgcrypto (used for UUID generation)..."
psql -v ON_ERROR_STOP=1 \
  -U "${POSTGRES_USER}" \
  -d "${POSTGRES_DB}" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SQL

echo "pgcrypto setup complete."