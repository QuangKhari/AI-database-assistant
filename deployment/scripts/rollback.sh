#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <git-tag-or-commit>" >&2
  exit 1
fi

PROJECT_DIR="${PROJECT_DIR:-/opt/ai-database-assistant}"
TARGET_REF="$1"

cd "$PROJECT_DIR"
git fetch --tags origin
git checkout --detach "$TARGET_REF"
"$PROJECT_DIR/deployment/scripts/deploy.sh"
