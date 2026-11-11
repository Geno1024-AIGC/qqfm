#include "ntdb_vfs.h"

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define NT_PATH_MAX 4096
#define NT_CACHE    16 /* decrypted main-database pages kept hot */
#define NT_WAL_HDR  32
#define NT_FRAME_HDR 24
#define NT_FRAME    (NT_FRAME_HDR + NT_PAGE)
#define NT_WAL_MAGIC 0x377f0682u /* low bit selects checksum byte order */

typedef enum { NT_DB, NT_WAL, NT_MEM } nt_kind;

typedef struct {
    sqlite3_file base; /* must stay first: SQLite allocates szOsFile bytes */
    int kind;
    int fd; /* open file for NT_DB / NT_WAL, -1 otherwise */
    sqlite3_int64 size;

    char *zPath; /* NT_DB: path, used to locate -wal */
    unsigned char *pages[NT_CACHE];
    sqlite3_int64 pageno[NT_CACHE];
    unsigned int dbg_frame_slot[NT_CACHE]; /* WAL frame per cached page */

    /* NT_DB: our own WAL view (SQLite never sees the -wal file itself). */
    int wal_fd;
    int wal_scanned;
    int wal_disabled;
    unsigned int *wal_map; /* page → frame sequence, 1-based; 0 = no frame */
    sqlite3_int64 wal_map_cap;
    sqlite3_int64 wal_pages; /* page count from the last commit frame */
    unsigned int wal_frames;
    int wal_mode; /* 0 = none, 1 = checksum over stored bytes, 2 = over plaintext */
    int wal_p1_plain; /* page-1 frames use the plain (no salt) image */
    int wal_plain; /* mode 1: frames are stored unencrypted (QQ skips the WAL) */
    int wal_plain_votes; /* frames that look plain */
    int wal_enc_votes;   /* frames that decrypt to a valid page */

    unsigned int dbg_frame; /* page source for diagnostics: 0 = main file */
    long long usable_size;  /* page size minus reserved bytes (header byte 20) */

    unsigned char *mem; /* NT_MEM: journal / temp backing, RAM only */
    sqlite3_int64 mem_size;
    sqlite3_int64 mem_cap;
} nt_file;

static nt_aes256_key g_key;
static int g_key_ready;
static int g_no_wal;

static unsigned int g_wal_frames;
static int g_wal_mode;
static int g_wal_plain;
static sqlite3_int64 g_wal_pages;

static const unsigned char NT_MAGIC[16] = {
    'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', '\0'
};

void nt_vfs_set_key(const unsigned char key[32])
{
    nt_aes256_setkey(&g_key, key);
    g_key_ready = 1;
}

void nt_vfs_set_no_wal(int on) { g_no_wal = on; }

void nt_vfs_wal_stats(unsigned int *frames, int *mode, sqlite3_int64 *pages)
{
    if (frames) *frames = g_wal_frames;
    if (mode) *mode = g_wal_mode;
    if (pages) *pages = g_wal_pages;
}

int nt_vfs_wal_is_plain(void) { return g_wal_plain; }

/* ---- page decryption ---- */

/*
 * Style 0: page 1 as stored — salt occupies [0,16) and the magic it replaced
 *          is re-synthesised; cipher runs from 16.
 * Style 1: everything up to the reserve is cipher (every later page, and
 *          page-1 frames whose WAL image carries no salt).
 * The reserve tail (IV + HMAC + pad) passes through untouched: the IV must be
 * read from there and SQLite never looks past the usable area anyway.
 */
static void nt_decrypt_page(int style, const unsigned char *raw,
                            unsigned char *out)
{
    unsigned char iv[16];
    size_t cipher = NT_PAGE - NT_RESERVE; /* 4048 */

    memcpy(iv, raw + cipher, sizeof(iv));
    if (style == 0) {
        memcpy(out, NT_MAGIC, 16);
        nt_aes256_cbc_decrypt(&g_key, iv, raw + 16, out + 16, cipher - 16);
    } else {
        nt_aes256_cbc_decrypt(&g_key, iv, raw, out, cipher);
    }
    memcpy(out + cipher, raw + cipher, NT_RESERVE);
}

static int nt_header_plausible(const unsigned char *page)
{
    unsigned declared = ((unsigned)page[16] << 8) | page[17];
    unsigned encoding;
    if (declared == 1) declared = 65536;
    if (declared != NT_PAGE) return 0;
    if (page[18] < 1 || page[18] > 2) return 0;
    if (page[19] < 1 || page[19] > 2) return 0;
    encoding = ((unsigned)page[56] << 24) | ((unsigned)page[57] << 16) |
               ((unsigned)page[58] << 8) | page[59];
    return encoding >= 1 && encoding <= 3;
}

int nt_vfs_read_header_page(const char *path, unsigned char *page)
{
    unsigned char raw[NT_PAGE];
    int fd, ok;

    if (!g_key_ready) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    memset(raw, 0, sizeof(raw));
    if (pread(fd, raw, NT_PAGE, NT_HEADER) != NT_PAGE) {
        close(fd);
        return 0;
    }
    close(fd);
    nt_decrypt_page(0, raw, page);
    ok = nt_header_plausible(page);
    return ok;
}

/* ---- small helpers ---- */

/* Strips an accidental "file:" prefix and query string from a filename. */
static void nt_real_path(const char *zIn, char *out, size_t cap)
{
    const char *p = zIn ? zIn : "";
    size_t n;

    if (strncmp(p, "file:", 5) == 0) p += 5;
    n = strcspn(p, "?");
    if (n >= cap) n = cap - 1;
    memcpy(out, p, n);
    out[n] = '\0';
}

/* Reads exactly n bytes at offset, zero-filling any shortfall. */
static void nt_pread(int fd, void *buf, size_t n, sqlite3_int64 offset)
{
    unsigned char *p = buf;
    size_t done = 0;
    while (done < n) {
        ssize_t got = pread(fd, p + done, n - done, offset + (sqlite3_int64)done);
        if (got <= 0) break;
        done += (size_t)got;
    }
    if (done < n) memset(p + done, 0, n - done);
}

static unsigned int nt_be32(const unsigned char *p)
{
    return ((unsigned int)p[0] << 24) | ((unsigned int)p[1] << 16) |
           ((unsigned int)p[2] << 8) | p[3];
}

/*
 * WAL checksum step, mirroring SQLite's walChecksumBytes(): a rolling pair of
 * 32-bit accumulators over 8-byte groups. "native" means memory byte order
 * (this tool targets little-endian Android), otherwise each word is read
 * big-endian — exactly SQLite's BYTESWAP32 behaviour.
 */
static void nt_cksum(const unsigned char *a, int nbyte,
                     const unsigned int *in, unsigned int *out, int native)
{
    unsigned int s1 = in ? in[0] : 0;
    unsigned int s2 = in ? in[1] : 0;
    int i;

    for (i = 0; i + 8 <= nbyte; i += 8) {
        unsigned int d0, d1;
        if (native) {
            d0 = (unsigned int)a[i] | ((unsigned int)a[i + 1] << 8) |
                 ((unsigned int)a[i + 2] << 16) | ((unsigned int)a[i + 3] << 24);
            d1 = (unsigned int)a[i + 4] | ((unsigned int)a[i + 5] << 8) |
                 ((unsigned int)a[i + 6] << 16) | ((unsigned int)a[i + 7] << 24);
        } else {
            d0 = (a[i] << 24) | (a[i + 1] << 16) | (a[i + 2] << 8) | a[i + 3];
            d1 = (a[i + 4] << 24) | (a[i + 5] << 16) | (a[i + 6] << 8) | a[i + 7];
        }
        s1 += d0 + s2;
        s2 += d1 + s1;
    }
    out[0] = s1;
    out[1] = s2;
}

/* ---- our own WAL merge ---- */

/*
 * Frame flavour voting for mode 1 (checksum matches the stored bytes). That
 * outcome is ambiguous: QQ may encrypt the frames below wal.c (checksum was
 * computed over ciphertext) or may leave the whole WAL unencrypted (checksum
 * over plain bytes). One vote per frame; plaintext pages always carry the
 * magic or a b-tree type byte, ciphertext essentially never does.
 */
static void nt_wal_vote(nt_file *p, const unsigned char *raw,
                        unsigned int pgno)
{
    unsigned char dec[NT_PAGE];

    if (pgno == 1) {
        if (memcmp(raw, NT_MAGIC, 16) == 0) p->wal_plain_votes++;
        nt_decrypt_page(1, raw, dec); /* cipher from byte 0 */
        if (memcmp(dec, NT_MAGIC, 16) == 0 && nt_header_plausible(dec)) {
            p->wal_enc_votes++;
            p->wal_p1_plain = 1;
        } else {
            nt_decrypt_page(0, raw, dec); /* salt-skip image */
            if (nt_header_plausible(dec)) {
                p->wal_enc_votes++;
                p->wal_p1_plain = 0;
            }
        }
    } else {
        if (raw[0] == 2 || raw[0] == 3 || raw[0] == 5 || raw[0] == 10 ||
            raw[0] == 13)
            p->wal_plain_votes++;
        nt_decrypt_page(1, raw, dec);
        if (dec[0] == 2 || dec[0] == 3 || dec[0] == 5 || dec[0] == 10 ||
            dec[0] == 13)
            p->wal_enc_votes++;
    }
}

/*
 * SQLite cannot digest this WAL itself: frame checksums were computed on
 * whatever QQ's layers handed to wal.c, while xRead must hand SQLite plain
 * pages — the two views of the same bytes never agree. So the pager is kept
 * immutable (it never learns -wal exists) and we replay frames here: same
 * validity rules as walDecodeFrame — header checksum, matching salts, running
 * frame checksums, sequential frames stopping at the first failure. Both
 * checksum flavours QQ could have used are tried once and the winner sticks.
 */
static void nt_wal_map_grow(nt_file *p, sqlite3_int64 need)
{
    unsigned int *grown;
    sqlite3_int64 old = p->wal_map_cap;
    sqlite3_int64 cap = old ? old : 1024;

    if (need <= old) return;
    while (cap < need) cap *= 2;
    grown = realloc(p->wal_map, (size_t)cap * sizeof(unsigned int));
    if (!grown) return; /* overlay degrades to "main file only" */
    memset(grown + old, 0, (size_t)(cap - old) * sizeof(unsigned int));
    p->wal_map = grown;
    p->wal_map_cap = cap;
}

static void nt_wal_scan(nt_file *p)
{
    unsigned char hdr[NT_WAL_HDR];
    unsigned char fhdr[NT_FRAME_HDR];
    unsigned char raw[NT_PAGE], dec[NT_PAGE];
    unsigned int ck[2], try_ck[2];
    unsigned int magic, page_size;
    unsigned char salt[8];
    int native;
    sqlite3_int64 fsize, pos;
    char path[NT_PATH_MAX];

    p->wal_scanned = 1;
    if (p->wal_disabled || p->zPath == NULL) return;

    snprintf(path, sizeof(path), "%s-wal", p->zPath);
    p->wal_fd = open(path, O_RDONLY | O_CLOEXEC);
    if (p->wal_fd < 0) return;
    {
        struct stat st;
        if (fstat(p->wal_fd, &st) || st.st_size < NT_WAL_HDR + NT_FRAME + NT_FRAME_HDR) {
            close(p->wal_fd);
            p->wal_fd = -1;
            return;
        }
        fsize = st.st_size;
    }

    memset(hdr, 0, sizeof(hdr));
    nt_pread(p->wal_fd, hdr, NT_WAL_HDR, 0);
    magic = nt_be32(hdr);
    page_size = nt_be32(hdr + 8);
    if ((magic & 0xFFFFFFFEu) != NT_WAL_MAGIC || page_size != NT_PAGE ||
        nt_be32(hdr + 4) != 3007000u) {
        goto reject;
    }
    /* magic low bit: 0 = checksum words stored little-endian (our target) */
    native = (int)(magic & 1u) == 0;
    memcpy(salt, hdr + 16, 8);
    nt_cksum(hdr, NT_WAL_HDR - 8, NULL, ck, native);
    if (ck[0] != nt_be32(hdr + 24) || ck[1] != nt_be32(hdr + 28)) goto reject;

    nt_wal_map_grow(p, (p->size - NT_HEADER) / NT_PAGE + 1);

    pos = NT_WAL_HDR;
    while (pos + NT_FRAME_HDR + NT_PAGE <= fsize) {
        unsigned int pgno, dbsize;

        nt_pread(p->wal_fd, fhdr, NT_FRAME_HDR, pos);
        if (memcmp(fhdr + 8, salt, 8) != 0) break;
        pgno = nt_be32(fhdr);
        if (pgno == 0) break;
        nt_pread(p->wal_fd, raw, NT_PAGE, pos + NT_FRAME_HDR);

        if (p->wal_mode == 0) {
            /* First frame decides which flavour of checksum QQ wrote:
             *  A — over the stored bytes (QQ encrypted above wal.c), or
             *  B — over the decrypted page (QQ encrypted below it), where the
             *      page-1 image may carry the salt prefix or not.
             * try_ck ends up holding the winning checksum chain. */
            int matched = 0;
            nt_cksum(fhdr, 8, ck, try_ck, native);
            nt_cksum(raw, NT_PAGE, try_ck, try_ck, native);
            if (try_ck[0] == nt_be32(fhdr + 16) && try_ck[1] == nt_be32(fhdr + 20)) {
                p->wal_mode = 1; /* checksum covers stored bytes */
                matched = 1;
            }
            if (!matched && pgno == 1) {
                nt_decrypt_page(0, raw, dec); /* salt-skip image */
                nt_cksum(fhdr, 8, ck, try_ck, native);
                nt_cksum(dec, NT_PAGE, try_ck, try_ck, native);
                if (try_ck[0] == nt_be32(fhdr + 16) &&
                    try_ck[1] == nt_be32(fhdr + 20)) {
                    p->wal_mode = 2;
                    p->wal_p1_plain = 0;
                    matched = 1;
                }
            }
            if (!matched) {
                nt_decrypt_page(1, raw, dec); /* plain image (cipher from 0) */
                nt_cksum(fhdr, 8, ck, try_ck, native);
                nt_cksum(dec, NT_PAGE, try_ck, try_ck, native);
                if (try_ck[0] == nt_be32(fhdr + 16) &&
                    try_ck[1] == nt_be32(fhdr + 20)) {
                    p->wal_mode = 2;
                    p->wal_p1_plain = 1;
                    matched = 1;
                }
            }
            if (!matched) break; /* frame 0 invalid → no overlay */
        } else if (p->wal_mode == 1) {
            nt_cksum(fhdr, 8, ck, try_ck, native);
            nt_cksum(raw, NT_PAGE, try_ck, try_ck, native);
            if (try_ck[0] != nt_be32(fhdr + 16) || try_ck[1] != nt_be32(fhdr + 20))
                break;
        } else {
            int style = (pgno == 1) ? p->wal_p1_plain : 1;
            nt_decrypt_page(style, raw, dec);
            nt_cksum(fhdr, 8, ck, try_ck, native);
            nt_cksum(dec, NT_PAGE, try_ck, try_ck, native);
            if (try_ck[0] != nt_be32(fhdr + 16) || try_ck[1] != nt_be32(fhdr + 20)) {
                if (pgno != 1) break;
                /* Page-1 image shape not settled yet: adopt whichever works. */
                nt_decrypt_page(!style, raw, dec);
                nt_cksum(fhdr, 8, ck, try_ck, native);
                nt_cksum(dec, NT_PAGE, try_ck, try_ck, native);
                if (try_ck[0] != nt_be32(fhdr + 16) ||
                    try_ck[1] != nt_be32(fhdr + 20))
                    break;
                p->wal_p1_plain = !style;
            }
        }
        memcpy(ck, try_ck, sizeof(ck));
        if (p->wal_mode == 1) nt_wal_vote(p, raw, pgno);

        if (pgno > p->wal_map_cap) nt_wal_map_grow(p, pgno);
        if (p->wal_map && pgno <= p->wal_map_cap)
            p->wal_map[pgno - 1] = (unsigned int)(p->wal_frames + 1);
        dbsize = nt_be32(fhdr + 4);
        if (dbsize && (sqlite3_int64)dbsize > p->wal_pages)
            p->wal_pages = dbsize;
        p->wal_frames++;
        pos += NT_FRAME;
    }

reject:
    if (p->wal_mode == 0) {
        if (p->wal_fd >= 0) {
            close(p->wal_fd);
            p->wal_fd = -1;
        }
    } else if (p->wal_mode == 1) {
        /* Checksum matched stored bytes: unencrypted frames win when the
         * votes say so (plaintext never decrypts better than it reads). */
        p->wal_plain = p->wal_plain_votes > p->wal_enc_votes;
    }
    g_wal_frames = p->wal_frames;
    g_wal_mode = p->wal_mode;
    g_wal_plain = p->wal_plain;
    g_wal_pages = p->wal_pages;
}

static sqlite3_int64 nt_logical_size(nt_file *p)
{
    sqlite3_int64 main_sz = p->size - NT_HEADER;
    sqlite3_int64 wal_sz;
    if (!p->wal_scanned) nt_wal_scan(p);
    wal_sz = p->wal_pages * (sqlite3_int64)NT_PAGE;
    return main_sz > wal_sz ? main_sz : wal_sz;
}

static sqlite3_int64 nt_logical_pages(nt_file *p)
{
    sqlite3_int64 sz = nt_logical_size(p);
    return (sz + NT_PAGE - 1) / NT_PAGE;
}

/* ---- main database ---- */

static unsigned char *nt_db_page(nt_file *p, sqlite3_int64 pgno)
{
    int slot = (int)(pgno % NT_CACHE);

    if (pgno < 1 || pgno > nt_logical_pages(p)) return NULL;
    if (p->pageno[slot] == pgno) {
        p->dbg_frame = p->dbg_frame_slot[slot];
        return p->pages[slot];
    }

    p->dbg_frame = 0;
    if (p->wal_map && pgno <= p->wal_map_cap && p->wal_map[pgno - 1] &&
        p->wal_fd >= 0) {
        /* Newest frame wins over the main file. */
        unsigned int seq = p->wal_map[pgno - 1];
        unsigned char raw[NT_PAGE];
        sqlite3_int64 ofs = NT_WAL_HDR + (sqlite3_int64)(seq - 1) * NT_FRAME +
                            NT_FRAME_HDR;
        nt_pread(p->wal_fd, raw, NT_PAGE, ofs);
        p->dbg_frame = seq;
        if (p->wal_mode == 1 && p->wal_plain) {
            memcpy(p->pages[slot], raw, NT_PAGE); /* unencrypted frames */
        } else {
            /* style 0 = salt-skip page-1 image, 1 = cipher from byte 0. */
            nt_decrypt_page(pgno == 1 ? (p->wal_p1_plain ? 1 : 0) : 1,
                            raw, p->pages[slot]);
        }
    } else if (NT_HEADER + (pgno - 1) * (sqlite3_int64)NT_PAGE < p->size) {
        unsigned char raw[NT_PAGE];
        nt_pread(p->fd, raw, NT_PAGE,
                 NT_HEADER + (pgno - 1) * (sqlite3_int64)NT_PAGE);
        nt_decrypt_page(pgno == 1 ? 0 : 1, raw, p->pages[slot]);
    } else {
        return NULL;
    }
    p->pageno[slot] = pgno;
    p->dbg_frame_slot[slot] = p->dbg_frame;
    return p->pages[slot];
}

/* ---- diagnostics: page access that bypasses SQLite's parser ---- */

static void nt_file_release(nt_file *p); /* defined with the shared methods */

/*
 * SQLite's own varint: 1..9 bytes, **big-endian** (unlike protobuf's). The
 * first byte holds the most significant 7 bits; a 9th byte carries a full 8.
 */
static int nt_varint_at(const unsigned char *b, size_t n, size_t *i,
                        long long *v)
{
    unsigned long long x = 0;
    int k = 0;

    while (*i < n && k < 8) {
        unsigned char c = b[(*i)++];
        x = (x << 7) | (unsigned long long)(c & 0x7f);
        k++;
        if (!(c & 0x80)) {
            *v = (long long)x;
            return 0;
        }
    }
    if (k == 8) {
        if (*i >= n) return -1;
        x = (x << 8) | b[(*i)++];
        *v = (long long)x;
        return 0;
    }
    return -1;
}

static unsigned int nt_be16(const unsigned char *p)
{
    return ((unsigned int)p[0] << 8) | p[1];
}

/* Opens a standalone nt_file for diagnostics (same key, same WAL merge). */
static int nt_shadow_open(const char *path, nt_file *p)
{
    struct stat st;
    unsigned char page[NT_PAGE], plain[NT_PAGE];
    int i;

    memset(p, 0, sizeof(*p));
    p->fd = -1;
    p->wal_fd = -1;
    p->wal_disabled = g_no_wal;
    p->fd = open(path, O_RDONLY | O_CLOEXEC);
    if (p->fd < 0) return -1;
    if (fstat(p->fd, &st)) goto fail;
    p->size = st.st_size;
    if (p->size < NT_HEADER + NT_PAGE) goto fail;
    nt_pread(p->fd, page, NT_PAGE, NT_HEADER);
    nt_decrypt_page(0, page, plain);
    if (!nt_header_plausible(plain)) goto fail;
    p->usable_size = NT_PAGE - (long long)plain[20];
    if (p->usable_size < NT_PAGE / 2 || p->usable_size > NT_PAGE)
        p->usable_size = NT_PAGE - NT_RESERVE;
    p->zPath = malloc(strlen(path) + 1);
    if (!p->zPath) goto fail;
    memcpy(p->zPath, path, strlen(path) + 1);
    for (i = 0; i < NT_CACHE; i++) {
        p->pageno[i] = -1;
        p->pages[i] = malloc(NT_PAGE);
        if (!p->pages[i]) goto fail;
    }
    return 0;
fail:
    nt_file_release(p);
    return -1;
}

int nt_vfs_dump_page(const char *path, unsigned int pgno, unsigned char *page,
                     unsigned int *frame, long long *usable)
{
    nt_file p;
    const unsigned char *pg;
    int rc = -1;

    if (nt_shadow_open(path, &p)) return -1;
    pg = nt_db_page(&p, pgno);
    if (pg) {
        memcpy(page, pg, NT_PAGE);
        if (frame) *frame = p.dbg_frame;
        if (usable) *usable = p.usable_size;
        rc = 0;
    }
    nt_file_release(&p);
    return rc;
}

int nt_vfs_locate(const char *path, unsigned int root, long long key,
                  nt_locate_info *out)
{
    nt_file p;
    unsigned int pgno = root;
    int i;

    memset(out, 0, sizeof(*out));
    out->first_key = -1;
    out->last_key = -1;
    if (!root || nt_shadow_open(path, &p)) return -1;

    for (i = 0; i < 32 && pgno; i++) {
        const unsigned char *pg = nt_db_page(&p, pgno);
        int type, ncells, hdr, k;

        if (!pg) break;
        out->pgno = pgno;
        out->frame = p.dbg_frame;
        out->type = type = pg[0];
        out->ncells = ncells = (int)nt_be16(pg + 3);
        out->usable = p.usable_size;

        if (type == 0x05) { /* interior table page: cell = child ‖ rowid */
            unsigned int right = nt_be32(pg + 8), next = right;
            hdr = 12;
            out->depth++;
            for (k = 0; k < ncells; k++) {
                unsigned int ofs = nt_be16(pg + hdr + 2 * k);
                size_t ci = (size_t)ofs + 4;
                long long cellkey;
                if (ofs < (unsigned int)(hdr + 2 * ncells)
                    || ofs + 4 >= (unsigned int)p.usable_size
                    || nt_varint_at(pg, (size_t)p.usable_size, &ci,
                                    &cellkey)) {
                    out->bad_cells = 1;
                    out->bad_cell = (unsigned int)k;
                    out->bad_ofs = ofs;
                    break;
                }
                if (cellkey >= key) {
                    next = nt_be32(pg + ofs);
                    break;
                }
            }
            if (out->bad_cells) break;
            pgno = next;
            continue;
        }
        if (type != 0x0D) break; /* not a table leaf: report what we saw */

        /* table leaf: cell = payload-size ‖ rowid ‖ payload */
        hdr = 8;
        out->depth++;
        {
        long long last_rowid = -1, last_payload = 0;
        unsigned int last_over = 0, cell_over = 0;
        for (k = 0; k < ncells; k++) {
            unsigned int ofs = nt_be16(pg + hdr + 2 * k);
            size_t ci;
            long long payload, rowid, local, maxlocal, minlocal, need;

            if (ofs < (unsigned int)(hdr + 2 * ncells)
                || ofs + 2 >= (unsigned int)p.usable_size) {
                out->bad_cells++;
                if (out->bad_cells == 1) {
                    out->bad_cell = (unsigned int)k;
                    out->bad_ofs = ofs;
                }
                continue;
            }
            ci = ofs;
            if (nt_varint_at(pg, (size_t)p.usable_size, &ci, &payload)
                || nt_varint_at(pg, (size_t)p.usable_size, &ci, &rowid)) {
                out->bad_cells++;
                if (out->bad_cells == 1) {
                    out->bad_cell = (unsigned int)k;
                    out->bad_ofs = ofs;
                }
                continue;
            }
            maxlocal = p.usable_size - 35;
            minlocal = (p.usable_size - 12) * 32 / 255 - 23;
            if (payload <= maxlocal) local = payload;
            else local = minlocal + (payload - minlocal) % (p.usable_size - 4);
            need = (long long)(ci - ofs) + local;
            if ((long long)ofs + need > p.usable_size) {
                out->bad_cells++;
                if (out->bad_cells == 1) {
                    out->bad_cell = (unsigned int)k;
                    out->bad_ofs = ofs;
                    out->bad_payload = payload;
                    out->bad_need = need;
                }
                continue;
            }
            if (k == 0) out->first_key = rowid;
            out->last_key = rowid;

            if (payload > local) {
                /* Walk the overflow chain from this cell's head link. */
                sqlite3_int64 limit = nt_logical_pages(&p);
                unsigned int link = 0, ov;
                long long at = (long long)ofs + need;

                out->over_cells++;
                ov = nt_be32(pg + at);
                cell_over = ov;
                if (rowid >= key && !out->target_found) {
                    out->target_found = 1;
                    out->target_rowid = rowid;
                    out->target_payload = payload;
                    out->target_over = ov;
                    {
                        /* Read it once to record where it lives. */
                        const unsigned char *op = nt_db_page(&p, ov);
                        out->target_frame = op ? p.dbg_frame : 0;
                    }
                }
                while (ov) {
                    if ((sqlite3_int64)ov < 1 || (sqlite3_int64)ov > limit) {
                        out->over_bad++;
                        out->over_bad_cell = (unsigned int)k;
                        out->over_bad_pgno = ov;
                        out->over_bad_link = link;
                        break;
                    }
                    if (++out->over_pages > 4096) break; /* don't hang */
                    {
                        const unsigned char *op = nt_db_page(&p, ov);
                        if (!op) break;
                        ov = nt_be32(op);
                    }
                    link++;
                }
            } else if (rowid >= key && !out->target_found) {
                out->target_found = 1;
                out->target_rowid = rowid;
                out->target_payload = payload;
                out->target_over = 0;
                cell_over = 0;
            }
            last_rowid = rowid;
            last_payload = payload;
            last_over = cell_over;
        }
        if (!out->target_found && last_rowid >= 0) {
            /* key is past the end of this tree: report the final cell. */
            out->target_found = 1;
            out->target_rowid = last_rowid;
            out->target_payload = last_payload;
            out->target_over = last_over;
        }
        }
        break;
    }
    nt_file_release(&p);
    return 0;
}

static int ntDbRead(sqlite3_file *pFile, void *zBuf, int iAmt,
                    sqlite3_int64 iOfst)
{
    nt_file *p = (nt_file *)pFile;
    sqlite3_int64 logical = nt_logical_size(p);
    sqlite3_int64 start = iOfst;
    sqlite3_int64 end = iOfst + iAmt;
    unsigned char *dst = zBuf;

    if (iAmt <= 0) return SQLITE_OK;
    if (iOfst < 0) return SQLITE_IOERR_READ;
    if (iOfst >= logical) {
        memset(zBuf, 0, (size_t)iAmt);
        return SQLITE_IOERR_SHORT_READ;
    }
    while (iAmt > 0) {
        sqlite3_int64 pgno = iOfst / NT_PAGE + 1;
        int off = (int)(iOfst % NT_PAGE);
        int chunk = NT_PAGE - off;
        const unsigned char *page;

        if (chunk > iAmt) chunk = iAmt;
        page = nt_db_page(p, pgno);
        if (page) memcpy(dst, page + off, (size_t)chunk);
        else memset(dst, 0, (size_t)chunk);
        dst += chunk;
        iOfst += chunk;
        iAmt -= chunk;
    }
    if (end > logical) {
        /* Short reads must zero everything past the end of the file. */
        sqlite3_int64 zoff = logical - start; /* buffer index where EOF begins */
        if (zoff < 0) zoff = 0;
        memset((unsigned char *)zBuf + zoff, 0, (size_t)(end - logical));
        return SQLITE_IOERR_SHORT_READ;
    }
    return SQLITE_OK;
}

/* ---- WAL handle (defensive: immutable mode should never open it) ---- */

static int ntWalRead(sqlite3_file *pFile, void *zBuf, int iAmt,
                     sqlite3_int64 iOfst)
{
    nt_file *p = (nt_file *)pFile;
    if (iAmt <= 0) return SQLITE_OK;
    if (iOfst < 0) return SQLITE_IOERR_READ;
    if (iOfst >= p->size) {
        memset(zBuf, 0, (size_t)iAmt);
        return SQLITE_IOERR_SHORT_READ;
    }
    nt_pread(p->fd, zBuf, (size_t)iAmt, iOfst);
    if (iOfst + iAmt > p->size) {
        memset((unsigned char *)zBuf + (size_t)(p->size - iOfst), 0,
               (size_t)(iOfst + iAmt - p->size));
        return SQLITE_IOERR_SHORT_READ;
    }
    return SQLITE_OK;
}

/* ---- RAM-only handle (journal / temp) ---- */

static int ntMemRead(sqlite3_file *pFile, void *zBuf, int iAmt,
                     sqlite3_int64 iOfst)
{
    nt_file *p = (nt_file *)pFile;
    if (iAmt <= 0) return SQLITE_OK;
    if (iOfst < 0) return SQLITE_IOERR_READ;
    if (iOfst >= p->mem_size) {
        memset(zBuf, 0, (size_t)iAmt);
        return SQLITE_IOERR_SHORT_READ;
    }
    {
        sqlite3_int64 avail = p->mem_size - iOfst;
        int n = iAmt;
        if (avail < iAmt) n = (int)avail;
        memcpy(zBuf, p->mem + iOfst, (size_t)n);
        if (n < iAmt) {
            memset((unsigned char *)zBuf + n, 0, (size_t)(iAmt - n));
            return SQLITE_IOERR_SHORT_READ;
        }
    }
    return SQLITE_OK;
}

static int ntMemWrite(sqlite3_file *pFile, const void *zBuf, int iAmt,
                      sqlite3_int64 iOfst)
{
    nt_file *p = (nt_file *)pFile;
    sqlite3_int64 need = iOfst + iAmt;

    if (need > p->mem_cap) {
        sqlite3_int64 cap = p->mem_cap ? p->mem_cap : 4096;
        unsigned char *grown;
        while (cap < need) cap *= 2;
        grown = realloc(p->mem, (size_t)cap);
        if (!grown) return SQLITE_NOMEM;
        memset(grown + p->mem_cap, 0, (size_t)(cap - p->mem_cap));
        p->mem = grown;
        p->mem_cap = cap;
    }
    if (iOfst > p->mem_size)
        memset(p->mem + p->mem_size, 0, (size_t)(iOfst - p->mem_size));
    memcpy(p->mem + iOfst, zBuf, (size_t)iAmt);
    if (need > p->mem_size) p->mem_size = need;
    return SQLITE_OK;
}

static int ntMemTruncate(sqlite3_file *pFile, sqlite3_int64 nByte)
{
    nt_file *p = (nt_file *)pFile;
    if (nByte < p->mem_size) p->mem_size = nByte;
    return SQLITE_OK;
}

/* ---- shared methods ---- */

static void nt_file_release(nt_file *p)
{
    int i;
    if (p->fd >= 0) close(p->fd);
    if (p->wal_fd >= 0) close(p->wal_fd);
    for (i = 0; i < NT_CACHE; i++) free(p->pages[i]);
    free(p->zPath);
    free(p->wal_map);
    free(p->mem);
    p->fd = -1;
    p->wal_fd = -1;
    p->zPath = NULL;
    p->wal_map = NULL;
    p->mem = NULL;
}

static int ntClose(sqlite3_file *pFile)
{
    nt_file *p = (nt_file *)pFile;
    nt_file_release(p);
    p->base.pMethods = NULL;
    return SQLITE_OK;
}

static int ntWrite(sqlite3_file *pFile, const void *zBuf, int iAmt,
                   sqlite3_int64 iOfst)
{
    /* The read-only rule for QQ's data lives here, at the choke point. */
    (void)pFile;
    (void)zBuf;
    (void)iAmt;
    (void)iOfst;
    return SQLITE_READONLY;
}

static int ntTruncate(sqlite3_file *pFile, sqlite3_int64 nByte)
{
    (void)pFile;
    (void)nByte;
    return SQLITE_READONLY;
}

static int ntSync(sqlite3_file *pFile, int flags)
{
    (void)pFile;
    (void)flags;
    return SQLITE_OK;
}

static int ntFileSize(sqlite3_file *pFile, sqlite3_int64 *pSize)
{
    nt_file *p = (nt_file *)pFile;
    switch (p->kind) {
    case NT_DB: *pSize = nt_logical_size(p); return SQLITE_OK;
    case NT_WAL: *pSize = p->size; return SQLITE_OK;
    default: *pSize = p->mem_size; return SQLITE_OK;
    }
}

static int ntLock(sqlite3_file *pFile, int eLock)
{
    (void)pFile;
    (void)eLock;
    return SQLITE_OK; /* single reader, no contention to model */
}

static int ntUnlock(sqlite3_file *pFile, int eLock)
{
    (void)pFile;
    (void)eLock;
    return SQLITE_OK;
}

static int ntCheckReserved(sqlite3_file *pFile, int *pResOut)
{
    (void)pFile;
    *pResOut = 0;
    return SQLITE_OK;
}

static int ntFileControl(sqlite3_file *pFile, int op, void *pArg)
{
    switch (op) {
    case SQLITE_FCNTL_LOCKSTATE:
        *(int *)pArg = 0;
        return SQLITE_OK;
    case SQLITE_FCNTL_HAS_MOVED:
        *(int *)pArg = 0;
        return SQLITE_OK;
    case SQLITE_FCNTL_VFSNAME:
        *(char **)pArg = sqlite3_mprintf("ntdb");
        return SQLITE_OK;
    case SQLITE_FCNTL_SIZE_HINT:
        return SQLITE_OK;
    default:
        return SQLITE_NOTFOUND;
    }
}

static int ntSectorSize(sqlite3_file *pFile)
{
    (void)pFile;
    return 4096;
}

static int ntDeviceCharacteristics(sqlite3_file *pFile)
{
    nt_file *p = (nt_file *)pFile;
    if (p->kind != NT_DB) return 0;
    /* Always: SQLite must never open -wal/-shm itself. Our VFS replays valid
     * frames into reads (nt_wal_scan), so the pager treats the file as a
     * plain immutable image. */
    return SQLITE_IOCAP_IMMUTABLE;
}

static const sqlite3_io_methods nt_io_db = {
    1,
    ntClose, ntDbRead, ntWrite, ntTruncate, ntSync, ntFileSize, ntLock,
    ntUnlock, ntCheckReserved, ntFileControl, ntSectorSize,
    ntDeviceCharacteristics,
    NULL, NULL, NULL, NULL, /* iVersion 2: shm — unused, pager is immutable */
    NULL, NULL,             /* iVersion 3: fetch — unused */
};

static const sqlite3_io_methods nt_io_wal = {
    1,
    ntClose, ntWalRead, ntWrite, ntTruncate, ntSync, ntFileSize, ntLock,
    ntUnlock, ntCheckReserved, ntFileControl, ntSectorSize,
    ntDeviceCharacteristics,
    NULL, NULL, NULL, NULL,
    NULL, NULL,
};

static const sqlite3_io_methods nt_io_mem = {
    1,
    ntClose, ntMemRead, ntMemWrite, ntMemTruncate, ntSync, ntFileSize, ntLock,
    ntUnlock, ntCheckReserved, ntFileControl, ntSectorSize,
    ntDeviceCharacteristics,
    NULL, NULL, NULL, NULL,
    NULL, NULL,
};

/* ---- VFS entry points ---- */

static int ntOpen(sqlite3_vfs *pVfs, const char *zName, sqlite3_file *pFile,
                  int flags, int *pOutFlags)
{
    nt_file *p = (nt_file *)pFile;
    char path[NT_PATH_MAX];
    int kind = NT_MEM;

    (void)pVfs;
    memset(p, 0, sizeof(*p));
    p->fd = -1;
    p->wal_fd = -1;

    if (flags & SQLITE_OPEN_MAIN_DB) kind = NT_DB;
    else if (flags & SQLITE_OPEN_WAL) kind = NT_WAL;

    if (kind == NT_MEM) {
        p->kind = NT_MEM;
        p->base.pMethods = &nt_io_mem;
        if (pOutFlags) *pOutFlags = flags;
        return SQLITE_OK;
    }

    nt_real_path(zName, path, sizeof(path));
    p->fd = open(path, O_RDONLY | O_CLOEXEC);
    if (p->fd < 0) {
        p->base.pMethods = NULL;
        return SQLITE_CANTOPEN;
    }
    {
        struct stat st;
        if (fstat(p->fd, &st)) {
            close(p->fd);
            p->fd = -1;
            p->base.pMethods = NULL;
            return SQLITE_CANTOPEN;
        }
        p->size = st.st_size;
    }
    p->kind = kind;
    p->wal_disabled = g_no_wal;

    if (kind == NT_DB) {
        int i;
        unsigned char page[NT_PAGE];

        /* Validate by decrypting page 1: a wrong key or geometry fails here
         * instead of corrupting reads much later. */
        memset(page, 0, sizeof(page));
        if (p->size < NT_HEADER + NT_PAGE || !g_key_ready) {
            close(p->fd);
            p->fd = -1;
            p->base.pMethods = NULL;
            return SQLITE_CANTOPEN;
        }
        nt_pread(p->fd, page, NT_PAGE, NT_HEADER);
        {
            unsigned char plain[NT_PAGE];
            nt_decrypt_page(0, page, plain);
            if (!nt_header_plausible(plain)) {
                close(p->fd);
                p->fd = -1;
                p->base.pMethods = NULL;
                return SQLITE_CANTOPEN;
            }
            /* byte 20 = reserved bytes per page; SQLite's usable area ends
             * there even though the crypto reserve is 48. */
            p->usable_size = NT_PAGE - (long long)plain[20];
            if (p->usable_size < NT_PAGE / 2 || p->usable_size > NT_PAGE)
                p->usable_size = NT_PAGE - NT_RESERVE;
        }
        p->zPath = malloc(strlen(path) + 1);
        if (p->zPath) memcpy(p->zPath, path, strlen(path) + 1);
        for (i = 0; i < NT_CACHE; i++) {
            p->pages[i] = malloc(NT_PAGE);
            p->pageno[i] = -1;
            if (!p->pages[i]) {
                ntClose(pFile);
                return SQLITE_NOMEM;
            }
        }
    }
    p->base.pMethods = (kind == NT_DB) ? &nt_io_db : &nt_io_wal;
    if (pOutFlags)
        *pOutFlags = (flags & ~(SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)) |
                     SQLITE_OPEN_READONLY;
    return SQLITE_OK;
}

static int ntDelete(sqlite3_vfs *pVfs, const char *zName, int syncDir)
{
    /* Never remove anything of QQ's: journals are RAM-only here and SQLite
     * (immutable pager) never asks for -wal/-shm. Report success rather than
     * risk an error path that retries with writes. */
    (void)pVfs;
    (void)zName;
    (void)syncDir;
    return SQLITE_OK;
}

static int ntAccess(sqlite3_vfs *pVfs, const char *zName, int flags,
                    int *pResOut)
{
    char path[NT_PATH_MAX];
    (void)pVfs;
    nt_real_path(zName, path, sizeof(path));
    switch (flags) {
    case SQLITE_ACCESS_EXISTS:
        *pResOut = access(path, F_OK) == 0;
        break;
    case SQLITE_ACCESS_READWRITE:
        *pResOut = 1; /* writes are refused at the VFS layer anyway */
        break;
    default:
        *pResOut = access(path, R_OK) == 0;
        break;
    }
    return SQLITE_OK;
}

static int ntFullPathname(sqlite3_vfs *pVfs, const char *zName, int nOut,
                          char *zOut)
{
    char path[NT_PATH_MAX];
    (void)pVfs;
    nt_real_path(zName, path, sizeof(path));
    if ((int)strlen(path) >= nOut) return SQLITE_CANTOPEN;
    memcpy(zOut, path, strlen(path) + 1);
    return SQLITE_OK;
}

static void *ntDlOpen(sqlite3_vfs *pVfs, const char *zPath)
{
    (void)pVfs;
    (void)zPath;
    return NULL; /* extensions are not part of this tool */
}

static void ntDlError(sqlite3_vfs *pVfs, int nByte, char *zErrMsg)
{
    (void)pVfs;
    if (nByte > 0) {
        static const char msg[] = "dynamic loading not supported";
        memcpy(zErrMsg, msg, (sizeof(msg) < (size_t)nByte) ? sizeof(msg)
                                                           : (size_t)nByte);
    }
}

static void (*ntDlSym(sqlite3_vfs *pVfs, void *pH, const char *zSym))(void)
{
    (void)pVfs;
    (void)pH;
    (void)zSym;
    return NULL;
}

static void ntDlClose(sqlite3_vfs *pVfs, void *pHandle)
{
    (void)pVfs;
    (void)pHandle;
}

static int ntRandomness(sqlite3_vfs *pVfs, int nByte, char *zOut)
{
    static int urandom = -1;
    int done = 0;
    (void)pVfs;
    if (urandom < 0) urandom = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (urandom >= 0) {
        ssize_t got = read(urandom, zOut, (size_t)nByte);
        if (got > 0) done = (int)got;
    }
    while (done < nByte) {
        zOut[done] = (char)(done * 31 + (int)getpid() + 17);
        done++;
    }
    return nByte;
}

static int ntSleep(sqlite3_vfs *pVfs, int microseconds)
{
    struct timespec ts;
    (void)pVfs;
    if (microseconds <= 0) return microseconds;
    ts.tv_sec = microseconds / 1000000;
    ts.tv_nsec = (microseconds % 1000000) * 1000L;
    nanosleep(&ts, NULL);
    return microseconds;
}

static int ntGetLastError(sqlite3_vfs *pVfs, int nByte, char *zErrMsg)
{
    (void)pVfs;
    if (nByte > 0) zErrMsg[0] = '\0';
    return 0;
}

static int ntCurrentTime(sqlite3_vfs *pVfs, double *pOut)
{
    struct timeval tv;
    (void)pVfs;
    if (gettimeofday(&tv, NULL)) return SQLITE_ERROR;
    *pOut = 2440587.5 + (double)tv.tv_sec / 86400.0 +
            ((double)tv.tv_usec / 86400.0) / 1e6;
    return SQLITE_OK;
}

static sqlite3_vfs g_nt_vfs = {
    1,                    /* iVersion */
    (int)sizeof(nt_file), /* szOsFile */
    NT_PATH_MAX,          /* mxPathname */
    NULL,                 /* pNext */
    "ntdb",               /* zName */
    NULL,                 /* pAppData */
    ntOpen,
    ntDelete,
    ntAccess,
    ntFullPathname,
    ntDlOpen,
    ntDlError,
    ntDlSym,
    ntDlClose,
    ntRandomness,
    ntSleep,
    ntCurrentTime,
    ntGetLastError,
    NULL, /* xCurrentTimeInt64 (iVersion 2) */
    NULL, NULL, NULL,       /* system calls (iVersion 3) */
};

int nt_vfs_register(void) { return sqlite3_vfs_register(&g_nt_vfs, 1); }
