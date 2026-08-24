package com.example.chatgptlocalapi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "bridge_settings";

    private EditText apiKeyInput;
    private EditText baseUrlInput;
    private EditText modelInput;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("ChatGPT Local API");

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("ChatGPT Local API Bridge");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("\nRuns an OpenAI-compatible API on this phone only.\n"
                + "Local base URL: http://127.0.0.1:8787/v1\n");
        intro.setTextSize(15);
        root.addView(intro);

        addLabel(root, "OpenAI API Key");
        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setHint("sk-...");
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setText(prefs.getString("api_key", ""));
        root.addView(apiKeyInput, matchWrap());

        addLabel(root, "Upstream base URL");
        baseUrlInput = new EditText(this);
        baseUrlInput.setSingleLine(true);
        baseUrlInput.setText(prefs.getString("base_url", "https://api.openai.com/v1"));
        root.addView(baseUrlInput, matchWrap());

        addLabel(root, "Default model");
        modelInput = new EditText(this);
        modelInput.setSingleLine(true);
        modelInput.setText(prefs.getString("model", "chat-latest"));
        root.addView(modelInput, matchWrap());

        Button startButton = new Button(this);
        startButton.setText("Save & Start API");
        startButton.setOnClickListener(v -> startBridge());
        root.addView(startButton, matchWrap());

        Button stopButton = new Button(this);
        stopButton.setText("Stop API");
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, LocalApiService.class));
            Toast.makeText(this, "API stopped", Toast.LENGTH_SHORT).show();
            root.postDelayed(this::refreshStatus, 250);
        });
        root.addView(stopButton, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, dp(14), 0, 0);
        root.addView(statusText, matchWrap());

        TextView help = new TextView(this);
        help.setText("\nEndpoints:\n"
                + "GET  /health\n"
                + "GET  /v1/models\n"
                + "POST /v1/chat/completions\n"
                + "POST /v1/responses\n\n"
                + "The bridge listens on loopback only, so other devices on Wi-Fi cannot connect.");
        help.setTextSize(14);
        root.addView(help, matchWrap());

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void startBridge() {
        String apiKey = apiKeyInput.getText().toString().trim();
        String baseUrl = baseUrlInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Enter an OpenAI API key first", Toast.LENGTH_LONG).show();
            return;
        }
        if (baseUrl.isEmpty()) {
            baseUrl = "https://api.openai.com/v1";
            baseUrlInput.setText(baseUrl);
        }
        if (model.isEmpty()) {
            model = "chat-latest";
            modelInput.setText(model);
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("api_key", apiKey)
                .putString("base_url", baseUrl)
                .putString("model", model)
                .apply();

        Intent intent = new Intent(this, LocalApiService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Starting API on port 8787", Toast.LENGTH_SHORT).show();
        statusText.postDelayed(this::refreshStatus, 350);
    }

    private void refreshStatus() {
        if (statusText == null) return;
        statusText.setText(LocalApiService.RUNNING
                ? "Status: RUNNING\nhttp://127.0.0.1:8787/v1"
                : "Status: STOPPED\nPort: 8787");
    }

    private void addLabel(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText("\n" + text);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(14);
        root.addView(label, matchWrap());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
