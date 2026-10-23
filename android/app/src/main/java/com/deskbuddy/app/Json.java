package com.deskbuddy.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny flat-JSON reader for the small messages DeskBuddy exchanges.
 * The first occurrence of a key wins, so outer fields beat fields nested
 * inside {@code state}.
 */
final class Json {

    private Json() {}

    static Map<String, String> flat(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null) return out;
        int i = 0, n = s.length();
        while (i < n) {
            int q = s.indexOf('"', i);
            if (q < 0) break;
            int ke = endOfString(s, q + 1);
            if (ke < 0) break;
            String key = unescape(s.substring(q + 1, ke));
            int c = ke + 1;
            while (c < n && Character.isWhitespace(s.charAt(c))) c++;
            if (c >= n || s.charAt(c) != ':') { i = ke + 1; continue; }
            c++;
            while (c < n && Character.isWhitespace(s.charAt(c))) c++;
            String value;
            if (c < n && s.charAt(c) == '"') {
                int ve = endOfString(s, c + 1);
                if (ve < 0) break;
                value = unescape(s.substring(c + 1, ve));
                i = ve + 1;
            } else {
                int ve = c;
                while (ve < n && s.charAt(ve) != ',' && s.charAt(ve) != '}') ve++;
                value = s.substring(c, ve).trim();
                i = ve + 1;
            }
            if (!out.containsKey(key)) out.put(key, value);
        }
        return out;
    }

    private static int endOfString(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') { i++; continue; }
            if (ch == '"') return i;
        }
        return -1;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) { b.append(ch); continue; }
            char nx = s.charAt(++i);
            switch (nx) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Exception e) { /* ignore */ }
                    }
                    break;
                default: b.append(nx);
            }
        }
        return b.toString();
    }

    static String str(Map<String, String> m, String key, String def) {
        String v = m.get(key);
        return v == null ? def : v;
    }

    static float num(Map<String, String> m, String key, float def) {
        String v = m.get(key);
        if (v == null) return def;
        try { return Float.parseFloat(v); } catch (Exception e) { return def; }
    }

    static int integer(Map<String, String> m, String key, int def) {
        return Math.round(num(m, key, def));
    }

    static boolean bool(Map<String, String> m, String key, boolean def) {
        String v = m.get(key);
        if (v == null) return def;
        return "true".equals(v) || "1".equals(v);
    }

    static String quote(String raw) {
        if (raw == null) return "\"\"";
        StringBuilder b = new StringBuilder(raw.length() + 8);
        b.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else if (ch < 0x20) b.append(' ');
            else b.append(ch);
        }
        b.append('"');
        return b.toString();
    }
}
