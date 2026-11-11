#include "aes.h"

#include <string.h>

/* ---- GF(2^8) arithmetic (polynomial 0x11b) ---- */

static unsigned char exp512[512]; /* 2^i, doubled table so no index wraps */
static unsigned char log256[256];
static unsigned char sbox[256];
static unsigned char isbox[256];
static int tables_ready;

static unsigned char gmul_raw(unsigned char a, unsigned char b)
{
    unsigned char p = 0;
    while (b) {
        if (b & 1) p ^= a;
        a = (unsigned char)((a << 1) ^ ((a & 0x80) ? 0x1b : 0));
        b >>= 1;
    }
    return p;
}

/*
 * S-box = affine transform of the multiplicative inverse in GF(2^8):
 * b' = b ^ rotl(b,1) ^ rotl(b,2) ^ rotl(b,3) ^ rotl(b,4) ^ 0x63 (FIPS-197 5.1.1).
 */
static void build_tables(void)
{
    unsigned char x = 1, y = 1;
    int i;

    /* Discrete log / antilog tables: 3 is a generator of GF(2^8)\{0}. */
    for (i = 0; i < 255; i++) {
        exp512[i] = y;
        log256[y] = (unsigned char)i;
        y = gmul_raw(y, 3);
    }
    for (i = 255; i < 512; i++) exp512[i] = exp512[i - 255];
    log256[0] = 0; /* unused; x^0 is never asked for */

    for (i = 0; i < 256; i++) {
        unsigned char inv;
        if (i == 0) {
            inv = 0;
        } else {
            /* i = 3^log(i) → i^254 = 3^(log(i)*254 mod 255) */
            int e = (log256[i] * 254) % 255;
            inv = exp512[e];
        }
        x = (unsigned char)(inv ^ (inv << 1 | inv >> 7) ^
                            (inv << 2 | inv >> 6) ^ (inv << 3 | inv >> 5) ^
                            (inv << 4 | inv >> 4) ^ 0x63);
        sbox[i] = x;
        isbox[x] = (unsigned char)i;
    }
    tables_ready = 1;
}

static unsigned char gmul(unsigned char a, unsigned char b)
{
    if (a == 0 || b == 0) return 0;
    return exp512[log256[a] + log256[b]];
}

/* ---- key expansion (AES-256: Nk=8, Nr=14) ---- */

void nt_aes256_setkey(nt_aes256_key *key, const unsigned char raw[32])
{
    unsigned char *w = key->rk;
    unsigned char rcon = 1;
    int i;

    if (!tables_ready) build_tables();
    memcpy(w, raw, 32);

    for (i = 8; i < 60; i++) {
        unsigned char t0 = w[(i - 1) * 4 + 0];
        unsigned char t1 = w[(i - 1) * 4 + 1];
        unsigned char t2 = w[(i - 1) * 4 + 2];
        unsigned char t3 = w[(i - 1) * 4 + 3];
        if (i % 8 == 0) {
            unsigned char tmp = t0;
            t0 = (unsigned char)(sbox[t1] ^ rcon);
            t1 = sbox[t2];
            t2 = sbox[t3];
            t3 = sbox[tmp];
            rcon = gmul(rcon, 2);
        } else if (i % 8 == 4) {
            t0 = sbox[t0];
            t1 = sbox[t1];
            t2 = sbox[t2];
            t3 = sbox[t3];
        }
        w[i * 4 + 0] = (unsigned char)(w[(i - 8) * 4 + 0] ^ t0);
        w[i * 4 + 1] = (unsigned char)(w[(i - 8) * 4 + 1] ^ t1);
        w[i * 4 + 2] = (unsigned char)(w[(i - 8) * 4 + 2] ^ t2);
        w[i * 4 + 3] = (unsigned char)(w[(i - 8) * 4 + 3] ^ t3);
    }
}

/* ---- inverse cipher ---- */

/* state is laid out linearly as column-major s[r + 4c], as in FIPS-197. */

static void add_round_key(unsigned char *s, const unsigned char *rk)
{
    int i;
    for (i = 0; i < 16; i++) s[i] ^= rk[i];
}

/* Row r is rotated right by r columns. */
static void inv_shift_rows(unsigned char *s)
{
    unsigned char t[16];
    int r, c;
    memcpy(t, s, 16);
    for (r = 0; r < 4; r++)
        for (c = 0; c < 4; c++)
            s[r + 4 * c] = t[r + 4 * ((c + 4 - r) % 4)];
}

static void inv_sub_bytes(unsigned char *s)
{
    int i;
    for (i = 0; i < 16; i++) s[i] = isbox[s[i]];
}

static void inv_mix_columns(unsigned char *s)
{
    int c;
    for (c = 0; c < 4; c++) {
        unsigned char *a = s + 4 * c;
        unsigned char a0 = a[0], a1 = a[1], a2 = a[2], a3 = a[3];
        a[0] = (unsigned char)(gmul(a0, 0x0e) ^ gmul(a1, 0x0b) ^ gmul(a2, 0x0d) ^ gmul(a3, 0x09));
        a[1] = (unsigned char)(gmul(a0, 0x09) ^ gmul(a1, 0x0e) ^ gmul(a2, 0x0b) ^ gmul(a3, 0x0d));
        a[2] = (unsigned char)(gmul(a0, 0x0d) ^ gmul(a1, 0x09) ^ gmul(a2, 0x0e) ^ gmul(a3, 0x0b));
        a[3] = (unsigned char)(gmul(a0, 0x0b) ^ gmul(a1, 0x0d) ^ gmul(a2, 0x09) ^ gmul(a3, 0x0e));
    }
}

void nt_aes256_decrypt_block(const nt_aes256_key *key,
                             const unsigned char in[16],
                             unsigned char out[16])
{
    unsigned char s[16];
    const unsigned char *rk = key->rk;
    int round;

    memcpy(s, in, 16);
    add_round_key(s, rk + 14 * 16);
    for (round = 13; round >= 1; round--) {
        inv_shift_rows(s);
        inv_sub_bytes(s);
        add_round_key(s, rk + round * 16);
        inv_mix_columns(s);
    }
    inv_shift_rows(s);
    inv_sub_bytes(s);
    add_round_key(s, rk);
    memcpy(out, s, 16);
}

void nt_aes256_cbc_decrypt(const nt_aes256_key *key, const unsigned char iv[16],
                           const unsigned char *in, unsigned char *out,
                           size_t len)
{
    unsigned char prev[16];
    size_t off;

    memcpy(prev, iv, 16);
    for (off = 0; off + 16 <= len; off += 16) {
        unsigned char blk[16];
        nt_aes256_decrypt_block(key, in + off, blk);
        blk[0] ^= prev[0];   blk[1] ^= prev[1];
        blk[2] ^= prev[2];   blk[3] ^= prev[3];
        blk[4] ^= prev[4];   blk[5] ^= prev[5];
        blk[6] ^= prev[6];   blk[7] ^= prev[7];
        blk[8] ^= prev[8];   blk[9] ^= prev[9];
        blk[10] ^= prev[10]; blk[11] ^= prev[11];
        blk[12] ^= prev[12]; blk[13] ^= prev[13];
        blk[14] ^= prev[14]; blk[15] ^= prev[15];
        memcpy(out + off, blk, 16);
        memcpy(prev, in + off, 16); /* chain on the ciphertext */
    }
}

/*
 * NIST SP 800-38A F.2.5 CBC-AES256: first block, with the expected
 * plaintext recorded alongside so a broken table build cannot pass.
 */
int nt_aes256_kat(void)
{
    static const unsigned char key[32] = {
        0x60, 0x3d, 0xeb, 0x10, 0x15, 0xca, 0x71, 0xbe, 0x2b, 0x73, 0xae,
        0xf0, 0x85, 0x7d, 0x77, 0x81, 0x1f, 0x35, 0x2c, 0x07, 0x3b, 0x61,
        0x08, 0xd7, 0x2d, 0x98, 0x10, 0xa3, 0x09, 0x14, 0xdf, 0xf4
    };
    static const unsigned char iv[16] = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
    };
    static const unsigned char cipher[16] = {
        0xf5, 0x8c, 0x4c, 0x04, 0xd6, 0xe5, 0xf1, 0xba,
        0x77, 0x9e, 0xab, 0xfb, 0x5f, 0x7b, 0xfb, 0xd6
    };
    static const unsigned char plain[16] = {
        0x6b, 0xc1, 0xbe, 0xe2, 0x2e, 0x40, 0x9f, 0x96,
        0xe9, 0x3d, 0x7e, 0x11, 0x73, 0x93, 0x17, 0x2a
    };
    nt_aes256_key k;
    unsigned char out[16];

    nt_aes256_setkey(&k, key);
    nt_aes256_cbc_decrypt(&k, iv, cipher, out, 16);
    return memcmp(out, plain, 16) != 0;
}
