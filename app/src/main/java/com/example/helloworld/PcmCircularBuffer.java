// PcmCircularBuffer.java
package com.example.helloworld;

import android.util.Log;

public class PcmCircularBuffer {
    private static final String TAG = "PcmCircularBuffer";

    private final int capacity;      // 总采样点数量
    private final float[] buffer;    // 环形缓冲区
    private final long[] timestamps; // 每个采样点对应的时间戳（毫秒）
    private int writeIndex = 0;
    private boolean isFull = false;

    public PcmCircularBuffer(int sampleRate, int maxSeconds) {
        this.capacity = sampleRate * maxSeconds; // e.g., 16000 * 10 = 160000
        this.buffer = new float[capacity];
        this.timestamps = new long[capacity];
    }

    // 写入一段 PCM 数据（浮点），附带起始时间戳
    // 在类内添加：
    private long lastWriteStart = -1;
    private long lastWriteEnd = -1;

    public synchronized void write(float[] data, long startTimestampMs, int sampleRate) {
        for (int i = 0; i < data.length; i++) {
            int index = (writeIndex + i) % capacity;
            buffer[index] = data[i];
            timestamps[index] = startTimestampMs + (i * 1000L / sampleRate);
        }
        writeIndex = (writeIndex + data.length) % capacity;
        if (data.length >= capacity) {
            isFull = true;
        }
        // 记录本次写入的有效时间区间
        lastWriteStart = startTimestampMs;
        lastWriteEnd = startTimestampMs + (data.length * 1000L / sampleRate);
    }


    public synchronized float[] readWindowRelaxed(long currentTimeMs, int sampleCount) {
        float[] result = new float[sampleCount];
        int count = 0;

        for (int i = 0; i < capacity && count < sampleCount; i++) {
            int index = (writeIndex - 1 - i + capacity) % capacity;
            long ts = timestamps[index];

            if (ts > 0 && ts <= currentTimeMs) {
                result[sampleCount - count - 1] = buffer[index];
                count++;
            }
        }
        if (count < sampleCount * 0.9f) {
            Log.w(TAG, "🚫 readWindowRelaxed: 样本太少，本轮放弃分析");
            return null;
        }
        if (count < sampleCount) {
            Log.w(TAG, "🔁 readWindowRelaxed: 样本不足，仅获取到 " + count + " 个，期望 " + sampleCount + "，将补零");
        } else {
            Log.d(TAG, "✅ readWindowRelaxed: 成功获取样本 " + count + " 个");
        }
        Log.d(TAG, "📊 currentTime = " + currentTimeMs + "，writeIndex = " + writeIndex);
        return result;
    }



    public synchronized void reset() {
        Log.d(TAG, "🧹 清空 PCM 缓冲区...");
        for (int i = 0; i < capacity; i++) {
            buffer[i] = 0f;
            timestamps[i] = 0L;
        }
        writeIndex = 0;
        isFull = false;
        lastWriteStart = -1;
        lastWriteEnd = -1;
    }

}
