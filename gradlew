#!/usr/bin/env sh
set -eu

VERSION=8.13
CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/simplelink-bootstrap"
DIST_DIR="$CACHE_ROOT/gradle-$VERSION"
ZIP="$CACHE_ROOT/gradle-$VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"

if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE_ROOT"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$URL" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "$URL"
    else
      echo "curl or wget is required for the first Gradle bootstrap." >&2
      exit 1
    fi
  fi
  CHECKSUM_FILE="$ZIP.sha256"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$URL.sha256" -o "$CHECKSUM_FILE"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$CHECKSUM_FILE" "$URL.sha256"
  fi
  if command -v sha256sum >/dev/null 2>&1 && [ -s "$CHECKSUM_FILE" ]; then
    EXPECTED=$(tr -d '[:space:]' < "$CHECKSUM_FILE")
    ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
    [ "$EXPECTED" = "$ACTUAL" ] || { echo "Gradle checksum mismatch." >&2; rm -f "$ZIP"; exit 1; }
  fi
  TMP="$CACHE_ROOT/.extract-$VERSION-$$"
  rm -rf "$TMP"
  mkdir -p "$TMP"
  unzip -q "$ZIP" -d "$TMP"
  rm -rf "$DIST_DIR"
  mv "$TMP/gradle-$VERSION" "$DIST_DIR"
  rm -rf "$TMP"
fi

exec "$DIST_DIR/bin/gradle" "$@"
