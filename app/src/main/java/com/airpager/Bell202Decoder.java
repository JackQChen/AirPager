package com.airpager;

public class Bell202Decoder {

    private static final int BAUD = 1200;
    private static final float F_MARK = 1200f;
    private static final float F_SPACE = 2200f;

    public static volatile float confidenceThreshold = 2.4f;
    // 载波门限：mark+space总能量低于这个值，判定"当前无信号"，直接跳过，不进入解码
    public static volatile float carrierThreshold = 500f;

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

            float magMark = goertzelMagnitude(F_MARK);
            float magSpace = goertzelMagnitude(F_SPACE);

            // 关键新增：没有载波就直接复位，不进入任何解码逻辑
            if (magMark + magSpace < carrierThreshold) {
                state = ST_IDLE;
                lastMark = true;
                continue;
            }

            boolean isMark = classify(magMark, magSpace);

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

    private boolean classify(float magMark, float magSpace) {
        float ratio;
        boolean dominantIsMark;
        if (magMark >= magSpace) {
            dominantIsMark = true;
            ratio = (magSpace <= 0.0001f) ? Float.MAX_VALUE : magMark / magSpace;
        } else {
            dominantIsMark = false;
            ratio = (magMark <= 0.0001f) ? Float.MAX_VALUE : magSpace / magMark;
        }
        if (ratio < confidenceThreshold) return lastMark;
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