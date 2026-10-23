// ---------------------------------------------------------------------------
// A tiny flat-JSON reader. Good enough for the handful of small messages the
// buddy exchanges ({"t":"sensors","ax":0.1,...}). No dynamic allocation.
// ---------------------------------------------------------------------------
#pragma once
#include <Arduino.h>
#include <math.h>
#include <stdlib.h>

// Returns a pointer to the first character of the value for `key`.
inline const char* jfind(const char* s, const char* key) {
  if (!s || !key || !*key) return nullptr;
  const size_t kl = strlen(key);
  const char* p = s;
  while ((p = strchr(p, '"')) != nullptr) {
    p++;
    if (strncmp(p, key, kl) == 0 && p[kl] == '"') {
      const char* q = p + kl + 1;
      while (*q == ' ' || *q == '\t') q++;
      if (*q == ':') {
        q++;
        while (*q == ' ' || *q == '\t') q++;
        return q;
      }
    }
  }
  return nullptr;
}

inline bool jhas(const char* s, const char* key) { return jfind(s, key) != nullptr; }

inline float jnum(const char* s, const char* key, float def = NAN) {
  const char* p = jfind(s, key);
  if (!p) return def;
  if (*p == '"') p++;
  return (float)atof(p);
}

inline bool jbool(const char* s, const char* key, bool def = false) {
  const char* p = jfind(s, key);
  if (!p) return def;
  if (*p == 't' || *p == '1') return true;
  if (*p == 'f' || *p == '0') return false;
  return def;
}

inline void jstr(const char* s, const char* key, char* out, size_t n) {
  if (!out || n == 0) return;
  out[0] = '\0';
  const char* p = jfind(s, key);
  if (!p) return;
  if (*p == '"') {
    p++;
    size_t i = 0;
    while (*p && *p != '"' && i < n - 1) {
      if (*p == '\\' && p[1]) p++;
      out[i++] = *p++;
    }
    out[i] = '\0';
  } else {
    size_t i = 0;
    while (p[i] && p[i] != ',' && p[i] != '}' && p[i] != ' ' && i < n - 1) {
      out[i] = p[i];
      i++;
    }
    out[i] = '\0';
  }
}

// Copy `in` into `out` escaping characters that would break a JSON string.
inline void jescape(const char* in, char* out, size_t n) {
  if (!out || n == 0) return;
  size_t o = 0;
  for (const char* p = in; p && *p && o + 6 < n; p++) {
    unsigned char c = (unsigned char)*p;
    if (c == '"' || c == '\\') {
      out[o++] = '\\';
      out[o++] = (char)c;
    } else if (c == '\n') {
      out[o++] = '\\'; out[o++] = 'n';
    } else if (c < 0x20) {
      o += snprintf(out + o, n - o, "\\u%04x", c);
    } else {
      out[o++] = (char)c;
    }
  }
  out[o] = '\0';
}
