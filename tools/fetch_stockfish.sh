#!/usr/bin/env bash
# Fetch Stockfish sources + NNUE network for the NDK build.
#   Usage: tools/fetch_stockfish.sh [SF_TAG]
#   Defaults: sf_19 (the single SFNNv16 default net is embedded into the binary
#   via INCBIN, so no runtime filesDir push is needed — see
#   app/src/main/cpp/CMakeLists.txt).
#
#   A truncated net download aborts the engine on device at startup (LEB128 assert
#   in debug, "was not loaded successfully" + terminate in release), so every
#   download is size-verified and re-fetched when short.
#
#   SF 19 retired the secondary small net: only EvalFileDefaultName
#   ("nn-1a298aa575a0.nnue") is needed. Stale nn-baff1ede1f90.nnue files in
#   app/src/main/cpp/stockfish/src are harmless leftovers — delete them.
set -euo pipefail
SF_TAG="${1:-sf_19}"
# Must match EvalFileDefaultName in src/evaluate.h of the checked-out tag.
# NOTE: do NOT trust HEAD Content-Length from the CDN (it once reported 78944870
# for this file); the name IS the content hash, so verify with sha256sum — it
# must start with the name's 12 hex digits (verified 2026-09-06: 1a298aa575a0…).
NET="nn-1a298aa575a0.nnue"
NET_SIZE=98511183
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../app/src/main/cpp"

if [ ! -d "$CPP/stockfish" ]; then
  git clone --depth 1 --branch "$SF_TAG" https://github.com/official-stockfish/Stockfish.git "$CPP/stockfish"
fi

NNUE_DIR="$CPP/stockfish/src"
fetch_net() {
  local name="$1" expect="$2"
  local dest="$NNUE_DIR/$name"
  local have=0
  if [ -f "$dest" ]; then
    have="$(wc -c < "$dest" | tr -d ' ')"
  fi
  if [ "$have" != "$expect" ]; then
    echo "Downloading $name (have $have bytes, want $expect) ..."
    curl -fL --retry 3 --retry-all-errors -o "$dest.tmp" \
      "https://tests.stockfishchess.org/api/nn/$name"
    mv "$dest.tmp" "$dest"
    have="$(wc -c < "$dest" | tr -d ' ')"
    if [ "$have" != "$expect" ]; then
      echo "ERROR: $name size $have != $expect, download truncated?" >&2
      exit 1
    fi
  else
    echo "$name OK ($have bytes)"
  fi
}

fetch_net "$NET" "$NET_SIZE"

echo "OK. Nets are embedded into libstockfish.so at build time (INCBIN)."
