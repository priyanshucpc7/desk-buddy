// ---------------------------------------------------------------------------
// DeskBuddy - a display-less ESP32-S3 desk pet.
//
//   * the ESP32 is the brain
//   * your laptop browser is the face            -> http://deskbuddy.local
//   * your old Android phone is the eyes, hands   (DeskBuddy app, raw TCP :81)
//     and body: it renders the face and streams its sensors back
//
// No display, no sensors and no extra hardware are required on the board.
// ---------------------------------------------------------------------------
#include <Arduino.h>
#include "config.h"
#include "brain.h"
#include "net.h"
#include "json.h"
#include <ArduinoOTA.h>

#if DB_USE_LED
#include <Adafruit_NeoPixel.h>
Adafruit_NeoPixel strip(1, DB_LED_PIN, NEO_GRB + NEO_KHZ800);
#endif

Brain brain;
DeskNet net;

static uint32_t lastStatePush = 0;
static uint32_t lastSensorPush = 0;
static float accelPrev = 9.81f;
static uint32_t lastJolt = 0;
static uint8_t joltCount = 0;
static uint32_t lastLuxReport = 0;

// ---------------------------------------------------------------------------
// outbound
// ---------------------------------------------------------------------------
static void emit(const char* json) {
  net.broadcastAll(json);
}

static void pushState() {
  char st[420];
  brain.stateJson(st, sizeof(st));
  net.broadcastAll(st);
}

static void statusExtra(char* out, size_t n) {
  char st[440], se[160];
  brain.stateJson(st, sizeof(st));
  brain.sensorJson(se, sizeof(se));
  snprintf(out, n, ",\"state\":%s,\"sensors\":%s", st, se);
}

static void greet(uint8_t kind) {
  char st[420];
  brain.stateJson(st, sizeof(st));
  char msg[620];
  snprintf(msg, sizeof(msg),
           "{\"t\":\"hello\",\"fw\":\"%s\",\"kind\":\"%s\",\"name\":\"%s\","
           "\"ap\":%d,\"apSsid\":\"%s\",\"apPass\":\"%s\",\"staIp\":\"%s\","
           "\"state\":%s}",
           DB_VERSION, kind == CK_WEB ? "web" : "phone", brain.name(),
           net.apOnly() ? 1 : 0, net.apSsid(), net.apPass(), net.staIp().c_str(), st);
  if (kind == CK_WEB) net.broadcastWeb(msg);
  else net.broadcastPhone(msg);
}

// ---------------------------------------------------------------------------
// input
// ---------------------------------------------------------------------------
static void handleCommand(const char* json) {
  char cmd[24] = {0}, val[24] = {0};
  jstr(json, "c", cmd, sizeof(cmd));
  jstr(json, "v", val, sizeof(val));

  if (!strcmp(cmd, "feed")) brain.feed();
  else if (!strcmp(cmd, "play")) brain.play();
  else if (!strcmp(cmd, "sleep")) { if (!brain.sleeping()) brain.toggleSleep(); }
  else if (!strcmp(cmd, "wake")) { if (brain.sleeping()) brain.toggleSleep(); }
  else if (!strcmp(cmd, "toggle")) brain.toggleSleep();
  else if (!strcmp(cmd, "pet")) brain.pet(1.0f);
  else if (!strcmp(cmd, "poke")) brain.poke();
  else if (!strcmp(cmd, "rename")) { if (val[0]) brain.rename(val); }
  else if (!strcmp(cmd, "reset")) brain.resetStats();
  else if (!strcmp(cmd, "status")) pushState();
  else if (!strcmp(cmd, "say")) { if (val[0]) { char b[160]; snprintf(b, sizeof(b), "{\"t\":\"say\",\"text\":\"%s\"}", val); net.broadcastAll(b); } }
#if DB_DEBUG
  Serial.printf("[cmd] %s %s\n", cmd, val);
#endif
}

static void handleSensors(const char* json) {
  float ax = jnum(json, "ax", 0), ay = jnum(json, "ay", 0), az = jnum(json, "az", 0);
  float lux = jnum(json, "lux", -1);
  float mic = jnum(json, "mic", 0);
  float batt = jnum(json, "batt", -1);

  brain.setSensors(ax, ay, az, lux, mic, batt);

  // jolt / shake detection: a real shake hits +/- several m/s2 repeatedly
  float mag = sqrtf(ax * ax + ay * ay + az * az);
  float dev = fabsf(mag - 9.81f);
  uint32_t now = millis();
  if (dev > 5.0f) {
    if (now - lastJolt > 260) joltCount = 0;
    lastJolt = now;
    if (joltCount < 6) joltCount++;
    if (joltCount >= 2) {
      joltCount = 0;
      brain.shake(constrain(dev / 12.0f, 0.3f, 1.0f));
    }
  }
  accelPrev = mag;

  if (mic > 0.01f) brain.suddenNoise(mic);
  if (lux >= 0 && now - lastLuxReport > 2000) {
    lastLuxReport = now;
    brain.brightness(lux);
  }
}

static void onMessage(uint8_t kind, const char* json) {
  char t[16] = {0};
  jstr(json, "t", t, sizeof(t));
  if (!strcmp(t, "sensors")) {
    handleSensors(json);
  } else if (!strcmp(t, "touch")) {
    char kindv[12] = {0};
    jstr(json, "kind", kindv, sizeof(kindv));
    float x = jnum(json, "x", -1), y = jnum(json, "y", -1);
    float v = jnum(json, "v", 1.0f);
    if (!strcmp(kindv, "pet")) brain.pet(v);
    else brain.poke(x, y);
  } else if (!strcmp(t, "cmd")) {
    handleCommand(json);
  } else if (!strcmp(t, "ping")) {
    // keepalive only
  } else if (!strcmp(t, "hello")) {
    greet(kind);
  }
}

static void onConnect(uint8_t kind) {
  greet(kind);
  if (kind == CK_PHONE) {
    static bool greeted = false;
    if (!greeted) {
      greeted = true;
      brain.pet(0.5f);
    }
  }
  pushState();
}

static void onDisconnect(uint8_t kind) {
  (void)kind;
}

// ---------------------------------------------------------------------------
// hardware extras
// ---------------------------------------------------------------------------
#if DB_USE_LED
static void ledTick(uint32_t now) {
  static uint32_t last = 0;
  if (now - last < 60) return;
  last = now;
  uint8_t r = 0, g = 0, b = 0;
  float pulse = 0.6f + 0.4f * sinf(now / 900.0f);
  switch (brain.mood()) {
    case MOOD_HAPPY:    r = 255; g = 170; b = 60; break;
    case MOOD_EXCITED:  r = 255; g = 90;  b = 140; break;
    case MOOD_LOVE:     r = 255; g = 60;  b = 110; break;
    case MOOD_SLEEPING:
    case MOOD_SLEEPY:   r = 30;  g = 40;  b = 120; break;
    case MOOD_HUNGRY:   r = 255; g = 120; b = 0; break;
    case MOOD_ANNOYED:  r = 255; g = 40;  b = 20; break;
    case MOOD_DIZZY:    r = 140; g = 60;  b = 255; break;
    default:            r = 120; g = 200; b = 255; break;
  }
  strip.setPixelColor(0, strip.Color((uint8_t)(r * pulse), (uint8_t)(g * pulse),
                                     (uint8_t)(b * pulse)));
  strip.show();
}
#endif

#if DB_USE_BUZZER
static void buzz(uint16_t freq, uint16_t ms) {
  ledcWriteTone(DB_BUZZER_PIN, freq);
  delay(ms);
  ledcWriteTone(DB_BUZZER_PIN, 0);
}
#endif

static void buttonTick(uint32_t now) {
#if DB_BUTTON_PIN >= 0
  static bool last = true;
  static uint32_t downAt = 0;
  static bool longFired = false;
  bool v = digitalRead(DB_BUTTON_PIN);  // active low
  if (last && !v) {
    downAt = now;
    longFired = false;
  } else if (!last && v) {
    uint32_t held = now - downAt;
    if (!longFired && held < 900) brain.pet(1.0f);
  } else if (!v && !longFired && now - downAt > 1200) {
    longFired = true;
    brain.toggleSleep();
  }
  last = v;
#endif
}

static void serialTick() {
  static char line[96];
  static size_t len = 0;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      line[len] = 0;
      if (len > 0) {
        if (!strcmp(line, "feed")) brain.feed();
        else if (!strcmp(line, "play")) brain.play();
        else if (!strcmp(line, "sleep")) brain.toggleSleep();
        else if (!strcmp(line, "poke")) brain.poke();
        else if (!strcmp(line, "pet")) brain.pet(1.0f);
        else if (!strcmp(line, "shake")) brain.shake(1.0f);
        else if (!strcmp(line, "noise")) brain.suddenNoise(0.9f);
        else if (!strcmp(line, "reset")) brain.resetStats();
        else if (!strcmp(line, "status")) {
          char st[440];
          brain.stateJson(st, sizeof(st));
          Serial.println(st);
          pushState();
        }
        else if (!strcmp(line, "help"))
          Serial.println("cmds: status feed play sleep poke pet shake noise reset | name <x> | wifi | scan | reboot");
        else if (!strncmp(line, "name ", 5)) brain.rename(line + 5);
        else if (!strncmp(line, "wifi ", 5)) {
          char* bar = strchr(line + 5, '|');
          if (bar) {
            *bar = 0;
            net.setCredentials(line + 5, bar + 1);
            Serial.println("saved - rebooting into that network");
          } else {
            Serial.println("usage: wifi <ssid>|<password>");
          }
        } else if (!strcmp(line, "scan")) {
          net.scan();
        } else if (!strcmp(line, "reboot")) {
          Serial.println("rebooting");
          delay(100);
          ESP.restart();
        } else if (!strcmp(line, "wifi")) {
          Serial.printf("AP %s / %s  http://%s\n", net.apSsid(), net.apPass(),
                        net.apIp().c_str());
          Serial.printf("STA %s  http://%s\n", net.hasSta() ? "up" : "down",
                        net.staIp().c_str());
        } else {
          Serial.printf("?\n");
        }
      }
      len = 0;
    } else if (len < sizeof(line) - 1) {
      line[len++] = c;
    }
  }
}

// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(350);
#if DB_DEBUG
  Serial.println();
  Serial.println("  .----------------.   DeskBuddy " DB_VERSION);
  Serial.println("  |  o          o  |   no screen, no sensors, still alive");
  Serial.println("  '----------------'");
#endif

#if DB_BUTTON_PIN >= 0
  pinMode(DB_BUTTON_PIN, INPUT_PULLUP);
#endif
#if DB_USE_LED
  strip.begin();
  strip.setBrightness(60);
  strip.show();
#endif
#if DB_USE_BUZZER
  ledcAttach(DB_BUZZER_PIN, 2000, 8);
#endif

  brain.begin();
  brain.setEmitter(emit);
  net.onMessage(onMessage);
  net.onConnect(onConnect);
  net.onDisconnect(onDisconnect);
  net.onStatus(statusExtra);
  net.begin();

#if DB_OTA
  ArduinoOTA.setHostname(DB_HOSTNAME);
  ArduinoOTA.onStart([]() { Serial.println("[ota] update starting"); });
  ArduinoOTA.onEnd([]() { Serial.println("[ota] done, rebooting"); });
  ArduinoOTA.begin();
  Serial.println("[ota] arduino-ota ready on port 3232");
#endif

  pushState();
#if DB_DEBUG
  Serial.printf("[buddy] %s is awake. mood=%s hap=%.0f en=%.0f full=%.0f\n", brain.name(),
                brain.moodName(brain.mood()), brain.debugHappy(), brain.debugEnergy(),
                brain.debugFull());
#endif
}

void loop() {
  uint32_t now = millis();
  net.loop();
  brain.update(now);
#if DB_OTA
  ArduinoOTA.handle();
#endif
  serialTick();
  buttonTick(now);

#if DB_USE_LED
  ledTick(now);
#endif

  if (now - lastStatePush > 1000) {
    lastStatePush = now;
    pushState();
  }

  // relay phone sensor data to the laptop UI a few times per second
  if (brain.hasSensors() && brain.sensorsAge(now) < 3000 && now - lastSensorPush > 250) {
    lastSensorPush = now;
    char se[180];
    brain.sensorJson(se, sizeof(se));
    net.broadcastWeb(se);
  }
}
