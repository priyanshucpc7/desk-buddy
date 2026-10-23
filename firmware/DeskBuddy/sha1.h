// ---------------------------------------------------------------------------
// Minimal SHA-1 + base64, used only for the WebSocket opening handshake.
// Kept in-tree so the firmware has zero external library dependencies.
// ---------------------------------------------------------------------------
#pragma once
#include <Arduino.h>

struct DbSha1 {
  uint32_t h[5];
  uint64_t len;
  uint8_t buf[64];
  size_t n;

  DbSha1() { reset(); }

  void reset() {
    h[0] = 0x67452301; h[1] = 0xEFCDAB89; h[2] = 0x98BADCFE;
    h[3] = 0x10325476; h[4] = 0xC3D2E1F0;
    len = 0; n = 0;
  }

  static uint32_t rol(uint32_t v, int b) { return (v << b) | (v >> (32 - b)); }

  void block(const uint8_t* p) {
    uint32_t w[80];
    for (int i = 0; i < 16; i++)
      w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
             ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
    for (int i = 16; i < 80; i++)
      w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);

    uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
    for (int i = 0; i < 80; i++) {
      uint32_t f, k;
      if (i < 20)      { f = (b & c) | (~b & d);            k = 0x5A827999; }
      else if (i < 40) { f = b ^ c ^ d;                     k = 0x6ED9EBA1; }
      else if (i < 60) { f = (b & c) | (b & d) | (c & d);   k = 0x8F1BBCDC; }
      else             { f = b ^ c ^ d;                     k = 0xCA62C1D6; }
      uint32_t t = rol(a, 5) + f + e + k + w[i];
      e = d; d = c; c = rol(b, 30); b = a; a = t;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
  }

  void update(const uint8_t* p, size_t l) {
    len += (uint64_t)l * 8;
    while (l) {
      size_t take = 64 - n;
      if (take > l) take = l;
      memcpy(buf + n, p, take);
      n += take; p += take; l -= take;
      if (n == 64) { block(buf); n = 0; }
    }
  }

  void final(uint8_t out[20]) {
    uint64_t bits = len;
    uint8_t pad = 0x80;
    update(&pad, 1);
    uint8_t z = 0;
    while (n != 56) update(&z, 1);
    uint8_t lb[8];
    for (int i = 0; i < 8; i++) lb[i] = (uint8_t)(bits >> (56 - i * 8));
    // length bytes are appended without being counted again
    memcpy(buf + n, lb, 8);
    block(buf);
    n = 0;
    for (int i = 0; i < 5; i++) {
      out[i * 4]     = (uint8_t)(h[i] >> 24);
      out[i * 4 + 1] = (uint8_t)(h[i] >> 16);
      out[i * 4 + 2] = (uint8_t)(h[i] >> 8);
      out[i * 4 + 3] = (uint8_t)h[i];
    }
  }
};

static const char DB_B64[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

inline void dbBase64(const uint8_t* in, size_t len, char* out) {
  size_t o = 0;
  for (size_t i = 0; i < len; i += 3) {
    uint32_t v = (uint32_t)in[i] << 16;
    if (i + 1 < len) v |= (uint32_t)in[i + 1] << 8;
    if (i + 2 < len) v |= in[i + 2];
    out[o++] = DB_B64[(v >> 18) & 63];
    out[o++] = DB_B64[(v >> 12) & 63];
    out[o++] = (i + 1 < len) ? DB_B64[(v >> 6) & 63] : '=';
    out[o++] = (i + 2 < len) ? DB_B64[v & 63] : '=';
  }
  out[o] = '\0';
}

// base64(sha1(key + RFC6455 GUID))
inline void dbWsAccept(const char* key, char* out, size_t n) {
  char tmp[160];
  snprintf(tmp, sizeof(tmp), "%s258EAFA5-E914-47DA-95CA-C5AB0DC85B11", key);
  DbSha1 sha;
  sha.update((const uint8_t*)tmp, strlen(tmp));
  uint8_t digest[20];
  sha.final(digest);
  char b64[32];
  dbBase64(digest, 20, b64);
  strncpy(out, b64, n - 1);
  out[n - 1] = '\0';
}
