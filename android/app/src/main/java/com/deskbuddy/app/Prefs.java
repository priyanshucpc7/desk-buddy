package com.deskbuddy.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Remember the buddy's address and local preferences. */
final class Prefs {
    private static final String FILE = "deskbuddy";
    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String host() {
        return sp.getString("host", "");
    }

    void setHost(String host) {
        sp.edit().putString("host", host).apply();
    }

    boolean mic() {
        return sp.getBoolean("mic", true);
    }

    void setMic(boolean v) {
        sp.edit().putBoolean("mic", v).apply();
    }

    boolean awake() {
        return sp.getBoolean("awake", true);
    }

    void setAwake(boolean v) {
        sp.edit().putBoolean("awake", v).apply();
    }

    boolean tutorialSeen() {
        return sp.getBoolean("seen", false);
    }

    void setTutorialSeen() {
        sp.edit().putBoolean("seen", true).apply();
    }
}
