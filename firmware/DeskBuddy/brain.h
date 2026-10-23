// ---------------------------------------------------------------------------
// DeskBuddy brain: the little personality that lives on the ESP32.
// No display, no sensors on the board itself - everything arrives over WiFi.
// ---------------------------------------------------------------------------
#pragma once
#include <Arduino.h>

enum Mood : uint8_t {
  MOOD_HAPPY = 0,
  MOOD_CURIOUS,
  MOOD_SLEEPY,
  MOOD_SLEEPING,
  MOOD_HUNGRY,
  MOOD_ANNOYED,
  MOOD_DIZZY,
  MOOD_LOVE,
  MOOD_STARTLED,
  MOOD_BORED,
  MOOD_EXCITED,
  MOOD_COUNT
};

class Brain {
 public:
  void begin();
  void update(uint32_t now);

  // ---- inputs -------------------------------------------------------------
  void poke(float x = -1, float y = -1);
  void pet(float amount = 1.0f);
  void shake(float strength);
  void suddenNoise(float level);
  void brightness(float lux);
  void feed();
  void play();
  void toggleSleep();
  void rename(const char* name);
  void resetStats();

  // ---- state --------------------------------------------------------------
  bool sleeping() const { return m == MOOD_SLEEPING; }
  Mood mood() const { return m; }
  const char* moodName(Mood mm) const;
  uint32_t level() const { return lvl; }
  const char* name() const { return s.name; }
  float debugHappy() const { return s.happy; }
  float debugEnergy() const { return s.energy; }
  float debugFull() const { return s.full; }

  void stateJson(char* out, size_t n, bool includeSelf = true);
  void statsJson(char* out, size_t n);

  // destination for reaction / speech events (set by the sketch)
  void setEmitter(void (*fn)(const char* json)) { emit = fn; }

  // Telemetry from the phone, shown in the laptop UI.
  void setSensors(float ax, float ay, float az, float lux, float mic, float batt);
  void sensorJson(char* out, size_t n);
  uint32_t sensorsAge(uint32_t now) const { return now - lastSensor; }
  bool hasSensors() const { return lastSensor != 0; }

 private:
  struct Stats {
    float happy = 68;
    float energy = 78;
    float full = 62;
    float bond = 0;
    float curiosity = 0.5f;
    float appetite = 0.5f;
    float laziness = 0.5f;
    uint32_t born = 0;
    uint32_t pets = 0;
    uint32_t feeds = 0;
    uint32_t plays = 0;
    char name[20] = "Biscuit";
  } s;

  Mood m = MOOD_HAPPY;
  uint32_t lvl = 1;
  uint32_t lastTick = 0;
  uint32_t moodUntil = 0;      // temporary mood (dizzy, startled, love...)
  uint32_t lastInteract = 0;
  uint32_t lastSay = 0;
  uint32_t lastNoise = 0;
  uint32_t lastSave = 0;
  uint32_t lastPet = 0;
  uint32_t lastSensor = 0;
  uint32_t lastLevelNotice = 0;
  uint8_t pokeCount = 0;
  uint32_t pokeWindow = 0;
  bool dark = false;
  bool awakeOverride = false;  // user tapped "wake up"

  float sAx = 0, sAy = 0, sAz = 0, sLux = 0, sMic = 0, sBatt = -1;

  void (*emit)(const char* json) = nullptr;

  void load();
  void save();
  void deriveMood();
  void react(const char* event, float value, float x = -1, float y = -1);
  void say(const char* text);
  void sayForMood(bool force);
  void checkLevel();
  void hold(Mood mm, uint32_t ms) { m = mm; moodUntil = millis() + ms; }
};
