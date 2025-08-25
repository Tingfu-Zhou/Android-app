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
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnlineAnalysisService extends Service {
    private static final String TAG = "OnlineAnalysisService";
    private static final String CHANNEL_ID = "OnlineAnalysisChannel";
    private static final int NOTIFICATION_ID = 1001;

    // ======= 添加开始：静态实例引用 =======
    private static OnlineAnalysisService instance;

    public static OnlineAnalysisService getInstance() {
        return instance;
    }
    // ======= 添加结束 =======

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

    // 悬浮窗相关
    private WindowManager windowManager;
    private View floatingView;
    // ======= 添加开始：悬浮窗TextView引用 =======
    private TextView tvVideoAction;
    private TextView tvAudioAction;
    private TextView tvOverlay;
    // ======= 添加结束 =======

    // 数据回调
    private static OnlineDataCallback dataCallback;
    private Handler mainHandler;
    private HandlerThread imageProcessThread;
    private Handler imageProcessHandler;

    // 音频静音检测
    private long lastAudioActiveTime = 0;
    // ======= 修改开始：调整静音检测参数 =======
    private static final long SILENCE_THRESHOLD_MS = 5000; // 改为5秒无声音才暂停（原来是3秒）
    private static final float AUDIO_AMPLITUDE_THRESHOLD = 0.005f; // 降低静音阈值（原来是0.01f）
    private int consecutiveSilentFrames = 0; // 添加连续静音帧计数
    private static final int MIN_SILENT_FRAMES = 10; // 至少连续10个静音帧才认为是真正静音
    // ======= 修改结束 =======

    // 帧率控制
    private long lastFrameTime = 0;
    private static final long FRAME_INTERVAL_MS = 100; // 100ms一帧，与本地分析一致

    // ======= 添加开始：为Service添加接口更新悬浮窗 =======
    public interface OnlineDataCallback {
        void onFrameAvailable(Bitmap frame, long timestamp);
        void onAudioDataAvailable(byte[] audioData, int length);
        void onAudioStateChanged(boolean hasAudio);
        void onServiceStopped();
        // 新增：UI更新回调
        void onVideoActionUpdate(String action, float confidence);
        void onAudioActionUpdate(String action, float confidence);
        void onFusionResultUpdate(String result);
    }

    // 新增：更新悬浮窗的UI方法
    public void updateFloatingVideoAction(final String text) {
        if (tvVideoAction != null) {
            mainHandler.post(() -> tvVideoAction.setText(text));
        }
    }

    public void updateFloatingAudioAction(final String text) {
        if (tvAudioAction != null) {
            mainHandler.post(() -> tvAudioAction.setText(text));
        }
    }

    public void updateFloatingFusionResult(final String text) {
        if (tvOverlay != null) {
            mainHandler.post(() -> tvOverlay.setText(text));
        }
    }
    // ======= 添加结束 =======

    public static void setDataCallback(OnlineDataCallback callback) {
        dataCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        // ======= 添加开始：设置实例引用 =======
        instance = this;
        // ======= 添加结束 =======

        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
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

            // 显示悬浮窗
            showFloatingWindow();
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
                    // 检测音频活动
                    boolean hasAudio = detectAudioActivity(buffer, bytesRead);

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

    private boolean detectAudioActivity(byte[] buffer, int length) {
        // 简单的振幅检测
        float sum = 0;
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            sum += Math.abs(sample / 32768.0f);
        }
        float average = sum / (length / 2);
        return average > AUDIO_AMPLITUDE_THRESHOLD;
    }

    private void showFloatingWindow() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission");
            return;
        }

        // 创建悬浮窗视图
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

        // ======= 添加开始：获取TextView引用 =======
        tvVideoAction = floatingView.findViewById(R.id.tvVideoAction);
        tvAudioAction = floatingView.findViewById(R.id.tvAudioAction);
        tvOverlay = floatingView.findViewById(R.id.tvOverlay);
        // ======= 添加结束 =======

        // 设置悬浮窗参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 10;
        params.y = 100;

        // 设置退出按钮点击事件
        Button btnExit = floatingView.findViewById(R.id.btn_exit);
        btnExit.setOnClickListener(v -> {
            Log.d(TAG, "Exit button clicked");
            stopSelf();
        });

        // 添加拖动功能
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX - (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });

        // 添加悬浮窗到窗口
        windowManager.addView(floatingView, params);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");

        // ======= 添加开始：清除实例引用 =======
        instance = null;
        // ======= 添加结束 =======

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

        // 移除悬浮窗
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
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