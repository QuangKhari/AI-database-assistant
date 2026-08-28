#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="${PROJECT_DIR:-/opt/ai-database-assistant}"
WEB_ROOT="${WEB_ROOT:-/var/www/ai-database-assistant}"

cd "$PROJECT_DIR"

if [[ ! -f .env.production ]]; then
  echo "Missing $PROJECT_DIR/.env.production" >&2
  exit 1
fi

npm --prefix frontend ci
npm --prefix frontend run build

sudo install -d -m 0755 "$WEB_ROOT"
sudo cp -a frontend/dist/. "$WEB_ROOT/"

docker compose --env-file .env.production -f compose.prod.yml up -d --build

for attempt in {1..18}; do
  if curl --fail --silent http://127.0.0.1:8080/api/health >/dev/null; then
    echo "Deployment healthy."
    exit 0
  fi
  sleep 5
done

echo "Backend did not become healthy in time." >&2
docker compose --env-file .env.production -f compose.prod.yml ps
exit 1
