// ---------------------------------------------------------------------------
// DeskBuddy - build-time configuration
// ---------------------------------------------------------------------------
#pragma once

#define DB_VERSION   "1.4.1"

// Name advertised over mDNS -> http://deskbuddy.local
#define DB_HOSTNAME  "deskbuddy"

// The ESP32 always raises its own setup hotspot so you can reach the buddy even
// with no WiFi credentials saved yet.
#define DB_AP_PASS   "deskbuddy"   // min 8 chars, or "" for an open network

// Ports. 80 = face UI + WebSocket, 81 = phone sensor link (newline JSON).
#define DB_HTTP_PORT 80
#define DB_NODE_PORT 81

// Built-in BOOT button (GPIO0 on every S3 devkit) = pet the buddy.
#define DB_BUTTON_PIN 0

// Optional extras. Turn on only if your board has them.
#define DB_USE_LED    0            // WS2812 status LED (needs Adafruit NeoPixel)
#define DB_LED_PIN    48           // 48 on most S3 devkits, 38 on some clones
#define DB_USE_BUZZER 0
#define DB_BUZZER_PIN 5

// How chatty the serial log is.
#define DB_DEBUG      1

// Wireless firmware updates (arduino-cli / Arduino IDE over WiFi).
#define DB_OTA        1
