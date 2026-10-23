package com.deskbuddy.app;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

/**
 * Line-delimited JSON link to the ESP32 (TCP :81).
 * Survives network hiccups with a simple exponential backoff.
 */
final class Link {

    interface Listener {
        void onLinkState(boolean connected, String detail);
        void onMessage(Map<String, String> message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object writeLock = new Object();

    private volatile boolean running;
    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile String host = "";
    private volatile int port = 81;
    private Listener listener;
    private Thread thread;

    void setListener(Listener l) {
        listener = l;
    }

    boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    String host() {
        return host;
    }

    void start(String host, int port) {
        stop();
        this.host = host;
        this.port = port;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "deskbuddy-link");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        closeSocket();
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    void send(String json) {
        BufferedWriter w = writer;
        if (w == null) return;
        try {
            synchronized (writeLock) {
                w.write(json);
                w.write('\n');
                w.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void closeSocket() {
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
        socket = null;
        writer = null;
    }

    private void loop() {
        int backoff = 1000;
        while (running) {
            Socket s = null;
            try {
                s = new Socket();
                s.setTcpNoDelay(true);
                s.setSoTimeout(0);
                s.connect(new InetSocketAddress(host, port), 6000);
                socket = s;
                final String h = host;
                writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));

                main.post(new Runnable() {
                    @Override public void run() {
                        Listener l = listener;
                        if (l != null) l.onLinkState(true, h);
                    }
                });
                send("{\"t\":\"hello\",\"name\":\"android\",\"ver\":1}");
                backoff = 1000;

                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line;
                while (running && (line = r.readLine()) != null) {
                    final Map<String, String> m = Json.flat(line);
                    if (m.isEmpty()) continue;
                    main.post(new Runnable() {
                        @Override public void run() {
                            Listener l = listener;
                            if (l != null) l.onMessage(m);
                        }
                    });
                }
            } catch (Exception e) {
                // fall through to retry
            } finally {
                closeSocket();
                final Listener l = listener;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (l != null) l.onLinkState(false, null);
                    }
                });
            }
            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 8000);
        }
    }
}
