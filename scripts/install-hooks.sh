#!/usr/bin/env bash
# Install Glyphew git hooks into .git/hooks/.
# Run once after every fresh clone: bash scripts/install-hooks.sh
set -e
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOOKS_DIR="$REPO_ROOT/.git/hooks"
SRC_DIR="$REPO_ROOT/scripts/hooks"

for hook in "$SRC_DIR"/*; do
  name="$(basename "$hook")"
  dest="$HOOKS_DIR/$name"
  cp "$hook" "$dest"
  chmod +x "$dest"
  echo "installed: .git/hooks/$name"
done
echo "done."
