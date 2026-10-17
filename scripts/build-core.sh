#!/usr/bin/env bash
# Binds the olcOS Go core for Android: the olcrtc mobile package, the
# tun2socks glue (tunstack), the bonding client (bond) and the olcos Xray
# engine, from the gomobile/ wrapper module the iOS app binds, into one AAR.
#
#   bash scripts/build-core.sh <core checkout> <output .aar>
#
# <core checkout> is the olcos-ios-dev tree (or its public snapshot,
# olcos-ios-build) with its submodules: gomobile/, olcrtc-upstream/,
# olcos-core/, scripts/mobile-shim/ and, when present, scripts/olcrtc-core.patch
# (the closed core patch series, applied for the bind like the iOS build does).
# Needs Go (the version in gomobile/go.mod), a JDK (gomobile runs javac), the
# Android SDK and an NDK (ANDROID_HOME, ANDROID_NDK_HOME). Phones (arm64) and
# the emulator (amd64) only: the two 32-bit ABIs would double the bind time.
set -euo pipefail

CORE="$(cd "$1" && pwd)"
# The output directory (app/libs) is not in the repository: make it before
# resolving the path, or the cd fails and the name resolves to the root.
mkdir -p "$(dirname "$2")"
OUT="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
UPSTREAM="$CORE/olcrtc-upstream"
CORE_PATCH="$CORE/scripts/olcrtc-core.patch"

for f in "$UPSTREAM/mobile/runtime.go" "$CORE/olcos-core/go.mod" "$CORE/gomobile/go.mod"; do
  [ -f "$f" ] || { echo "error: $f missing (submodules not checked out?)" >&2; exit 1; }
done
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
  export ANDROID_NDK_HOME
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "error: no Android NDK (ANDROID_NDK_HOME)" >&2; exit 1; }
echo "note: NDK at $ANDROID_NDK_HOME"

export PATH="$PATH:$(go env GOPATH)/bin"
(cd "$CORE/gomobile" && go mod tidy)
GOMOBILE_VER="$(cd "$CORE/gomobile" && go list -m -f '{{.Version}}' golang.org/x/mobile)"
echo "note: installing gomobile@${GOMOBILE_VER}"
go install "golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VER}"
gomobile init

# The same build-time shims as the iOS bind, removed afterwards whatever
# happens: logflags pins the core's log prefix to the time-only shape the
# apps parse; the ipsec shim is iOS-only but harmless here.
SHIM_DSTS=(
  "$UPSTREAM/mobile/zz_olcos_logflags.go"
  "$UPSTREAM/internal/protect/zz_olcos_ipsec.go"
)
PATCHED_FILES=()
if [ -s "$CORE_PATCH" ]; then
  while IFS=$'\t' read -r _ _ path; do
    PATCHED_FILES+=("$path")
  done < <(git -C "$UPSTREAM" apply --numstat "$CORE_PATCH")
fi
restore_patched() {
  if [ "${#PATCHED_FILES[@]}" -gt 0 ]; then
    git -C "$UPSTREAM" checkout -- "${PATCHED_FILES[@]}"
  fi
}
trap 'rm -f "${SHIM_DSTS[@]}"; restore_patched' EXIT
restore_patched
if [ -s "$CORE_PATCH" ]; then
  git -C "$UPSTREAM" apply "$CORE_PATCH"
  echo "note: core patch series applied"
fi
cp "$CORE/scripts/mobile-shim/logflags.go"      "${SHIM_DSTS[0]}"
cp "$CORE/scripts/mobile-shim/protect_ipsec.go" "${SHIM_DSTS[1]}"

echo "note: gomobile bind -target=android -> $OUT"
cd "$CORE/gomobile"
gomobile bind -target=android/arm64,android/amd64 -androidapi 26 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o "$OUT" \
  github.com/openlibrecommunity/olcrtc/mobile ./tunstack ./bond github.com/Hotelk52339/olcos-core/olcos
echo "done: $OUT ($(du -h "$OUT" | cut -f1))"
