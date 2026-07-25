package com.airpager;

public class Bell202Decoder {

    private static final int BAUD = 1200;
    private static final float F_MARK = 1200f;
    private static final float F_SPACE = 2200f;

    // 置信度阈值：强势频段能量需达到弱势频段的多少倍才算"确信"判定
    // 设为静态字段方便Activity的SeekBar直接跨类实时调整（同进程内共享）
    public static volatile float confidenceThreshold = 2.4f;

    private final int sampleRate;
    private final int samplesPerBit;
    private final short[] ring;
    private int ringFill = 0;
    private int ringPos = 0;

    private final DecodeBus.Listener listener;

    private static final int ST_IDLE = 0;
    private static final int ST_DATA = 1;
    private int state = ST_IDLE;

    private int sampleCounter = 0;
    private int bitsCollected = 0;
    private int byteAcc = 0;
    private boolean lastMark = true;

    public Bell202Decoder(int sampleRate, DecodeBus.Listener listener) {
        this.sampleRate = sampleRate;
        this.samplesPerBit = Math.round(sampleRate / (float) BAUD);
        this.ring = new short[samplesPerBit];
        this.listener = listener;
    }

    public void feed(short[] buf, int len) {
        for (int i = 0; i < len; i++) {
            ring[ringPos] = buf[i];
            ringPos = (ringPos + 1) % samplesPerBit;
            if (ringFill < samplesPerBit) ringFill++;
            sampleCounter++;

            if (ringFill < samplesPerBit) continue;

            boolean isMark = classifyWindow();

            switch (state) {
                case ST_IDLE:
                    if (lastMark && !isMark) {
                        state = ST_DATA;
                        sampleCounter = 0;
                        bitsCollected = 0;
                        byteAcc = 0;
                    }
                    break;

                case ST_DATA:
                    int targetSample = (int) ((bitsCollected + 1.5) * samplesPerBit);
                    if (sampleCounter >= targetSample) {
                        if (bitsCollected < 8) {
                            if (isMark) byteAcc |= (1 << bitsCollected);
                            bitsCollected++;
                        } else {
                            if (isMark) {
                                char c = (char) (byteAcc & 0x7F);
                                if (listener != null) listener.onChar(c);
                            }
                            state = ST_IDLE;
                        }
                    }
                    break;
            }
            lastMark = isMark;
        }
    }

    /** 带置信度判定：能量差距不够大时，保持上一次的判定结果，而不是强行翻转 */
    private boolean classifyWindow() {
        float magMark = goertzelMagnitude(F_MARK);
        float magSpace = goertzelMagnitude(F_SPACE);

        float ratio;
        boolean dominantIsMark;
        if (magMark >= magSpace) {
            dominantIsMark = true;
            ratio = (magSpace <= 0.0001f) ? Float.MAX_VALUE : magMark / magSpace;
        } else {
            dominantIsMark = false;
            ratio = (magMark <= 0.0001f) ? Float.MAX_VALUE : magSpace / magMark;
        }

        if (ratio < confidenceThreshold) {
            return lastMark; // 置信度不够，沿用上一次判定
        }
        return dominantIsMark;
    }

    private float goertzelMagnitude(float targetFreq) {
        double k = (samplesPerBit * targetFreq) / sampleRate;
        double omega = (2.0 * Math.PI * k) / samplesPerBit;
        double cosine = Math.cos(omega);
        double coeff = 2.0 * cosine;

        double q0, q1 = 0, q2 = 0;
        for (int i = 0; i < samplesPerBit; i++) {
            int idx = (ringPos + i) % samplesPerBit;
            double sample = ring[idx];
            q0 = coeff * q1 - q2 + sample;
            q2 = q1;
            q1 = q0;
        }
        double sine = Math.sin(omega);
        double real = q1 - q2 * cosine;
        double imag = q2 * sine;
        return (float) Math.sqrt(real * real + imag * imag);
    }
}