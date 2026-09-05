#!/usr/bin/env bash
# Fetch Stockfish sources + NNUE networks for the NDK build.
#   Usage: tools/fetch_stockfish.sh [SF_TAG]
#   Defaults: sf_16.1 (both default nets are embedded into the binary via INCBIN,
#   so no runtime filesDir push is needed — see app/src/main/cpp/CMakeLists.txt).
#
#   A truncated net download aborts the engine on device at startup (LEB128 assert
#   in debug, "was not loaded successfully" + terminate in release), so every
#   download is size-verified and re-fetched when short.
set -euo pipefail
SF_TAG="${1:-sf_16.1}"
# SF 16.1 default nets (must match EvalFileDefaultNameBig/Small in evaluate.h).
BIG_NET="nn-b1a57edbea57.nnue"
BIG_SIZE=65429575
SMALL_NET="nn-baff1ede1f90.nnue"
SMALL_SIZE=3480122
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

fetch_net "$BIG_NET" "$BIG_SIZE"
fetch_net "$SMALL_NET" "$SMALL_SIZE"

echo "OK. Nets are embedded into libstockfish.so at build time (INCBIN)."
