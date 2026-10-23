// ---------------------------------------------------------------------------
// DeskNet: WiFi + a tiny hand-rolled HTTP/WebSocket server for the browser UI
// and a newline-delimited JSON socket for the Android sensor node.
//
// Deliberately dependency free (no AsyncWebServer, no ArduinoJson) so the
// firmware builds identically on any machine and on GitHub Actions.
// ---------------------------------------------------------------------------
#pragma once
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClient.h>
#include <DNSServer.h>
#include "config.h"

#define DB_MAX_SLOTS   6
#define DB_MAX_WEB     4
#define DB_MAX_PHONE   3

enum ClientKind : uint8_t { CK_NONE = 0, CK_WEB = 1, CK_PHONE = 2 };

class DeskNet {
 public:
  void begin();
  void loop();

  void broadcastAll(const char* json);
  void broadcastWeb(const char* json);
  void broadcastPhone(const char* json);

  int webCount();
  int phoneCount();
  bool apOnly() const { return !staOk; }
  bool hasSta() const { return staOk; }
  const char* apSsid() const { return apSsid_; }
  const char* apPass() const { return DB_AP_PASS; }
  String staIp() const { return staOk ? WiFi.localIP().toString() : String(""); }
  String apIp() const { return WiFi.softAPIP().toString(); }
  uint32_t uptime() const { return millis() / 1000UL; }

  void onMessage(void (*fn)(uint8_t kind, const char* json)) { msgFn = fn; }
  void onConnect(void (*fn)(uint8_t kind)) { conFn = fn; }
  void onDisconnect(void (*fn)(uint8_t kind)) { disFn = fn; }
  void onStatus(void (*fn)(char* out, size_t n)) { statusFn = fn; }

  void kickAll();
  void restartSoon(uint32_t ms = 600);
  void setCredentials(const char* ssid, const char* pass);
  void scan();

 private:
  struct Slot {
    WiFiClient c;
    uint8_t kind = CK_NONE;
    uint8_t ws = 0;
    uint32_t lastRx = 0;
    uint32_t openedAt = 0;

    // request / line buffer
    char buf[1024];
    size_t len = 0;
    bool headersDone = false;

    // websocket frame parser
    uint8_t st = 0;
    uint8_t hdr[8];
    uint8_t hdrLen = 0;
    uint8_t hdrNeed = 0;
    uint8_t opcode = 0;
    bool fin = true;
    bool masked = false;
    uint8_t mask[4];
    uint8_t maskPos = 0;
    uint32_t need = 0;
    uint32_t got = 0;
    char msg[512];
    size_t msgLen = 0;
  };

  Slot slots_[DB_MAX_SLOTS];
  WiFiServer http_{DB_HTTP_PORT};
  WiFiServer node_{DB_NODE_PORT};
  DNSServer dns_;
  bool staOk = false;
  bool dnsOn = false;
  char apSsid_[24] = "DeskBuddy";
  char staSsid_[40] = {0};
  char staPass_[72] = {0};
  uint32_t restartAt = 0;
  uint32_t lastStaTry = 0;

  void (*msgFn)(uint8_t, const char*) = nullptr;
  void (*conFn)(uint8_t) = nullptr;
  void (*disFn)(uint8_t) = nullptr;
  void (*statusFn)(char*, size_t) = nullptr;

  void accept(Slot& s, WiFiClient& c, uint8_t kind);
  void closeSlot(Slot& s);
  void handleHttp(Slot& s);
  void handleWsByte(Slot& s, uint8_t b);
  void wsFrameDone(Slot& s);
  void handleWsMessage(Slot& s);
  void wsSend(Slot& s, const char* p, size_t n);
  void sendPage(Slot& s);
  void sendStatus(Slot& s);
  void sendJson(WiFiClient& c, const char* body);
  void sendText(WiFiClient& c, int code, const char* type, const char* body);
  void handleSetup(Slot& s, const char* query);
  void handleLine(Slot& s);
  void parseQuery(const char* q, const char* key, char* out, size_t n);
};
