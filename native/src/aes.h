/*
 * AES-256-CBC decryption for the QQ nt_msg.db reader.
 *
 * QQ's database pages are AES-256-CBC with a per-page IV; only decryption is
 * needed. The S-boxes are built at startup from GF(2^8) inverses plus the
 * FIPS-197 affine transform instead of being transcribed, and nt_aes256_kat()
 * checks the result against the NIST SP 800-38A F.2.5 known-answer vector
 * before any real data is touched.
 */
#ifndef NTDB_AES_H
#define NTDB_AES_H

#include <stddef.h>

/* Expanded round keys: 60 words for AES-256 (14 rounds). */
typedef struct {
    unsigned char rk[240];
} nt_aes256_key;

/* Derives round keys from a 32-byte key. Builds the S-box tables once. */
void nt_aes256_setkey(nt_aes256_key *key, const unsigned char raw[32]);

/* Decrypts one 16-byte block. */
void nt_aes256_decrypt_block(const nt_aes256_key *key,
                             const unsigned char in[16],
                             unsigned char out[16]);

/*
 * CBC-decrypts len bytes (a multiple of 16). iv holds the 128-bit initial
 * vector and is not modified. in and out may alias.
 */
void nt_aes256_cbc_decrypt(const nt_aes256_key *key, const unsigned char iv[16],
                           const unsigned char *in, unsigned char *out,
                           size_t len);

/* Returns 0 when the NIST known-answer test passes, non-zero otherwise. */
int nt_aes256_kat(void);

#endif /* NTDB_AES_H */
