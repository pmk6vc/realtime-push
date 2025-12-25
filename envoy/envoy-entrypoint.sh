#!/usr/bin/env sh
set -eu

required() {
  name="$1"
  val="$(eval "printf '%s' \"\${$name-}\"")"
  if [ -z "$val" ]; then
    echo "ERROR: missing required env var: $name" >&2
    exit 2
  fi
}

# Required for your template
required KC_ISSUER
required KC_JWKS_URI
required KC_JWKS_HOST
required KC_JWKS_PORT
required UPSTREAM_HOST
required UPSTREAM_PORT

envsubst < /etc/envoy/envoy.template.yaml > /etc/envoy/envoy.yaml

echo "=== Rendered /etc/envoy/envoy.yaml (first 200 lines) ==="
sed -n '1,200p' /etc/envoy/envoy.yaml

echo "=== Validating Envoy config ==="
envoy -c /etc/envoy/envoy.yaml --mode validate

echo "=== Starting Envoy ==="
exec envoy -c /etc/envoy/envoy.yaml --log-level info
