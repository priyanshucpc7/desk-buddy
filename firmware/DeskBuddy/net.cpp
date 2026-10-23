#include "net.h"
#include "web_ui.h"
#include "sha1.h"
#include <Preferences.h>
#include <ESPmDNS.h>
#include <esp_mac.h>
#include <esp_arduino_version.h>
#include <ctype.h>

static Preferences wifiPrefs;
static const char* kSetupOk =
    "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
    "<body style='background:#101322;color:#ffe7d1;font:16px system-ui;padding:2rem'>"
    "<h2>Saved.</h2><p>DeskBuddy is restarting and will join your WiFi.</p>"
    "<p>Then open <b>http://deskbuddy.local</b> (or the IP printed on serial).</p></body>";

void DeskNet::begin() {
  WiFi.persistent(false);
  WiFi.setHostname(DB_HOSTNAME);

  wifiPrefs.begin("wifi", true);
  String ssid = wifiPrefs.getString("ssid", "");
  String pass = wifiPrefs.getString("pass", "");
  wifiPrefs.end();
  strncpy(staSsid_, ssid.c_str(), sizeof(staSsid_) - 1);
  strncpy(staPass_, pass.c_str(), sizeof(staPass_) - 1);

  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_SOFTAP);
  snprintf(apSsid_, sizeof(apSsid_), "DeskBuddy-%02X%02X", mac[4], mac[5]);

  if (ssid.length()) {
    WiFi.mode(WIFI_AP_STA);
    WiFi.begin(ssid.c_str(), pass.c_str());
#if DB_DEBUG
    Serial.printf("[net] joining \"%s\"", ssid.c_str());
#endif
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) {
      delay(250);
#if DB_DEBUG
      Serial.print('.');
#endif
    }
    staOk = (WiFi.status() == WL_CONNECTED);
    if (staOk) WiFi.setSleep(false);
#if DB_DEBUG
    Serial.printf("\n[net] sta=%d ip=%s\n", staOk,
                  WiFi.localIP().toString().c_str());
#endif
  } else {
    WiFi.mode(WIFI_AP);
  }

  uint8_t ch = staOk ? (uint8_t)WiFi.channel() : 1;
  const char* apPass = strlen(DB_AP_PASS) >= 8 ? DB_AP_PASS : nullptr;
  WiFi.softAP(apSsid_, apPass, ch, false, 4);
  delay(120);
  dns_.start(53, "*", WiFi.softAPIP());
  dnsOn = true;

  http_.begin();
  http_.setNoDelay(true);
  node_.begin();
  node_.setNoDelay(true);

  if (MDNS.begin(DB_HOSTNAME)) {
    MDNS.addService("http", "tcp", DB_HTTP_PORT);
    MDNS.addService("deskbuddy", "tcp", DB_HTTP_PORT);
  }

#if DB_DEBUG
  Serial.printf("[net] AP \"%s\" pass=\"%s\" -> http://%s\n", apSsid_,
                apPass ? apPass : "(open)", WiFi.softAPIP().toString().c_str());
  Serial.printf("[net] servers: http=%d node=%d\n", DB_HTTP_PORT, DB_NODE_PORT);
#endif
}

void DeskNet::restartSoon(uint32_t ms) {
  restartAt = millis() + ms;
}

void DeskNet::setCredentials(const char* ssid, const char* pass) {
  if (!ssid || !*ssid) return;
  wifiPrefs.begin("wifi", false);
  wifiPrefs.putString("ssid", ssid);
  wifiPrefs.putString("pass", pass ? pass : "");
  wifiPrefs.end();
  strncpy(staSsid_, ssid, sizeof(staSsid_) - 1);
  strncpy(staPass_, pass ? pass : "", sizeof(staPass_) - 1);
  Serial.printf("[net] saved \"%s\", restarting\n", ssid);
  restartSoon(500);
}

void DeskNet::scan() {
  Serial.println("[net] scanning...");
  int n = WiFi.scanNetworks(false, false);
  for (int i = 0; i < n; i++) {
    Serial.printf("  %2d  %-28s %4d dBm  ch%-2d  enc=%d\n", i + 1,
                  WiFi.SSID(i).c_str(), (int)WiFi.RSSI(i), (int)WiFi.channel(i),
                  (int)WiFi.encryptionType(i));
  }
  WiFi.scanDelete();
  Serial.printf("[net] %d networks\n", n);
}

void DeskNet::loop() {
  if (restartAt && millis() > restartAt) {
    restartAt = 0;
    ESP.restart();
  }
  if (dnsOn) dns_.processNextRequest();

  // Keep trying the saved network in the background so a router reboot or a
  // late hotspot doesn't strand the buddy in AP-only mode.
  uint32_t now = millis();
  if (!staOk && staSsid_[0] && now - lastStaTry > 20000) {
    lastStaTry = now;
    WiFi.begin(staSsid_, staPass_);
#if DB_DEBUG
    Serial.printf("[net] retry \"%s\" ...\n", staSsid_);
#endif
  }
  if (!staOk && WiFi.status() == WL_CONNECTED) {
    staOk = true;
    WiFi.setSleep(false);
#if DB_DEBUG
    Serial.printf("[net] STA up: http://%s\n", WiFi.localIP().toString().c_str());
#endif
  }

#if ESP_ARDUINO_VERSION_MAJOR >= 3
  WiFiClient a1 = http_.accept();
  WiFiClient a2 = node_.accept();
#else
  WiFiClient a1 = http_.available() ? http_.client() : WiFiClient();
  WiFiClient a2 = node_.available() ? node_.client() : WiFiClient();
#endif

  if (a1) {
    for (auto& s : slots_) {
      if (s.kind == CK_NONE) { accept(s, a1, CK_WEB); break; }
    }
  }
  if (a2) {
    for (auto& s : slots_) {
      if (s.kind == CK_NONE) { accept(s, a2, CK_PHONE); break; }
    }
  }

  uint32_t now2 = millis();
  for (auto& s : slots_) {
    if (s.kind == CK_NONE) continue;
    if (!s.c.connected()) { closeSlot(s); continue; }

    // idle / half-open protection
    uint32_t limit = (s.kind == CK_WEB) ? 60000UL : 45000UL;
    if (now2 - s.lastRx > limit) {
#if DB_DEBUG
      Serial.printf("[net] timeout %s\n", s.kind == CK_WEB ? "web" : "phone");
#endif
      closeSlot(s);
      continue;
    }

    while (s.c.available()) {
      int b = s.c.read();
      if (b < 0) break;
      s.lastRx = now2;
      if (s.kind == CK_PHONE) {
        if (b == '\n') {
          handleLine(s);
        } else if (s.len < sizeof(s.buf) - 1) {
          s.buf[s.len++] = (char)b;
        } else {
          s.len = 0;  // oversized line, drop it
        }
      } else if (s.ws) {
        handleWsByte(s, (uint8_t)b);
      } else {
        if (s.len < sizeof(s.buf) - 1) s.buf[s.len++] = (char)b;
        if (s.len >= 4 && s.buf[s.len - 1] == '\n' && s.buf[s.len - 2] == '\r' &&
            s.buf[s.len - 3] == '\n' && s.buf[s.len - 4] == '\r') {
          s.buf[s.len] = 0;
          handleHttp(s);
        } else if (s.len >= sizeof(s.buf) - 1) {
          closeSlot(s);
          break;
        }
      }
    }
  }
}

void DeskNet::accept(Slot& s, WiFiClient& c, uint8_t kind) {
  if (!c) return;
  s.c = c;
  s.c.setNoDelay(true);
  s.kind = kind;
  s.ws = 0;
  s.len = 0;
  s.headersDone = false;
  s.msgLen = 0;
  s.st = 0;
  s.hdrLen = 0;
  s.got = 0;
  s.lastRx = millis();
  s.openedAt = s.lastRx;
  if (kind == CK_PHONE) {
    s.c.printf("{\"t\":\"hello\",\"ok\":true,\"fw\":\"%s\"}\n", DB_VERSION);
    if (conFn) conFn(CK_PHONE);
#if DB_DEBUG
    Serial.printf("[net] phone connected from %s\n", s.c.remoteIP().toString().c_str());
#endif
  }
}

void DeskNet::closeSlot(Slot& s) {
  if (s.kind == CK_NONE) return;
  uint8_t was = s.kind;
  s.c.stop();
  s.kind = CK_NONE;
  s.ws = 0;
  s.len = 0;
  s.msgLen = 0;
  if (disFn) disFn(was);
#if DB_DEBUG
  Serial.printf("[net] %s disconnected\n", was == CK_WEB ? "web" : "phone");
#endif
}

void DeskNet::kickAll() {
  for (auto& s : slots_) closeSlot(s);
}

int DeskNet::webCount() {
  int n = 0;
  for (auto& s : slots_) if (s.kind == CK_WEB && s.ws) n++;
  return n;
}

int DeskNet::phoneCount() {
  int n = 0;
  for (auto& s : slots_) if (s.kind == CK_PHONE) n++;
  return n;
}

void DeskNet::broadcastAll(const char* json) {
  broadcastWeb(json);
  broadcastPhone(json);
}

void DeskNet::broadcastWeb(const char* json) {
  if (!json) return;
  size_t n = strlen(json);
  for (auto& s : slots_) {
    if (s.kind == CK_WEB && s.ws) wsSend(s, json, n);
  }
}

void DeskNet::broadcastPhone(const char* json) {
  if (!json) return;
  size_t n = strlen(json);
  for (auto& s : slots_) {
    if (s.kind == CK_PHONE && s.c.connected()) {
      // NetworkClient has no meaningful availableForWrite(); a line here is
      // tiny, so a blocking write is fine (and far better than dropping it).
      if (s.c.write((const uint8_t*)json, n) != n) continue;
      s.c.write('\n');
    }
  }
}

void DeskNet::wsSend(Slot& s, const char* p, size_t n) {
  if (!s.c.connected()) return;
  uint8_t h[4];
  h[0] = 0x81;
  if (n < 126) {
    h[1] = (uint8_t)n;
    s.c.write(h, 2);
  } else if (n < 65536) {
    h[1] = 126;
    h[2] = (uint8_t)(n >> 8);
    h[3] = (uint8_t)(n & 0xff);
    s.c.write(h, 4);
  } else {
    return;
  }
  s.c.write((const uint8_t*)p, n);
}

void DeskNet::handleLine(Slot& s) {
  s.buf[s.len] = 0;
  if (s.len && s.buf[s.len - 1] == '\r') s.buf[--s.len] = 0;
  if (s.len > 2 && msgFn) msgFn(CK_PHONE, s.buf);
  s.len = 0;
}

// ---------------------------------------------------------------------------
// WebSocket framing (server side, text frames in/out)
// ---------------------------------------------------------------------------
void DeskNet::wsFrameDone(Slot& s) {
  uint8_t op = s.opcode;
  bool fin = s.fin;
  s.st = 0;
  s.hdrLen = 0;
  s.got = 0;
  s.maskPos = 0;
  if (op == 8) { closeSlot(s); return; }
  if (op == 9 || op == 10) { s.msgLen = 0; return; }  // ping/pong payload
  if (fin && (op == 1 || op == 0)) handleWsMessage(s);
  if (fin) s.msgLen = 0;
}

void DeskNet::handleWsByte(Slot& s, uint8_t b) {
  switch (s.st) {
    case 0:
      s.hdr[s.hdrLen++] = b;
      if (s.hdrLen == 2) {
        s.fin = (s.hdr[0] & 0x80) != 0;
        s.opcode = s.hdr[0] & 0x0f;
        s.masked = (s.hdr[1] & 0x80) != 0;
        uint8_t l = s.hdr[1] & 0x7f;
        s.hdrLen = 0;
        s.got = 0;
        s.maskPos = 0;
        if (l < 126) {
          s.need = l;
          s.st = s.masked ? 2 : 3;
        } else if (l == 126) {
          s.st = 4;
        } else {
          s.st = 5;  // 64-bit length, expect small messages only
        }
      }
      break;

    case 2:  // masking key
      s.mask[s.maskPos++] = b;
      if (s.maskPos == 4) {
        s.maskPos = 0;
        s.st = 3;
        if (s.need == 0) wsFrameDone(s);
      }
      break;

    case 4:  // 16-bit length
      s.hdr[s.hdrLen++] = b;
      if (s.hdrLen == 2) {
        s.need = ((uint32_t)s.hdr[0] << 8) | s.hdr[1];
        s.hdrLen = 0;
        s.st = s.masked ? 2 : 3;
        s.maskPos = 0;
        if (s.need > 1024) { closeSlot(s); return; }
        if (s.need == 0 && s.st == 3) wsFrameDone(s);
      }
      break;

    case 5:  // 64-bit length
      s.hdr[s.hdrLen++] = b;
      if (s.hdrLen == 8) {
        uint64_t big = 0;
        for (int i = 0; i < 8; i++) big = (big << 8) | s.hdr[i];
        s.hdrLen = 0;
        if (big > 1024) { closeSlot(s); return; }
        s.need = (uint32_t)big;
        s.st = s.masked ? 2 : 3;
        s.maskPos = 0;
        if (s.need == 0 && s.st == 3) wsFrameDone(s);
      }
      break;

    default: {  // 3 = payload
      uint8_t v = s.masked ? (uint8_t)(b ^ s.mask[s.maskPos & 3]) : b;
      s.maskPos++;
      if ((s.opcode == 1 || s.opcode == 0) && s.msgLen < sizeof(s.msg) - 1) {
        s.msg[s.msgLen++] = (char)v;
      }
      s.got++;
      if (s.got >= s.need) wsFrameDone(s);
      break;
    }
  }
}

void DeskNet::handleWsMessage(Slot& s) {
  s.msg[s.msgLen] = 0;
  if (s.msgLen > 1 && msgFn) msgFn(CK_WEB, s.msg);
  s.msgLen = 0;
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------
void DeskNet::handleHttp(Slot& s) {
  char method[8] = {0}, target[192] = {0};
  sscanf(s.buf, "%7s %191s", method, target);
  if (!target[0]) { closeSlot(s); return; }

  char path[192];
  strncpy(path, target, sizeof(path) - 1);
  path[sizeof(path) - 1] = 0;
  char* q = strchr(path, '?');
  const char* query = nullptr;
  if (q) { *q = 0; query = q + 1; }

  // WebSocket upgrade?
  const char* keyHdr = strstr(s.buf, "Sec-WebSocket-Key:");
  const char* upg = strstr(s.buf, "Upgrade:");
  if (keyHdr && upg) {
    const char* k = keyHdr + strlen("Sec-WebSocket-Key:");
    while (*k == ' ') k++;
    char key[64];
    size_t i = 0;
    while (k[i] && k[i] != '\r' && k[i] != '\n' && i < sizeof(key) - 1) {
      key[i] = k[i];
      i++;
    }
    key[i] = 0;
    char acc[40];
    dbWsAccept(key, acc, sizeof(acc));
    s.c.printf(
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\nConnection: Upgrade\r\n"
        "Sec-WebSocket-Accept: %s\r\n\r\n",
        acc);
    s.ws = 1;
    s.kind = CK_WEB;
    s.len = 0;
    s.st = 0;
    s.msgLen = 0;
    if (conFn) conFn(CK_WEB);
#if DB_DEBUG
    Serial.printf("[net] web connected from %s (%d total)\n",
                  s.c.remoteIP().toString().c_str(), webCount());
#endif
    return;
  }

  if (!strcmp(path, "/api/status")) { sendStatus(s); return; }
  if (!strcmp(path, "/setup")) { handleSetup(s, query); return; }
  if (!strcmp(path, "/api/reboot")) {
    sendText(s.c, 200, "application/json", "{\"ok\":true,\"reboot\":true}");
    restartSoon(700);
    return;
  }
  if (!strcmp(path, "/") || !strcmp(path, "/index.html")) { sendPage(s); return; }
  if (!strcmp(path, "/favicon.ico")) {
    s.c.print("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n");
    s.c.flush();
    s.c.stop();
    s.kind = CK_NONE;
    return;
  }
  // captive-portal probes get bounced to the face
  if (!strncmp(path, "/generate_204", 13) || !strncmp(path, "/hotspot-detect", 15) ||
      !strncmp(path, "/connecttest", 12) || !strncmp(path, "/ncsi", 5)) {
    s.c.printf("HTTP/1.1 302 Found\r\nLocation: http://%s/\r\nContent-Length: 0\r\n"
               "Connection: close\r\n\r\n", WiFi.softAPIP().toString().c_str());
    s.c.flush();
    s.c.stop();
    s.kind = CK_NONE;
    return;
  }
  sendText(s.c, 404, "text/plain", "not here");
}

void DeskNet::sendPage(Slot& s) {
  uint32_t n = WEB_UI_GZ_LEN;
  s.c.printf(
      "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
      "Content-Encoding: gzip\r\nContent-Length: %u\r\n"
      "Cache-Control: no-store\r\nConnection: close\r\n\r\n",
      (unsigned)n);
  s.c.write(WEB_UI_GZ, n);
  s.c.flush();
  delay(4);
  s.c.stop();
  s.kind = CK_NONE;
}

void DeskNet::sendJson(WiFiClient& c, const char* body) {
  sendText(c, 200, "application/json", body);
}

void DeskNet::sendText(WiFiClient& c, int code, const char* type, const char* body) {
  size_t n = strlen(body);
  c.printf("HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %u\r\n"
           "Cache-Control: no-store\r\nConnection: close\r\n\r\n",
           code, code == 200 ? "OK" : "ERR", type, (unsigned)n);
  if (code != 204) c.write((const uint8_t*)body, n);
  c.flush();
  delay(3);
  c.stop();
}

void DeskNet::sendStatus(Slot& s) {
  char extra[640] = {0};
  if (statusFn) statusFn(extra, sizeof(extra));
  char body[900];
  snprintf(body, sizeof(body),
           "{\"ok\":true,\"fw\":\"%s\",\"ap\":%d,\"host\":\"%s.local\","
           "\"staIp\":\"%s\",\"apIp\":\"%s\",\"apSsid\":\"%s\",\"apPass\":\"%s\","
           "\"web\":%d,\"phone\":%d,\"uptime\":%lu%s}",
           DB_VERSION, apOnly() ? 1 : 0, DB_HOSTNAME,
           staIp().c_str(), apIp().c_str(), apSsid_, DB_AP_PASS,
           webCount(), phoneCount(), (unsigned long)uptime(), extra);
  sendJson(s.c, body);
  s.c.stop();
  s.kind = CK_NONE;
}

static void urlDecode(const char* in, char* out, size_t n) {
  size_t o = 0;
  for (size_t i = 0; in[i] && o + 1 < n; i++) {
    if (in[i] == '%' && isxdigit((unsigned char)in[i + 1]) && isxdigit((unsigned char)in[i + 2])) {
      char hex[3] = {in[i + 1], in[i + 2], 0};
      out[o++] = (char)strtol(hex, nullptr, 16);
      i += 2;
    } else if (in[i] == '+') {
      out[o++] = ' ';
    } else {
      out[o++] = in[i];
    }
  }
  out[o] = 0;
}

void DeskNet::parseQuery(const char* q, const char* key, char* out, size_t n) {
  out[0] = 0;
  if (!q) return;
  size_t kl = strlen(key);
  const char* p = q;
  while (*p) {
    if (!strncmp(p, key, kl) && p[kl] == '=') {
      const char* v = p + kl + 1;
      char raw[128];
      size_t i = 0;
      while (v[i] && v[i] != '&' && i < sizeof(raw) - 1) { raw[i] = v[i]; i++; }
      raw[i] = 0;
      urlDecode(raw, out, n);
      return;
    }
    p = strchr(p, '&');
    if (!p) return;
    p++;
  }
}

void DeskNet::handleSetup(Slot& s, const char* query) {
  char ssid[64], pass[80], name[24], forget[8];
  parseQuery(query, "ssid", ssid, sizeof(ssid));
  parseQuery(query, "pass", pass, sizeof(pass));
  parseQuery(query, "name", name, sizeof(name));
  parseQuery(query, "forget", forget, sizeof(forget));

  if (forget[0] == '1') {
    wifiPrefs.begin("wifi", false);
    wifiPrefs.clear();
    wifiPrefs.end();
    sendText(s.c, 200, "text/html", kSetupOk);
    restartSoon(900);
    return;
  }
  if (!ssid[0]) {
    sendText(s.c, 400, "text/plain", "need ?ssid=...&pass=...");
    return;
  }
  wifiPrefs.begin("wifi", false);
  wifiPrefs.putString("ssid", ssid);
  wifiPrefs.putString("pass", pass);
  wifiPrefs.end();

  if (msgFn && name[0]) {
    char cmd[64];
    snprintf(cmd, sizeof(cmd), "{\"t\":\"cmd\",\"c\":\"rename\",\"v\":\"%s\"}", name);
    msgFn(CK_WEB, cmd);
  }
  sendText(s.c, 200, "text/html", kSetupOk);
  restartSoon(900);
}
