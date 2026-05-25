package com.example.helloworld;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnlineAnalysisService extends Service {
    private static final String TAG = "OnlineAnalysisService";
    private static final String CHANNEL_ID = "OnlineAnalysisChannel";
    private static final int NOTIFICATION_ID = 1001;

    // 屏幕捕获相关
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    // 音频捕获相关
    private AudioRecord audioRecord;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private final AtomicBoolean isAudioRecording = new AtomicBoolean(false);
    private Thread audioThread;

    // 数据回调
    private static OnlineDataCallback dataCallback;
    private Handler mainHandler;
    private HandlerThread imageProcessThread;
    private Handler imageProcessHandler;

    // 音频静音检测
    private long lastAudioActiveTime = 0;
    // ======= 修改开始：RMS+峰值+子窗口+迟滞 的更精确 VAD =======
    private static final long SILENCE_THRESHOLD_MS = 5000; // 5 秒无声才暂停
    private int consecutiveSilentFrames = 0;               // 连续静音帧计数
    private static final int MIN_SILENT_FRAMES = 10;       // 至少连续 10 帧（≈5s）才判定静音

    // 把 0.5s 缓冲拆成 4 个子窗口（每个 ≈125ms），任一子窗口超阈值即视为整体有声
    // —— 避免在长窗口里偶尔的安静段把平均/峰值稀释掉
    private static final int VAD_SUB_WINDOWS = 4;

    // 当前已为"无声"时：转为"有声"的激活阈值（任一指标超过即可）—— 中等敏感
    private static final float VAD_ACTIVATE_RMS_DB  = -55f;
    private static final float VAD_ACTIVATE_PEAK_DB = -42f;

    // 当前已为"有声"时：保持"有声"的释放阈值（任一指标超过即可）—— 比激活阈值更宽松
    // 这样很难误判为静音；只有 RMS 和 峰值 都很低才允许翻转到无声
    private static final float VAD_KEEP_RMS_DB  = -60f;
    private static final float VAD_KEEP_PEAK_DB = -48f;

    // 调试日志节流（每 N 秒打一条实测 dB，便于后续调参）
    private long lastVadLogTime = 0;
    private static final long VAD_LOG_INTERVAL_MS = 5000;
    // ======= 修改结束 =======

    // 帧率控制
    private long lastFrameTime = 0;
    private static final long FRAME_INTERVAL_MS = 100; // 100ms一帧，与本地分析一致

    public interface OnlineDataCallback {
        void onFrameAvailable(Bitmap frame, long timestamp);
        void onAudioDataAvailable(byte[] audioData, int length);
        void onAudioStateChanged(boolean hasAudio);
        void onServiceStopped();
    }

    public static void setDataCallback(OnlineDataCallback callback) {
        dataCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        // 创建图像处理线程
        imageProcessThread = new HandlerThread("ImageProcessThread");
        imageProcessThread.start();
        imageProcessHandler = new Handler(imageProcessThread.getLooper());

        // 获取屏幕参数
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // 降低分辨率以提高性能
        screenWidth = screenWidth / 2;
        screenHeight = screenHeight / 2;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");

        if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("data")) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");

            // 创建通知渠道
            createNotificationChannel();

            // ======= 修改开始：根据Android版本选择合适的启动方式 =======
            // 启动前台服务
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 需要特殊处理
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10-13
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                // Android 9及以下
                startForeground(NOTIFICATION_ID, notification);
            }
            // ======= 修改结束 =======

            // 启动屏幕捕获
            startScreenCapture(resultCode, data);

            // 启动音频捕获
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startAudioCapture(data);
            }
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "在线分析服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("正在进行在线视频分析");

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("在线分析中")
                .setContentText("正在分析屏幕内容")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void startScreenCapture(int resultCode, Intent data) {
        Log.d(TAG, "Starting screen capture");

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            return;
        }

        // ======= 修改开始：Android 14+ 需要注册回调 =======
        // 注册MediaProjection回调（Android 14+要求）
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection stopped");
                stopSelf();
            }
        }, mainHandler);
        // ======= 修改结束 =======

        // 创建ImageReader用于接收屏幕图像
        imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2
        );

        // 设置图像可用监听器
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                imageProcessHandler.post(() -> processImage(reader));
            }
        }, imageProcessHandler);

        // 创建虚拟显示
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, null
        );

        Log.d(TAG, "Screen capture started");
    }

    private void processImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            long currentTime = System.currentTimeMillis();

            // 控制帧率
            if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
                return;
            }
            lastFrameTime = currentTime;

            // 转换为Bitmap
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            // ======= 修改：创建可变的Bitmap =======
            Bitmap bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            // 裁剪掉可能的padding
            Bitmap croppedBitmap = bitmap;  // ======= 修改：保留原始bitmap引用 =======
            if (rowPadding > 0) {
                croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
                bitmap.recycle();  // ======= 修改：回收原始bitmap =======
            }

            // 回调帧数据
            if (dataCallback != null) {
                dataCallback.onFrameAvailable(croppedBitmap, currentTime);  // ======= 修改：使用裁剪后的bitmap =======
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void startAudioCapture(Intent projectionData) {
        Log.d(TAG, "Starting audio capture");

        // ======= 修改：添加权限检查和异常处理 =======
        // 检查音频录制权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "没有音频录制权限，无法捕获音频");
            return;
        }

        try {
            // 配置音频捕获
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build();

            // 计算缓冲区大小
            int bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            );
            bufferSize = Math.max(bufferSize, SAMPLE_RATE * 2); // 至少1秒缓冲

            // 创建AudioRecord - 添加try-catch处理SecurityException
            try {
                audioRecord = new AudioRecord.Builder()
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG)
                                .build())
                        .setAudioPlaybackCaptureConfig(config)
                        .setBufferSizeInBytes(bufferSize)
                        .build();
            } catch (SecurityException e) {
                Log.e(TAG, "创建AudioRecord失败：缺少权限", e);
                return;
            }

            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording();
                isAudioRecording.set(true);

                // 启动音频读取线程
                audioThread = new Thread(this::audioRecordLoop);
                audioThread.start();

                Log.d(TAG, "Audio capture started");
            } else {
                Log.e(TAG, "AudioRecord initialization failed");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error starting audio capture", e);
        }
    }

    private void audioRecordLoop() {
        byte[] buffer = new byte[SAMPLE_RATE]; // 0.5秒缓冲
        boolean wasActive = false;

        while (isAudioRecording.get()) {
            try {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);

                if (bytesRead > 0) {
                    // 检测音频活动（带迟滞：当前是否已"有声"会影响阈值）
                    boolean hasAudio = detectAudioActivity(buffer, bytesRead, wasActive);

                    // ======= 修改开始：改进静音检测逻辑 =======
                    if (hasAudio) {
                        lastAudioActiveTime = System.currentTimeMillis();
                        consecutiveSilentFrames = 0; // 重置静音帧计数

                        // 如果之前是静音，通知恢复分析
                        if (!wasActive) {
                            wasActive = true;
                            if (dataCallback != null) {
                                mainHandler.post(() -> dataCallback.onAudioStateChanged(true));
                            }
                            Log.d(TAG, "检测到音频活动，恢复分析");
                        }

                        // 发送音频数据
                        if (dataCallback != null) {
                            byte[] audioData = new byte[bytesRead];
                            System.arraycopy(buffer, 0, audioData, 0, bytesRead);
                            dataCallback.onAudioDataAvailable(audioData, bytesRead);
                        }

                    } else {
                        consecutiveSilentFrames++;

                        // 只有连续多个静音帧且超过时间阈值才判定为静音
                        if (consecutiveSilentFrames >= MIN_SILENT_FRAMES) {
                            long silenceDuration = System.currentTimeMillis() - lastAudioActiveTime;
                            if (wasActive && silenceDuration > SILENCE_THRESHOLD_MS) {
                                wasActive = false;
                                if (dataCallback != null) {
                                    mainHandler.post(() -> dataCallback.onAudioStateChanged(false));
                                }
                                Log.d(TAG, "音频静音超过" + SILENCE_THRESHOLD_MS + "ms，暂停分析");

                                // ★ 新增：静音时发送停止信号
                                if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()
                                        && !BLEManager.globalManager.isPausedByLocal()) {
                                    BLEManager.globalManager.sendAction("Noise", 0);
                                    Log.i(TAG, "[在线模式] 静音检测，已发送停止信号(Noise)");
                                }
                            }
                        } else {
                            // 如果静音帧数不够，仍然发送数据（可能只是短暂静音）
                            if (wasActive && dataCallback != null) {
                                byte[] audioData = new byte[bytesRead];
                                System.arraycopy(buffer, 0, audioData, 0, bytesRead);
                                dataCallback.onAudioDataAvailable(audioData, bytesRead);
                            }
                        }
                    }
                    // ======= 修改结束 =======
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in audio record loop", e);
                break;
            }
        }
    }

    /**
     * 子窗口 + RMS/峰值 + 迟滞 的 VAD。
     * - 把缓冲拆成 VAD_SUB_WINDOWS 个子窗口，任一窗口超阈值即视为整体有声；
     *   避免长窗口里偶尔的安静段把平均值/峰值稀释掉。
     * - RMS 和 峰值 任一指标超过阈值即视为有声。
     * - 当前 currentlyActive=true 时使用更宽松的"保持有声"阈值（更难误判为静音）；
     *   currentlyActive=false 时使用稍严的"激活"阈值（避免噪声触发恢复）。
     */
    private boolean detectAudioActivity(byte[] buffer, int length, boolean currentlyActive) {
        int sampleCount = length / 2;
        if (sampleCount <= 0) return currentlyActive; // 没数据时保持原状态

        int samplesPerWindow = Math.max(1, sampleCount / VAD_SUB_WINDOWS);

        // 当前状态决定阈值（迟滞）
        final float rmsThreshold  = currentlyActive ? VAD_KEEP_RMS_DB  : VAD_ACTIVATE_RMS_DB;
        final float peakThreshold = currentlyActive ? VAD_KEEP_PEAK_DB : VAD_ACTIVATE_PEAK_DB;

        boolean active = false;
        float maxRmsDb  = Float.NEGATIVE_INFINITY;
        float maxPeakDb = Float.NEGATIVE_INFINITY;

        for (int w = 0; w < VAD_SUB_WINDOWS; w++) {
            int sampleStart = w * samplesPerWindow;
            int sampleEnd   = (w == VAD_SUB_WINDOWS - 1) ? sampleCount : sampleStart + samplesPerWindow;
            int byteStart   = sampleStart * 2;
            int byteEnd     = Math.min(sampleEnd * 2, length);
            int subSamples  = (byteEnd - byteStart) / 2;
            if (subSamples <= 0) continue;

            double sumSquare = 0.0;
            int peakAbs = 0;
            for (int i = byteStart; i + 1 < byteEnd; i += 2) {
                short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                int absSample = (sample == Short.MIN_VALUE) ? Short.MAX_VALUE : Math.abs(sample);
                if (absSample > peakAbs) peakAbs = absSample;
                double norm = sample / 32768.0;
                sumSquare += norm * norm;
            }

            double rms  = Math.sqrt(sumSquare / subSamples);
            double peak = peakAbs / 32768.0;
            float rmsDb  = (float) (20.0 * Math.log10(Math.max(rms,  1e-9)));
            float peakDb = (float) (20.0 * Math.log10(Math.max(peak, 1e-9)));

            if (rmsDb  > maxRmsDb)  maxRmsDb  = rmsDb;
            if (peakDb > maxPeakDb) maxPeakDb = peakDb;

            if (rmsDb > rmsThreshold || peakDb > peakThreshold) {
                active = true;
                // 早退出：已经判定为有声，剩余子窗口不必再算
                // 但为了打印 max dB 仍继续（开销很小）
            }
        }

        // 节流的调试日志：方便后续观察实际 dB 水平 / 调参
        long now = System.currentTimeMillis();
        if (now - lastVadLogTime > VAD_LOG_INTERVAL_MS) {
            lastVadLogTime = now;
            Log.d(TAG, String.format(
                    "[音频检测] maxSubRMS=%.1fdB maxSubPeak=%.1fdB -> active=%b (state=%s, th: RMS>%.0fdB or Peak>%.0fdB)",
                    maxRmsDb, maxPeakDb, active,
                    currentlyActive ? "ACTIVE" : "SILENT",
                    rmsThreshold, peakThreshold));
        }

        return active;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");

        // ★ 新增：退出在线模式时发送停止信号
        if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()) {
            BLEManager.globalManager.sendAction("Noise", 0);
            Log.i(TAG, "[在线模式] 退出服务，已发送停止信号(Noise)");
        }

        // 停止音频录制
        isAudioRecording.set(false);
        if (audioThread != null) {
            try {
                audioThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping audio thread", e);
            }
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        // 停止屏幕捕获
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        // 停止图像处理线程
        if (imageProcessThread != null) {
            imageProcessThread.quitSafely();
            imageProcessThread = null;
        }

        // 通知回调
        if (dataCallback != null) {
            mainHandler.post(() -> dataCallback.onServiceStopped());
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}