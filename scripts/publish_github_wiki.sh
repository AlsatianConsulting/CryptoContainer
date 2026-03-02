#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WIKI_REMOTE="${1:-git@github.com:AlsatianConsulting/CryptoContainer.wiki.git}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "Cloning wiki remote: $WIKI_REMOTE"
if ! git clone "$WIKI_REMOTE" "$WORK_DIR/wiki"; then
  echo "Failed to clone wiki remote. Ensure the GitHub wiki is enabled for the repository."
  exit 1
fi

rsync -a --delete "$ROOT_DIR/wiki/" "$WORK_DIR/wiki/"

git -C "$WORK_DIR/wiki" add -A
if git -C "$WORK_DIR/wiki" diff --cached --quiet; then
  echo "No wiki changes to publish."
  exit 0
fi

git -C "$WORK_DIR/wiki" commit -m "Update CryptoContainer wiki"
git -C "$WORK_DIR/wiki" push origin master

echo "Wiki published successfully."
