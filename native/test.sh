#!/bin/sh
# Offline end-to-end test: build, generate a synthetic QQ-format database,
# and read it back through the decrypting VFS.
#
#   ./test.sh              host + arm64 build, then the fixture test
#   ./test.sh host         host only (no NDK required — used by CI)
set -e
cd "$(dirname "$0")"

./build.sh "${1:-}"

FIXTURE="$(mktemp /tmp/ntdb-fixture-XXXXXX.db)"
trap 'rm -f "$FIXTURE"' EXIT

MD5KEY=00112233445566778899aabbccddeeff
WAL_MARKER=WAL-OVERLAY-9f3c   # must match synth.py
python3 test/synth.py "$FIXTURE" "$MD5KEY"

echo "== schema report =="
OUT=$(./build/ntdbread-host --md5key "$MD5KEY" "$FIXTURE")
echo "$OUT"
echo "$OUT" | grep -q "^page_count=38$" || { echo "FAIL: page_count" >&2; exit 1; }
echo "$OUT" | grep -q "^table sessions 2$" || { echo "FAIL: sessions table" >&2; exit 1; }
echo "$OUT" | grep -q "^table messages 4$" || { echo "FAIL: messages table" >&2; exit 1; }
echo "$OUT" | grep -q "^wal_mode=none " || { echo "FAIL: wal_mode" >&2; exit 1; }

echo "== sql query =="
ROWS=$(./build/ntdbread-host --md5key "$MD5KEY" "$FIXTURE" \
    "SELECT count(*) FROM messages WHERE length(body) > 0" 2>/dev/null)
echo "$ROWS"
echo "$ROWS" | tail -1 | grep -q "^400$" || { echo "FAIL: message count" >&2; exit 1; }

echo "== wrong key is rejected =="
if ./build/ntdbread-host --md5key ffffffffffffffffffffffffffffffff "$FIXTURE" \
    >/dev/null 2>&1; then
    echo "FAIL: wrong key accepted" >&2
    exit 1
fi

# ---- WAL merge flavours -----------------------------------------------------
# Each case rewrites the fixture plus a -wal carrying the pages of an updated
# revision; the query must see the WAL value, not the main-file value.
wal_case() {
    flavour="$1"; want_mode="$2"; want_plain="$3"
    WFIX="$(mktemp /tmp/ntdb-wal-XXXXXX.db)"
    OUT=$(python3 test/synth.py "$WFIX" "$MD5KEY" "$flavour")
    echo "$OUT"
    echo "== wal flavour: $flavour =="

    OUT=$(./build/ntdbread-host --md5key "$MD5KEY" "$WFIX")
    echo "$OUT"
    echo "$OUT" | grep -q "^wal_mode=$want_mode wal_frames=[1-9]" \
        || { echo "FAIL: $flavour wal_mode/frames" >&2; exit 1; }
    echo "$OUT" | grep -q "wal_plain=$want_plain" \
        || { echo "FAIL: $flavour wal_plain" >&2; exit 1; }

    ROW=$(./build/ntdbread-host --md5key "$MD5KEY" "$WFIX" \
        "SELECT body FROM messages WHERE id = 1" 2>/dev/null | tail -1)
    echo "overlay row: $ROW"
    [ "$ROW" = "$WAL_MARKER" ] \
        || { echo "FAIL: $flavour frame overlay not applied" >&2; exit 1; }

    # --no-wal must fall back to the main file and see the old value.
    ROW=$(./build/ntdbread-host --no-wal --md5key "$MD5KEY" "$WFIX" \
        "SELECT body FROM messages WHERE id = 1" 2>/dev/null | tail -1)
    [ "$ROW" != "$WAL_MARKER" ] \
        || { echo "FAIL: $flavour --no-wal still overlays" >&2; exit 1; }

    rm -f "$WFIX" "$WFIX-wal"
}

wal_case plain    raw  1
wal_case enc-raw  raw  0
wal_case enc-dec  dec  0

echo "test ok"
