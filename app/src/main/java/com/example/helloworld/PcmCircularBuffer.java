// PcmCircularBuffer.java
package com.example.helloworld;

import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PcmCircularBuffer {
    private static final String TAG = "PcmCircularBuffer";

    private final int capacity;      // 总采样点数量
    private final float[] buffer;    // 环形缓冲区
    private final long[] timestamps; // 每个采样点对应的时间戳（毫秒）
    private int writeIndex = 0;
    private boolean isFull = false;

    // 在线模式相关
    private boolean onlineMode = false;
    private int totalSamplesWritten = 0;
    private final int targetSampleRate = 16000; // 目标采样率

    // 记录写入的时间区间
    private long lastWriteStart = -1;
    private long lastWriteEnd = -1;

    public PcmCircularBuffer(int sampleRate, int maxSeconds) {
        this(sampleRate, maxSeconds, false);
    }

    public PcmCircularBuffer(int sampleRate, int maxSeconds, boolean onlineMode) {
        this.capacity = sampleRate * maxSeconds; // e.g., 16000 * 10 = 160000
        this.buffer = new float[capacity];
        this.timestamps = new long[capacity];
        this.onlineMode = onlineMode;
        Log.d(TAG, "初始化缓冲区: " + sampleRate + "Hz, " + maxSeconds + "秒, 模式: " + (onlineMode ? "在线" : "离线"));
    }

    /**
     * 离线模式：写入一段 PCM 数据（浮点），附带起始时间戳
     */
    public synchronized void write(float[] data, long startTimestampMs, int sampleRate) {
        // 如果需要重采样到16kHz
        float[] resampledData = data;
        if (sampleRate != targetSampleRate) {
            resampledData = resample(data, sampleRate, targetSampleRate);
        }

        for (int i = 0; i < resampledData.length; i++) {
            int index = (writeIndex + i) % capacity;
            buffer[index] = resampledData[i];
            timestamps[index] = startTimestampMs + (i * 1000L / targetSampleRate);
        }

        writeIndex = (writeIndex + resampledData.length) % capacity;
        if (resampledData.length >= capacity) {
            isFull = true;
        }

        // 记录本次写入的有效时间区间
        lastWriteStart = startTimestampMs;
        lastWriteEnd = startTimestampMs + (resampledData.length * 1000L / targetSampleRate);

        if (onlineMode) {
            totalSamplesWritten += resampledData.length;
        }
    }

    /**
     * 在线模式：添加原始字节数据
     * @param audioData PCM字节数组
     * @param length 有效数据长度
     */
    public synchronized void addByteData(byte[] audioData, int length) {
        if (!onlineMode) {
            Log.w(TAG, "addByteData 仅在在线模式下使用");
            return;
        }

        // 将byte数组转换为float数组
        ByteBuffer buffer = ByteBuffer.wrap(audioData, 0, length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int numSamples = length / 2; // 16位音频，每个样本2字节
        float[] floatData = new float[numSamples];

        for (int i = 0; i < numSamples; i++) {
            if (buffer.remaining() >= 2) {
                short sample = buffer.getShort();
                floatData[i] = sample / 32768.0f;
            }
        }

        // 使用当前系统时间作为时间戳（在线模式）
        long currentTime = System.currentTimeMillis();
        write(floatData, currentTime - (numSamples * 1000L / targetSampleRate), targetSampleRate);
    }

    /**
     * 离线模式：基于时间戳读取窗口
     */
    public synchronized float[] readWindowRelaxed(long currentTimeMs, int sampleCount) {
        if (onlineMode) {
            // 在线模式直接调用getLatestData
            return getLatestData(sampleCount);
        }

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

    /**
     * 在线模式：获取最新的指定长度音频数据
     * @param numSamples 需要的样本数
     * @return float数组，如果数据不足返回null
     */
    public synchronized float[] getLatestData(int numSamples) {
        // 检查是否有足够的数据
        int availableSamples = onlineMode ?
                Math.min(totalSamplesWritten, capacity) :
                (isFull ? capacity : writeIndex);

        if (availableSamples < numSamples) {
            Log.w(TAG, "音频数据不足: 需要" + numSamples + "，实际" + availableSamples);
            return null;
        }

        float[] result = new float[numSamples];

        // 计算读取起始位置
        int readStart = writeIndex - numSamples;
        if (readStart < 0) {
            readStart += capacity;
        }

        // 读取数据
        for (int i = 0; i < numSamples; i++) {
            int idx = (readStart + i) % capacity;
            result[i] = buffer[idx];
        }

        Log.d(TAG, "✅ getLatestData: 成功获取 " + numSamples + " 个样本");
        return result;
    }

    /**
     * 音频重采样
     */
    private float[] resample(float[] input, int inputRate, int outputRate) {
        if (inputRate == outputRate) {
            return input;
        }

        int inputLength = input.length;
        int outputLength = (int) ((long) inputLength * outputRate / inputRate);
        float[] output = new float[outputLength];

        // 创建时间轴
        float[] inputTime = new float[inputLength];
        for (int i = 0; i < inputLength; i++) {
            inputTime[i] = (float) i / inputRate;
        }

        float[] outputTime = new float[outputLength];
        for (int i = 0; i < outputLength; i++) {
            outputTime[i] = (float) i / outputRate;
        }

        // 线性插值
        for (int i = 0, j = 0; i < outputLength; i++) {
            while (j < inputLength - 1 && inputTime[j + 1] < outputTime[i]) {
                j++;
            }

            if (j < inputLength - 1) {
                float t1 = inputTime[j];
                float t2 = inputTime[j + 1];
                float v1 = input[j];
                float v2 = input[j + 1];
                float ratio = (outputTime[i] - t1) / (t2 - t1);
                output[i] = v1 + ratio * (v2 - v1);
            } else {
                output[i] = input[j];
            }
        }

        Log.d(TAG, "重采样: " + inputRate + "Hz -> " + outputRate + "Hz, " +
                inputLength + " -> " + outputLength + " 样本");
        return output;
    }

    /**
     * 重置缓冲区
     */
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
        totalSamplesWritten = 0;
    }

    /**
     * 获取当前缓冲的样本数
     */
    public int getBufferedSamples() {
        if (onlineMode) {
            return Math.min(totalSamplesWritten, capacity);
        } else {
            return isFull ? capacity : writeIndex;
        }
    }

    /**
     * 获取缓冲的时长（秒）
     */
    public float getBufferedSeconds() {
        return (float) getBufferedSamples() / targetSampleRate;
    }
}