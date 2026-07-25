package com.airpager;

import android.app.*;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.*;

public class DecodeService extends Service {

    public static volatile boolean isRunning = false;
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";
    public static final String ACTION_CALIBRATE = "CALIBRATE";
    private static final String CHANNEL_ID = "airpager_channel";

    private Thread audioThread;
    private PowerManager.WakeLock wakeLock;
    private volatile Bell202Decoder decoder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopDecoding();
            return START_NOT_STICKY;
        }

        if (ACTION_CALIBRATE.equals(action)) {
            if (decoder != null) decoder.startCalibration(1000);
            return START_STICKY;
        }

        startForeground(1, buildNotification());
        startDecoding();
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Bell202 Decoder", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AirPager 正在监听")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
    }

    private void startDecoding() {
        if (isRunning) return;
        isRunning = true;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirPager::DecodeLock");
        wakeLock.acquire();

        audioThread = new Thread(() -> {
            AudioRecord recorder = null;
            try {
                int sampleRate = 48000;
                int minBuf = AudioRecord.getMinBufferSize(
                        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);

                recorder.startRecording();

                Handler main = new Handler(Looper.getMainLooper());
                DecodeBus.Listener uiProxy = new DecodeBus.Listener() {
                    @Override public void onChar(char c) {
                        main.post(() -> { if (DecodeBus.listener != null) DecodeBus.listener.onChar(c); });
                    }
                    @Override public void onStatus(String status) {
                        main.post(() -> { if (DecodeBus.listener != null) DecodeBus.listener.onStatus(status); });
                    }
                    @Override public void onCalibrated(float recommendedThreshold) {
                        main.post(() -> { if (DecodeBus.listener != null) DecodeBus.listener.onCalibrated(recommendedThreshold); });
                    }
                };

                decoder = new Bell202Decoder(sampleRate, uiProxy);

                short[] buf = new short[minBuf];
                while (isRunning) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n > 0) decoder.feed(buf, n);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception ignored) {}
                    recorder.release();
                }
                decoder = null;
                isRunning = false;
            }
        });
        audioThread.start();
    }

    private void stopDecoding() {
        isRunning = false;
        if (audioThread != null) {
            try { audioThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() { stopDecoding(); super.onDestroy(); }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}