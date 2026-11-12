#!/usr/bin/env bash
set -euo pipefail

VERSION="v5.0.1"
EXPECTED_SHA256="764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
DEST="app/src/main/assets/scrcpy-server.jar"

echo "Fetching scrcpy-server ${VERSION}..."
mkdir -p "$(dirname "$DEST")"
curl -L -o "$DEST" "https://github.com/Genymobile/scrcpy/releases/download/${VERSION}/scrcpy-server-${VERSION}"

ACTUAL_SHA256=$(sha256sum "$DEST" | awk '{print $1}')
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "ERROR: SHA-256 mismatch!"
    echo "Expected: $EXPECTED_SHA256"
    echo "Got:      $ACTUAL_SHA256"
    rm -f "$DEST"
    exit 1
fi

echo "Successfully verified scrcpy-server ${VERSION} ($ACTUAL_SHA256)"
