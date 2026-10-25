# Display mode

DeskBuddy can present itself as a bare face — no header, no vitals, no buttons —
so a phone or a small screen looks like a dedicated desk robot display. It is
the same renderer and the same moods as the full page, just without the page
chrome. It is a **bare OLED screen**: true black, only the eyes, brows and mouth.
No robot shell, feet, bezel or border — the phone screen itself is the display.

## Browser / phone

- The page opens in face mode by default. Toggle **Settings → Open in face mode**
  off if you prefer the dashboard on load.
- On a phone browser, entering fullscreen tries to lock the screen to landscape;
  the Android app always runs face mode in landscape.
- You can also click the **monitor icon** in the header, or open **`/?screen=1`**.
- The page goes full-screen (first tap if the browser blocks auto-fullscreen),
  hides every control and keeps the screen awake when the browser allows it.
- **Exit:** triple-tap the top-left corner, or press `Esc` on a keyboard.
  The mode is remembered when entered from the button; `?screen=1` is one-shot.
- Keyboard shortcuts still work: `F` feed, `P` dance, `G` sing, `J` game,
  `S` sleep/wake, `L` voice, `T` timer. On a touch screen, **long-press the
  face** to toggle the microphone.
- On a phone the browser may block the microphone over plain HTTP. Use the
  Android app's **Talk** button (or long-press the face) for voice in display
  mode, or the typed command box in Settings.

The face scales from the viewport, so the same page works at any size:

| Panel | Result |
| --- | --- |
| 240 × 240 (round/square colour OLED) | head + feet fill the panel |
| 320 × 240 / 240 × 320 | fills the short side, extra margin on the long side |
| 128 × 64 (mono OLED) | face is readable; use a mono port for the real panel |
| 390 × 844 / 1280 × 800 | phone / laptop kiosk |

## Android app

The app launches straight into **Open in face mode** (on by default) and is
locked to **landscape**. Status bar, buttons and dashboard are hidden; only the
eyes and mouth are drawn, edge to edge on black. Use the connection panel once
to switch it off if you want the dashboard at launch. Press Back or triple-tap
the top-left to leave temporarily; the next launch still starts with the face. Tap **Talk** before entering display mode, or
long-press the face at any time; the phone's own recognizer and TTS handle the
command, so voice still works without a web page.

## Buying a real OLED

The browser page cannot run on an OLED module — the ESP32 has to draw pixels.
When you pick a panel, the firmware needs a native renderer for it:

- **Colour SPI panels** (GC9A01 240×240 round, ST7735 160×128, ILI9341 240×320):
  `TFT_eSPI` on the ESP32-S3. The geometry in `web/index.html` is already
  resolution-independent: head width = `1.717 × r`, head height = `1.50 × r`,
  feet add about `0.36 × r`, so `r = min(W, H) / 1.80` with a small margin.
- **Mono I2C/SPI panels** (SSD1306 128×64, SH1106): `U8g2`. The six eye shapes
  and the mouth are simple paths and map cleanly to monochrome; the glow,
  scanlines and gradients become dithered patterns.
- **Parallel RGB / QSPI panels**: use `esp_lcd` with a partial-update buffer;
  the face only needs the screen area redrawn when it changes.

This page is the visual reference for all of them. Tell me which controller and
resolution you buy and I can add the ESP32 driver and the frame renderer.
