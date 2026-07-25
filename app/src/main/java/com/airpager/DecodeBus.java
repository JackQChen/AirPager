package com.airpager;

public class DecodeBus {
    public interface Listener {
        void onChar(char c);
        void onStatus(String status);
        void onCalibrated(float recommendedThreshold);
    }
    public static volatile Listener listener;
}