#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
CHECK_DIR="$(mktemp -d)"
trap 'rm -rf "$CHECK_DIR"' EXIT
javac -d "$CHECK_DIR" tools/CheckJavaSyntax.java
mapfile -t SOURCES < <(find app/src -name '*.java' | sort)
java -cp "$CHECK_DIR" CheckJavaSyntax "${SOURCES[@]}"
javac -d "$CHECK_DIR" app/src/main/java/dev/grain/photo/{Framing,CurveMath,PreviewMath,FocusMath,ExposureMath}.java tools/SmokeTest.java
java -cp "$CHECK_DIR" SmokeTest
