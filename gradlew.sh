#!/bin/sh
cwd=$(pwd)
exec "I:/chessai/.gradle-dist/gradle-8.7/bin/gradle" -p "$cwd" "$@"
