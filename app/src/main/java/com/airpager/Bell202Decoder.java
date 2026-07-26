package com.airpager;

public class Bell202Decoder {

    private static final int BAUD = 1200;
    private static final float F_MARK = 1200f;
    private static final float F_SPACE = 2200f;

    // 载波检测窗口带宽：只用来判断"现在有没有信号"，可以宽一点，抗噪声更好
    private static final float CARRIER_BANDWIDTH_HZ = 300f;

    // bit判定窗口：严格等于一个符号周期长度(带宽=波特率)，
    // 绝不能加宽，否则会把相邻bit的能量搅在一起(上一版的bug就在这里)

    private static final int ANALYSIS_STEP = 2;
    private static final int DEBOUNCE_LEN = 3; // 窗口本来就短，去抖动不宜太长，否则引入额外延迟

    public static volatile float confidenceThreshold = 2.4f;
    public static volatile float carrierThreshold = 0.05f;

    private final int sampleRate;
    private final int samplesPerBit;      // 同时也是bit判定窗口长度
    private final int carrierWindowSamples;

    private final float[] bitRing;
    private int bitRingPos = 0;
    private int bitRingFill = 0;

    private final float[] carrierRing;
    private int carrierRingPos = 0;
    private int carrierRingFill = 0;

    private int stepCounter = 0;

    private final boolean[] history;
    private int historyIdx = 0;
    private int historyMarkCount = 0;

    private boolean debouncedIsMark = true;
    private float lastCarrierMagMark = 0f;
    private float lastCarrierMagSpace = 0f;

    private final DecodeBus.Listener listener;

    private static final int ST_IDLE = 0;
    private static final int ST_DATA = 1;
    private int state = ST_IDLE;

    private int sampleCounter = 0;
    private int bitsCollected = 0;
    private int byteAcc = 0;

    private volatile boolean calibrating = false;
    private long calibrationEndAtMs = 0;
    private double calibrationSum = 0;
    private long calibrationCount = 0;

    public Bell202Decoder(int sampleRate, DecodeBus.Listener listener) {
        this.sampleRate = sampleRate;
        this.samplesPerBit = Math.round(sampleRate / (float) BAUD);
        this.carrierWindowSamples = Math.max(
                samplesPerBit, Math.round(sampleRate / CARRIER_BANDWIDTH_HZ));
        this.bitRing = new float[samplesPerBit];
        this.carrierRing = new float[carrierWindowSamples];
        this.history = new boolean[DEBOUNCE_LEN];
        this.listener = listener;
    }

    public void startCalibration(long durationMs) {
        calibrationSum = 0;
        calibrationCount = 0;
        calibrationEndAtMs = System.currentTimeMillis() + durationMs;
        calibrating = true;
    }

    public void feed(short[] buf, int len) {
        for (int i = 0; i < len; i++) {
            float sample = buf[i] / 32768f;

            bitRing[bitRingPos] = sample;
            bitRingPos = (bitRingPos + 1) % samplesPerBit;
            if (bitRingFill < samplesPerBit) bitRingFill++;

            carrierRing[carrierRingPos] = sample;
            carrierRingPos = (carrierRingPos + 1) % carrierWindowSamples;
            if (carrierRingFill < carrierWindowSamples) carrierRingFill++;

            sampleCounter++;

            if (bitRingFill < samplesPerBit || carrierRingFill < carrierWindowSamples) continue;

            stepCounter++;
            if (stepCounter >= ANALYSIS_STEP) {
                stepCounter = 0;
                recomputeCarrier();
                recomputeBitClassification();
            }

            if (calibrating) {
                calibrationSum += (lastCarrierMagMark + lastCarrierMagSpace);
                calibrationCount++;
                if (System.currentTimeMillis() >= calibrationEndAtMs) {
                    calibrating = false;
                    float avg = calibrationCount > 0
                            ? (float) (calibrationSum / calibrationCount) : 0f;
                    float recommended = avg * 3.0f + 0.005f;
                    carrierThreshold = recommended;
                    if (listener != null) listener.onCalibrated(recommended);
                }
                continue;
            }

            boolean hasCarrier = (lastCarrierMagMark + lastCarrierMagSpace) >= carrierThreshold;
            if (!hasCarrier) {
                state = ST_IDLE;
                continue;
            }

            switch (state) {
                case ST_IDLE:
                    if (!debouncedIsMark) {
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
                            if (debouncedIsMark) byteAcc |= (1 << bitsCollected);
                            bitsCollected++;
                        } else {
                            if (debouncedIsMark) {
                                char c = (char) (byteAcc & 0x7F);
                                if (listener != null) listener.onChar(c);
                            }
                            state = ST_IDLE;
                        }
                    }
                    break;
            }
        }
    }

    /** 载波能量检测：宽窗口，只回答"现在有没有信号"，不参与具体bit判定 */
    private void recomputeCarrier() {
        lastCarrierMagMark = goertzelMagnitude(carrierRing, carrierRingPos, carrierWindowSamples, F_MARK);
        lastCarrierMagSpace = goertzelMagnitude(carrierRing, carrierRingPos, carrierWindowSamples, F_SPACE);
    }

    /** bit分类：窗口严格等于一个符号周期，避免相邻bit的能量互相污染 */
    private void recomputeBitClassification() {
        float magMark = goertzelMagnitude(bitRing, bitRingPos, samplesPerBit, F_MARK);
        float magSpace = goertzelMagnitude(bitRing, bitRingPos, samplesPerBit, F_SPACE);

        boolean rawIsMark;
        float ratio;
        if (magMark >= magSpace) {
            rawIsMark = true;
            ratio = (magSpace <= 1e-6f) ? Float.MAX_VALUE : magMark / magSpace;
        } else {
            rawIsMark = false;
            ratio = (magMark <= 1e-6f) ? Float.MAX_VALUE : magSpace / magMark;
        }
        if (ratio < confidenceThreshold) {
            rawIsMark = debouncedIsMark;
        }

        boolean old = history[historyIdx];
        if (old) historyMarkCount--;
        history[historyIdx] = rawIsMark;
        if (rawIsMark) historyMarkCount++;
        historyIdx = (historyIdx + 1) % DEBOUNCE_LEN;

        debouncedIsMark = historyMarkCount * 2 >= DEBOUNCE_LEN;
    }

    private float goertzelMagnitude(float[] ring, int ringPos, int windowSize, float targetFreq) {
        double k = (windowSize * targetFreq) / sampleRate;
        double omega = (2.0 * Math.PI * k) / windowSize;
        double cosine = Math.cos(omega);
        double coeff = 2.0 * cosine;

        double q0, q1 = 0, q2 = 0;
        for (int i = 0; i < windowSize; i++) {
            int idx = (ringPos + i) % windowSize;
            double s = ring[idx];
            q0 = coeff * q1 - q2 + s;
            q2 = q1;
            q1 = q0;
        }
        double sine = Math.sin(omega);
        double real = q1 - q2 * cosine;
        double imag = q2 * sine;
        return (float) (Math.sqrt(real * real + imag * imag) / (windowSize / 2.0));
    }
}