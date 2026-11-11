/*
 * SHA-512 and PBKDF2-HMAC-SHA512.
 *
 * The native tool derives the page key itself from the md5 prefix so it can
 * run standalone under su; NDK ships no crypto library, so this is a compact
 * from-scratch implementation pinned by startup known-answer tests against
 * published digests.
 */
#ifndef NTDB_SHA512_H
#define NTDB_SHA512_H

#include <stddef.h>

typedef struct {
    unsigned long long h[8];
    unsigned long long total_len; /* message length in bytes */
    unsigned char buf[128];
    size_t buflen;
} nt_sha512_ctx;

void nt_sha512_init(nt_sha512_ctx *ctx);
void nt_sha512_update(nt_sha512_ctx *ctx, const void *data, size_t len);
void nt_sha512_final(nt_sha512_ctx *ctx, unsigned char out[64]);
void nt_sha512(const void *data, size_t len, unsigned char out[64]);

/* PBKDF2 (RFC 2898) with HMAC-SHA512, outlen bytes derived. */
void nt_pbkdf2_sha512(const char *pass, size_t passlen,
                      const unsigned char *salt, size_t saltlen,
                      int iterations, unsigned char *out, size_t outlen);

/* Returns 0 when the embedded known-answer tests all pass. */
int nt_sha512_kat(void);

#endif /* NTDB_SHA512_H */
