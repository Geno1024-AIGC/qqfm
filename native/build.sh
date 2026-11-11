#!/bin/sh
# Builds the native nt_msg.db reader.
#
#   ./build.sh          host binary (with a crypto self-test) + arm64 Android
#                       binary (requires the NDK, see ANDROID_NDK_HOME)
#   ./build.sh host     host binary only
#
# The arm64 binary is packaged into the APK's assets and executed via su at
# runtime; CI builds it with the same script and runs the host --kat test.
set -e
cd "$(dirname "$0")"

# CI installs the NDK under ANDROID_HOME (setup-android), a local SDK usually
# lives under $HOME/Android/Sdk; ANDROID_NDK_HOME wins when set explicitly.
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/29.0.14206865}"
HOSTCC="${HOSTCC:-cc}"
CFLAGS="-O2 -std=gnu11 -Wall -Wextra -Wno-unused-parameter"
SQLITE_CFLAGS="-O2 -std=gnu11 -w" # third-party: silence its own warnings
SRC="src/aes.c src/sha512.c src/ntdb_vfs.c src/main.c"
OUT_HOST="build/ntdbread-host"
OUT_ARM64="build/ntdbread-arm64"

mkdir -p build

build_one() {
    # $1 = compiler, $2 = output, $3 = extra ldflags (unused, kept simple)
    "$1" $SQLITE_CFLAGS -Ithird_party -c third_party/sqlite3.c -o build/sqlite3.o
    for f in $SRC; do
        "$1" $CFLAGS -Ithird_party -Isrc -c "$f" -o "build/$(basename "$f" .c).o"
    done
    "$1" build/sqlite3.o build/aes.o build/sha512.o build/ntdb_vfs.o \
        build/main.o -o "$2"
}

echo "== host =="
build_one "$HOSTCC" "$OUT_HOST"
"./$OUT_HOST" --kat

case "${1:-}" in
host)
    exit 0
    ;;
esac

CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
if [ ! -x "$CC" ]; then
    echo "NDK clang not found: $CC" >&2
    echo "Install the NDK or set ANDROID_NDK_HOME (or run ./build.sh host)." >&2
    exit 1
fi
echo "== arm64 (minSdk 26) =="
build_one "$CC" "$OUT_ARM64"
file "$OUT_ARM64" 2>/dev/null || true
echo "built: $OUT_HOST $OUT_ARM64"
