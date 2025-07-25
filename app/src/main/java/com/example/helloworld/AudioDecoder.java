package com.example.helloworld;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;


/**
 * AudioDecoder 类：
 * AudioDecoder只负责实时解码并把 PCM 数据送入 PcmCircularBuffer
 */
public class AudioDecoder {

    private static final String TAG = "AudioDecoder";
    private Supplier<Integer> videoPositionProvider;

    // 7.24新增：seek后宽限期相关常量
    private static final long SEEK_GRACE_PERIOD = 2000; // seek后的宽限期2秒
    private static final long MAX_ADVANCE_NORMAL = 3000; // 正常情况下提前3秒
    private static final long MAX_ADVANCE_AFTER_SEEK = 6000; // seek后允许提前6秒
    private volatile long lastSeekTimeInDecoder = 0; // 记录解码器中的seek时间


    private Context context;
    private Uri videoUri;
    private AudioInferenceHelper audioHelper;
    private volatile boolean isDecoding = false;

    // 音频分析完成监听器（用于主界面记录音频耗时）
    private Runnable onCompleteListener = null;

    private PcmCircularBuffer pcmBuffer;

    private volatile boolean isDecodingFinished = false; // 新增：标志位，表示解码是否完成

    private int seekToPositionMs = -1;


    /**
     * 供外部通知 decoder 跳转位置
     */
    // 可接受误差范围（毫秒）
    private static final int SEEK_POSITION_TOLERANCE_MS = 200;

    private long lastSeekRealTime = 0;

    public void seekTo(int ms) {
        long now = System.currentTimeMillis();
        if ((now - lastSeekRealTime) < 1000) {
            Log.w(TAG, "⚠️ seekTo 频率过快，距上次不足 1000ms，跳过");
            return;
        }
        lastSeekRealTime = now;

        // 7.24新增：记录seek时间，供解码线程使用
        lastSeekTimeInDecoder = now;

        Log.d(TAG, "🔄 AudioDecoder.seekTo() called, new time: " + ms + "ms");

        stop();
        this.seekToPositionMs = Math.max(ms - 4000, 0);//提前4秒
        startDecoding();
        Log.d(TAG, "🚀 seekTo() -> startDecoding(), seekToPositionMs = " + seekToPositionMs);

    }


    public AudioDecoder(Context context, Uri videoUri, PcmCircularBuffer buffer, Supplier<Integer> videoPositionProvider) {
        this.context = context;
        this.videoUri = videoUri;
        this.pcmBuffer = buffer;
        this.videoPositionProvider = videoPositionProvider;
    }


    /**
     * 设置音频分析完成后的回调
     */
    public void setOnCompleteListener(Runnable listener) {
        this.onCompleteListener = listener;
    }

    /**
     * 开始解码音频（在子线程中执行）
     */
    public void startDecoding() {
        Log.d(TAG, "startDecoding: 创建解码子线程, 准备开始音频解码");
        final int targetSampleRate = 16000;
        final int targetWindowSize = targetSampleRate * 2; // 32000
        float[] resampledBuffer = new float[targetWindowSize];

        new Thread(() -> {
            long lastPtsMs = -1;  // ✅ 用于检测 pts 是否回退
            isDecoding = true;
            int windowCount = 0;
            boolean sentEos = false;
            boolean outputDone = false;

            MediaExtractor extractor = new MediaExtractor();
            MediaCodec decoder = null;


            try {
                Log.d(TAG, "startDecoding: 打开视频资源...");
                AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(videoUri, "r");
                extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                Log.d(TAG, "startDecoding: MediaExtractor 已设置数据源, 开始寻找音频轨道");

                int audioTrackIndex = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        break;
                    }
                }

                if (audioTrackIndex < 0) {
                    Log.e(TAG, "startDecoding: 未找到音频轨道！");
                    return;
                }

                Log.d(TAG, "startDecoding: 成功选中音频轨道 " + audioTrackIndex);
                extractor.selectTrack(audioTrackIndex);
                MediaFormat format = extractor.getTrackFormat(audioTrackIndex);

                // ✅ 若有 seek 请求，跳转解码器起始位置
                if (seekToPositionMs >= 0) {
                    extractor.seekTo(seekToPositionMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    Log.d(TAG, "🔄 解码器 seekTo: " + seekToPositionMs + "ms");
                }


                decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                decoder.configure(format, null, null, 0);
                decoder.start();
                Log.d(TAG, "startDecoding: decoder 已启动");

                final int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                final int windowSize = sampleRate * 4;
                Log.d(TAG, "startDecoding: sampleRate=" + sampleRate + ", windowSize=" + windowSize);

                ByteBuffer[] inputBuffers = decoder.getInputBuffers();
                ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                float[] audioBuffer = new float[windowSize];
                int bufferPos = 0;

                outerLoop:
                while (isDecoding && !(sentEos && outputDone)) {
                    Log.d(TAG, "🔁 解码循环进行中...");

                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputIndex];
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0) {
                            Log.d(TAG, "startDecoding: 读取sampleSize<0, 已到达结尾");
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sentEos = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                            Log.d(TAG, "startDecoding: 向解码器输入 " + sampleSize + " 字节, pts=" + presentationTimeUs);
                        }
                    }

                    int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);

                    while (outputIndex >= 0) {
                        long currentPtsMs = bufferInfo.presentationTimeUs / 1000;

                        // 7.24 改进的限速逻辑,防止写入太快
                        long playbackTime = videoPositionProvider.get();

                        // 7.24 计算自上次seek以来的时间
                        long timeSinceSeek = System.currentTimeMillis() - lastSeekTimeInDecoder;

                        // 7.24 根据是否刚seek过来决定允许的最大提前量
                        long maxAdvance;
                        if (timeSinceSeek < SEEK_GRACE_PERIOD && lastSeekTimeInDecoder > 0) {
                            maxAdvance = MAX_ADVANCE_AFTER_SEEK; // seek后2秒内允许提前6秒
                            Log.d(TAG, String.format("🚀 Seek宽限期内，允许提前%d秒 (已过%dms)",
                                    maxAdvance/1000, timeSinceSeek));
                        } else {
                            maxAdvance = MAX_ADVANCE_NORMAL; // 正常情况提前3秒
                        }

                        // 7.24 使用动态的maxAdvance
                        Log.d(TAG, String.format("🔎 写入 PCM：pts=%d, 当前播放=%d, 提前 %.2f 秒, 限制=%.1f秒",
                                currentPtsMs, playbackTime, (currentPtsMs - playbackTime) / 1000.0, maxAdvance / 1000.0));

                        if (currentPtsMs > playbackTime + maxAdvance) {
                            Log.d(TAG, String.format("⏸️ 解码器限速等待，当前播放时间: %dms，解码帧时间: %dms，超出限制%.1f秒",
                                    playbackTime, currentPtsMs, maxAdvance / 1000.0));
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                break outerLoop;
                            }
                            continue;
                        }

                        boolean isEosFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                        Log.d(TAG, "✅ [Decoder] 拿到解码输出帧，pts = " + currentPtsMs + "ms");

                        // ⚠️ 检查 pts 是否异常回退
                        if (lastPtsMs >= 0 && currentPtsMs < lastPtsMs) {
                            Log.w(TAG, "⚠️ 解码输出帧 pts 倒退，当前: " + currentPtsMs + "ms, 上一帧: " + lastPtsMs + "ms，跳过此帧");

                            // 🛑 防止死循环！如果连续出现倒退帧，强制终止解码流程
                            if (currentPtsMs == 0 && currentPtsMs < lastPtsMs) {
                                Log.e(TAG, "🛑 视频播放结束");
                                outputDone = true;
                                sentEos = true;
                                decoder.releaseOutputBuffer(outputIndex, false);
                                break outerLoop;
                            }
                            decoder.releaseOutputBuffer(outputIndex, false);
                            outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
                            continue;
                        }


                        lastPtsMs = currentPtsMs;

                        // ✅ 正常处理帧数据
                        ByteBuffer outputBuffer = outputBuffers[outputIndex];
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                        Log.d(TAG, "当前 outputBuffer 剩余字节: " + outputBuffer.remaining());
                        Log.d(TAG, "当前 presentationTimeUs: " + bufferInfo.presentationTimeUs);

                        while (outputBuffer.remaining() >= 2 && bufferPos < windowSize) {
                            short s = outputBuffer.getShort();
                            audioBuffer[bufferPos++] = s / 32768f;
                        }

                        if (bufferPos == windowSize) {
                            Log.d(TAG, "缓冲区满，准备写入 PCM。windowSize=" + windowSize + ", sampleRate=" + sampleRate);

                            if (sampleRate != targetSampleRate) {
                                float[] originalTime = new float[bufferPos];
                                for (int i = 0; i < bufferPos; i++) {
                                    originalTime[i] = (float) i / sampleRate;
                                }

                                float[] targetTime = new float[targetWindowSize];
                                for (int i = 0; i < targetWindowSize; i++) {
                                    targetTime[i] = (float) i / targetSampleRate;
                                }

                                for (int i = 0, j = 0; i < targetWindowSize; i++) {
                                    while (j < bufferPos - 1 && originalTime[j + 1] < targetTime[i]) {
                                        j++;
                                    }
                                    if (j < bufferPos - 1) {
                                        float t1 = originalTime[j];
                                        float t2 = originalTime[j + 1];
                                        float v1 = audioBuffer[j];
                                        float v2 = audioBuffer[j + 1];
                                        float ratio = (targetTime[i] - t1) / (t2 - t1);
                                        resampledBuffer[i] = v1 + ratio * (v2 - v1);
                                    } else {
                                        resampledBuffer[i] = audioBuffer[j];
                                    }
                                }
                                long segmentStartTimestamp = bufferInfo.presentationTimeUs / 1000L;

                                Log.d(TAG, "📤 bufferInfo pts(ms): " + (bufferInfo.presentationTimeUs / 1000L));
                                Log.d(TAG, "📤 [write] bufferPos = " + bufferPos + ", sampleRate = " + sampleRate + ", segmentStartTimestamp = " + segmentStartTimestamp);
                                pcmBuffer.write(resampledBuffer, segmentStartTimestamp, targetSampleRate);
                                Log.d(TAG, "✅ 已写入 PCM 缓冲区, 样本数: " + targetWindowSize);
                            } else {
                                long segmentStartTimestamp = bufferInfo.presentationTimeUs / 1000L;

                                Log.d(TAG, "📤 bufferInfo pts(ms): " + (bufferInfo.presentationTimeUs / 1000L));
                                Log.d(TAG, "📤 [write] bufferPos = " + bufferPos + ", sampleRate = " + sampleRate + ", segmentStartTimestamp = " + segmentStartTimestamp);
                                pcmBuffer.write(audioBuffer, segmentStartTimestamp, sampleRate);
                                Log.d(TAG, "✅ 原始采样率写入 PCM 缓冲区: " + bufferPos + " 样本");
                            }

                            bufferPos = 0;
                            windowCount++;
                        }

                        if (isEosFrame) {
                            Log.d(TAG, "📴 检测到解码器输出 EOS，设置 outputDone = true");
                            outputDone = true;
                        }

                        decoder.releaseOutputBuffer(outputIndex, false);
                        outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
                    }

                }

                Log.d(TAG, "startDecoding: 解码循环结束, 准备触发 onCompleteListener");
                isDecodingFinished = true;
                if (onCompleteListener != null) {
                    onCompleteListener.run();
                }

                decoder.stop();
                decoder.release();
                extractor.release();
                Log.d(TAG, "startDecoding: MediaCodec 和 MediaExtractor 已释放");

            } catch (IOException e) {
                Log.e(TAG, "音频解码失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 停止解码线程
     */
    public void stop() {
        Log.d(TAG, "stop: 请求停止解码线程...");
        isDecoding = false;
    }


}

