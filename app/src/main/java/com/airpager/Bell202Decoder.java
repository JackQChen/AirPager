package com.airpager;

public class Bell202Decoder {

    private static final int BAUD = 1200;
    private static final float F_MARK = 1200f;   // 逻辑1 / 空闲态
    private static final float F_SPACE = 2200f;  // 逻辑0

    private final int sampleRate;
    private final int samplesPerBit;
    private final short[] ring;
    private int ringFill = 0;
    private int ringPos = 0;

    private final DecodeBus.Listener listener;

    private static final int ST_IDLE = 0; // 等待起始位（mark->space跳变）
    private static final int ST_DATA = 1; // 采集数据位中
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

    /** 持续喂入PCM采样点 */
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
                            if (isMark) byteAcc |= (1 << bitsCollected); // LSB优先
                            bitsCollected++;
                        } else {
                            if (isMark) { // 停止位校验通过
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

    private boolean classifyWindow() {
        return goertzelMagnitude(F_MARK) >= goertzelMagnitude(F_SPACE);
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