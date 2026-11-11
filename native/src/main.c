/*
 * ntdbread — read QQ's encrypted nt_msg.db in place.
 *
 *   ntdbread --md5key <md5(prefix+rand)> <nt_msg.db>            schema report
 *   ntdbread --md5key … <nt_msg.db> 'SELECT …'                  rows as TSV
 *   ntdbread --kat                                              crypto self-test
 *
 * The AES key is derived here from the md5 value and the salt stored at byte
 * 1024 (PBKDF2-HMAC-SHA512, 4000 iterations) — the exact parameters the Phase
 * 0 probe proved on a live QQ 9.1.35 device. Everything is read-only; SQLite
 * never receives write access to anything.
 */
#include "ntdb_vfs.h"
#include "sha512.h"
#include "sqlite3.h"

#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define NT_KDF_ITER 4000
#define NT_SALT_OFS 1024

static void die(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "ntdbread: ");
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
    exit(1);
}

static int hex_decode(const char *hex, unsigned char *out, size_t outlen)
{
    size_t i;
    if (strlen(hex) != outlen * 2) return -1;
    for (i = 0; i < outlen; i++) {
        int hi, lo, c;
        c = (unsigned char)hex[i * 2];
        hi = (c >= '0' && c <= '9') ? c - '0'
             : (c >= 'a' && c <= 'f') ? c - 'a' + 10
             : (c >= 'A' && c <= 'F') ? c - 'A' + 10 : -1;
        c = (unsigned char)hex[i * 2 + 1];
        lo = (c >= '0' && c <= '9') ? c - '0'
             : (c >= 'a' && c <= 'f') ? c - 'a' + 10
             : (c >= 'A' && c <= 'F') ? c - 'A' + 10 : -1;
        if (hi < 0 || lo < 0) return -1;
        out[i] = (unsigned char)((hi << 4) | lo);
    }
    return 0;
}

static void hex_encode(const unsigned char *in, size_t n, char *out)
{
    static const char digits[] = "0123456789abcdef";
    size_t i;
    for (i = 0; i < n; i++) {
        out[i * 2] = digits[in[i] >> 4];
        out[i * 2 + 1] = digits[in[i] & 0x0f];
    }
    out[n * 2] = '\0';
}

static void dump_hex(const unsigned char *b, size_t n)
{
    size_t i;
    for (i = 0; i < n; i++) printf("%02x", b[i]);
    putchar('\n');
}

/*
 * A live nt_msg.db is still being written by QQ while we read it, so a scan
 * can occasionally observe a torn page ("database disk image is malformed").
 * Output is buffered and the whole statement re-run against a freshly opened
 * handle: a transient failure must neither print half a result set nor print
 * it twice.
 */
typedef struct {
    char *p;
    size_t len, cap;
} nt_buf;

static char *buf_reserve(nt_buf *b, size_t n) /* room for n bytes + NUL */
{
    if (b->len + n + 1 > b->cap) {
        size_t cap = b->cap ? b->cap : 8192;
        char *p;
        while (cap < b->len + n + 1) cap *= 2;
        p = realloc(b->p, cap);
        if (!p) return NULL;
        b->p = p;
        b->cap = cap;
    }
    return b->p + b->len;
}

static int buf_add(nt_buf *b, const char *fmt, ...)
{
    va_list ap;
    int n;
    char *dst;

    va_start(ap, fmt);
    n = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    if (n < 0) return -1;
    dst = buf_reserve(b, (size_t)n);
    if (!dst) return -1;
    va_start(ap, fmt);
    vsnprintf(dst, (size_t)n + 1, fmt, ap);
    va_end(ap);
    b->len += (size_t)n;
    return 0;
}

/* Errors worth another attempt: the file changed underneath a read. */
static int transient(int rc)
{
    switch (rc & 0xff) {
    case SQLITE_CORRUPT:
    case SQLITE_IOERR:
    case SQLITE_BUSY:
    case SQLITE_LOCKED:
        return 1;
    default:
        return 0;
    }
}

static void usage(void)
{
    fprintf(stderr,
            "usage: ntdbread [--no-wal] [--kat] [--pics] "
            "(--key HEX64 | --md5key HEX32) <nt_msg.db> [SQL]\n"
            "  --kat        run crypto known-answer tests and exit\n"
            "  --no-wal     ignore -wal frames (main file only)\n"
            "  --md5key     md5(prefix+rand); salt is read from byte 1024,\n"
            "               key = PBKDF2-HMAC-SHA512(pass, salt, %d)\n"
            "  --key        derived 64-hex AES-256 key (skips PBKDF2)\n"
            "  --pics       scan group_msg_table + c2c_msg_table and print\n"
            "               TSV: table msgid col40011 col40020 elem md5\n"
            "               filename size (-1 = unknown) for every message\n"
            "               body carrying an image/video md5\n"
            "  --locate N   walk the b-tree to the leaf holding msgid N and\n"
            "               report the page's cells (root auto-detected for\n"
            "               group_msg_table; --root overrides)\n"
            "  --dump-page N  dump page N: source, type, cell count, bytes\n"
            "  SQL          one statement; results printed as TSV with a\n"
            "               header line of column names\n",
            NT_KDF_ITER);
}

/* ---- message-body extraction (fields verified on a live QQ 9.1.35 db) ---- */

/*
 * 40800 = one outer length-delimited field 40800 wrapping the element list.
 * A picture element carries f40010 (element type), f45402 (file name =
 * md5 + extension), f45406 (raw 16-byte md5), f45405 (byte size) and
 * f45424 (md5 as 32 hex chars — authoritative when present). Text messages
 * carry none of these, which is what keeps hex-looking authkeys out.
 */
struct pic_info {
    char md5[33];
    char name[96];
    long long size; /* f45405 */
    long long elem; /* f40010 */
    int have;
};

static int varint_at(const unsigned char *b, size_t n, size_t *i,
                     unsigned long long *v)
{
    unsigned long long x = 0;
    int shift = 0;
    while (*i < n) {
        unsigned char c = b[(*i)++];
        x |= (unsigned long long)(c & 0x7f) << shift;
        if (!(c & 0x80)) {
            *v = x;
            return 0;
        }
        shift += 7;
        if (shift > 63) return -1;
    }
    return -1;
}

static int is_hex(const unsigned char *b, size_t n)
{
    size_t k;
    for (k = 0; k < n; k++) {
        int c = b[k];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
              || (c >= 'A' && c <= 'F')))
            return 0;
    }
    return 1;
}

static void lower_copy(char *dst, const unsigned char *src, size_t n)
{
    size_t k;
    for (k = 0; k < n; k++) {
        char c = (char)src[k];
        if (c >= 'A' && c <= 'F') c += 32;
        dst[k] = c;
    }
    dst[n] = '\0';
}

static void scan_element(const unsigned char *b, size_t n, struct pic_info *p)
{
    size_t i = 0, k;
    unsigned long long tag, fn, v;

    while (i < n && varint_at(b, n, &i, &tag) == 0) {
        fn = tag >> 3;
        switch (tag & 7) {
        case 0: /* varint */
            if (varint_at(b, n, &i, &v)) return;
            if (fn == 40010) p->elem = (long long)v;
            else if (fn == 45405) p->size = (long long)v;
            break;
        case 1: /* fixed64 */
            if (i + 8 > n) return;
            i += 8;
            break;
        case 2: { /* length-delimited */
            unsigned long long len;
            const unsigned char *val;
            if (varint_at(b, n, &i, &len) || len > n - i) return;
            val = b + i;
            i += (size_t)len;
            if (fn == 45424 && len == 32 && is_hex(val, 32)) {
                lower_copy(p->md5, val, 32);
                p->have = 1;
            } else if (fn == 45406 && len == 16 && !p->have) {
                hex_encode(val, 16, p->md5);
                p->have = 1;
            } else if (fn == 45402 && len && len < sizeof(p->name)) {
                for (k = 0; k < len; k++) {
                    unsigned char c = val[k];
                    p->name[k] = (c >= 0x20 && c < 0x7f) ? (char)c : '?';
                }
                p->name[len] = '\0';
            }
            break;
        }
        case 5: /* fixed32 */
            if (i + 4 > n) return;
            i += 4;
            break;
        default:
            return; /* not protobuf — stop rather than wander off */
        }
    }
}

/* Returns 1 when the body yielded an md5. */
static int scan_blob(const unsigned char *b, size_t n, struct pic_info *p)
{
    size_t i = 0;
    int inner = 0;
    unsigned long long tag, fn;

    memset(p, 0, sizeof(*p));
    p->elem = -1;
    p->size = -1;

    while (i < n && varint_at(b, n, &i, &tag) == 0) {
        fn = tag >> 3;
        if ((tag & 7) == 2) {
            unsigned long long len;
            if (varint_at(b, n, &i, &len) || len > n - i) return 0;
            if (fn == 40800) {
                scan_element(b + i, (size_t)len, p);
                inner = 1;
            }
            i += (size_t)len;
        } else if ((tag & 7) == 0) {
            unsigned long long v;
            if (varint_at(b, n, &i, &v)) return 0;
        } else if ((tag & 7) == 1) {
            if (i + 8 > n) return 0;
            i += 8;
        } else if ((tag & 7) == 5) {
            if (i + 4 > n) return 0;
            i += 4;
        } else {
            return 0;
        }
    }
    if (!inner) scan_element(b, n, p); /* already an element list */
    return p->have;
}

/* Prints sqlite_master tables plus the WAL merge summary, into out. */
static int report(sqlite3 *db, nt_buf *out, char *emsg, size_t emsglen)
{
    sqlite3_stmt *st;
    unsigned int frames = 0;
    int mode = 0;
    sqlite3_int64 wpages = 0;
    int rc, s;

    rc = sqlite3_prepare_v2(db, "PRAGMA page_count;", -1, &st, NULL);
    if (rc == SQLITE_OK) {
        s = sqlite3_step(st);
        if (s == SQLITE_ROW)
            buf_add(out, "page_count=%lld\n",
                    (long long)sqlite3_column_int64(st, 0));
        else
            rc = s;
    }
    if (st) sqlite3_finalize(st);
    if (rc != SQLITE_OK) {
        snprintf(emsg, emsglen, "page_count failed: %s", sqlite3_errmsg(db));
        return rc;
    }

    /* page_count already touched xFileSize, which runs the WAL scan. */
    nt_vfs_wal_stats(&frames, &mode, &wpages);
    buf_add(out, "wal_mode=%s wal_frames=%u wal_pages=%lld wal_plain=%d\n",
            mode == 0 ? "none" : mode == 1 ? "raw" : "dec",
            frames, (long long)wpages, nt_vfs_wal_is_plain());

    rc = sqlite3_prepare_v2(
        db,
        "SELECT rootpage, name FROM sqlite_master WHERE type='table' "
        "ORDER BY rootpage;",
        -1, &st, NULL);
    if (rc != SQLITE_OK) {
        snprintf(emsg, emsglen, "sqlite_master failed: %s", sqlite3_errmsg(db));
        return rc;
    }
    while ((s = sqlite3_step(st)) == SQLITE_ROW)
        buf_add(out, "table %s %lld\n",
                sqlite3_column_text(st, 1)
                    ? (const char *)sqlite3_column_text(st, 1)
                    : "?",
                (long long)sqlite3_column_int64(st, 0));
    if (s != SQLITE_DONE)
        snprintf(emsg, emsglen, "sqlite_master failed: %s", sqlite3_errmsg(db));
    sqlite3_finalize(st);
    if (s != SQLITE_DONE) {
        return s;
    }

    rc = sqlite3_prepare_v2(db,
                            "SELECT count(*) FROM sqlite_master "
                            "WHERE type!='table';",
                            -1, &st, NULL);
    if (rc == SQLITE_OK) {
        s = sqlite3_step(st);
        if (s == SQLITE_ROW)
            buf_add(out, "master_other=%d\n", sqlite3_column_int(st, 0));
        else
            rc = s;
    }
    if (st) sqlite3_finalize(st);
    if (rc != SQLITE_OK) {
        snprintf(emsg, emsglen, "master_other failed: %s", sqlite3_errmsg(db));
        return rc;
    }
    return SQLITE_OK;
}

/* Runs one statement, TSV into out. Returns SQLITE_OK or the failing code. */
static int run_sql(sqlite3 *db, const char *sql, nt_buf *out,
                   char *emsg, size_t emsglen, int *prows)
{
    sqlite3_stmt *st = NULL;
    int rc, ncol, i, rows = 0;

    rc = sqlite3_prepare_v2(db, sql, -1, &st, NULL);
    if (rc != SQLITE_OK) {
        snprintf(emsg, emsglen, "prepare failed: %s", sqlite3_errmsg(db));
        return rc;
    }
    ncol = sqlite3_column_count(st);
    for (i = 0; i < ncol; i++) {
        const char *name = sqlite3_column_name(st, i);
        buf_add(out, "%s%s", i ? "\t" : "", name ? name : "");
    }
    buf_add(out, "\n");

    while ((rc = sqlite3_step(st)) == SQLITE_ROW) {
        for (i = 0; i < ncol; i++) {
            int type = sqlite3_column_type(st, i);
            if (i) buf_add(out, "\t");
            if (type == SQLITE_NULL) {
                /* nothing */
            } else if (type == SQLITE_BLOB) {
                const unsigned char *blob = sqlite3_column_blob(st, i);
                int n = sqlite3_column_bytes(st, i);
                char *hexx = buf_reserve(out, (size_t)n * 2);
                if (hexx) {
                    hex_encode(blob, (size_t)n, hexx);
                    out->len += (size_t)n * 2;
                }
            } else if (type == SQLITE_INTEGER) {
                buf_add(out, "%lld", (long long)sqlite3_column_int64(st, i));
            } else if (type == SQLITE_FLOAT) {
                buf_add(out, "%g", sqlite3_column_double(st, i));
            } else {
                const unsigned char *text = sqlite3_column_text(st, i);
                int n = sqlite3_column_bytes(st, i);
                int j;
                for (j = 0; j < n; j++) {
                    char c = (char)text[j];
                    /* keep the row on one line */
                    if (c == '\t' || c == '\n' || c == '\r') c = ' ';
                    buf_add(out, "%c", c);
                }
            }
        }
        buf_add(out, "\n");
        rows++;
    }
    if (rc == SQLITE_DONE) rc = SQLITE_OK; /* finished normally */
    if (rc != SQLITE_OK)
        snprintf(emsg, emsglen, "step failed: %s", sqlite3_errmsg(db));
    sqlite3_finalize(st);
    if (rc != SQLITE_OK) {
        return rc;
    }
    if (prows) *prows = rows;
    return SQLITE_OK;
}

/*
 * Streams message bodies carrying an md5 in resumable msgid chunks. A torn
 * page aborts the call with the sqlite error code while st->last already
 * points past every row that made it into out; the caller then reopens the
 * database and calls again, so a live writer costs one chunk of latency,
 * never the whole run.
 */
struct pics_run {
    int table;             /* index into pics_tables */
    int tstate[2];         /* 0 unchecked, 1 absent, 2 present */
    long long last;        /* resume point: emit rows with msgid > last */
    int rows, matches;
};

static const char *pics_tables[] = {"group_msg_table", "c2c_msg_table"};
#define PICS_TABLES ((int)(sizeof(pics_tables) / sizeof(pics_tables[0])))
#define PICS_CHUNK 50000

/* Chunk size; NTDB_PICS_CHUNK overrides it so the offline test can cross a
 * chunk boundary inside a 3-row fixture. */
static int pics_chunk(void)
{
    const char *e = getenv("NTDB_PICS_CHUNK");
    int n = e ? atoi(e) : 0;
    return (n > 0 && n <= 1000000) ? n : PICS_CHUNK;
}

static int run_pics_resume(sqlite3 *db, struct pics_run *st, nt_buf *out,
                           char *emsg, size_t emsglen)
{
    char sql[256];

    if (out->len == 0)
        buf_add(out, "table\tmsgid\tcol40011\tcol40020\telem\tmd5\tfilename"
                     "\tsize\n");

    while (st->table < PICS_TABLES) {
        const char *table = pics_tables[st->table];
        sqlite3_stmt *q = NULL;
        int rc, chunk_rows = 0, chunk = pics_chunk();

        if (st->tstate[st->table] == 0) {
            sqlite3_stmt *chk = NULL;
            if (sqlite3_prepare_v2(db,
                                   "SELECT 1 FROM sqlite_master "
                                   "WHERE type='table' AND name=?;",
                                   -1, &chk, NULL) == SQLITE_OK) {
                sqlite3_bind_text(chk, 1, table, -1, SQLITE_STATIC);
                st->tstate[st->table] =
                    sqlite3_step(chk) == SQLITE_ROW ? 2 : 1;
                sqlite3_finalize(chk);
            } else {
                st->tstate[st->table] = 2; /* the real query reports why */
            }
        }
        if (st->tstate[st->table] == 1) {
            st->table++;
            st->last = -1;
            continue;
        }

        /* "40001" is the rowid-aliased primary key, so this seeks. */
        snprintf(sql, sizeof(sql),
                 "SELECT \"40001\", \"40011\", \"40020\", \"40800\" "
                 "FROM \"%s\" WHERE \"40001\" > ? "
                 "ORDER BY \"40001\" LIMIT %d",
                 table, chunk);
        rc = sqlite3_prepare_v2(db, sql, -1, &q, NULL);
        if (rc != SQLITE_OK) {
            snprintf(emsg, emsglen, "%s: prepare failed: %s", table,
                     sqlite3_errmsg(db));
            return rc;
        }
        sqlite3_bind_int64(q, 1, st->last);

        while ((rc = sqlite3_step(q)) == SQLITE_ROW) {
            struct pic_info p;
            const unsigned char *blob = sqlite3_column_blob(q, 3);
            int n = sqlite3_column_bytes(q, 3);

            st->last = sqlite3_column_int64(q, 0);
            st->rows++;
            chunk_rows++;
            if ((st->rows % 200000) == 0)
                fprintf(stderr, "ntdbread: %s scanned=%d media=%d\n", table,
                        st->rows, st->matches);
            if (!blob || n <= 0) continue;
            if (!scan_blob(blob, (size_t)n, &p)) continue;
            st->matches++;

            buf_add(out, "%s\t%lld\t", table, st->last);
            if (sqlite3_column_type(q, 1) != SQLITE_NULL)
                buf_add(out, "%lld", (long long)sqlite3_column_int64(q, 1));
            buf_add(out, "\t");
            if (sqlite3_column_type(q, 2) != SQLITE_NULL) {
                const unsigned char *text = sqlite3_column_text(q, 2);
                int tn = sqlite3_column_bytes(q, 2), j;
                for (j = 0; j < tn; j++) {
                    char c = (char)text[j];
                    if (c == '\t' || c == '\n' || c == '\r') c = ' ';
                    buf_add(out, "%c", c);
                }
            }
            buf_add(out, "\t%lld\t%s\t%s\t%lld\n", p.elem, p.md5, p.name,
                    p.size);
        }
        if (rc == SQLITE_DONE) rc = SQLITE_OK;
        if (rc != SQLITE_OK) {
            snprintf(emsg, emsglen, "%s: step failed at msgid>%lld: %s", table,
                     st->last, sqlite3_errmsg(db));
            sqlite3_finalize(q);
            return rc;
        }
        sqlite3_finalize(q);

        if (chunk_rows < chunk) { /* short chunk: table exhausted */
            st->table++;
            st->last = -1;
        }
    }
    return SQLITE_OK;
}

int main(int argc, char **argv)
{
    const char *dbpath = NULL;
    const char *sql = NULL;
    const char *keyhex = NULL;
    const char *md5hex = NULL;
    unsigned char key[32];
    sqlite3 *db = NULL;
    int no_wal = 0, kat_only = 0, pics = 0;
    int locate = 0, dump = 0;
    long long locate_key = 0;
    unsigned int dump_pgno = 0, root_pgno = 0;
    int i, rc;

    for (i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (strcmp(a, "--kat") == 0) {
            kat_only = 1;
        } else if (strcmp(a, "--no-wal") == 0) {
            no_wal = 1;
        } else if (strcmp(a, "--pics") == 0) {
            pics = 1;
        } else if (strcmp(a, "--locate") == 0) {
            if (++i >= argc) die("--locate needs a msgid");
            locate_key = strtoll(argv[i], NULL, 10);
            locate = 1;
        } else if (strcmp(a, "--dump-page") == 0) {
            if (++i >= argc) die("--dump-page needs a page number");
            dump_pgno = (unsigned int)strtoul(argv[i], NULL, 10);
            dump = 1;
        } else if (strcmp(a, "--root") == 0) {
            if (++i >= argc) die("--root needs a page number");
            root_pgno = (unsigned int)strtoul(argv[i], NULL, 10);
        } else if (strcmp(a, "--key") == 0) {
            if (++i >= argc) die("--key needs a value");
            keyhex = argv[i];
        } else if (strcmp(a, "--md5key") == 0) {
            if (++i >= argc) die("--md5key needs a value");
            md5hex = argv[i];
        } else if (strcmp(a, "-h") == 0 || strcmp(a, "--help") == 0) {
            usage();
            return 0;
        } else if (a[0] == '-' && a[1] != '\0') {
            usage();
            die("unknown option: %s", a);
        } else if (!dbpath) {
            dbpath = a;
        } else if (!sql) {
            sql = a;
        } else {
            usage();
            die("unexpected argument: %s", a);
        }
    }

    /* Crypto must prove itself before any real data is decrypted. */
    if (nt_aes256_kat()) die("AES-256-CBC known-answer test failed");
    if (nt_sha512_kat()) die("SHA-512/PBKDF2 known-answer test failed");
    if (kat_only) {
        printf("kat ok: aes-256-cbc, sha512, pbkdf2-sha512\n");
        return 0;
    }
    if (!dbpath || (!keyhex && !md5hex)) {
        usage();
        return 2;
    }
    if (pics && sql) die("--pics does not take a SQL statement");
    if ((locate || dump) && sql) die("--locate/--dump-page do not take SQL");

    if (keyhex) {
        if (hex_decode(keyhex, key, 32)) die("--key must be 64 hex characters");
    } else {
        unsigned char salt[16];
        char pass[33];
        int fd;
        ssize_t got;
        int j;

        if (strlen(md5hex) != 32 || hex_decode(md5hex, key, 16))
            die("--md5key must be 32 hex characters");
        /* PBKDF2 password = the ASCII of the md5 hex string, as SQLCipher. */
        memcpy(pass, md5hex, 32);
        pass[32] = '\0';
        for (j = 0; j < 32; j++)
            if (pass[j] >= 'A' && pass[j] <= 'F') pass[j] += 32;

        fd = open(dbpath, O_RDONLY | O_CLOEXEC);
        if (fd < 0) die("cannot open %s", dbpath);
        got = pread(fd, salt, sizeof(salt), NT_SALT_OFS);
        close(fd);
        if (got != (ssize_t)sizeof(salt))
            die("cannot read the KDF salt at offset %d", NT_SALT_OFS);
        nt_pbkdf2_sha512(pass, 32, salt, sizeof(salt), NT_KDF_ITER, key, 32);
    }

    nt_vfs_set_key(key);
    nt_vfs_set_no_wal(no_wal);
    if (nt_vfs_register() != SQLITE_OK) die("cannot register the VFS");

    /* Diagnostics that bypass SQLite's parser entirely. */
    if (dump) {
        unsigned char page[NT_PAGE];
        unsigned int frame = 0;
        long long usable = 0;

        if (nt_vfs_dump_page(dbpath, dump_pgno, page, &frame, &usable))
            die("cannot read page %u", dump_pgno);
        printf("page=%u source=%s frame=%u type=0x%02x ncells=%u usable=%lld\n",
               dump_pgno, frame ? "wal" : "main", frame, page[0],
               ((unsigned)page[3] << 8) | page[4], usable);
        printf("bytes: ");
        dump_hex(page, 128);
        return 0;
    }
    if (locate) {
        nt_locate_info li;
        unsigned char page[NT_PAGE];
        unsigned int frame = 0;
        long long usable = 0;

        if (!root_pgno) {
            sqlite3 *mdb = NULL;
            sqlite3_stmt *q = NULL;
            if (sqlite3_open_v2(dbpath, &mdb, SQLITE_OPEN_READONLY, NULL)
                    == SQLITE_OK
                && sqlite3_prepare_v2(mdb,
                                      "SELECT rootpage FROM sqlite_master "
                                      "WHERE name='group_msg_table';",
                                      -1, &q, NULL) == SQLITE_OK
                && sqlite3_step(q) == SQLITE_ROW)
                root_pgno = (unsigned int)sqlite3_column_int64(q, 0);
            sqlite3_finalize(q);
            if (mdb) sqlite3_close(mdb);
        }
        if (!root_pgno)
            die("cannot find the group_msg_table root page; pass --root");
        if (nt_vfs_locate(dbpath, root_pgno, locate_key, &li))
            die("cannot locate msgid %lld", locate_key);

        printf("locate msgid=%lld root=%u\n", locate_key, root_pgno);
        printf("page=%u source=%s frame=%u type=0x%02x ncells=%d depth=%d "
               "usable=%lld\n",
               li.pgno, li.frame ? "wal" : "main", li.frame, li.type,
               li.ncells, li.depth, li.usable);
        printf("keys: first=%lld last=%lld\n", li.first_key, li.last_key);
        printf("badcells=%d", li.bad_cells);
        if (li.bad_cells)
            printf(" first_bad_cell=%u ofs=%u payload=%lld need=%lld",
                   li.bad_cell, li.bad_ofs, li.bad_payload, li.bad_need);
        printf("\n");
        printf("overflow: cells=%lld pages=%lld bad=%u", li.over_cells,
               li.over_pages, li.over_bad);
        if (li.over_bad)
            printf(" bad_cell=%u bad_pgno=%u depth=%u", li.over_bad_cell,
                   li.over_bad_pgno, li.over_bad_link);
        printf("\n");
        if (li.target_found)
            printf("target: rowid=%lld payload=%lld over_pgno=%u "
                   "over_source=%s over_frame=%u\n",
                   li.target_rowid, li.target_payload, li.target_over,
                   li.target_over
                       ? (li.target_frame ? "wal" : "main")
                       : "inline",
                   li.target_frame);
        if (li.pgno
            && !nt_vfs_dump_page(dbpath, li.pgno, page, &frame, &usable)) {
            printf("bytes: ");
            dump_hex(page, 96);
        }
        return 0;
    }

    {
        nt_buf out = {NULL, 0, 0};
        char emsg[256] = "";
        struct pics_run prun;
        int attempt = 0, rows = 0, open_failed = 0;
        int fails = 0, prev_table = 0;
        long long prev_last = -1;

        memset(&prun, 0, sizeof(prun));
        prun.last = -1;

        rc = SQLITE_ERROR;
        for (;;) {
            attempt++;
            if (attempt > 1) {
                /* QQ may be mid-write; give it a moment, then take a new
                 * snapshot with freshly opened files. */
                struct timespec ts;
                ts.tv_sec = 0;
                ts.tv_nsec = 300000000L * (attempt > 4 ? 4 : attempt - 1);
                nanosleep(&ts, NULL);
                fprintf(stderr, "ntdbread: transient failure (%s), retrying\n",
                        emsg);
            }
            if (!pics) {
                out.len = 0; /* one-shot queries discard partial rows */
                rows = 0;
            }
            prev_table = prun.table;
            prev_last = prun.last;
            open_failed = 0;
            rc = sqlite3_open_v2(dbpath, &db, SQLITE_OPEN_READONLY, NULL);
            if (rc != SQLITE_OK) {
                open_failed = 1;
                snprintf(emsg, sizeof(emsg), "open failed (%d): %s", rc,
                         db ? sqlite3_errmsg(db) : "out of memory");
                if (db) {
                    sqlite3_close(db);
                    db = NULL;
                }
            } else {
                /* No spill to disk: temp files would only add writes. */
                sqlite3_exec(db, "PRAGMA temp_store=MEMORY;", NULL, NULL, NULL);
                if (pics)
                    rc = run_pics_resume(db, &prun, &out, emsg, sizeof(emsg));
                else if (sql)
                    rc = run_sql(db, sql, &out, emsg, sizeof(emsg), &rows);
                else
                    rc = report(db, &out, emsg, sizeof(emsg));
                if (rc == SQLITE_OK) break;
                sqlite3_close(db);
                db = NULL;
            }
            if (!transient(rc)) break;
            if (pics) {
                if (prun.table != prev_table || prun.last != prev_last) {
                    fails = 0; /* new ground covered — keep going */
                } else if (++fails >= 3) {
                    fprintf(stderr,
                            "ntdbread: no progress after 3 attempts, stopping "
                            "at table=%s msgid>%lld\n",
                            prun.table < PICS_TABLES
                                ? pics_tables[prun.table]
                                : "?",
                            prun.last);
                    break;
                }
            } else if (attempt >= 3) {
                break;
            }
        }

        if (rc != SQLITE_OK) {
            fprintf(stderr, "ntdbread: %s\n", emsg);
            if (open_failed) {
                unsigned char page[4096];
                if (nt_vfs_read_header_page(dbpath, page)) {
                    fprintf(stderr,
                            "ntdbread: page 1 *does* decrypt (page=%u ver=%u/%u "
                            "enc=%u) — failure is structural, not "
                            "cryptographic\n",
                            ((unsigned)page[16] << 8) | page[17], page[18],
                            page[19],
                            ((unsigned)page[56] << 24) |
                                ((unsigned)page[57] << 16) |
                                ((unsigned)page[58] << 8) | page[59]);
                } else {
                    fprintf(stderr,
                            "ntdbread: page 1 does not decrypt to a plausible "
                            "header — wrong key or geometry\n");
                }
            }
            if (pics) {
                /* whatever was extracted is still useful — hand it over */
                fwrite(out.p, 1, out.len, stdout);
                fprintf(stderr,
                        "ntdbread: partial list: %d media rows, resume point "
                        "table=%d msgid>%lld\n",
                        prun.matches, prun.table, prun.last);
            }
            free(out.p);
            if (db) sqlite3_close(db);
            return 1;
        }

        fwrite(out.p, 1, out.len, stdout);
        free(out.p);
        if (pics)
            fprintf(stderr, "ntdbread: scanned=%d media=%d\n", prun.rows,
                    prun.matches);
        else if (sql)
            fprintf(stderr, "ntdbread: rows=%d\n", rows);
        sqlite3_close(db);
        return 0;
    }
}
