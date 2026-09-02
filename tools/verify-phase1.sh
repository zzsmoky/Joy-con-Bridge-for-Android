#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
check_classes="$(mktemp -d "${TMPDIR:-/tmp}/joycon2-bridge-source-check.XXXXXX")"

trap 'rm -rf "$check_classes"' EXIT

if command -v javac >/dev/null 2>&1; then
  compiler=(javac)
elif java --list-modules 2>/dev/null | grep -q '^jdk.compiler@'; then
  compiler=(java --module jdk.compiler/com.sun.tools.javac.Main)
else
  echo "No Java compiler is available." >&2
  exit 1
fi

"$project_root/tools/run-protocol-self-test.sh"

"${compiler[@]}" -d "$check_classes" "$project_root/tools/Phase1SourceCheck.java"
java -cp "$check_classes" Phase1SourceCheck "$project_root"
