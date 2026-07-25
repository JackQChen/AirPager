package com.airpager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity implements DecodeBus.Listener {

    private TextView textOutput;
    private TextView textStatus;
    private ScrollView scrollView;
    private SeekBar seekCarrier;
    private final StringBuilder log = new StringBuilder();

    // 置信度: 进度0~40 映射 1.0~5.0，步长0.1
    private static final float CONF_MIN = 1.0f;
    private static final float CONF_STEP = 0.1f;
    private static final int CONF_DEFAULT_PROGRESS = 14; // 1.0 + 14*0.1 = 2.4

    // 载波门限: 进度0~200 映射 0.0~2.0，步长0.01（归一化后的能量尺度）
    private static final float CARRIER_STEP = 0.01f;
    private static final int CARRIER_DEFAULT_PROGRESS = 5; // 0.05

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textOutput = findViewById(R.id.textOutput);
        textStatus = findViewById(R.id.textStatus);
        scrollView = findViewById(R.id.scrollView);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        TextView labelConfidence = findViewById(R.id.textConfidenceLabel);
        SeekBar seekConfidence = findViewById(R.id.seekConfidence);
        seekConfidence.setMax(40);
        seekConfidence.setProgress(CONF_DEFAULT_PROGRESS);
        Bell202Decoder.confidenceThreshold = CONF_MIN + CONF_DEFAULT_PROGRESS * CONF_STEP;
        labelConfidence.setText(String.format("置信度阈值: %.1f",
                Bell202Decoder.confidenceThreshold));
        seekConfidence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = CONF_MIN + progress * CONF_STEP;
                Bell202Decoder.confidenceThreshold = value;
                labelConfidence.setText(String.format("置信度阈值: %.1f", value));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        TextView labelCarrier = findViewById(R.id.textCarrierLabel);
        seekCarrier = findViewById(R.id.seekCarrier);
        seekCarrier.setMax(200);
        seekCarrier.setProgress(CARRIER_DEFAULT_PROGRESS);
        Bell202Decoder.carrierThreshold = CARRIER_DEFAULT_PROGRESS * CARRIER_STEP;
        labelCarrier.setText(String.format("载波门限: %.3f", Bell202Decoder.carrierThreshold));
        seekCarrier.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress * CARRIER_STEP;
                Bell202Decoder.carrierThreshold = value;
                labelCarrier.setText(String.format("载波门限: %.3f", value));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        Button btnCalibrate = findViewById(R.id.btnCalibrate);
        btnCalibrate.setOnClickListener(v -> {
            if (!DecodeService.isRunning) {
                textStatus.setText("请先点Start，再校准");
                return;
            }
            textStatus.setText("校准中，请保持安静...");
            Intent intent = new Intent(this, DecodeService.class);
            intent.setAction(DecodeService.ACTION_CALIBRATE);
            startService(intent);
        });

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
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onStatus(String status) { textStatus.setText(status); }

    @Override
    public void onCalibrated(float recommendedThreshold) {
        int progress = Math.round(recommendedThreshold / CARRIER_STEP);
        if (progress > seekCarrier.getMax()) progress = seekCarrier.getMax();
        if (progress < 0) progress = 0;
        seekCarrier.setProgress(progress); // 触发上面注册的联动逻辑，自动更新阈值和文字
        textStatus.setText(String.format("校准完成，建议门限 %.3f", recommendedThreshold));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}