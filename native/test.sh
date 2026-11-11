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
echo "$OUT" | grep -q "^page_count=40$" || { echo "FAIL: page_count" >&2; exit 1; }
echo "$OUT" | grep -q "^table sessions 2$" || { echo "FAIL: sessions table" >&2; exit 1; }
echo "$OUT" | grep -q "^table messages 4$" || { echo "FAIL: messages table" >&2; exit 1; }
echo "$OUT" | grep -q "^table group_msg_table 5$" || { echo "FAIL: group_msg_table" >&2; exit 1; }
echo "$OUT" | grep -q "^table c2c_msg_table 6$" || { echo "FAIL: c2c_msg_table" >&2; exit 1; }
echo "$OUT" | grep -q "^wal_mode=none " || { echo "FAIL: wal_mode" >&2; exit 1; }

echo "== sql query =="
ROWS=$(./build/ntdbread-host --md5key "$MD5KEY" "$FIXTURE" \
    "SELECT count(*) FROM messages WHERE length(body) > 0" 2>/dev/null)
echo "$ROWS"
echo "$ROWS" | tail -1 | grep -q "^400$" || { echo "FAIL: message count" >&2; exit 1; }

echo "== picture extraction =="
# image via f45424 (authoritative hex), video via f45406 fallback, and the
# text message's md5-shaped noise must not be extracted.
PICS=$(./build/ntdbread-host --md5key "$MD5KEY" "$FIXTURE" --pics 2>/dev/null)
echo "$PICS"
pic_row() {  # pic_row table msgid col40011 col40020 elem md5 filename size
    printf '%s\n' "$PICS" | awk -F'\t' -v t="$1" -v id="$2" -v c11="$3" \
        -v c20="$4" -v el="$5" -v md="$6" -v fn="$7" -v sz="$8" \
        '$1==t && $2==id && $3==c11 && $4==c20 && $5==el && $6==md && \
         $7==fn && $8==sz {found=1} END {exit !found}'
}
printf '%s\n' "$PICS" | awk -F'\t' 'NR==1 {
    if ($1!="table" || $2!="msgid" || $3!="col40011" || $4!="col40020" ||
        $5!="elem" || $6!="md5" || $7!="filename" || $8!="size") exit 1
    next
} {rows++}
END {exit !(NR>0 && rows==3)}' || { echo "FAIL: pics header/row count" >&2; exit 1; }

pic_row group_msg_table 1001 2 u_TESTPEER 2 28e13060375d9b8e2f95960f578e6eae \
    28E13060375D9B8E2F95960F578E6EAE.png 1199 \
    || { echo "FAIL: image row (f45424)" >&2; exit 1; }
pic_row group_msg_table 1003 3 "" 3 5a6b7c8d9e0f1a2b3c4d5e6f70819a2b \
    5A6B7C8D9E0F1A2B3C4D5E6F70819A2B.mp4 1199 \
    || { echo "FAIL: video row (f45406 fallback)" >&2; exit 1; }
pic_row c2c_msg_table 2001 2 u_C2CPEER 2 0f4b1c7a9d3e5268b0ac41d7e93f2a51 \
    0F4B1C7A9D3E5268B0AC41D7E93F2A51.png 1199 \
    || { echo "FAIL: c2c image row" >&2; exit 1; }
echo "$PICS" | grep -q "0123456789abcdef0123456789abcdef" \
    && { echo "FAIL: noise md5 extracted" >&2; exit 1; }
true

echo "== pics chunked scan =="
# chunk size 2 forces the fixture's 3 group rows across two chunks; the
# result must be byte-identical to the single-chunk scan above.
CHUNKED=$(NTDB_PICS_CHUNK=2 ./build/ntdbread-host --md5key "$MD5KEY" \
    "$FIXTURE" --pics 2>/dev/null)
[ "$CHUNKED" = "$PICS" ] || { echo "FAIL: chunked scan differs" >&2; exit 1; }

echo "== pics torn read keeps partial output =="
TORN="$(mktemp /tmp/ntdb-torn-XXXXXX.db)"
TORNERR="$(mktemp /tmp/ntdb-torn-err-XXXXXX)"
cp "$FIXTURE" "$TORN"
python3 - "$TORN" <<'PYEOF'
import sys
# flip the group_msg_table root leaf (rootpage 5) b-tree header
with open(sys.argv[1], "r+b") as f:
    pos = 1024 + (5 - 1) * 4096 + 8
    f.seek(pos)
    b = f.read(64)
    f.seek(pos)
    f.write(bytes(x ^ 0xFF for x in b))
PYEOF
if TORNOUT=$(./build/ntdbread-host --md5key "$MD5KEY" "$TORN" --pics \
        2>"$TORNERR"); then
    echo "FAIL: corrupted fixture accepted" >&2
    exit 1
fi
echo "stderr: $(tr '\n' '|' < "$TORNERR")"
grep -q "no progress after 3 attempts" "$TORNERR" \
    || { echo "FAIL: no give-up message" >&2; exit 1; }
grep -q "partial list" "$TORNERR" \
    || { echo "FAIL: no partial-list message" >&2; exit 1; }
[ "$(printf '%s\n' "$TORNOUT" | wc -l)" -eq 1 ] \
    || { echo "FAIL: expected header only on stdout" >&2; exit 1; }
rm -f "$TORN" "$TORNERR"

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
