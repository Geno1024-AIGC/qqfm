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

/* Prints sqlite_master tables plus the WAL merge summary. */
static int report(sqlite3 *db)
{
    sqlite3_stmt *st;
    unsigned int frames = 0;
    int mode = 0;
    sqlite3_int64 wpages = 0;
    int rc;

    rc = sqlite3_prepare_v2(db, "PRAGMA page_count;", -1, &st, NULL);
    if (rc == SQLITE_OK && sqlite3_step(st) == SQLITE_ROW)
        printf("page_count=%lld\n", (long long)sqlite3_column_int64(st, 0));
    if (st) sqlite3_finalize(st);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "ntdbread: page_count failed: %s\n", sqlite3_errmsg(db));
        return 1;
    }

    /* page_count already touched xFileSize, which runs the WAL scan. */
    nt_vfs_wal_stats(&frames, &mode, &wpages);
    printf("wal_mode=%s wal_frames=%u wal_pages=%lld wal_plain=%d\n",
           mode == 0 ? "none" : mode == 1 ? "raw" : "dec",
           frames, (long long)wpages, nt_vfs_wal_is_plain());

    rc = sqlite3_prepare_v2(
        db,
        "SELECT rootpage, name FROM sqlite_master WHERE type='table' "
        "ORDER BY rootpage;",
        -1, &st, NULL);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "ntdbread: sqlite_master failed: %s\n", sqlite3_errmsg(db));
        return 1;
    }
    while (sqlite3_step(st) == SQLITE_ROW)
        printf("table %s %lld\n", sqlite3_column_text(st, 1)
                   ? (const char *)sqlite3_column_text(st, 1) : "?",
               (long long)sqlite3_column_int64(st, 0));
    sqlite3_finalize(st);

    rc = sqlite3_prepare_v2(db,
                            "SELECT count(*) FROM sqlite_master "
                            "WHERE type!='table';",
                            -1, &st, NULL);
    if (rc == SQLITE_OK && sqlite3_step(st) == SQLITE_ROW)
        printf("master_other=%d\n", sqlite3_column_int(st, 0));
    if (st) sqlite3_finalize(st);
    return 0;
}

/* Runs one statement, TSV on stdout. */
static int run_sql(sqlite3 *db, const char *sql)
{
    sqlite3_stmt *st = NULL;
    int rc, ncol, i, rows = 0;

    rc = sqlite3_prepare_v2(db, sql, -1, &st, NULL);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "ntdbread: prepare failed: %s\n", sqlite3_errmsg(db));
        return 1;
    }
    ncol = sqlite3_column_count(st);
    for (i = 0; i < ncol; i++) {
        const char *name = sqlite3_column_name(st, i);
        printf("%s%s", i ? "\t" : "", name ? name : "");
    }
    printf("\n");

    while ((rc = sqlite3_step(st)) == SQLITE_ROW) {
        for (i = 0; i < ncol; i++) {
            int type = sqlite3_column_type(st, i);
            if (i) putchar('\t');
            if (type == SQLITE_NULL) {
                /* nothing */
            } else if (type == SQLITE_BLOB) {
                const unsigned char *blob = sqlite3_column_blob(st, i);
                int n = sqlite3_column_bytes(st, i);
                char *hexx = malloc((size_t)n * 2 + 1);
                if (hexx) {
                    hex_encode(blob, (size_t)n, hexx);
                    fputs(hexx, stdout);
                    free(hexx);
                }
            } else if (type == SQLITE_INTEGER) {
                printf("%lld", (long long)sqlite3_column_int64(st, i));
            } else if (type == SQLITE_FLOAT) {
                printf("%g", sqlite3_column_double(st, i));
            } else {
                const unsigned char *text = sqlite3_column_text(st, i);
                int n = sqlite3_column_bytes(st, i);
                int j;
                for (j = 0; j < n; j++) {
                    char c = (char)text[j];
                    /* keep the row on one line */
                    if (c == '\t' || c == '\n' || c == '\r') c = ' ';
                    putchar(c);
                }
            }
        }
        putchar('\n');
        rows++;
    }
    sqlite3_finalize(st);
    if (rc != SQLITE_DONE) {
        fprintf(stderr, "ntdbread: step failed: %s\n", sqlite3_errmsg(db));
        return 1;
    }
    fprintf(stderr, "ntdbread: rows=%d\n", rows);
    return 0;
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
    int i, rc, exitcode = 0;

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

    rc = sqlite3_open_v2(dbpath, &db, SQLITE_OPEN_READONLY, NULL);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "ntdbread: open failed (%d): %s\n", rc,
                db ? sqlite3_errmsg(db) : "out of memory");
        {
            unsigned char page[4096];
            if (nt_vfs_read_header_page(dbpath, page)) {
                fprintf(stderr,
                        "ntdbread: page 1 *does* decrypt (page=%u ver=%u/%u "
                        "enc=%u) — failure is structural, not cryptographic\n",
                        ((unsigned)page[16] << 8) | page[17], page[18], page[19],
                        ((unsigned)page[56] << 24) | ((unsigned)page[57] << 16) |
                            ((unsigned)page[58] << 8) | page[59]);
            } else {
                fprintf(stderr,
                        "ntdbread: page 1 does not decrypt to a plausible "
                        "header — wrong key or geometry\n");
            }
        }
        sqlite3_close(db);
        return 1;
    }

    /* No spill to disk: temp files would only add writes to prove nothing. */
    sqlite3_exec(db, "PRAGMA temp_store=MEMORY;", NULL, NULL, NULL);

    if (sql) exitcode = run_sql(db, sql);
    else exitcode = report(db);

    sqlite3_close(db);
    return exitcode;
}
