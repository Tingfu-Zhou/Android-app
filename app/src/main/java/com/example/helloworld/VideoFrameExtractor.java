package com.example.helloworld;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;
import android.graphics.SurfaceTexture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;



public class VideoFrameExtractor {
    private MediaExtractor extractor;
    private MediaCodec decoder;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private EGLRenderer eglRenderer;

    private int videoWidth;
    private int videoHeight;
    private boolean isInitialized = false;
    // 设置输出帧分辨率：MobileNetV3Small 视频分类模型输入为 160x160
    private static final int TARGET_WIDTH = 160;
    private static final int TARGET_HEIGHT = 160;

    public VideoFrameExtractor(Context context, Uri videoUri) throws IOException {
        this(context, videoUri, null);
    }

    public VideoFrameExtractor(Context context, Uri videoUri, Map<String, String> headers) throws IOException {
        extractor = new MediaExtractor();
        extractor.setDataSource(context, videoUri, headers);

        // 查找视频轨道
        int videoTrackIndex = -1;
        MediaFormat videoFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                videoTrackIndex = i;
                videoFormat = format;
                break;
            }
        }
        if (videoTrackIndex < 0) throw new IOException("未找到视频轨道");

        extractor.selectTrack(videoTrackIndex);
        String mime = videoFormat.getString(MediaFormat.KEY_MIME);
        videoWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        videoHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);

        decoder = MediaCodec.createDecoderByType(mime);

        // 初始化 OpenGL 环境和 SurfaceTexture
        eglRenderer = new EGLRenderer(TARGET_WIDTH, TARGET_HEIGHT);
        //eglRenderer = new EGLRenderer(videoWidth, videoHeight);
        surfaceTexture = eglRenderer.getSurfaceTexture();
        surface = new Surface(surfaceTexture);

        decoder.configure(videoFormat, surface, null, 0);
        decoder.start();
        isInitialized = true;
    }

    /**
     * 获取指定时间戳（微秒）附近的一帧图像
     */
    public Bitmap getFrameAt(long timeUs) throws IOException {
        if (!isInitialized) throw new IllegalStateException("Decoder not initialized");

        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean outputDone = false;
        boolean inputDone = false;

        while (!outputDone) {
            // 填充输入缓冲区
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            // 获取输出缓冲区
            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true); // 渲染到 SurfaceTexture
                try {
                    Thread.sleep(20); // 稍等渲染完成
                } catch (InterruptedException e) {
                    Log.e("[MLKit]", "Thread sleep 中断", e);
                }
                surfaceTexture.updateTexImage();
                Bitmap frame = eglRenderer.getCurrentFrame();
                Log.d("[MLKit] VideoFrameExtractor", "抽帧尺寸: " + frame.getWidth() + "x" + frame.getHeight());
                outputDone = true;
                return frame;
            }
        }
        return null;
    }

    public void seekTo(long timeUs) {
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        decoder.flush(); // 清空解码器内部缓冲区
    }


    public void release() {
        if (decoder != null) decoder.stop();
        if (decoder != null) decoder.release();
        if (extractor != null) extractor.release();
        if (surface != null) surface.release();
        if (eglRenderer != null) eglRenderer.release();
    }
}
