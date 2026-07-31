#!/usr/bin/env sh
set -eu

compose_file=${1:-compose.yaml}
services=$(docker compose --file "$compose_file" --profile workers config --services)

require_service() {
  service=$1
  echo "$services" | grep -qx "$service" || {
    echo "missing required local service: $service" >&2
    exit 1
  }
}

for service in \
  postgres migrations queue-emulator object-store-working object-store-finalized \
  telemetry notification-sink analytics-sink core web \
  worker-inspection worker-extraction worker-narration-analysis worker-speech \
  worker-packaging worker-finalization worker-erasure worker-reconciliation
do
  require_service "$service"
done

rendered=$(docker compose --file "$compose_file" --profile workers config)
echo "$rendered" | grep -q 'image: postgres:17\.' || {
  echo "local PostgreSQL must pin production major 17" >&2
  exit 1
}

echo "$rendered" | grep -q 'APP_MODE: worker' || {
  echo "worker services must use the production worker entrypoint" >&2
  exit 1
}

rg -q 'location /oauth2/' apps/web/nginx.conf &&
  rg -q 'location /login/oauth2/' apps/web/nginx.conf || {
  echo "same-origin web proxy must route both OIDC browser endpoints to the core" >&2
  exit 1
}

rg -Fq "script-src 'nonce-\$request_id' 'strict-dynamic'" apps/web/security-headers.conf || {
  echo "web boundary must issue nonce-based strict CSP" >&2
  exit 1
}

jq -e '.hosting.rewrites | any(.source == "/oauth2/**") and any(.source == "/login/oauth2/**")' firebase.json >/dev/null || {
  echo "Firebase must preserve the same-origin OIDC routes" >&2
  exit 1
}

echo "local environment contract is valid"
