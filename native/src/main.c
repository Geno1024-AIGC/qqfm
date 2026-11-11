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
            "usage: ntdbread [--no-wal] [--kat] (--key HEX64 | --md5key HEX32) "
            "<nt_msg.db> [SQL]\n"
            "  --kat        run crypto known-answer tests and exit\n"
            "  --no-wal     ignore -wal frames (main file only)\n"
            "  --md5key     md5(prefix+rand); salt is read from byte 1024,\n"
            "               key = PBKDF2-HMAC-SHA512(pass, salt, %d)\n"
            "  --key        derived 64-hex AES-256 key (skips PBKDF2)\n"
            "  SQL          one statement; results printed as TSV with a\n"
            "               header line of column names\n",
            NT_KDF_ITER);
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

int main(int argc, char **argv)
{
    const char *dbpath = NULL;
    const char *sql = NULL;
    const char *keyhex = NULL;
    const char *md5hex = NULL;
    unsigned char key[32];
    sqlite3 *db = NULL;
    int no_wal = 0, kat_only = 0;
    int i, rc;

    for (i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (strcmp(a, "--kat") == 0) {
            kat_only = 1;
        } else if (strcmp(a, "--no-wal") == 0) {
            no_wal = 1;
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

    {
        nt_buf out = {NULL, 0, 0};
        char emsg[256] = "";
        int attempt, rows = 0, open_failed = 0;

        rc = SQLITE_ERROR;
        for (attempt = 0; attempt < 3; attempt++) {
            if (attempt) {
                /* QQ may be mid-write; give it a moment, then take a new
                 * snapshot with freshly opened files. */
                struct timespec ts;
                ts.tv_sec = 0;
                ts.tv_nsec = 300000000L * attempt;
                nanosleep(&ts, NULL);
                fprintf(stderr, "ntdbread: transient failure (%s), retrying\n",
                        emsg);
            }
            out.len = 0; /* discard any partial rows from a torn read */
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
                rc = sql ? run_sql(db, sql, &out, emsg, sizeof(emsg), &rows)
                         : report(db, &out, emsg, sizeof(emsg));
                if (rc == SQLITE_OK) break;
                sqlite3_close(db);
                db = NULL;
            }
            if (!transient(rc)) break;
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
            free(out.p);
            if (db) sqlite3_close(db);
            return 1;
        }

        fwrite(out.p, 1, out.len, stdout);
        free(out.p);
        if (sql) fprintf(stderr, "ntdbread: rows=%d\n", rows);
        sqlite3_close(db);
        return 0;
    }
}
