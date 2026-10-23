#!/usr/bin/env bash
# Fails when the Android UI changed since the base ref without a change to mockup/.
#
# Usage: tools/check-mockup.sh [base-ref]
# Without a base ref (first release) it only checks that mockup/index.html exists.
# Run from the repository root. CI uses it from the release workflow.
set -euo pipefail

UI='app/src/main/java/dev/walkcode/app/ui/'
base="${1:-}"

if [ -z "$base" ]; then
  if [ -f mockup/index.html ]; then
    echo "mockup check: no earlier release to compare with, mockup/index.html present"
    exit 0
  fi
  echo "::error::mockup/index.html is missing"
  exit 1
fi

changed=$(git diff --name-only "$base" HEAD)
ui_changed=$(printf '%s\n' "$changed" | grep -c "^$UI" || true)
mock_changed=$(printf '%s\n' "$changed" | grep -c '^mockup/' || true)

if [ "$ui_changed" -gt 0 ] && [ "$mock_changed" -eq 0 ]; then
  echo "::error::$ui_changed UI file(s) changed since $base but mockup/ did not."
  echo "::error::Update mockup/index.html to match the new screens, then release again."
  printf '%s\n' "$changed" | grep "^$UI" | sed 's/^/  changed UI file: /'
  exit 1
fi

echo "mockup check ok: $ui_changed UI file(s) and $mock_changed mockup file(s) changed since $base"
