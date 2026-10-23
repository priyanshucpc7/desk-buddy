#!/usr/bin/env python3
"""Local preview server for the DeskBuddy face.

Serves web/index.html on http://localhost:8080 and pretends to be the ESP32 on
ws://localhost:8081/ws, so you can art-direct the UI without flashing anything.

    python3 tools/preview.py            # then open http://localhost:8080
    python3 tools/preview.py --port 9000

The real firmware serves the same page from gzip'd flash and speaks the same
protocol, so what you see here is what the buddy does.
"""
import argparse
import asyncio
import http.server
import json
import pathlib
import random
import socketserver
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
WEB = ROOT / "web" / "index.html"

state = {
    "t": "state", "name": "Preview", "mood": "happy", "hap": 86, "en": 72, "full": 64,
    "bond": 137, "lvl": 2, "age": 120, "sleep": 0, "pets": 12, "feeds": 3, "plays": 5,
    "cur": 0.52, "app": 0.61, "lazy": 0.4, "dark": 0, "temp": 48.2,
}
sensors = {"t": "sens", "ax": 0.4, "ay": 9.1, "az": 3.6, "lux": 312, "mic": 0.18, "batt": 84}
clients = set()


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] in ("/", "/index.html"):
            body = WEB.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path.startswith("/api/status"):
            body = json.dumps({"ok": True, "fw": "1.0.0-preview", "ap": 0,
                               "apSsid": "DeskBuddy-PREV", "apPass": "deskbuddy",
                               "staIp": "127.0.0.1", "apIp": "127.0.0.1",
                               "web": len(clients), "phone": 1, "uptime": 120,
                               "state": state, "sensors": sensors}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def log_message(self, *a):
        pass


async def ws_handler(ws):
    clients.add(ws)
    try:
        await ws.send(json.dumps(dict(state, t="hello", state=state, kind="web")))
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            t = msg.get("t")
            if t == "touch":
                kind = msg.get("kind")
                event = "pet" if kind == "pet" else "poke"
                await broadcast(json.dumps({"t": "react", "e": event, "v": 1,
                                            "x": msg.get("x", -1), "y": msg.get("y", -1),
                                            "mood": state["mood"]}))
                if event == "pet":
                    state["pets"] += 1
                    state["bond"] += 1
                    state["hap"] = min(100, state["hap"] + 2)
                    await announce({"t": "say", "text": random.choice(
                        ["purrrr.", "you may pet me once more.", "okay, this is nice."])})
            elif t == "cmd":
                c = msg.get("c")
                if c == "feed":
                    state["full"] = min(100, state["full"] + 28)
                    state["feeds"] += 1
                    await announce({"t": "react", "e": "feed", "v": 1, "x": -1, "y": -1,
                                    "mood": state["mood"]})
                elif c == "play":
                    state["en"] = max(0, state["en"] - 12)
                    state["hap"] = min(100, state["hap"] + 10)
                    state["plays"] += 1
                    await announce({"t": "react", "e": "play", "v": 1, "x": -1, "y": -1,
                                    "mood": "excited"})
                elif c in ("sleep", "wake"):
                    state["sleep"] = 1 if c == "sleep" else 0
                    state["mood"] = "sleeping" if c == "sleep" else "sleepy"
                    await announce({"t": "react", "e": "sleep" if c == "sleep" else "wake",
                                    "v": 1, "x": -1, "y": -1, "mood": state["mood"]})
                elif c == "reset":
                    state.update(hap=68, en=78, full=62, bond=0, lvl=1, pets=0, feeds=0, plays=0)
                elif c == "rename" and msg.get("v"):
                    state["name"] = msg["v"]
                await push_state()
            elif t == "hello":
                await push_state()
    finally:
        clients.discard(ws)


async def broadcast(payload):
    for c in list(clients):
        try:
            await c.send(payload)
        except Exception:
            clients.discard(c)


async def announce(obj):
    await broadcast(json.dumps(obj))


async def push_state():
    state["bond"] = int(state["bond"])
    state["lvl"] = 1 + state["bond"] // 100
    state["age"] = int(time.time()) % 100000
    await broadcast(json.dumps(state))


async def ticker():
    while True:
        await asyncio.sleep(1.2)
        state["en"] = max(0, state["en"] - 0.02)
        state["full"] = max(0, state["full"] - 0.02)
        await push_state()
        sensors["mic"] = max(0.02, min(1, random.random() * 0.25))
        await broadcast(json.dumps(sensors))


async def main(port):
    import websockets
    async with websockets.serve(ws_handler, "0.0.0.0", port + 1):
        print(f"ws   ws://localhost:{port + 1}/ws")
        print(f"page http://localhost:{port}/  (append ?ws={port + 1} if needed)")
        await ticker()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()

    httpd = socketserver.TCPServer(("", args.port), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        asyncio.run(main(args.port))
    except KeyboardInterrupt:
        pass
