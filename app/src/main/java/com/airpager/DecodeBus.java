package com.airpager;

public class DecodeBus {
    public interface Listener {
        void onChar(char c);
        void onStatus(String status);
    }
    public static volatile Listener listener;
}