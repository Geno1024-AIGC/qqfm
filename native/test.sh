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

echo "== locate / dump-page diagnostics =="
# group_msg_table's root is a leaf holding rowids 1001..1003.
LOC=$(./build/ntdbread-host --md5key "$MD5KEY" "$FIXTURE" --locate 1002)
echo "$LOC"
echo "$LOC" | grep -q "^page=5 source=main frame=0 type=0x0d ncells=3 depth=1" \
    || { echo "FAIL: locate page" >&2; exit 1; }
echo "$LOC" | grep -q "^keys: first=1001 last=1003$" \
    || { echo "FAIL: locate keys" >&2; exit 1; }
echo "$LOC" | grep -q "^badcells=0$" || { echo "FAIL: locate badcells" >&2; exit 1; }

# messages is a two-level b-tree: the walk must cross an interior page (depth=2)
# and land on the leaf whose rowid range brackets the key.
LOC=$(./build/ntdbread-host --md5key "$MD5KEY" "$FIXTURE" --root 4 --locate 200)
echo "$LOC"
echo "$LOC" | grep -q "type=0x0d .* depth=2 " \
    || { echo "FAIL: interior page not walked" >&2; exit 1; }
echo "$LOC" | grep -q "^keys: first=199 last=220$" \
    || { echo "FAIL: wrong leaf" >&2; exit 1; }

DUMP=$(./build/ntdbread-host --md5key "$MD5KEY" "$FIXTURE" --dump-page 5)
echo "$DUMP"
echo "$DUMP" | grep -q "type=0x0d ncells=3 usable=4048" \
    || { echo "FAIL: dump-page" >&2; exit 1; }

# A body larger than the page spills into overflow pages; --locate must walk
# that chain and report the head page rather than mis-declaring the cell bad.
OVP="$(mktemp /tmp/ntdb-over-plain-XXXXXX.db)"
OV="$(mktemp /tmp/ntdb-over-XXXXXX.db)"
trap 'rm -f "$FIXTURE" "$OVP" "$OV"' EXIT
python3 - "$OVP" "$OV" "$MD5KEY" <<'PYEOF'
import sys
sys.path.insert(0, "test")
import synth
synth.build_plain(sys.argv[1])
synth.add_big_blobs(sys.argv[1], 2, 20000)
with open(sys.argv[1], "rb") as f:
    plain = f.read()
synth.encrypt(plain, sys.argv[3], sys.argv[2])
PYEOF
OVL=$(./build/ntdbread-host --md5key "$MD5KEY" "$OV" --root 3 --locate 101)
echo "$OVL"
echo "$OVL" | grep -q "^overflow: cells=.* bad=0$" \
    || { echo "FAIL: overflow chain reported bad" >&2; exit 1; }
echo "$OVL" | grep -q "^target: rowid=101 payload=20[0-9][0-9][0-9] over_pgno=[1-9]" \
    || { echo "FAIL: overflow head not followed" >&2; exit 1; }

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
pic_row() {  # pic_row table msgid peer sender elem md5 filename size
    printf '%s\n' "$PICS" | awk -F'\t' -v t="$1" -v id="$2" -v pe="$3" \
        -v se="$4" -v el="$5" -v md="$6" -v fn="$7" -v sz="$8" \
        '$1==t && $2==id && $3==pe && $4==se && $5==el && $6==md && \
         $7==fn && $8==sz {found=1} END {exit !found}'
}
printf '%s\n' "$PICS" | awk -F'\t' 'NR==1 {
    if ($1!="table" || $2!="msgid" || $3!="peer" || $4!="sender" ||
        $5!="elem" || $6!="md5" || $7!="filename" || $8!="size" ||
        $9!="path") exit 1
    next
} {rows++}
END {exit !(NR>0 && rows==3)}' || { echo "FAIL: pics header/row count" >&2; exit 1; }

pic_row group_msg_table 1001 761140305 u_SENDER 2 28e13060375d9b8e2f95960f578e6eae \
    28E13060375D9B8E2F95960F578E6EAE.png 1199 \
    || { echo "FAIL: image row (f45424)" >&2; exit 1; }
pic_row group_msg_table 1003 761140305 u_SENDER 3 5a6b7c8d9e0f1a2b3c4d5e6f70819a2b \
    5A6B7C8D9E0F1A2B3C4D5E6F70819A2B.mp4 1199 \
    || { echo "FAIL: video row (f45406 fallback)" >&2; exit 1; }
pic_row c2c_msg_table 2001 u_C2CPEER u_SENDER 2 0f4b1c7a9d3e5268b0ac41d7e93f2a51 \
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
# The torn table is group_msg_table; the c2c table is intact, so its rows must
# still come out. Losing the rest of the database to one bad b-tree is the bug
# this checks for.
printf '%s\n' "$TORNOUT" | awk -F'\t' 'NR==1 {
    if ($1!="table" || $2!="msgid" || $3!="peer" || $4!="sender" ||
        $5!="elem" || $6!="md5" || $7!="filename" || $8!="size") exit 1
    next
} END {exit !(NR>0)}' || { echo "FAIL: pics header missing" >&2; exit 1; }
printf '%s\n' "$TORNOUT" | grep -q "0f4b1c7a9d3e5268b0ac41d7e93f2a51" \
    || { echo "FAIL: intact c2c table was not scanned after the torn one" >&2; exit 1; }
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
