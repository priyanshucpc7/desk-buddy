package com.deskbuddy.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The phone half of DeskBuddy: full-screen face + sensor pack.
 *
 * The ESP32 is the brain and speaks line-delimited JSON on TCP :81. This app
 * renders the same buddy as the browser UI, forwards taps and pets, and
 * streams accelerometer / light / sound / battery data back.
 */
public class MainActivity extends Activity implements Link.Listener, FaceView.Listener {

    private static final int PORT = 81;
    private static final int REQ_MIC = 7;

    private FaceView face;
    private Link link;
    private Sensors sensors;
    private Prefs prefs;

    private TextView statusText;
    private TextView statsText;
    private TextView speech;
    private View statusDot;
    private FrameLayout panel;
    private LinearLayout foundList;
    private EditText hostInput;
    private CheckBox micBox;
    private CheckBox awakeBox;
    private CheckBox displayBox;
    private View topBar, bottomBar;
    private boolean displayMode = false;
    private TextView panelHint;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicastLock;
    private final List<String> found = new ArrayList<String>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String buddyName = "DeskBuddy";
    private boolean connected = false;

    // ------------------------------------------------------------------ setup
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        link = new Link();
        link.setListener(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        immersive();

        String host = prefs.host();
        if (host != null && host.length() > 0) {
            hostInput.setText(host);
            connect(host);
        } else {
            openPanel(true);
        }
        displayMode = prefs.display() && host != null && host.length() > 0;
        applyDisplayMode();

        if (prefs.mic() && Build.VERSION.SDK_INT >= 23 && !micGranted()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private boolean micGranted() {
        return getPackageManager().checkPermission(Manifest.permission.RECORD_AUDIO,
                getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        getWindow().addFlags(
                prefs.awake() ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0);
        if (!prefs.awake()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (connected) startSensors();
    }

    @Override protected void onPause() {
        super.onPause();
        stopSensors();
    }

    @Override public void onBackPressed() {
        if (displayMode) { setDisplayMode(false); return; }
        if (panel != null && panel.getVisibility() == View.VISIBLE) { closePanel(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopDiscovery();
        if (link != null) link.stop();
        stopSensors();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // --------------------------------------------------------------------- ui
    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF090C15);

        face = new FaceView(this);
        face.setListener(this);
        root.addView(face, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // top bar
        LinearLayout top = new LinearLayout(this);
        topBar = top;
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding((int) dp(16), (int) dp(12), (int) dp(12), (int) dp(8));

        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF4C5568);
        statusDot.setBackground(dot);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams((int) dp(8), (int) dp(8));
        dotLp.rightMargin = (int) dp(8);
        top.addView(statusDot, dotLp);

        statusText = new TextView(this);
        statusText.setText("connecting…");
        statusText.setTextColor(0xFF8892A6);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(statusText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView menu = new TextView(this);
        menu.setText("≡");
        menu.setTextColor(0xFFF4EEE7);
        menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding((int) dp(12), 0, (int) dp(12), 0);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPanel(true); }
        });
        top.addView(menu);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topLp.topMargin = (int) dp(10);
        root.addView(top, topLp);

        // speech bubble
        speech = new TextView(this);
        speech.setTextColor(0xFF171A24);
        speech.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        speech.setGravity(Gravity.CENTER);
        speech.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        speech.setPadding((int) dp(16), (int) dp(10), (int) dp(16), (int) dp(10));
        GradientDrawable bubble = new GradientDrawable();
        bubble.setCornerRadius(dp(15));
        bubble.setColor(0xFFF7F1EA);
        speech.setBackground(bubble);
        speech.setVisibility(View.INVISIBLE);
        speech.setAlpha(0f);
        FrameLayout.LayoutParams spLp = new FrameLayout.LayoutParams(
                (int) dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        spLp.topMargin = (int) dp(74);
        root.addView(speech, spLp);

        // bottom bar
        LinearLayout bottom = new LinearLayout(this);
        bottomBar = bottom;
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding((int) dp(14), 0, (int) dp(14), (int) dp(18));
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        statsText = new TextView(this);
        statsText.setTextColor(0xFF98A1B6);
        statsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statsText.setGravity(Gravity.CENTER);
        bottom.addView(statsText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) dp(10);

        row.addView(actionButton("Feed", 0xFFFFC9A6, new Runnable() {
            @Override public void run() { command("feed"); }
        }), buttonLp());
        row.addView(actionButton("Play", 0xFFD6C8FF, new Runnable() {
            @Override public void run() { command("play"); }
        }), buttonLp());
        row.addView(actionButton("Sleep", 0xFFB7E7FF, new Runnable() {
            @Override public void run() { command(sleeping ? "wake" : "sleep"); }
        }), buttonLp());
        bottom.addView(row, rowLp);

        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(bottom, botLp);

        // connection / settings panel
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0xCC05070D);
        panel.setVisibility(View.GONE);
        panel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* swallow */ }
        });
        panel.addView(buildPanelCard(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = (int) dp(4);
        lp.rightMargin = (int) dp(4);
        return lp;
    }

    private TextView actionButton(String label, int color, final Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) dp(10), (int) dp(12), (int) dp(10), (int) dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(13));
        bg.setColor(0x14FFFFFF);
        bg.setStroke((int) dp(1), 0x22FFFFFF);
        b.setBackground(bg);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!connected) { openPanel(true); return; }
                action.run();
            }
        });
        return b;
    }

    private View buildPanelCard() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) dp(22), (int) dp(22), (int) dp(22), (int) dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0xFF141A2B);
        bg.setStroke((int) dp(1), 0x22FFFFFF);
        card.setBackground(bg);

        FrameLayout.LayoutParams margins = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        margins.setMargins((int) dp(14), (int) dp(24), (int) dp(14), (int) dp(24));
        scroll.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setLayoutParams(margins);

        TextView title = new TextView(this);
        title.setText("Connect to DeskBuddy");
        title.setTextColor(0xFFF4EEE7);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Make sure the phone and the ESP32 are on the same WiFi, then enter the buddy's address. "
                + "No screen on the board? Join its hotspot DeskBuddy-XXXX and use 192.168.4.1.");
        sub.setTextColor(0xFF8892A6);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = (int) dp(8);
        subLp.bottomMargin = (int) dp(14);
        card.addView(sub, subLp);

        hostInput = new EditText(this);
        hostInput.setHint("192.168.1.42 or deskbuddy.local");
        hostInput.setHintTextColor(0xFF5C6579);
        hostInput.setTextColor(0xFFF4EEE7);
        hostInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hostInput.setSingleLine(true);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(dp(11));
        inputBg.setColor(0x0DFFFFFF);
        inputBg.setStroke((int) dp(1), 0x1AFFFFFF);
        hostInput.setBackground(inputBg);
        hostInput.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        card.addView(hostInput);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = (int) dp(10);

        TextView connectBtn = plainButton("Connect", true);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submitHost(); }
        });
        actions.addView(connectBtn, buttonLp());

        TextView findBtn = plainButton("Find on WiFi", false);
        findBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                panelHint.setText("Looking for buddies on this network…");
                startDiscovery();
            }
        });
        actions.addView(findBtn, buttonLp());
        card.addView(actions, actLp);

        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams flLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flLp.topMargin = (int) dp(8);
        card.addView(foundList, flLp);

        panelHint = new TextView(this);
        panelHint.setTextColor(0xFF8892A6);
        panelHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        phLp.topMargin = (int) dp(10);
        card.addView(panelHint, phLp);

        micBox = new CheckBox(this);
        micBox.setText("Stream microphone (reactions to noise)");
        micBox.setTextColor(0xFFC9D1DF);
        micBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        micBox.setChecked(prefs.mic());
        micBox.setPadding(0, (int) dp(6), 0, 0);
        micBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.setMic(micBox.isChecked());
                if (micBox.isChecked() && Build.VERSION.SDK_INT >= 23 && !micGranted()) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
                if (connected) startSensors();
            }
        });
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        micLp.topMargin = (int) dp(6);
        card.addView(micBox, micLp);

        awakeBox = new CheckBox(this);
        awakeBox.setText("Keep screen awake");
        awakeBox.setTextColor(0xFFC9D1DF);
        awakeBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        awakeBox.setChecked(prefs.awake());
        awakeBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.setAwake(awakeBox.isChecked());
                if (awakeBox.isChecked()) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        });
        card.addView(awakeBox);

        displayBox = new CheckBox(this);
        displayBox.setText("Display mode (face only)");
        displayBox.setTextColor(0xFFC9D1DF);
        displayBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        displayBox.setChecked(displayMode);
        displayBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setDisplayMode(displayBox.isChecked());
            }
        });
        LinearLayout.LayoutParams dispLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dispLp.topMargin = (int) dp(6);
        card.addView(displayBox, dispLp);

        TextView dispHint = new TextView(this);
        dispHint.setText("Hides every control, like a small OLED screen. "
                + "Triple-tap the top-left corner or press Back to leave.");
        dispHint.setTextColor(0xFF5C6579);
        dispHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        dispHint.setLineSpacing(dp(2), 1f);
        card.addView(dispHint);

        TextView close = plainButton("Done", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closePanel(); }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = (int) dp(14);
        card.addView(close, closeLp);

        TextView tip = new TextView(this);
        tip.setText("Desktop face: open http://deskbuddy.local in a browser. "
                + "This app is the sensor pack and a second face.");
        tip.setTextColor(0xFF5C6579);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tip.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = (int) dp(12);
        card.addView(tip, tipLp);

        hostInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO) { submitHost(); return true; }
                return false;
            }
        });

        return scroll;
    }

    private TextView plainButton(String label, boolean primary) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(primary ? 0xFF231A0F : 0xFFF4EEE7);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) dp(12), (int) dp(11), (int) dp(12), (int) dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(11));
        bg.setColor(primary ? 0xFFFFD479 : 0x14FFFFFF);
        if (!primary) bg.setStroke((int) dp(1), 0x22FFFFFF);
        b.setBackground(bg);
        return b;
    }

    private void openPanel(boolean force) {
        if (!force && connected) return;
        panel.setVisibility(View.VISIBLE);
        panelHint.setText(prefs.host().length() > 0 ? "Last address: " + prefs.host() : "");
        if (!connected) startDiscovery();
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        stopDiscovery();
    }

    private void submitHost() {
        String host = hostInput.getText().toString().trim();
        host = host.replace("http://", "").replace("https://", "");
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);
        if (host.length() == 0) {
            panelHint.setText("Enter the buddy's IP address first.");
            return;
        }
        panelHint.setText("Connecting to " + host + "…");
        connect(host);
    }

    private void connect(String host) {
        prefs.setHost(host);
        hostInput.setText(host);
        link.start(host, PORT);
        statusText.setText("connecting to " + host);
    }

    // ----------------------------------------------------------------- sensing
    private void startSensors() {
        boolean mic = prefs.mic() && (Build.VERSION.SDK_INT < 23 || micGranted());
        if (sensors != null) sensors.stop();
        sensors = new Sensors();
        sensors.start(this, new Sensors.Sink() {
            @Override public void sample(String json) {
                link.send(json);
            }
        }, mic);
    }

    private void stopSensors() {
        if (sensors != null) {
            sensors.stop();
            sensors = null;
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_MIC) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && connected) startSensors();
            if (!granted) {
                micBox.setChecked(false);
                prefs.setMic(false);
                panelHint.setText("Microphone permission denied — other sensors still work.");
            }
        }
    }

    // ------------------------------------------------------------------- mDNS
    private void startDiscovery() {
        if (discovery != null) return;
        if (nsd == null) nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            panelHint.setText("This device can't browse the network. Enter the IP manually.");
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("deskbuddy-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }

        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                stopDiscovery();
                ui.post(new Runnable() {
                    @Override public void run() {
                        panelHint.setText("Network search unavailable — enter the IP manually.");
                    }
                });
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }

            @Override public void onDiscoveryStarted(String serviceType) { }

            @Override public void onDiscoveryStopped(String serviceType) { }

            @Override public void onServiceFound(NsdServiceInfo info) {
                if (info == null || info.getServiceType() == null) return;
                if (!info.getServiceType().contains("deskbuddy")) return;
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) { }

                        @Override public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            InetAddress addr = serviceInfo.getHost();
                            if (addr == null) return;
                            final String ip = addr.getHostAddress();
                            ui.post(new Runnable() {
                                @Override public void run() { addFound(ip); }
                            });
                        }
                    });
                } catch (Exception ignored) {
                }
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
        };

        try {
            nsd.discoverServices("_deskbuddy._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
            ui.postDelayed(new Runnable() {
                @Override public void run() { stopDiscovery(); }
            }, 9000);
        } catch (Exception e) {
            stopDiscovery();
            panelHint.setText("Network search failed — enter the IP manually.");
        }
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
        }
        discovery = null;
        if (multicastLock != null) {
            try { multicastLock.release(); } catch (Exception ignored) { }
            multicastLock = null;
        }
    }

    private void addFound(final String ip) {
        if (found.contains(ip)) return;
        found.add(ip);
        panelHint.setText(found.size() + " buddy found — tap to connect.");
        TextView row = new TextView(this);
        row.setText(ip);
        row.setTextColor(0xFFFFC9A6);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.setPadding((int) dp(10), (int) dp(9), (int) dp(10), (int) dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor(0x10FFFFFF);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(4);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hostInput.setText(ip);
                connect(ip);
            }
        });
        foundList.addView(row, lp);
    }

    // -------------------------------------------------------------------- link
    @Override public void onLinkState(final boolean up, final String detail) {
        connected = up;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(up ? 0xFF8FE3C0 : 0xFFFF9C86);
        statusDot.setBackground(dot);

        if (up) {
            statusText.setText(buddyName + " · linked");
            closePanel();
            startSensors();
            panelHint.setText("");
            face.setMood(sleeping ? "sleeping" : "happy");
        } else {
            statusText.setText("offline · tap ≡ to connect");
            stopSensors();
            face.setMood("bored");
            if (panel.getVisibility() != View.VISIBLE && prefs.host().length() == 0) openPanel(true);
        }
    }

    @Override public void onMessage(Map<String, String> m) {
        String t = Json.str(m, "t", "");

        if ("react".equals(t)) {
            String e = Json.str(m, "e", "");
            float v = Json.num(m, "v", 1f);
            face.react(e, v);
            if ("poke".equals(e) || "shake".equals(e)) haptic();
            String mood = Json.str(m, "mood", "");
            if (mood.length() > 0 && !sleeping) face.setMood(mood);
            return;
        }

        if ("say".equals(t)) {
            showSpeech(Json.str(m, "text", ""));
            return;
        }

        // hello carries a nested state; state messages are flat
        if ("state".equals(t) || (m.containsKey("hap") && m.containsKey("name"))) {
            String name = Json.str(m, "name", buddyName);
            if (name.length() > 0) buddyName = name;
            int lvl = Json.integer(m, "lvl", 1);
            int hap = Json.integer(m, "hap", 0);
            int en = Json.integer(m, "en", 0);
            int full = Json.integer(m, "full", 0);
            sleeping = Json.bool(m, "sleep", false);
            String mood = Json.str(m, "mood", "happy");
            face.setMood(sleeping ? "sleeping" : mood);
            if (connected) statusText.setText(buddyName + " · Lv " + lvl);
            statsText.setText(String.format(Locale.US,
                    "happiness %d   ·   energy %d   ·   food %d", hap, en, full));
        }
    }

    private boolean sleeping = false;

    private void setDisplayMode(boolean on) {
        displayMode = on;
        prefs.setDisplay(on);
        if (displayBox != null) displayBox.setChecked(on);
        applyDisplayMode();
    }

    private void applyDisplayMode() {
        if (topBar != null) topBar.setVisibility(displayMode ? View.GONE : View.VISIBLE);
        if (bottomBar != null) bottomBar.setVisibility(displayMode ? View.GONE : View.VISIBLE);
        if (face != null) face.setFaceOnly(displayMode);
        if (displayMode) {
            closePanel();
            if (speech != null) {
                speech.animate().cancel();
                speech.setAlpha(0f);
                speech.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void showSpeech(String message) {
        if (displayMode) return;
        if (message == null || message.length() == 0) return;
        speech.setText(message);
        speech.setVisibility(View.VISIBLE);
        speech.animate().alpha(1f).setDuration(180).start();
        ui.removeCallbacks(hideSpeech);
        ui.postDelayed(hideSpeech, Math.min(9000, 2600 + message.length() * 55));
    }

    private final Runnable hideSpeech = new Runnable() {
        @Override public void run() {
            speech.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                @Override public void run() { speech.setVisibility(View.INVISIBLE); }
            }).start();
        }
    };

    private void command(String cmd) {
        link.send("{\"t\":\"cmd\",\"c\":" + Json.quote(cmd) + "}");
    }

    private void haptic() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(18);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ face events
    @Override public void onPoke(float x, float y) {
        link.send(String.format(Locale.US,
                "{\"t\":\"touch\",\"kind\":\"tap\",\"x\":%.2f,\"y\":%.2f}", x, y));
        haptic();
    }

    @Override public void onExitDisplay() {
        if (displayMode) setDisplayMode(false);
    }

    @Override public void onPet(float amount) {
        link.send(String.format(Locale.US,
                "{\"t\":\"touch\",\"kind\":\"pet\",\"v\":%.2f}", amount));
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && panel.getVisibility() == View.VISIBLE) {
            closePanel();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
