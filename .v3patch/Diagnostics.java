package com.example.chatgptlocalapi;

import org.json.JSONObject;

public final class Diagnostics {
    private Diagnostics() {}
    public static volatile long requests = 0;
    public static volatile long toolResponses = 0;
    public static volatile String lastEndpoint = "-";
    public static volatile String lastState = "idle";
    public static volatile String lastError = "";
    public static volatile String lastWebText = "";

    public static void request(String endpoint) {
        requests++;
        lastEndpoint = endpoint;
        lastState = "request_received";
        lastError = "";
    }
    public static void state(String s) { lastState = s == null ? "" : s; }
    public static void error(String e) { lastError = e == null ? "" : e; lastState = "error"; }
    public static void webText(String s) { lastWebText = s == null ? "" : (s.length() > 600 ? s.substring(0,600) : s); }

    public static String json() {
        JSONObject j = new JSONObject();
        try {
            j.put("requests", requests);
            j.put("tool_responses", toolResponses);
            j.put("last_endpoint", lastEndpoint);
            j.put("last_state", lastState);
            j.put("last_error", lastError);
            j.put("web_session", BrowserBridge.status());
            j.put("last_web_text", lastWebText);
            return j.toString();
        } catch (Exception e) {
            return "{\"last_state\":\"diagnostics_error\",\"last_error\":\"" + safe(e.toString()) + "\"}";
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
