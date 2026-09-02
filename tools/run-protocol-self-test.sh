#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
test_classes="$(mktemp -d "${TMPDIR:-/tmp}/joycon2-bridge-self-test.XXXXXX")"

trap 'rm -rf "$test_classes"' EXIT

if command -v javac >/dev/null 2>&1; then
  compiler=(javac)
elif java --list-modules 2>/dev/null | grep -q '^jdk.compiler@'; then
  compiler=(java --module jdk.compiler/com.sun.tools.javac.Main)
else
  echo "No Java compiler is available." >&2
  exit 1
fi

"${compiler[@]}" -d "$test_classes" \
  "$project_root/app/src/main/java/dev/joycon2/bridge/protocol/JoyCon2Side.java" \
  "$project_root/app/src/main/java/dev/joycon2/bridge/protocol/JoyCon2Button.java" \
  "$project_root/app/src/main/java/dev/joycon2/bridge/protocol/JoyCon2Protocol.java" \
  "$project_root/app/src/main/java/dev/joycon2/bridge/protocol/JoyCon2InputState.java" \
  "$project_root/app/src/main/java/dev/joycon2/bridge/protocol/JoyCon2ReportDecoder.java" \
  "$project_root/tools/JoyCon2ProtocolSelfTest.java"

java -ea -cp "$test_classes" dev.joycon2.bridge.protocol.JoyCon2ProtocolSelfTest
