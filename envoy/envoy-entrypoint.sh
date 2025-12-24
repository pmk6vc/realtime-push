#!/usr/bin/env sh
set -eu

require_var() {
  name="$1"
  # Use eval to read the variable by name in POSIX sh
  eval "val=\${$name:-}"
  if [ -z "$val" ]; then
    echo "ERROR: Required env var '$name' is not set (or is empty)." >&2
    exit 1
  fi
}

# Required variables for this template
require_var "KC_ISSUER"
require_var "KC_JWKS_URI"
require_var "KC_JWKS_HOST"
require_var "KC_JWKS_PORT"
require_var "UPSTREAM_HOST"
require_var "UPSTREAM_PORT"

envsubst < /etc/envoy/envoy.template.yaml > /etc/envoy/envoy.yaml

# Quick sanity check: ensure no unsubstituted ${...} remains
if grep -q '\${[^}]*}' /etc/envoy/envoy.yaml; then
  echo "ERROR: Unsubstituted template variables remain in /etc/envoy/envoy.yaml:" >&2
  grep -n '\${[^}]*}' /etc/envoy/envoy.yaml >&2
  exit 1
fi

echo "=== Rendered /etc/envoy/envoy.yaml ==="
sed -n '1,200p' /etc/envoy/envoy.yaml
echo "=== Starting Envoy ==="

exec envoy -c /etc/envoy/envoy.yaml --log-level info
