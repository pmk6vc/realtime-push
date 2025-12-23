#!/usr/bin/env sh
set -eu

# Fail fast if required vars are missing
: "${MESSAGING_APP_HOST:?Must set MESSAGING_APP_HOST}"
: "${MESSAGING_APP_PORT:?Must set MESSAGING_APP_PORT}"

envsubst < /etc/envoy/envoy.yaml.template > /etc/envoy/envoy.yaml

echo "=== Rendered /etc/envoy/envoy.yaml ==="
sed -n '1,200p' /etc/envoy/envoy.yaml
echo "=== Starting Envoy ==="

exec envoy -c /etc/envoy/envoy.yaml --log-level info
