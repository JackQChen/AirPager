package com.airpager;

public class Bell202Decoder {

    private static final int BAUD = 1200;
    private static final float F_MARK = 1200f;
    private static final float F_SPACE = 2200f;

    // 分析窗口对应的目标带宽(Hz)：数值越小，滤波器选择性越好，抗噪能力越强
    // minimodem对Bell202默认用200Hz，这里取300Hz做时序响应速度和选择性的折中
    private static final float ANALYSIS_BANDWIDTH_HZ = 300f;

    // 每隔多少个原始采样点重新计算一次Goertzel（省计算量，不需要逐样本都算）
    private static final int ANALYSIS_STEP = 2;

    // 去抖动窗口长度：连续这么多次评估里占多数的结果，才采信为最终判定
    private static final int DEBOUNCE_LEN = 5;

    public static volatile float confidenceThreshold = 2.4f;
    // 载波门限：现在是"归一化(-1.0~1.0)后"的能量尺度，不再是原始int16尺度
    public static volatile float carrierThreshold = 0.05f;

    private final int sampleRate;
    private final int samplesPerBit;
    private final int analysisWindowSamples;

    private final float[] ring;
    private int ringPos = 0;
    private int ringFill = 0;

    private int stepCounter = 0;

    private final boolean[] history;
    private int historyIdx = 0;
    private int historyMarkCount = 0;

    private boolean debouncedIsMark = true;
    private float lastMagMark = 0f;
    private float lastMagSpace = 0f;

    private final DecodeBus.Listener listener;

    private static final int ST_IDLE = 0;
    private static final int ST_DATA = 1;
    private int state = ST_IDLE;

    private int sampleCounter = 0;
    private int bitsCollected = 0;
    private int byteAcc = 0;

    // 背景噪声校准相关
    private volatile boolean calibrating = false;
    private long calibrationEndAtMs = 0;
    private double calibrationSum = 0;
    private long calibrationCount = 0;

    public Bell202Decoder(int sampleRate, DecodeBus.Listener listener) {
        this.sampleRate = sampleRate;
        this.samplesPerBit = Math.round(sampleRate / (float) BAUD);
        this.analysisWindowSamples = Math.max(
                samplesPerBit, Math.round(sampleRate / ANALYSIS_BANDWIDTH_HZ));
        this.ring = new float[analysisWindowSamples];
        this.history = new boolean[DEBOUNCE_LEN];
        this.listener = listener;
    }

    /** 触发一次背景噪声校准，durationMs毫秒后通过listener.onCalibrated回调建议阈值 */
    public void startCalibration(long durationMs) {
        calibrationSum = 0;
        calibrationCount = 0;
        calibrationEndAtMs = System.currentTimeMillis() + durationMs;
        calibrating = true;
    }

    public void feed(short[] buf, int len) {
        for (int i = 0; i < len; i++) {
            float sample = buf[i] / 32768f; // 归一化到 -1.0~1.0，跟minimodem内部尺度对齐
            ring[ringPos] = sample;
            ringPos = (ringPos + 1) % analysisWindowSamples;
            if (ringFill < analysisWindowSamples) ringFill++;
            sampleCounter++;

            if (ringFill < analysisWindowSamples) continue;

            stepCounter++;
            if (stepCounter >= ANALYSIS_STEP) {
                stepCounter = 0;
                recomputeClassification();
            }

            // 校准模式：只统计能量水平，不跑解码状态机
            if (calibrating) {
                calibrationSum += (lastMagMark + lastMagSpace);
                calibrationCount++;
                if (System.currentTimeMillis() >= calibrationEndAtMs) {
                    calibrating = false;
                    float avg = calibrationCount > 0
                            ? (float) (calibrationSum / calibrationCount) : 0f;
                    float recommended = avg * 3.0f + 0.005f; // 留出余量，避免噪声波动漏判
                    carrierThreshold = recommended;
                    if (listener != null) listener.onCalibrated(recommended);
                }
                continue;
            }

            boolean hasCarrier = (lastMagMark + lastMagSpace) >= carrierThreshold;
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

    private void recomputeClassification() {
        lastMagMark = goertzelMagnitude(F_MARK);
        lastMagSpace = goertzelMagnitude(F_SPACE);

        boolean rawIsMark;
        float ratio;
        if (lastMagMark >= lastMagSpace) {
            rawIsMark = true;
            ratio = (lastMagSpace <= 1e-6f) ? Float.MAX_VALUE : lastMagMark / lastMagSpace;
        } else {
            rawIsMark = false;
            ratio = (lastMagMark <= 1e-6f) ? Float.MAX_VALUE : lastMagSpace / lastMagMark;
        }
        // 置信度不够时，这次评估维持"上一次去抖动后的结果"，不强行采信
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

    private float goertzelMagnitude(float targetFreq) {
        double k = (analysisWindowSamples * targetFreq) / sampleRate;
        double omega = (2.0 * Math.PI * k) / analysisWindowSamples;
        double cosine = Math.cos(omega);
        double coeff = 2.0 * cosine;

        double q0, q1 = 0, q2 = 0;
        for (int i = 0; i < analysisWindowSamples; i++) {
            int idx = (ringPos + i) % analysisWindowSamples;
            double s = ring[idx];
            q0 = coeff * q1 - q2 + s;
            q2 = q1;
            q1 = q0;
        }
        double sine = Math.sin(omega);
        double real = q1 - q2 * cosine;
        double imag = q2 * sine;
        // 按窗口长度归一化，使幅度量级和窗口长度无关，阈值才有稳定意义
        return (float) (Math.sqrt(real * real + imag * imag) / (analysisWindowSamples / 2.0));
    }
}