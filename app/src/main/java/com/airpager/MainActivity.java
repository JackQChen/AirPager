package com.airpager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity implements DecodeBus.Listener {

    private TextView textOutput;
    private TextView textStatus;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textOutput = findViewById(R.id.textOutput);
        textOutput.setMovementMethod(new ScrollingMovementMethod());
        textStatus = findViewById(R.id.textStatus);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        Button btn = findViewById(R.id.btnToggle);
        btn.setOnClickListener(v -> {
            Intent intent = new Intent(this, DecodeService.class);
            if (!DecodeService.isRunning) {
                intent.setAction(DecodeService.ACTION_START);
                startForegroundService(intent);
                btn.setText("Stop");
                textStatus.setText("监听中...");
            } else {
                intent.setAction(DecodeService.ACTION_STOP);
                startService(intent);
                btn.setText("Start");
                textStatus.setText("已停止");
            }
        });
    }

    @Override
    protected void onResume() { super.onResume(); DecodeBus.listener = this; }

    @Override
    protected void onPause() { super.onPause(); DecodeBus.listener = null; }

    @Override
    public void onChar(char c) {
        log.append(c);
        textOutput.setText(log.toString());
    }

    @Override
    public void onStatus(String status) { textStatus.setText(status); }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}