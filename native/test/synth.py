#!/usr/bin/env python3
"""Builds a synthetic QQ-style encrypted nt_msg.db for offline testing.

Mirrors what the Phase 0 probe proved on the real database:

  file   = [1024-byte prefix][page, page, …]      page = 4096
  salt   = first 16 bytes of the stream (prefix + 0)
  key    = PBKDF2-HMAC-SHA512(md5hex_ascii, salt, 4000) → 32 bytes
  page N = AES-256-CBC over [0,4048), IV at 4048, reserve tail unused
  page 1 = salt replaces "SQLite format 3\\0", cipher starts at 16

Usage:  synth.py <out.db> [md5key-hex] [wal-flavour]
Creates a small multi-page database with a few tables and rows.

wal-flavour (optional) also writes <out.db>-wal next to the fixture and
updates one row in a second revision of the data so frame overlay is
observable from a query result:

  plain    frames stored unencrypted, checksum over stored bytes
  enc-raw  frames encrypted, checksum over the stored (cipher) bytes
  enc-dec  frames encrypted, checksum over the decrypted page bytes
"""
import ctypes
import ctypes.util
import hashlib
import os
import shutil
import sqlite3
import sys
import tempfile

PAGE = 4096
RESERVE = 48
CIPHER_END = PAGE - RESERVE  # 4048
HEADER = 1024

# Message-body fixtures, mirroring the structure parsed from the live device:
# outer f40800 → element with f45002 (type; f40010 in older builds), f45402
# (name), f45406 (raw md5), f45405 (size), f45424 (md5 as hex). The text body
# carries hex-looking noise in fields the extractor must ignore.
IMG_MD5 = "28e13060375d9b8e2f95960f578e6eae"
IMG2_MD5 = "0f4b1c7a9d3e5268b0ac41d7e93f2a51"
VID_MD5 = "5a6b7c8d9e0f1a2b3c4d5e6f70819a2b"
NOISE_MD5 = "0123456789abcdef0123456789abcdef"  # must never be extracted


def _varint(v: int) -> bytes:
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        out.append(b | 0x80 if v else b)
        if not v:
            return bytes(out)


def _f_var(fn: int, v: int) -> bytes:
    return _varint((fn << 3) | 0) + _varint(v)


def _f_bytes(fn: int, payload: bytes) -> bytes:
    return _varint((fn << 3) | 2) + _varint(len(payload)) + payload


def picture_body(md5hex: str, name: str, elem: int = 2,
                 with_hex_field: bool = True) -> bytes:
    parts = [
        _f_var(45002, elem),
        _f_bytes(45402, name.encode()),
        _f_bytes(45406, bytes.fromhex(md5hex)),
        _f_var(45405, 1199),
    ]
    if with_hex_field:
        parts.append(_f_bytes(45424, md5hex.upper().encode()))
    # nested noise: a 32-hex string in a field the extractor must not read
    parts.append(_f_bytes(45600, _f_bytes(1, b"x" * 10)))
    return _f_bytes(40800, b"".join(parts))


def text_body() -> bytes:
    inner = _f_var(40010, 5)
    inner += _f_var(45001, 7706770972790875328)
    # rich text whose payload contains an md5-shaped string: must be ignored
    inner += _f_bytes(48214, ("<gtip><nor txt=\"noise " + NOISE_MD5
                              + "\"></nor></gtip>").encode())
    # …and a bare 32-hex blob in an unknown field, likewise
    inner += _f_bytes(45403, NOISE_MD5.encode())
    return _f_bytes(40800, inner)


def pkcs_pad_free_encrypt(data: bytes, key: bytes, iv: bytes) -> bytes:
    # AES-256-CBC without padding (lengths are block multiples here).
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
        return enc.update(data) + enc.finalize()
    except ImportError:
        from Crypto.Cipher import AES  # pycryptodome
        return AES.new(key, AES.MODE_CBC, iv).encrypt(data)


def build_plain(path: str) -> None:
    # Reserve 48 bytes per page (as QQ does) so SQLite never stores cell data
    # past 4048 — the encryption only covers [0,4048), so a reserve-0 database
    # would lose the tail of every page.
    lib = ctypes.CDLL(ctypes.util.find_library("sqlite3"))
    db = ctypes.c_void_p()
    if lib.sqlite3_open(path.encode(), ctypes.byref(db)) != 0:
        raise RuntimeError("sqlite3_open failed")
    try:
        reserve = ctypes.c_int(48)
        if lib.sqlite3_file_control(db, b"main", 38, ctypes.byref(reserve)) != 0:
            raise RuntimeError("SQLITE_FCNTL_RESERVE_BYTES failed")

        def run(sql: str) -> None:
            err = ctypes.c_char_p()
            rc = lib.sqlite3_exec(db, sql.encode("utf-8"), None, None, ctypes.byref(err))
            if rc != 0:
                raise RuntimeError(f"exec failed ({rc}): {err.value}")

        run("PRAGMA page_size=4096")
        run("PRAGMA journal_mode=DELETE")
        run("CREATE TABLE sessions(id INTEGER PRIMARY KEY, name TEXT, kind INTEGER)")
        run("CREATE TABLE blobs(id INTEGER PRIMARY KEY, payload BLOB)")
        run(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY, session INTEGER, "
            "md5 TEXT, body TEXT)"
        )
        run(
            'CREATE TABLE group_msg_table('
            '"40001" INTEGER PRIMARY KEY, "40011" INTEGER, '
            '"40020" TEXT, "40021" TEXT, "40800" BLOB)'
        )
        run(
            'CREATE TABLE c2c_msg_table('
            '"40001" INTEGER PRIMARY KEY, "40011" INTEGER, '
            '"40020" TEXT, "40021" TEXT, "40800" BLOB)'
        )
        run(
            "INSERT INTO sessions(name, kind) VALUES "
            + ",".join(
                f"('会话-{i} {'群' if i % 2 else '私聊'}', {i % 3})"
                for i in range(50)
            )
        )
        run(
            "INSERT INTO messages(session, md5, body) VALUES "
            + ",".join(
                f"({i % 50 + 1}, '{hashlib.md5(f'img{i}'.encode()).hexdigest()}', "
                f"'消息正文 {i} " + "内容" * 20 + "')"
                for i in range(400)
            )
        )
        run(
            "INSERT INTO blobs(payload) VALUES "
            + ",".join(f"(x'{os.urandom(512).hex()}')" for _ in range(100))
        )

        def insert_msgs(table: str, rows) -> None:
            values = ",".join(
                "(%d, %d, 'u_SENDER', %s, x'%s')"
                % (msgid, elem, "NULL" if peer is None else f"'{peer}'",
                   body.hex())
                for msgid, elem, peer, body in rows
            )
            run(f"INSERT INTO {table} VALUES {values}")

        insert_msgs("group_msg_table", [
            (1001, 2, "761140305",
             picture_body(IMG_MD5, IMG_MD5.upper() + ".png")),
            (1002, 5, None, text_body()),
            # video: no f45424 — exercises the raw f45406 fallback
            (1003, 3, "761140305",
             picture_body(VID_MD5, VID_MD5.upper() + ".mp4",
                          elem=3, with_hex_field=False)),
        ])
        insert_msgs("c2c_msg_table", [
            (2001, 2, "u_C2CPEER",
             picture_body(IMG2_MD5, IMG2_MD5.upper() + ".png")),
        ])
        run("VACUUM")
    finally:
        lib.sqlite3_close(db)


def encrypt(plain: bytes, md5key: str, out: str) -> None:
    assert len(plain) % PAGE == 0, "fixture must be page-aligned"
    salt = bytes(range(16))
    key = hashlib.pbkdf2_hmac("sha512", md5key.encode("ascii"), salt, 4000, 32)
    iv0 = bytes(range(16, 32))

    stream = bytearray()
    npages = len(plain) // PAGE
    for pgno in range(1, npages + 1):
        page = plain[(pgno - 1) * PAGE: pgno * PAGE]
        iv = iv0 if pgno == 1 else bytes((pgno * 7 + j) % 256 for j in range(16))
        if pgno == 1:
            cipher = pkcs_pad_free_encrypt(page[16:CIPHER_END], key, iv)
            stored = salt + cipher + iv + bytes(PAGE - CIPHER_END - 16)
        else:
            cipher = pkcs_pad_free_encrypt(page[:CIPHER_END], key, iv)
            stored = cipher + iv + bytes(RESERVE - 16)
        assert len(stored) == PAGE
        stream += stored

    prefix = b"SQLite header QQ_NT synthetic-test-fixture".ljust(HEADER, b"\0")
    with open(out, "wb") as f:
        f.write(prefix + bytes(stream))


WAL_MARKER = "WAL-OVERLAY-9f3c"


def add_big_blobs(path: str, count: int = 2, size: int = 20000) -> None:
    """Appends blobs large enough to spill into overflow pages, so the locate
    diagnostics have a real overflow chain to walk."""
    con = sqlite3.connect(path)
    con.executemany(
        "INSERT INTO blobs(payload) VALUES (?)",
        [(os.urandom(size),) for _ in range(count)],
    )
    con.commit()
    con.close()


def update_one_row(path: str) -> None:
    """Second revision of the data: one row body changes (and the header
    change counter), so the pages that differ become WAL frames."""
    con = sqlite3.connect(path)
    con.execute("UPDATE messages SET body = ? WHERE id = 1", (WAL_MARKER,))
    con.commit()
    con.close()


def nt_cksum(data: bytes, s1: int, s2: int) -> tuple:
    """Mirror of ntdb_vfs.c nt_cksum() with native (little-endian) words —
    magic 0x377f0682 has its low bit clear, so the target reads LE."""
    for i in range(0, len(data) - len(data) % 8, 8):
        d0 = int.from_bytes(data[i:i + 4], "little")
        d1 = int.from_bytes(data[i + 4:i + 8], "little")
        s1 = (s1 + d0 + s2) & 0xFFFFFFFF
        s2 = (s2 + d1 + s1) & 0xFFFFFFFF
    return s1, s2


def write_wal(plain_a: bytes, plain_b: bytes, md5key: str, wal_path: str,
              flavour: str) -> int:
    """Writes a -wal whose frames carry the pages plain_b changed vs plain_a.

    Frames run in ascending page order with page 1 last (commit frame,
    dbsize = page count), which is what a real checkpoint-less writer looks
    like to our sequential scanner.
    """
    assert len(plain_a) == len(plain_b), "revisions must be same size"
    npages = len(plain_a) // PAGE
    changed = [pg for pg in range(1, npages + 1)
               if plain_a[(pg - 1) * PAGE:pg * PAGE]
               != plain_b[(pg - 1) * PAGE:pg * PAGE]]
    order = [pg for pg in changed if pg != 1]
    if 1 in changed:
        order.append(1)
    if not order:
        return 0

    key = hashlib.pbkdf2_hmac("sha512", md5key.encode("ascii"),
                              bytes(range(16)), 4000, 32)
    salt = bytes(range(0x40, 0x48))

    hdr = bytearray(32)
    hdr[0:4] = (0x377F0682).to_bytes(4, "big")
    hdr[4:8] = (3007000).to_bytes(4, "big")
    hdr[8:12] = (PAGE).to_bytes(4, "big")
    hdr[16:24] = salt
    s1, s2 = nt_cksum(bytes(hdr[:24]), 0, 0)
    hdr[24:28] = s1.to_bytes(4, "big")
    hdr[28:32] = s2.to_bytes(4, "big")

    out = bytearray(hdr)
    for idx, pg in enumerate(order):
        page = plain_b[(pg - 1) * PAGE:pg * PAGE]
        if flavour == "plain":
            body = page
            ck_input = body
        else:
            iv = bytes((pg * 7 + j) % 256 for j in range(16))
            cipher = pkcs_pad_free_encrypt(page[:CIPHER_END], key, iv)
            body = cipher + iv + bytes(PAGE - CIPHER_END - 16)
            if flavour == "enc-raw":
                ck_input = body
            else:  # enc-dec: checksum sees the decrypted page, whose reserve
                ck_input = page[:CIPHER_END] + iv + bytes(PAGE - CIPHER_END - 16)
        fhdr = bytearray(24)
        fhdr[0:4] = pg.to_bytes(4, "big")
        fhdr[4:8] = (npages if idx == len(order) - 1 else 0).to_bytes(4, "big")
        fhdr[8:16] = salt
        s1, s2 = nt_cksum(bytes(fhdr[:8]), s1, s2)
        s1, s2 = nt_cksum(ck_input, s1, s2)
        fhdr[16:20] = s1.to_bytes(4, "big")
        fhdr[20:24] = s2.to_bytes(4, "big")
        out += fhdr + body

    with open(wal_path, "wb") as f:
        f.write(out)
    return len(order)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    out = sys.argv[1]
    md5key = sys.argv[2] if len(sys.argv) > 2 else "00112233445566778899aabbccddeeff"
    flavour = sys.argv[3] if len(sys.argv) > 3 else None

    fd, tmp = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    fd2, tmp2 = tempfile.mkstemp(suffix=".db")
    os.close(fd2)
    try:
        build_plain(tmp)
        with open(tmp, "rb") as f:
            plain = f.read()
        if flavour:
            shutil.copyfile(tmp, tmp2)
            update_one_row(tmp2)
            with open(tmp2, "rb") as f:
                plain_b = f.read()
    finally:
        os.unlink(tmp)
        os.unlink(tmp2)

    encrypt(plain, md5key, out)
    print(f"fixture: {out} ({len(plain)} plaintext bytes, md5key={md5key})")
    if flavour:
        frames = write_wal(plain, plain_b, md5key, out + "-wal", flavour)
        print(f"wal: {out}-wal ({flavour}, {frames} frames)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
