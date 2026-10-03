package com.secuready.mirrorking;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "MirrorKingMain";

    private EditText hostInput;
    private EditText portInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 60, 40, 40);

        TextView title = new TextView(this);
        title.setText("MirrorKing Stealth Guardian");
        title.setTextSize(22);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Zero-Alert Reverse Tunnel for Broken Screens");
        desc.setPadding(0, 10, 0, 30);
        layout.addView(desc);

        SharedPreferences sp = getSharedPreferences("mirrorking_prefs", MODE_PRIVATE);
        String savedHost = sp.getString("host", "127.0.0.1");
        int savedPort = sp.getInt("port", Config.DEFAULT_PORT);

        hostInput = new EditText(this);
        hostInput.setHint("PC / Relay Host (e.g. your-app.onrender.com)");
        hostInput.setText(savedHost);
        layout.addView(hostInput);

        portInput = new EditText(this);
        portInput.setHint("Port (default: 8888 or 443)");
        portInput.setText(String.valueOf(savedPort));
        layout.addView(portInput);

        Button startBtn = new Button(this);
        startBtn.setText("Save & Start Stealth Tunnel");
        startBtn.setOnClickListener(this);
        layout.addView(startBtn);

        setContentView(layout);

        String host = getIntent().getStringExtra("host");
        int port = getIntent().getIntExtra("port", 0);

        if (host != null && !host.isEmpty()) {
            startMirrorKing(host, port > 0 ? port : Config.DEFAULT_PORT);
        }
    }

    @Override
    public void onClick(View v) {
        String host = hostInput.getText().toString().trim();
        int port = Config.DEFAULT_PORT;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (Exception ignored) {}

        startMirrorKing(host, port);
    }

    private void startMirrorKing(String host, int port) {
        // Save to SharedPreferences for persistence across reboots
        SharedPreferences sp = getSharedPreferences("mirrorking_prefs", MODE_PRIVATE);
        sp.edit().putString("host", host).putInt("port", port).apply();

        Intent serviceIntent = new Intent(this, MirrorKingService.class);
        serviceIntent.putExtra("host", host);
        serviceIntent.putExtra("port", port);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Log.i(TAG, "MirrorKing Stealth Service started for " + host + ":" + port);
        // Pure stealth: close activity immediately without MediaProjection prompts
        finish();
    }
}
