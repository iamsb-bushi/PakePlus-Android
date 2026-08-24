package com.example.chatgptlocalapi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalApiService extends Service {
    public static final int PORT = 8787;
    public static volatile boolean RUNNING = false;

    private static final String PREFS = "bridge_settings";
    private static final String CHANNEL_ID = "local_api_bridge";

    private ServerSocket serverSocket;
    private ExecutorService acceptExecutor;
    private ExecutorService clientExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(8787, buildNotification("Listening on 127.0.0.1:" + PORT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RUNNING) {
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServer() {
        acceptExecutor = Executors.newSingleThreadExecutor();
        clientExecutor = Executors.newCachedThreadPool();

        acceptExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), PORT));
                RUNNING = true;
                updateNotification("Running on 127.0.0.1:" + PORT);

                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    clientExecutor.execute(() -> handleClient(socket));
                }
            } catch (IOException ignored) {
            } finally {
                RUNNING = false;
                updateNotification("Stopped");
            }
        });
    }

    private void stopServer() {
        RUNNING = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (acceptExecutor != null) acceptExecutor.shutdownNow();
        if (clientExecutor != null) clientExecutor.shutdownNow();
        stopForeground(true);
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {

            s.setSoTimeout(120000);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) {
                sendJson(out, 400, errorJson("Malformed HTTP request"));
                return;
            }

            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) path = path.substring(0, queryIndex);

            Map<String, String> headers = new HashMap<>();
            while (true) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(
                            line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }

            if ("OPTIONS".equals(method)) {
                sendNoContent(out);
                return;
            }

            int contentLength = 0;
            try {
                contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }

            byte[] body = readExactly(in, contentLength);

            if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject json = new JSONObject();
                json.put("status", "ok");
                json.put("listen", "127.0.0.1");
                json.put("port", PORT);
                sendJson(out, 200, json.toString());
                return;
            }

            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String defaultModel = prefs.getString("model", "chat-latest");

            if ("GET".equals(method) && "/v1/models".equals(path)) {
                String escaped = JSONObject.quote(defaultModel);
                String response = "{\"object\":\"list\",\"data\":[{\"id\":"
                        + escaped
                        + ",\"object\":\"model\",\"created\":0,\"owned_by\":\"openai\"}]}";
                sendJson(out, 200, response);
                return;
            }

            boolean allowedPost = "POST".equals(method)
                    && ("/v1/chat/completions".equals(path)
                    || "/v1/responses".equals(path));

            if (!allowedPost) {
                sendJson(out, 404, errorJson("Unknown endpoint"));
                return;
            }

            String apiKey = prefs.getString("api_key", "").trim();
            String baseUrl = prefs.getString("base_url", "https://api.openai.com/v1").trim();

            if (apiKey.isEmpty()) {
                sendJson(out, 503, errorJson("OpenAI API key is not configured in the app"));
                return;
            }

            byte[] requestBody = body;
            try {
                JSONObject requestJson = new JSONObject(
                        new String(body, StandardCharsets.UTF_8));
                if (!requestJson.has("model")
                        || requestJson.optString("model", "").trim().isEmpty()) {
                    requestJson.put("model", defaultModel);
                }
                requestBody = requestJson.toString().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                sendJson(out, 400, errorJson("Request body must be valid JSON"));
                return;
            }

            proxyToOpenAI(out, path, baseUrl, apiKey, requestBody, headers);
        } catch (Exception ignored) {
        }
    }

    private void proxyToOpenAI(
            OutputStream clientOut,
            String localPath,
            String baseUrl,
            String apiKey,
            byte[] requestBody,
            Map<String, String> clientHeaders) throws IOException {

        String target = buildUpstreamUrl(baseUrl, localPath);
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(0);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept",
                clientHeaders.getOrDefault("accept", "*/*"));

        try (OutputStream upstreamOut = connection.getOutputStream()) {
            upstreamOut.write(requestBody);
        }

        int status = connection.getResponseCode();
        String contentType = connection.getContentType();
        if (contentType == null || contentType.trim().isEmpty()) {
            contentType = "application/json; charset=utf-8";
        }

        InputStream upstreamIn;
        if (status >= 400) {
            upstreamIn = connection.getErrorStream();
        } else {
            upstreamIn = connection.getInputStream();
        }

        writeHead(clientOut, status, contentType);

        if (upstreamIn != null) {
            try (InputStream source = upstreamIn) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    clientOut.write(buffer, 0, read);
                    clientOut.flush();
                }
            }
        }
        connection.disconnect();
    }

    private String buildUpstreamUrl(String baseUrl, String localPath) {
        String base = baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        if (base.endsWith("/v1") && localPath.startsWith("/v1/")) {
            return base + localPath.substring(3);
        }
        return base + localPath;
    }

    private void sendJson(OutputStream out, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String reason = reasonFor(status);
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + corsHeaders()
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private void sendNoContent(OutputStream out) throws IOException {
        String head = "HTTP/1.1 204 No Content\r\n"
                + corsHeaders()
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void writeHead(OutputStream out, int status, String contentType) throws IOException {
        String head = "HTTP/1.1 " + status + " " + reasonFor(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + corsHeaders()
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private String corsHeaders() {
        return "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Authorization, Content-Type\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n";
    }

    private String errorJson(String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("message", message);
            error.put("type", "local_bridge_error");
            JSONObject wrapper = new JSONObject();
            wrapper.put("error", error);
            return wrapper.toString();
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"Local bridge error\"}}";
        }
    }

    private String reasonFor(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Upstream Response";
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;

        while (buffer.size() < 16384) {
            int current = in.read();
            if (current == -1) {
                if (buffer.size() == 0) return null;
                break;
            }

            if (previous == '\r' && current == '\n') {
                byte[] raw = buffer.toByteArray();
                int length = raw.length > 0 && raw[raw.length - 1] == '\r'
                        ? raw.length - 1 : raw.length;
                return new String(raw, 0, length, StandardCharsets.US_ASCII);
            }

            buffer.write(current);
            previous = current;
        }

        return new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
    }

    private byte[] readExactly(InputStream in, int length) throws IOException {
        if (length <= 0) return new byte[0];

        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(result, offset, length - offset);
            if (read == -1) break;
            offset += read;
        }

        if (offset == length) return result;

        byte[] shortened = new byte[offset];
        System.arraycopy(result, 0, shortened, 0, offset);
        return shortened;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Local API service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the localhost OpenAI-compatible bridge running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("ChatGPT Local API")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(8787, buildNotification(text));
        }
    }
}
