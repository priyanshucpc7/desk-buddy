#include "brain.h"
#include <Preferences.h>
#include <math.h>
#include "json.h"

static Preferences prefs;
static const char* MOOD_NAMES[MOOD_COUNT] = {
    "happy", "curious", "sleepy", "sleeping", "hungry",
    "annoyed", "dizzy", "love", "startled", "bored", "excited"};

// A small, deliberately un-corporate vocabulary.
static const char* LINES_HAPPY[] = {
    "you're my favourite human.", "this desk is a nice desk.",
    "i could do this all day.", "hi. hi. hi again.", "i'm having a good pixel day."};
static const char* LINES_HUNGRY[] = {
    "my tummy is making dial-up noises.", "snack? snack. snack!",
    "i have detected a 100% chance of hunger.", "feed me and i'll be your best friend."};
static const char* LINES_SLEEPY[] = {
    "five more minutes...", "my pixels feel heavy.", "just resting my eyes.", "zzz..."};
static const char* LINES_BORED[] = {
    "poke me. i dare you.", "so... what are we doing?",
    "i counted the keys on your keyboard. 104.", "is it the weekend yet?"};
static const char* LINES_LOVE[] = {
    "purrrr.", "you may pet me once more.", "okay, this is nice.", "i like your hand."};
static const char* LINES_ANNOYED[] = {
    "hey! i'm not a fidget toy.", "poke me one more time. i dare you.",
    "personal space, please.", "i'm telling the router."};
static const char* LINES_DIZZY[] = {
    "the room is doing a barrel roll.", "wheee... no, wait.", "i regret everything."};
static const char* LINES_EXCITED[] = {
    "zoomies!!", "let's gooo!", "best day!", "again! again!"};
static const char* LINES_STARTLED[] = {"!!", "what was that?", "my heart is running at 60fps."};
static const char* LINES_CURIOUS[] = {
    "what are you working on?", "hmm?", "i'm listening.", "is that a bug or a feature?"};

static const char* pick(const char* const* arr, size_t count) {
  return arr[esp_random() % count];
}

const char* Brain::moodName(Mood mm) const {
  return mm < MOOD_COUNT ? MOOD_NAMES[mm] : "happy";
}

void Brain::begin() {
  prefs.begin("buddy", false);
  // Bump when the stats layout changes so stale NVS from older firmware
  // (or an older project on the same chip) is ignored instead of adopted.
  const uint32_t kStateVersion = 1;
  bool fresh = prefs.getUInt("ver", 0) != kStateVersion;
  load();
  s.born = millis();
  if (fresh) {
    s.happy = 68;
    s.energy = 78;
    s.full = 62;
    s.bond = 0;
    s.pets = s.feeds = s.plays = 0;
    lvl = 1;
    static const char* NAMES[] = {"Biscuit", "Mochi", "Pixel", "Tofu", "Bean", "Nori",
                                  "Pebble", "Custard", "Miso", "Waffle", "Boba", "Momo"};
    strncpy(s.name, NAMES[esp_random() % (sizeof(NAMES) / sizeof(NAMES[0]))], sizeof(s.name) - 1);
    s.name[sizeof(s.name) - 1] = 0;
    s.curiosity = 0.25f + (esp_random() % 70) / 100.0f;
    s.appetite = 0.25f + (esp_random() % 70) / 100.0f;
    s.laziness = 0.25f + (esp_random() % 70) / 100.0f;
    prefs.putUInt("ver", kStateVersion);
    save();
  }
  lastInteract = millis();
  lastSay = millis();
  deriveMood();
}

void Brain::load() {
  String n = prefs.getString("name", "Biscuit");
  strncpy(s.name, n.c_str(), sizeof(s.name) - 1);
  s.name[sizeof(s.name) - 1] = 0;
  s.happy = prefs.getFloat("hap", s.happy);
  s.energy = prefs.getFloat("en", s.energy);
  s.full = prefs.getFloat("full", s.full);
  s.bond = prefs.getFloat("bond", s.bond);
  s.curiosity = prefs.getFloat("cur", s.curiosity);
  s.appetite = prefs.getFloat("app", s.appetite);
  s.laziness = prefs.getFloat("lazy", s.laziness);
  s.pets = prefs.getUInt("pets", 0);
  s.feeds = prefs.getUInt("feeds", 0);
  s.plays = prefs.getUInt("plays", 0);
  lvl = 1 + (uint32_t)(s.bond / 100.0f);
}

void Brain::save() {
  prefs.putString("name", s.name);
  prefs.putFloat("hap", s.happy);
  prefs.putFloat("en", s.energy);
  prefs.putFloat("full", s.full);
  prefs.putFloat("bond", s.bond);
  prefs.putFloat("cur", s.curiosity);
  prefs.putFloat("app", s.appetite);
  prefs.putFloat("lazy", s.laziness);
  prefs.putUInt("pets", s.pets);
  prefs.putUInt("feeds", s.feeds);
  prefs.putUInt("plays", s.plays);
}

void Brain::update(uint32_t now) {
  if (lastTick == 0) { lastTick = now; return; }
  float dt = (now - lastTick) / 1000.0f;
  lastTick = now;
  if (dt <= 0.0f) return;          // loop ran faster than 1 ms
  if (dt > 5.0f) dt = 0.1f;        // resumed after a stall, don't fast-forward

  const bool sleepingNow = (m == MOOD_SLEEPING);

  // ---- slow needs -------------------------------------------------------
  if (!sleepingNow) {
    s.full -= 0.0045f * (0.6f + s.appetite) * dt;                   // ~1 / 3.5 min
    s.energy -= 0.0035f * (0.6f + s.laziness) * dt;
    float comfort = 34.0f + s.full * 0.22f + s.energy * 0.26f;      // what mood drifts toward
    if (s.happy > comfort) s.happy -= 0.010f * dt;
    else s.happy += 0.006f * dt;
    s.bond += 0.020f * dt;
  } else {
    s.energy += 0.045f * dt;
    s.full -= 0.0018f * dt;
    s.happy += 0.004f * dt;
  }

  s.happy = constrain(s.happy, 0.0f, 100.0f);
  s.energy = constrain(s.energy, 0.0f, 100.0f);
  s.full = constrain(s.full, 0.0f, 100.0f);

  // ---- autonomy ---------------------------------------------------------
  if (!sleepingNow && s.energy < 9 && (dark || now - lastInteract > 240000UL)) {
    m = MOOD_SLEEPING;
    react("sleep", 1);
    say("out of battery. going to sleep.");
  }
  if (now - lastSay > 45000UL && now - lastInteract > 40000UL && !sleepingNow) {
    if (esp_random() % 3 == 0) sayForMood(false);
    lastSay = now;
  }
  if (now - lastInteract > 120000UL && m != MOOD_SLEEPING) s.happy -= 0.002f * dt;

  checkLevel();
  deriveMood();

  if (now - lastSave > 30000UL) {
    lastSave = now;
    save();
  }
}

void Brain::deriveMood() {
  uint32_t now = millis();
  if (now < moodUntil) return;  // temporary reaction still playing
  if (m == MOOD_SLEEPING) {
    if (awakeOverride) { awakeOverride = false; m = MOOD_SLEEPY; moodUntil = now + 6000; }
    return;
  }
  if (s.full < 22) { m = MOOD_HUNGRY; return; }
  if (s.energy < 18) { m = MOOD_SLEEPY; return; }
  if (now - lastInteract > 150000UL) { m = MOOD_BORED; return; }
  if (s.happy > 92) { m = MOOD_EXCITED; return; }
  if (s.happy > 62) { m = MOOD_HAPPY; return; }
  m = MOOD_CURIOUS;
}

void Brain::checkLevel() {
  uint32_t want = 1 + (uint32_t)(s.bond / 100.0f);
  if (want <= lvl) return;
  lvl = want;
  uint32_t now = millis();
  if (now - lastLevelNotice < 5000) return;  // don't spam on a petting spree
  lastLevelNotice = now;
  char buf[96];
  snprintf(buf, sizeof(buf), "i levelled up! level %u. we're basically family now.", (unsigned)lvl);
  say(buf);
  react("level", 1);
  save();
}

void Brain::react(const char* event, float value, float x, float y) {
  if (!emit) return;
  char buf[160];
  snprintf(buf, sizeof(buf),
           "{\"t\":\"react\",\"e\":\"%s\",\"v\":%.2f,\"x\":%.2f,\"y\":%.2f,\"mood\":\"%s\"}",
           event, value, x, y, moodName(m));
  emit(buf);
}

void Brain::say(const char* text) {
  if (!emit || !text) return;
  char esc[160], buf[224];
  jescape(text, esc, sizeof(esc));
  snprintf(buf, sizeof(buf), "{\"t\":\"say\",\"text\":\"%s\"}", esc);
  emit(buf);
  lastSay = millis();
}

void Brain::sayForMood(bool force) {
  if (!force && m == MOOD_SLEEPING) return;
  switch (m) {
    case MOOD_HUNGRY:  say(pick(LINES_HUNGRY, 4)); break;
    case MOOD_SLEEPY:
    case MOOD_SLEEPING: say(pick(LINES_SLEEPY, 4)); break;
    case MOOD_BORED:   say(pick(LINES_BORED, 4)); break;
    case MOOD_LOVE:    say(pick(LINES_LOVE, 4)); break;
    case MOOD_ANNOYED: say(pick(LINES_ANNOYED, 4)); break;
    case MOOD_DIZZY:   say(pick(LINES_DIZZY, 3)); break;
    case MOOD_EXCITED: say(pick(LINES_EXCITED, 4)); break;
    case MOOD_STARTLED: say(pick(LINES_STARTLED, 3)); break;
    case MOOD_CURIOUS: say(pick(LINES_CURIOUS, 4)); break;
    default:           say(pick(LINES_HAPPY, 5)); break;
  }
}

void Brain::poke(float x, float y) {
  uint32_t now = millis();
  lastInteract = now;
  if (now - pokeWindow > 6000UL) pokeCount = 0;
  pokeWindow = now;
  pokeCount++;

  if (m == MOOD_SLEEPING) {
    m = MOOD_STARTLED;
    moodUntil = now + 1500;
    say("i was sleeping!");
    react("poke", 1, x, y);
    return;
  }
  s.happy -= 0.6f;
  s.energy -= 0.3f;
  if (pokeCount >= 4) {
    pokeCount = 0;
    hold(MOOD_ANNOYED, 3000);
    react("poke", 1, x, y);
    say(pick(LINES_ANNOYED, 4));
  } else {
    hold(MOOD_STARTLED, 850);
    react("poke", 1, x, y);
  }
}

void Brain::pet(float amount) {
  uint32_t now = millis();
  if (now - lastPet < 110) return;
  lastPet = now;
  lastInteract = now;
  if (m == MOOD_SLEEPING) return;
  if (amount > 1.5f) amount = 1.5f;
  s.happy += 0.8f * amount;
  s.bond += 0.35f * amount;
  s.pets++;
  hold(MOOD_LOVE, 1400);
  react("pet", amount, -1, -1);
  if (esp_random() % 14 == 0) say(pick(LINES_LOVE, 4));
}

void Brain::shake(float strength) {
  uint32_t now = millis();
  lastInteract = now;
  if (now - lastNoise < 1200) return;
  lastNoise = now;
  m = MOOD_DIZZY;
  moodUntil = now + 2200;
  s.happy -= 1.2f * strength;
  s.energy -= 0.6f;
  react("shake", strength);
  if (esp_random() % 3 == 0) say(pick(LINES_DIZZY, 3));
}

void Brain::suddenNoise(float level) {
  uint32_t now = millis();
  if (level < 0.55f) return;
  if (now - lastNoise < 3500UL) return;
  lastNoise = now;
  lastInteract = now;
  bool wasSleeping = (m == MOOD_SLEEPING);
  m = MOOD_STARTLED;
  moodUntil = now + 1400;
  s.energy -= 0.5f;
  s.happy -= 0.4f;
  react("noise", level);
  if (wasSleeping) say("what?! i'm awake!");
  else if (esp_random() % 4 == 0) say(pick(LINES_STARTLED, 3));
}

void Brain::brightness(float lux) {
  bool wasDark = dark;
  dark = lux < 10.0f;
  if (dark && !wasDark) {
    react("dark", 1);
    if (s.energy < 55 && m != MOOD_SLEEPING) hold(MOOD_SLEEPY, 5000);
  } else if (!dark && wasDark) {
    react("bright", 1);
    if (m == MOOD_SLEEPING) {
      m = MOOD_SLEEPY;
      moodUntil = millis() + 4000;
      say("oh. morning.");
    }
  }
}

void Brain::feed() {
  uint32_t now = millis();
  lastInteract = now;
  s.full = constrain(s.full + 28.0f, 0, 100);
  s.happy = constrain(s.happy + 7.0f, 0, 100);
  s.bond += 4;
  s.feeds++;
  hold(MOOD_HAPPY, 2600);
  react("feed", 1);
  if (esp_random() % 3 == 0) say("nom nom nom.");
  save();
}

void Brain::play() {
  uint32_t now = millis();
  lastInteract = now;
  s.energy = constrain(s.energy - 12.0f, 0, 100);
  s.happy = constrain(s.happy + 10.0f, 0, 100);
  s.bond += 5;
  s.plays++;
  hold(MOOD_EXCITED, 4200);
  react("play", 1);
  say(pick(LINES_EXCITED, 4));
  save();
}

void Brain::toggleSleep() {
  uint32_t now = millis();
  lastInteract = now;
  if (m == MOOD_SLEEPING) {
    m = MOOD_SLEEPY;
    moodUntil = now + 3500;
    awakeOverride = true;
    react("wake", 1);
    say("okay okay, i'm up.");
  } else {
    m = MOOD_SLEEPING;
    moodUntil = 0;
    react("sleep", 1);
    say("goodnight, human.");
  }
}

void Brain::rename(const char* name) {
  if (!name) return;
  char clean[20];
  size_t o = 0;
  for (const char* p = name; *p && o < sizeof(clean) - 1; p++) {
    if (*p == '"' || *p == '\\' || (unsigned char)*p < 0x20) continue;
    clean[o++] = *p;
  }
  while (o > 0 && clean[o - 1] == ' ') o--;
  clean[o] = 0;
  if (o == 0) return;
  strncpy(s.name, clean, sizeof(s.name) - 1);
  s.name[sizeof(s.name) - 1] = 0;
  save();
  react("name", 1);
  say("new name, who dis?");
}

void Brain::resetStats() {
  s.happy = 68; s.energy = 78; s.full = 62; s.bond = 0;
  s.pets = s.feeds = s.plays = 0;
  lvl = 1;
  save();
  react("reset", 1);
  say("fresh start. hi!");
}

void Brain::setSensors(float ax, float ay, float az, float lux, float mic, float batt) {
  sAx = ax; sAy = ay; sAz = az; sLux = lux; sMic = mic; sBatt = batt;
  lastSensor = millis();
}

void Brain::sensorJson(char* out, size_t n) {
  snprintf(out, n,
           "{\"t\":\"sens\",\"ax\":%.2f,\"ay\":%.2f,\"az\":%.2f,\"lux\":%.1f,"
           "\"mic\":%.2f,\"batt\":%.1f}",
           sAx, sAy, sAz, sLux, sMic, sBatt);
}

void Brain::stateJson(char* out, size_t n, bool includeSelf) {
  char esc[64];
  jescape(s.name, esc, sizeof(esc));
  snprintf(out, n,
           "{\"t\":\"state\",\"name\":\"%s\",\"mood\":\"%s\",\"hap\":%d,\"en\":%d,"
           "\"full\":%d,\"bond\":%d,\"lvl\":%u,\"age\":%lu,\"sleep\":%d,"
           "\"pets\":%lu,\"feeds\":%lu,\"plays\":%lu,"
           "\"cur\":%.2f,\"app\":%.2f,\"lazy\":%.2f,\"dark\":%d,\"temp\":%.1f}",
           esc, moodName(m), (int)lroundf(s.happy), (int)lroundf(s.energy),
           (int)lroundf(s.full), (int)lroundf(s.bond), (unsigned)lvl,
           (unsigned long)((millis() - s.born) / 1000UL), m == MOOD_SLEEPING ? 1 : 0,
           (unsigned long)s.pets, (unsigned long)s.feeds, (unsigned long)s.plays,
           s.curiosity, s.appetite, s.laziness, dark ? 1 : 0,
           temperatureRead());
  (void)includeSelf;
}
