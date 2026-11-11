/*
 * Custom SQLite VFS that reads QQ's nt_msg.db in place, decrypting as it goes.
 *
 * The file layout proven by the Phase 0 probe (QQ 9.1.35):
 *
 *   [0, 1024)        QQ's own header (magic, rand, "HMAC_SHA1", …)
 *   [1024, ∞)        the SQLite stream, page 4096, crypto reserve 48:
 *                    page 1 = salt(16) ‖ ciphertext ‖ IV@4048 ‖ HMAC…, its
 *                    "SQLite format 3" magic overwritten by the salt and
 *                    re-synthesised on read; later pages are
 *                    ciphertext ‖ IV@4048 ‖ HMAC… over [0, 4048).
 *
 * SQLite therefore sees plain pages while never seeing the real offsets, and
 * every mutating operation returns SQLITE_READONLY so QQ's files cannot be
 * touched. The WAL is read frame by frame (24-byte frame header, data region
 * decrypted like a page); the wal-index lives in RAM seeded from the existing
 * -shm file, so nothing is created or written either.
 */
#ifndef NTDB_VFS_H
#define NTDB_VFS_H

#include "aes.h"
#include "sqlite3.h"

#define NT_HEADER  1024  /* QQ header before logical page 0 */
#define NT_PAGE    4096  /* page size (validated against the decrypted header) */
#define NT_RESERVE 48    /* IV(16) + HMAC-SHA1(20) + pad → 48 */

/* Call before sqlite3_open: the derived 32-byte AES key (PBKDF2-SHA512×4000). */
void nt_vfs_set_key(const unsigned char key[32]);

/*
 * Ignore -wal entirely: xDeviceCharacteristics then reports
 * SQLITE_IOCAP_IMMUTABLE, which makes the pager skip the WAL and its
 * shared-memory file (its fallback when reading goes wrong).
 */
void nt_vfs_set_no_wal(int on);

/* Registers the VFS as the default. Returns SQLITE_OK. */
int nt_vfs_register(void);

/*
 * Diagnostics: decrypt page 1 of [path] with the key set above and return 1
 * when the header reads back plausible (page size, format versions, encoding).
 */
int nt_vfs_read_header_page(const char *path, unsigned char *page);

/* Reports what the WAL merge found (valid once a page has been read). */
void nt_vfs_wal_stats(unsigned int *frames, int *mode, sqlite3_int64 *pages);

/* Non-zero when mode-1 frames turned out to be stored unencrypted. */
int nt_vfs_wal_is_plain(void);

/*
 * Diagnostics, independent of SQLite's parser: walk the table b-tree rooted at
 * `root` down to the leaf holding (or the first leaf after) rowid `key`, and
 * report what that page actually contains. Works on a page that SQLite calls
 * "malformed", which is exactly when it is needed.
 */
typedef struct {
    unsigned int pgno;      /* page reached; 0 when the walk failed */
    unsigned int frame;     /* WAL frame it came from, 0 = main file */
    int type;               /* b-tree page type byte (0x05/0x0D/…) */
    int ncells;
    int depth;              /* interior pages walked */
    long long usable;       /* page size minus the header's reserved bytes */
    long long first_key;    /* rowid of the first cell (leaf) */
    long long last_key;     /* rowid of the last cell (leaf) */
    int bad_cells;          /* cells whose bytes fall outside the page */
    unsigned int bad_cell;  /* index of the first bad cell */
    unsigned int bad_ofs;   /* its cell offset */
    long long bad_payload;  /* its declared payload size */
    long long bad_need;     /* bytes the cell would occupy */
    /* Overflow chains: a body larger than the page spills there, and a single
     * bad link is enough for SQLite to call the whole page malformed. */
    long long over_cells;   /* cells that spill to overflow pages */
    long long over_pages;   /* overflow pages visited */
    unsigned int over_bad;  /* chains with an impossible page number */
    unsigned int over_bad_cell;
    unsigned int over_bad_pgno;
    unsigned int over_bad_link; /* 0 = head link, 1.. = depth */
    /* The cell covering `key` (first rowid >= key, else the last cell). */
    int target_found;
    long long target_rowid;
    long long target_payload;
    unsigned int target_over;  /* its first overflow page, 0 = all inline */
    unsigned int target_frame; /* its overflow head's WAL frame, 0 = main */
    /* Pages visited on the way down, root first. */
    int path_len;
    unsigned int path[32];
} nt_locate_info;

int nt_vfs_locate(const char *path, unsigned int root, long long key,
                  nt_locate_info *out);

/* Diagnostics: return the decrypted page `pgno` (one NT_PAGE) and its source. */
int nt_vfs_dump_page(const char *path, unsigned int pgno, unsigned char *page,
                     unsigned int *frame, long long *usable);

#endif /* NTDB_VFS_H */
