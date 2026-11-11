#include "sha512.h"

#include <string.h>

/* FIPS 180-4 constants: fractional parts of the cube roots of the first
 * 80 primes (K) and of the square roots of the first 8 primes (H). They are
 * machine-generated into the known-answer test below, never hand-trusted. */
static const unsigned long long K512[80] = {
    0x428a2f98d728ae22ULL, 0x7137449123ef65cdULL,
    0xb5c0fbcfec4d3b2fULL, 0xe9b5dba58189dbbcULL,
    0x3956c25bf348b538ULL, 0x59f111f1b605d019ULL,
    0x923f82a4af194f9bULL, 0xab1c5ed5da6d8118ULL,
    0xd807aa98a3030242ULL, 0x12835b0145706fbeULL,
    0x243185be4ee4b28cULL, 0x550c7dc3d5ffb4e2ULL,
    0x72be5d74f27b896fULL, 0x80deb1fe3b1696b1ULL,
    0x9bdc06a725c71235ULL, 0xc19bf174cf692694ULL,
    0xe49b69c19ef14ad2ULL, 0xefbe4786384f25e3ULL,
    0x0fc19dc68b8cd5b5ULL, 0x240ca1cc77ac9c65ULL,
    0x2de92c6f592b0275ULL, 0x4a7484aa6ea6e483ULL,
    0x5cb0a9dcbd41fbd4ULL, 0x76f988da831153b5ULL,
    0x983e5152ee66dfabULL, 0xa831c66d2db43210ULL,
    0xb00327c898fb213fULL, 0xbf597fc7beef0ee4ULL,
    0xc6e00bf33da88fc2ULL, 0xd5a79147930aa725ULL,
    0x06ca6351e003826fULL, 0x142929670a0e6e70ULL,
    0x27b70a8546d22ffcULL, 0x2e1b21385c26c926ULL,
    0x4d2c6dfc5ac42aedULL, 0x53380d139d95b3dfULL,
    0x650a73548baf63deULL, 0x766a0abb3c77b2a8ULL,
    0x81c2c92e47edaee6ULL, 0x92722c851482353bULL,
    0xa2bfe8a14cf10364ULL, 0xa81a664bbc423001ULL,
    0xc24b8b70d0f89791ULL, 0xc76c51a30654be30ULL,
    0xd192e819d6ef5218ULL, 0xd69906245565a910ULL,
    0xf40e35855771202aULL, 0x106aa07032bbd1b8ULL,
    0x19a4c116b8d2d0c8ULL, 0x1e376c085141ab53ULL,
    0x2748774cdf8eeb99ULL, 0x34b0bcb5e19b48a8ULL,
    0x391c0cb3c5c95a63ULL, 0x4ed8aa4ae3418acbULL,
    0x5b9cca4f7763e373ULL, 0x682e6ff3d6b2b8a3ULL,
    0x748f82ee5defb2fcULL, 0x78a5636f43172f60ULL,
    0x84c87814a1f0ab72ULL, 0x8cc702081a6439ecULL,
    0x90befffa23631e28ULL, 0xa4506cebde82bde9ULL,
    0xbef9a3f7b2c67915ULL, 0xc67178f2e372532bULL,
    0xca273eceea26619cULL, 0xd186b8c721c0c207ULL,
    0xeada7dd6cde0eb1eULL, 0xf57d4f7fee6ed178ULL,
    0x06f067aa72176fbaULL, 0x0a637dc5a2c898a6ULL,
    0x113f9804bef90daeULL, 0x1b710b35131c471bULL,
    0x28db77f523047d84ULL, 0x32caab7b40c72493ULL,
    0x3c9ebe0a15c9bebcULL, 0x431d67c49c100d4cULL,
    0x4cc5d4becb3e42b6ULL, 0x597f299cfc657e2aULL,
    0x5fcb6fab3ad6faecULL, 0x6c44198c4a475817ULL,
};

static const unsigned long long H512[8] = {
    0x6a09e667f3bcc908ULL, 0xbb67ae8584caa73bULL,
    0x3c6ef372fe94f82bULL, 0xa54ff53a5f1d36f1ULL,
    0x510e527fade682d1ULL, 0x9b05688c2b3e6c1fULL,
    0x1f83d9abfb41bd6bULL, 0x5be0cd19137e2179ULL,
};

#define ROTR64(x, n) (((x) >> (n)) | ((x) << (64 - (n))))

static void sha512_block(unsigned char blk[128], unsigned long long h[8])
{
    unsigned long long w[80];
    unsigned long long a, b, c, d, e, f, g, hh;
    int t;

    for (t = 0; t < 16; t++) {
        w[t] = ((unsigned long long)blk[t * 8 + 0] << 56) |
               ((unsigned long long)blk[t * 8 + 1] << 48) |
               ((unsigned long long)blk[t * 8 + 2] << 40) |
               ((unsigned long long)blk[t * 8 + 3] << 32) |
               ((unsigned long long)blk[t * 8 + 4] << 24) |
               ((unsigned long long)blk[t * 8 + 5] << 16) |
               ((unsigned long long)blk[t * 8 + 6] << 8) |
               ((unsigned long long)blk[t * 8 + 7]);
    }
    for (t = 16; t < 80; t++) {
        unsigned long long s0 = ROTR64(w[t - 15], 1) ^ ROTR64(w[t - 15], 8) ^ (w[t - 15] >> 7);
        unsigned long long s1 = ROTR64(w[t - 2], 19) ^ ROTR64(w[t - 2], 61) ^ (w[t - 2] >> 6);
        w[t] = w[t - 16] + s0 + w[t - 7] + s1;
    }

    a = h[0]; b = h[1]; c = h[2]; d = h[3];
    e = h[4]; f = h[5]; g = h[6]; hh = h[7];

    for (t = 0; t < 80; t++) {
        unsigned long long s1 = ROTR64(e, 14) ^ ROTR64(e, 18) ^ ROTR64(e, 41);
        unsigned long long ch = (e & f) ^ ((~e) & g);
        unsigned long long t1 = hh + s1 + ch + K512[t] + w[t];
        unsigned long long s0 = ROTR64(a, 28) ^ ROTR64(a, 34) ^ ROTR64(a, 39);
        unsigned long long maj = (a & b) ^ (a & c) ^ (b & c);
        unsigned long long t2 = s0 + maj;
        hh = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }

    h[0] += a; h[1] += b; h[2] += c; h[3] += d;
    h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
}

void nt_sha512_init(nt_sha512_ctx *ctx)
{
    memcpy(ctx->h, H512, sizeof(H512));
    ctx->total_len = 0;
    ctx->buflen = 0;
}

void nt_sha512_update(nt_sha512_ctx *ctx, const void *data, size_t len)
{
    const unsigned char *p = (const unsigned char *)data;
    ctx->total_len += len;
    if (ctx->buflen) {
        size_t need = 128 - ctx->buflen;
        if (need > len) need = len;
        memcpy(ctx->buf + ctx->buflen, p, need);
        ctx->buflen += need;
        p += need;
        len -= need;
        if (ctx->buflen == 128) {
            sha512_block(ctx->buf, ctx->h);
            ctx->buflen = 0;
        }
    }
    while (len >= 128) {
        sha512_block((unsigned char *)(void *)p, ctx->h);
        p += 128;
        len -= 128;
    }
    if (len) {
        memcpy(ctx->buf, p, len);
        ctx->buflen = len;
    }
}

void nt_sha512_final(nt_sha512_ctx *ctx, unsigned char out[64])
{
    unsigned long long bits = ctx->total_len * 8;
    unsigned char pad = 0x80;
    unsigned char lenblk[16];
    int i;

    nt_sha512_update(ctx, &pad, 1);
    pad = 0;
    while (ctx->buflen != 112) nt_sha512_update(ctx, &pad, 1);
    for (i = 0; i < 8; i++) lenblk[i] = 0; /* messages stay < 2^61 bytes */
    for (i = 0; i < 8; i++) lenblk[8 + i] = (unsigned char)(bits >> (56 - 8 * i));
    nt_sha512_update(ctx, lenblk, 16); /* 112 + 16 fills the last block */

    for (i = 0; i < 8; i++) {
        out[i * 8 + 0] = (unsigned char)(ctx->h[i] >> 56);
        out[i * 8 + 1] = (unsigned char)(ctx->h[i] >> 48);
        out[i * 8 + 2] = (unsigned char)(ctx->h[i] >> 40);
        out[i * 8 + 3] = (unsigned char)(ctx->h[i] >> 32);
        out[i * 8 + 4] = (unsigned char)(ctx->h[i] >> 24);
        out[i * 8 + 5] = (unsigned char)(ctx->h[i] >> 16);
        out[i * 8 + 6] = (unsigned char)(ctx->h[i] >> 8);
        out[i * 8 + 7] = (unsigned char)(ctx->h[i]);
    }
}

void nt_sha512(const void *data, size_t len, unsigned char out[64])
{
    nt_sha512_ctx ctx;
    nt_sha512_init(&ctx);
    nt_sha512_update(&ctx, data, len);
    nt_sha512_final(&ctx, out);
}

/* ---- HMAC-SHA512 ---- */

typedef struct {
    nt_sha512_ctx inner;
    unsigned char opad_key[128];
} hmac512_ctx;

#define SHA512_BLOCK 128

static void hmac512_init(hmac512_ctx *h, const unsigned char *key, size_t keylen)
{
    unsigned char k[SHA512_BLOCK];
    unsigned char ipad[SHA512_BLOCK];
    unsigned char dbuf[64];
    size_t i;

    memset(k, 0, sizeof(k));
    if (keylen > SHA512_BLOCK) {
        nt_sha512(key, keylen, dbuf);
        memcpy(k, dbuf, 64);
    } else {
        memcpy(k, key, keylen);
    }
    for (i = 0; i < SHA512_BLOCK; i++) {
        ipad[i] = (unsigned char)(k[i] ^ 0x36);
        h->opad_key[i] = (unsigned char)(k[i] ^ 0x5c);
    }
    nt_sha512_init(&h->inner);
    nt_sha512_update(&h->inner, ipad, SHA512_BLOCK);
}

static void hmac512_update(hmac512_ctx *h, const void *data, size_t len)
{
    nt_sha512_update(&h->inner, data, len);
}

static void hmac512_final(hmac512_ctx *h, unsigned char out[64])
{
    unsigned char ibuf[64];
    nt_sha512_final(&h->inner, ibuf);
    nt_sha512_ctx o;
    nt_sha512_init(&o);
    nt_sha512_update(&o, h->opad_key, SHA512_BLOCK);
    nt_sha512_update(&o, ibuf, 64);
    nt_sha512_final(&o, out);
}

static void hmac512(const unsigned char *key, size_t keylen,
                    const unsigned char *msg, size_t msglen,
                    unsigned char out[64])
{
    hmac512_ctx h;
    hmac512_init(&h, key, keylen);
    hmac512_update(&h, msg, msglen);
    hmac512_final(&h, out);
}

/* ---- PBKDF2-HMAC-SHA512 ---- */

void nt_pbkdf2_sha512(const char *pass, size_t passlen,
                      const unsigned char *salt, size_t saltlen,
                      int iterations, unsigned char *out, size_t outlen)
{
    unsigned char block[4] = {0, 0, 0, 0};
    unsigned char u[64], t[64];
    unsigned int idx = 1;
    size_t done = 0;
    size_t n;

    while (done < outlen) {
        int i, j;
        block[0] = (unsigned char)(idx >> 24);
        block[1] = (unsigned char)(idx >> 16);
        block[2] = (unsigned char)(idx >> 8);
        block[3] = (unsigned char)idx;

        {
            hmac512_ctx h;
            hmac512_init(&h, (const unsigned char *)pass, passlen);
            hmac512_update(&h, salt, saltlen);
            hmac512_update(&h, block, 4);
            hmac512_final(&h, u);
        }
        memcpy(t, u, 64);
        for (i = 1; i < iterations; i++) {
            hmac512((const unsigned char *)pass, passlen, u, 64, u);
            for (j = 0; j < 64; j++) t[j] ^= u[j];
        }
        n = outlen - done;
        if (n > 64) n = 64;
        memcpy(out + done, t, n);
        done += n;
        idx++;
    }
}

/* ---- known-answer tests ---- */

static int hex_eq(const unsigned char *a, const char *hex, size_t n)
{
    static const char digits[] = "0123456789abcdef";
    size_t i;
    for (i = 0; i < n * 2; i++) {
        char want = hex[i];
        unsigned char got = (unsigned char)((a[i / 2] >> (i % 2 ? 0 : 4)) & 0x0f);
        char found = digits[got];
        if (want >= 'A' && want <= 'F') want = (char)(want - 'A' + 'a');
        if (want != found) return 0;
    }
    return 1;
}

int nt_sha512_kat(void)
{
    static const char abc[] =
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
        "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";
    static const char empty[] =
        "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
        "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e";
    static const char pbkdf2[] =
        "d197b1b33db0143e018b12f3d1d1479e6cdebdcc97c5c0f87f6902e072f457b5";
    unsigned char d[64];

    nt_sha512("abc", 3, d);
    if (!hex_eq(d, abc, 64)) return 1;
    nt_sha512("", 0, d);
    if (!hex_eq(d, empty, 64)) return 1;
    nt_pbkdf2_sha512("password", 8, (const unsigned char *)"salt", 4,
                     4096, d, 32);
    if (!hex_eq(d, pbkdf2, 32)) return 1;
    return 0;
}
