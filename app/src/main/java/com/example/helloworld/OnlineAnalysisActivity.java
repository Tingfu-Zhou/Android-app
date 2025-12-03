package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class OnlineAnalysisActivity extends AppCompatActivity implements OnlineAnalysisService.OnlineDataCallback {
    private static final String TAG = "OnlineAnalysisActivity";
    private static final int REQUEST_CODE_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_CODE_OVERLAY_PERMISSION = 1002;
    private static final int REQUEST_CODE_AUDIO_PERMISSION = 1003;

    // UI组件
    private TextView tvStatus;
    // ======= 修改开始：移除这三个TextView的声明（移到Service中） =======
    // private TextView tvVideoAction;
    // private TextView tvAudioAction;
    // private TextView tvOverlay;
    // ======= 修改结束 =======

    // 分析相关
    private InferenceHelper inferenceHelper;
    private AudioInferenceHelper audioHelper;

    // 线程控制
    private final AtomicBoolean isAnalysisPaused = new AtomicBoolean(true);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);

    // 后台线程和Handler
    private HandlerThread videoThread;
    private Handler videoHandler;
    private HandlerThread audioThread;
    private Handler audioHandler;
    private Handler mainHandler;

    // 线程间同步
    private final Object videoLock = new Object();
    private final Object audioLock = new Object();

    // 使用原子引用来安全地在线程间共享结果
    private final AtomicReference<String> latestAudioAction = new AtomicReference<>("");
    private final AtomicReference<String> latestVideoAction = new AtomicReference<>("");
    private final AtomicReference<Float> latestAudioConfidence = new AtomicReference<>(0f);
    private final AtomicReference<Float> latestVideoConfidence = new AtomicReference<>(0f);
    private final AtomicReference<String> latestBluetoothAction = new AtomicReference<>("");
    private final AtomicReference<Long> latestVideoTimestamp = new AtomicReference<>(0L);
    private final AtomicReference<Long> latestAudioTimestamp = new AtomicReference<>(0L);

    // 姿态窗口管理
    private final ArrayDeque<float[][]> poseWindow = new ArrayDeque<>();
    private static final int WINDOW_SIZE = 32;
    private static final int MULTI_STEP = 8;
    private int framesSinceLastMulti = 0;
    private static final int BINARY_WINDOW = 8;
    private final ArrayDeque<float[][]> poseWindow8 = new ArrayDeque<>();

    // 对每个关键点的 x, y 坐标做归一化处理时使用
    private static final int TARGET_WIDTH = 720;
    private static final int TARGET_HEIGHT = 480;

    // 音频缓冲 - 使用统一的PcmCircularBuffer
    private PcmCircularBuffer pcmBuffer;
    private long audioStartTime = 0;
    private boolean hasAudioData = false;

    // 帧缓冲
    private Bitmap latestFrame = null;
    private final Object frameLock = new Object();

    // 时间窗口平滑相关变量
    private static final int SMOOTH_WINDOW_SIZE = 10;
    private final LinkedList<ActionRecord> actionHistory = new LinkedList<>();
    private final Object historyLock = new Object();

    // 蓝牙发送状态管理器变量
    private static final long BLUETOOTH_MIN_DURATION = 2000;
    private static final long BLUETOOTH_SEND_INTERVAL = 1600;
    private long lastBluetoothSendTime = 0;
    private String currentBluetoothState = "";
    private long currentStateStartTime = 0;
    private String pendingBluetoothState = "";
    private long pendingStateStartTime = 0;

    // NEW: 频率档位确认与节流相关
    private int currentLevel = 0;                 // 已生效档位（0..10）
    private long currentLevelSinceMs = 0;         // 当前档位生效起始时间
    private Integer pendingLevel = null;          // 待确认档位
    private long pendingLevelSinceMs = 0;         // 待确认计时

    // NEW: 参数（可调）
    private static final int LEVEL_STABLE_MS   = 800;   // 新档位需稳定的最短时间
    private static final int LEVEL_MIN_DUR_MS  = 1500;  // 生效档位的最小驻留
    private static final int LEVEL_SEND_GAP_MS = 1600;  // 档位更新的发送间隔

    // 最近一次“已发送”档位（用于判断是否需要重发同一动作以更新档位）
    private int lastSentLevel = 0;                  // NEW: 记录上次发送出去的档位

    // 主线程融合循环间隔
    private static final long MAIN_FUSION_INTERVAL = 800;

    //音频节奏
    private final AudioRhythmEstimator rhythmEstimator = new AudioRhythmEstimator(16000);

    // 视频节律器
    private final VideoRhythmEstimator videoRhythmEstimator = new VideoRhythmEstimator();
    // 音频节奏
    private final java.util.concurrent.atomic.AtomicReference<Float> latestAudioRhythmHz =
            new java.util.concurrent.atomic.AtomicReference<>(Float.NaN);
    private final java.util.concurrent.atomic.AtomicReference<Float> latestAudioRhythmConf =
            new java.util.concurrent.atomic.AtomicReference<>(0f);
    private final java.util.concurrent.atomic.AtomicLong latestAudioRhythmTsMs =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicBoolean latestAudioRhythmValid =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // 视频节律, 视频侧“频率缓存”（先不进主融合，只做存储）
    private final java.util.concurrent.atomic.AtomicReference<Float> latestVideoFreqHz =
            new java.util.concurrent.atomic.AtomicReference<>(Float.NaN);
    private final java.util.concurrent.atomic.AtomicReference<Float> latestVideoFreqConf =
            new java.util.concurrent.atomic.AtomicReference<>(0f);
    private final java.util.concurrent.atomic.AtomicLong latestVideoFreqTsMs =
            new java.util.concurrent.atomic.AtomicLong(0);



    // MediaProjection相关
    private MediaProjectionManager projectionManager;

    private static class ActionRecord {
        String videoAction;
        String audioAction;
        float videoConfidence;
        float audioConfidence;
        long timestamp;

        ActionRecord(String vAction, String aAction, float vConf, float aConf) {
            this.videoAction = vAction != null ? vAction : "";
            this.audioAction = aAction != null ? aAction : "";
            this.videoConfidence = vConf;
            this.audioConfidence = aConf;
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online_analysis);

        // 初始化UI组件
        tvStatus = findViewById(R.id.tvStatus);
        // ======= 修改开始：注释掉（不再使用Activity中的TextView） =======
        // tvVideoAction = findViewById(R.id.tvVideoAction);
        // tvAudioAction = findViewById(R.id.tvAudioAction);
        // tvOverlay = findViewById(R.id.tvOverlay);
        // ======= 修改结束 =======

        // 初始化推理助手
        inferenceHelper = new InferenceHelper(this);
        audioHelper = new AudioInferenceHelper(this);

        // 初始化ML Kit
        AccuratePoseDetectorOptions options = new AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                .build();
        PoseDetector detector = PoseDetection.getClient(options);
        inferenceHelper.setPoseDetector(detector);

        // 初始化音频缓冲 - 使用在线模式
        pcmBuffer = new PcmCircularBuffer(16000, 10, true);

        // 初始化主线程Handler
        mainHandler = new Handler(Looper.getMainLooper());

        // 获取MediaProjectionManager
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // 设置服务回调
        OnlineAnalysisService.setDataCallback(this);

        // 检查并请求权限
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        // ======= 修改：先检查音频录制权限 =======
        // 改进音频权限请求流程
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            // 检查是否需要显示权限说明（用户之前拒绝过）
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {
                // 显示解释弹窗
                new AlertDialog.Builder(this)
                        .setTitle("需要音频录制权限")
                        .setMessage("在线分析需要音频录制权限才能捕获系统音频")
                        .setPositiveButton("授予权限", (dialog, which) -> {
                            ActivityCompat.requestPermissions(this,
                                    new String[]{Manifest.permission.RECORD_AUDIO},
                                    REQUEST_CODE_AUDIO_PERMISSION);
                        })
                        .setNegativeButton("取消", (dialog, which) -> finish())
                        .show();
            } else {
                // 第一次请求或者用户选择了"不再询问"
                // 先尝试直接请求
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_CODE_AUDIO_PERMISSION);
            }
            return;
        }

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("在线分析需要悬浮窗权限来显示控制按钮")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
                    })
                    .setNegativeButton("取消", (dialog, which) -> finish())
                    .show();
        } else {
            // 请求屏幕录制权限
            requestScreenCapture();
        }
    }

    private void requestScreenCapture() {
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                requestScreenCapture();
            } else {
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // 启动服务
                Intent serviceIntent = new Intent(this, OnlineAnalysisService.class);
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("data", data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                // 启动多线程分析
                startMultiThreadAnalysis();

                tvStatus.setText("在线分析已启动");
            } else {
                Toast.makeText(this, "需要屏幕录制权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 音频权限授予，继续检查其他权限
                checkAndRequestPermissions();
            } else {
                // ======= 添加开始：处理权限被拒绝的情况 =======
                // 检查是否选择了"不再询问"
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.RECORD_AUDIO)) {
                    // 用户选择了"不再询问"，引导去设置页面
                    new AlertDialog.Builder(this)
                            .setTitle("需要音频录制权限")
                            .setMessage("您已拒绝音频录制权限，请在设置中手动开启")
                            .setPositiveButton("去设置", (dialog, which) -> {
                                // 跳转到应用设置页面
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                Uri uri = Uri.fromParts("package", getPackageName(), null);
                                intent.setData(uri);
                                startActivity(intent);
                                finish();
                            })
                            .setNegativeButton("取消", (dialog, which) -> finish())
                            .show();
                } else {
                    // 用户只是拒绝了，没有选择"不再询问"
                    Toast.makeText(this, "需要音频录制权限才能捕获系统音频", Toast.LENGTH_LONG).show();
                    finish();
                }
                // ======= 添加结束 =======
            }
        }
    }
    @Override
    public void onFrameAvailable(Bitmap frame, long timestamp) {
        synchronized (frameLock) {
            // 不要立即回收旧的bitmap，让GC自动处理
            latestFrame = frame;
        }
    }

    @Override
    public void onAudioDataAvailable(byte[] audioData, int length) {
        // 将音频数据添加到缓冲区 - 使用统一的PcmCircularBuffer
        pcmBuffer.addByteData(audioData, length);
        hasAudioData = true;
    }

    @Override
    public void onAudioStateChanged(boolean hasAudio) {
        if (hasAudio) {
            Log.d(TAG, "[同步分析] 检测到音频，恢复分析");
            resumeAnalysis();
        } else {
            Log.d(TAG, "[同步分析] 音频静音，暂停分析");
            pauseAnalysis();
        }
        // 如果静音超过某个阈值（如5秒），才清空缓冲
        mainHandler.postDelayed(() -> {
            if (isAnalysisPaused.get()) {
                resetAnalysisBuffers();
            }
        }, 5000);
    }

    @Override
    public void onServiceStopped() {
        Log.d(TAG, "服务已停止");
        finish();
    }

    // ======= 添加开始：实现新增的回调方法（空实现） =======
    @Override
    public void onVideoActionUpdate(String action, float confidence) {
        // UI更新已在Service中处理，这里不需要实现
    }

    @Override
    public void onAudioActionUpdate(String action, float confidence) {
        // UI更新已在Service中处理，这里不需要实现
    }

    @Override
    public void onFusionResultUpdate(String result) {
        // UI更新已在Service中处理，这里不需要实现
    }
    // ======= 添加结束 =======

    private void startMultiThreadAnalysis() {
        Log.d(TAG, "启动多线程分析系统...");

        // 创建视频分析线程
        videoThread = new HandlerThread("VideoAnalysisThread");
        videoThread.start();
        videoHandler = new Handler(videoThread.getLooper());

        // 创建音频分析线程
        audioThread = new HandlerThread("AudioAnalysisThread");
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        // 启动视频分析循环
        startVideoAnalysisLoop();

        // 启动音频分析循环
        startAudioAnalysisLoop();

        // 启动主线程融合循环
        startMainFusionLoop();
    }

    private void startVideoAnalysisLoop() {
        Runnable videoRunnable = new Runnable() {
            @Override
            public void run() {
                if (shouldStop.get()) {
                    Log.d(TAG, "[视频线程] 收到停止信号，退出循环");
                    return;
                }

                if (isAnalysisPaused.get()) {
                    videoHandler.postDelayed(this, 100);
                    return;
                }

                long t0 = System.currentTimeMillis();

                try {
                    Bitmap frame;
                    synchronized (frameLock) {
                        frame = latestFrame;
                        latestFrame = null;
                    }

                    if (frame != null && !frame.isRecycled()) {  // ======= 修改：检查bitmap是否已回收 =======
                        // ML Kit姿态检测（异步）
                        long tMMPoseStart = System.currentTimeMillis();
                        inferenceHelper.runPoseModelViaMLKit(frame, keypointsRaw -> {
                            long tMMPoseEnd = System.currentTimeMillis();
                            Log.d(TAG, "[计时] MLKit 关键点检测耗时: " + (tMMPoseEnd - tMMPoseStart) + " ms");
                            processVideoFrame(keypointsRaw);
                        });

                        // ======= 修改：不要立即回收，让ML Kit处理完成后自动管理 =======
                        // frame.recycle();  // 移除这行
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[视频线程] 异常：", e);
                }

                // 继续下一轮
                videoHandler.postDelayed(this, 100);
            }
        };

        videoHandler.post(videoRunnable);
    }

    private void processVideoFrame(float[][][][] keypointsRaw) {
        synchronized (videoLock) {
            if (keypointsRaw == null || keypointsRaw.length == 0) {
                Log.w(TAG, "[视频线程] ML Kit 返回为null");
                return;
            }

            float[][] keypoints = keypointsRaw[0][0];

            // 归一化关键点
            for (int i = 0; i < keypoints.length; i++) {
                keypoints[i][0] = (keypoints[i][0] - TARGET_WIDTH / 2.0f) / (TARGET_WIDTH / 2.0f);
                keypoints[i][1] = (keypoints[i][1] - TARGET_HEIGHT / 2.0f) / (TARGET_HEIGHT / 2.0f);
            }

            // === NEW: 将归一化后的关键点推入“视频节律器” ===
            // 注意：只在“本帧有人体关键点可用”时调用；若你的管线里可能出现 kp==null，就先判空。
            if (keypoints != null) {
                // framePtsMs：请用你当前这一帧对应的播放/展示时间戳（你已有的变量）
                long framePtsMs = System.currentTimeMillis();

                videoRhythmEstimator.onPoseFrame(keypoints, framePtsMs);

                // 拉取最新估计值并存储（先不进主融合）
                float f = videoRhythmEstimator.getLatestFreqHz();
                float c = videoRhythmEstimator.getLatestConf();
                long  t = videoRhythmEstimator.getLatestTsMs();

                latestVideoFreqHz.set(f);
                latestVideoFreqConf.set(c);
                latestVideoFreqTsMs.set(t);
                Log.d(TAG, String.format("[频率测试] [在线模式] 视频节奏 - 频率:%.2f Hz, 置信度:%.2f", f, c));
            } else{
                // === NEW: Seek/镜头切换时同步重置节律器与缓存 ===
                videoRhythmEstimator.reset();
                latestVideoFreqHz.set(Float.NaN);
                latestVideoFreqConf.set(0f);
                latestVideoFreqTsMs.set(0);
            }



            // 8帧二分类窗口
            poseWindow8.add(keypoints);
            if (poseWindow8.size() > BINARY_WINDOW) poseWindow8.poll();

            // 32帧多分类
            poseWindow.add(keypoints);
            if (poseWindow.size() > WINDOW_SIZE) poseWindow.poll();
            framesSinceLastMulti++;

            if (poseWindow.size() == WINDOW_SIZE && framesSinceLastMulti >= MULTI_STEP) {
                framesSinceLastMulti = 0;

                float[][][] input = convertPoseWindowToInput(poseWindow);
                long tStgcnStart = System.currentTimeMillis();
                float[] scores = inferenceHelper.runStgcnModel(input);
                long tStgcnEnd = System.currentTimeMillis();
                Log.d(TAG, "[计时] ST-GCN++ 推理耗时: " + (tStgcnEnd - tStgcnStart) + " ms");

                if (scores != null) {
                    float[] probs = softmax(scores);

                    float probOral = probs[0] + probs[1];
                    float probDoslow = probs[2] + probs[3] + probs[4] + probs[5];
                    float probNoiseStand = probs[6];
                    float probNoiseSit = probs[7];

                    float TargetProb = probOral + probDoslow;
                    float NoiseProb = probNoiseStand + probNoiseSit;

                    float NOISE_RATIO_THRESHOLD = 1.5f;

                    String actionClass;
                    float bestScore;

                    if (NoiseProb > TargetProb * NOISE_RATIO_THRESHOLD) {
                        actionClass = "Noise";
                        bestScore = Math.max(probNoiseStand, probNoiseSit);
                    } else {
                        if (probOral > probDoslow) {
                            actionClass = "oral";
                            bestScore = probOral;
                        } else {
                            actionClass = "do";
                            bestScore = probDoslow;
                        }
                    }

                    float threshold = 0.0f;
                    if (bestScore < threshold) {
                        actionClass = "Noise";
                        bestScore = 1.0f;
                        Log.d(TAG, String.format(
                                "[同步分析] 视频分析判定为 Noise (最大概率=%.3f < 阈值)",
                                bestScore));
                    } else {
                        Log.d(TAG, String.format("[同步分析] 视频识别结果: %s (p=%.2f)",
                                actionClass, bestScore));
                        // 输出详细的概率分布
                        Log.d(TAG, String.format("[视频线程] 概率分布: oral=%.3f, do=%.3f, noise_stand=%.3f, noise_sit=%.3f",
                                probOral, probDoslow, probNoiseStand, probNoiseSit));
                    }

                    latestVideoAction.set(actionClass);
                    latestVideoConfidence.set(bestScore);
                    latestVideoTimestamp.set(System.currentTimeMillis());

                    final String finalActionClass = actionClass;
                    final float finalBestScore = bestScore;

                    // 通过Service更新悬浮窗UI
                    // 更新悬浮窗显示
                    String displayText = String.format("V: %s (%.2f)", finalActionClass, finalBestScore);
                    if (OnlineAnalysisService.getInstance() != null) {
                        OnlineAnalysisService.getInstance().updateFloatingVideoAction(displayText);
                    }
                }
            }
        }
    }

    private long lastAudioInferenceTime = 0;
    private static final long AUDIO_INFERENCE_INTERVAL = 1000;

    private void startAudioAnalysisLoop() {
        Runnable audioRunnable = new Runnable() {
            @Override
            public void run() {
                if (shouldStop.get()) {
                    Log.d(TAG, "[音频线程] 收到停止信号，退出循环");
                    return;
                }

                if (isAnalysisPaused.get() || !hasAudioData) {
                    audioHandler.postDelayed(this, 100);
                    return;
                }

                long currentSystemTime = System.currentTimeMillis();

                if ((currentSystemTime - lastAudioInferenceTime) >= AUDIO_INFERENCE_INTERVAL) {
                    synchronized (audioLock) {
                        // 读取2秒音频数据 - 使用统一的接口
                        float[] audioSegment = pcmBuffer.getLatestData(32000);


                        // **************音频频率分析**************
                        float[] last1s = pcmBuffer.getLatestData(16000); // 1秒 @16 kHz (根据您的API调整)
                        if (last1s != null && last1s.length > 0) {
                            rhythmEstimator.push(last1s); // 将~1秒追加到4秒内部缓冲区
                        }
                        // 仅在预热时(累积>=4秒)且**每~1秒节拍一次**时估计。
                        if (rhythmEstimator.isWarm()) {
                            AudioRhythmEstimator.Result rr = rhythmEstimator.estimate(System.currentTimeMillis());
                            // 仅存储(根据您的要求，不在此处融合)
                            Log.d(TAG, String.format("[频率测试] 音频节奏 [在线模式] - 有效:%s, 频率:%.2f Hz, 置信度:%.2f",
                                    rr.valid, rr.frequencyHz, rr.confidence));
                            latestAudioRhythmHz.set(rr.frequencyHz);
                            latestAudioRhythmConf.set(rr.confidence);
                            latestAudioRhythmTsMs.set(rr.timestampMs);
                            latestAudioRhythmValid.set(rr.valid);
                        }
                        //******************************************

                        if (audioSegment != null) {
                            long tAudioInferStart = System.currentTimeMillis();
                            AudioInferenceHelper.AudioInferenceResult result = audioHelper.predict(audioSegment);
                            long tAudioInferEnd = System.currentTimeMillis();
                            //Log.d(TAG, "[计时] 音频推理耗时: " + (tAudioInferEnd - tAudioInferStart) + " ms");

                            String[] audioClasses = {"do", "oral", "Noise"};
                            float threshold = 0.0f;

                            int index = result.index;
                            float confidence = result.confidence;

                            if (index >= 0 && index < audioClasses.length) {
                                if (confidence < threshold) {
                                    index = audioClasses.length - 1;
                                    confidence = 1.0f;
                                    Log.d(TAG, "[同步分析] 音频分析最大概率小于阈值，视为噪音");
                                }

                                String action = audioClasses[index];
                                // 这里本质是比例阈值判定，噪声需要比目标类高50%才被认定，如果识别为 Noise 但置信度较低，则判定为 do
                                if ("Noise".equals(action) && confidence < 0.6f) {
                                    action = "do";
                                    confidence = 1.0f - confidence;
                                    Log.d(TAG, "[同步分析] Noise置信度过低(" + confidence + ")，转换为 do，新置信度=" + confidence);
                                }
                                Log.d(TAG, String.format("[同步分析] 音频动作识别结果: %s (p=%.2f)", action, confidence));

                                latestAudioAction.set(action);
                                latestAudioConfidence.set(confidence);
                                latestAudioTimestamp.set(System.currentTimeMillis());

                                // 通过Service更新悬浮窗UI
                                final String displayText = String.format("A: %s (%.2f)", action, confidence);
                                if (OnlineAnalysisService.getInstance() != null) {
                                    OnlineAnalysisService.getInstance().updateFloatingAudioAction(displayText);
                                }
                            }

                            lastAudioInferenceTime = currentSystemTime;
                        }
                    }
                }

                audioHandler.postDelayed(this, 100);
            }
        };

        audioHandler.post(audioRunnable);
    }

    private void startMainFusionLoop() {
        Runnable fusionRunnable = new Runnable() {
            @Override
            public void run() {
                if (shouldStop.get()) {
                    return;
                }

                if (isAnalysisPaused.get()) {
                    mainHandler.postDelayed(this, MAIN_FUSION_INTERVAL);
                    return;
                }

                // [新增] 检查BLE是否被本地按键暂停
                if (BLEManager.globalManager != null && BLEManager.globalManager.isPausedByLocal()) {
                    Log.d(TAG, "[融合线程] BLE被本地按键暂停，跳过蓝牙发送");
                    // 仍然进行分析但不发送蓝牙命令
                }

                String videoAction = latestVideoAction.get();
                String audioAction = latestAudioAction.get();
                float videoConf = latestVideoConfidence.get();
                float audioConf = latestAudioConfidence.get();
                long videoTime = latestVideoTimestamp.get();
                long audioTime = latestAudioTimestamp.get();

                // 音频节奏
                boolean audioFreqValid = latestAudioRhythmValid.get();
                float audioFreq = latestAudioRhythmHz.get();
                float audioFreqConf = latestAudioRhythmConf.get();
                long audioFreqTs = latestAudioRhythmTsMs.get();
                // 视频节奏
                float videoFreq = latestVideoFreqHz.get();
                float videoFreqConf = latestVideoFreqConf.get();
                long  videoFreqTs = latestVideoFreqTsMs.get();

                long currentTime = System.currentTimeMillis();
                long videoAge = videoTime > 0 ? currentTime - videoTime : Long.MAX_VALUE;
                long audioAge = audioTime > 0 ? currentTime - audioTime : Long.MAX_VALUE;
                long videoFreqAge = videoFreqTs > 0 ? currentTime - videoFreqTs : Long.MAX_VALUE;
                long audioFreqAge = audioFreqTs > 0 ? currentTime - audioFreqTs : Long.MAX_VALUE;

                final long MAX_AGE = 2000;

                // 如果视频节奏结果过期，清空它
                if (videoFreqAge > MAX_AGE) {
                    videoFreq = Float.NaN;
                    videoFreqConf = 0f;
                    Log.d(TAG, "[融合] 视频节奏结果过期（" + videoFreqAge + "ms），已忽略");
                }

                // 如果音频频节奏结果过期，清空它
                if (audioFreqAge > MAX_AGE || !audioFreqValid) {
                    audioFreq = Float.NaN;
                    audioFreqConf = 0f;
                    if (audioFreqAge > MAX_AGE) {
                        Log.d(TAG, "[融合] 音频节奏结果过期（" + audioFreqAge + "ms），已忽略");
                    } else {
                        Log.d(TAG, "[融合] 音频节奏结果无效（audioFreqValid=false），已忽略");
                    }
                }

                if (videoAge > MAX_AGE) {
                    videoAction = "";
                    videoConf = 0f;
                    Log.d(TAG, "[融合] 视频结果过期（" + videoAge + "ms），已忽略");
                }

                if (audioAge > MAX_AGE) {
                    audioAction = "";
                    audioConf = 0f;
                    Log.d(TAG, "[融合] 音频结果过期（" + audioAge + "ms），已忽略");
                }

                // 动作类型归一化："oral" 统一处理为 "do"
                if ("oral".equals(videoAction)) {
                    videoAction = "do";
                    Log.d(TAG, "[融合] 视频动作类型 oral -> do");
                }
                if ("oral".equals(audioAction)) {
                    audioAction = "do";
                    Log.d(TAG, "[融合] 音频动作类型 oral -> do");
                }

                String finalAction = smoothedFusion(videoAction, audioAction, videoConf, audioConf);
                // 临时采用音频节律作为最终节律
                int finalFreq = mapFreqToLevel(audioFreq);

                // [修改] 检查BLE暂停状态，如果未暂停才发送
                if (!finalAction.isEmpty()) {
                    if (BLEManager.globalManager == null || !BLEManager.globalManager.isPausedByLocal()) {
                        updateBluetoothState(finalAction,finalFreq);
                        Log.d(TAG, String.format("[融合] finalAction: %s (V:%dms前, A:%dms前)",
                                finalAction, videoAge, audioAge));
                    } else {
                        Log.d(TAG, "[融合] BLE暂停中，跳过动作: " + finalAction);
                    }
                }

                // 通过Service更新悬浮窗UI
                String bluetoothAction = latestBluetoothAction.get();
                String displayText;
                if (!bluetoothAction.isEmpty()) {
                    displayText = "蓝牙: " + bluetoothAction + "节奏：" + finalFreq;
                } else {
                    displayText = "蓝牙: 等待...";
                }

                if (OnlineAnalysisService.getInstance() != null) {
                    OnlineAnalysisService.getInstance().updateFloatingFusionResult(displayText);
                }

                mainHandler.postDelayed(this, MAIN_FUSION_INTERVAL);
            }
        };

        mainHandler.post(fusionRunnable);
    }

    // === NEW: 频率->10档映射占位表（index 1..10：对应档位1~10；0为停止）
    private static final float[][] LEVEL_RANGES = new float[][]{
            null,                // 0占位（停止）
            {0.05f, 0.80f},      // 档1  占位：0.05~0.80 Hz
            {0.80f, 1.10f},      // 档2
            {1.10f, 1.40f},      // 档3
            {1.40f, 1.80f},      // 档4
            {1.80f, 2.30f},      // 档5
            {2.30f, 2.80f},      // 档6
            {2.80f, 3.40f},      // 档7
            {3.40f, 4.10f},      // 档8
            {4.10f, 4.80f},      // 档9
            {4.80f, 6.00f}       // 档10 占位：上限6Hz
    };

    // === NEW: 把 Hz 映射为 0..10 档（0为停止）——等工厂给确定值后替换 LEVEL_RANGES 即可
    private static int mapFreqToLevel(final float hz) {
        if (Float.isNaN(hz) || hz <= 0f) return 0;
        for (int lvl = 1; lvl <= 10; lvl++) {
            float[] r = LEVEL_RANGES[lvl];
            if (r != null && hz >= r[0] && hz < r[1]) return lvl;
        }
        return 10; // 超出则钳到最高档
    }

    private String smoothedFusion(String videoAction, String audioAction, float videoConf, float audioConf) {
        synchronized (historyLock) {
            actionHistory.add(new ActionRecord(videoAction, audioAction, videoConf, audioConf));

            while (actionHistory.size() > SMOOTH_WINDOW_SIZE) {
                actionHistory.poll();
            }

            if (actionHistory.size() < 3) {
                return selectBestAction(videoAction, audioAction, videoConf, audioConf);
            }

            Map<String, Float> actionScores = new HashMap<>();
            Map<String, Integer> actionCounts = new HashMap<>();

            int index = 0;
            for (ActionRecord record : actionHistory) {
                float weight = (float)(index + 1) / actionHistory.size();

                if (!record.videoAction.isEmpty() && !record.videoAction.equals("Background")) {
                    String videoKey = record.videoAction;
                    float score = actionScores.getOrDefault(videoKey, 0f);
                    score += record.videoConfidence * weight * 0.8f;
                    actionScores.put(videoKey, score);

                    int count = actionCounts.getOrDefault(videoKey, 0);
                    actionCounts.put(videoKey, count + 1);
                }

                if (!record.audioAction.isEmpty()) {
                    String audioKey = record.audioAction;
                    float score = actionScores.getOrDefault(audioKey, 0f);
                    score += record.audioConfidence * weight * 1.3f;
                    actionScores.put(audioKey, score);

                    int count = actionCounts.getOrDefault(audioKey, 0);
                    actionCounts.put(audioKey, count + 1);
                }

                index++;
            }

            String bestAction = "";
            float bestScore = 0;

            for (Map.Entry<String, Float> entry : actionScores.entrySet()) {
                String action = entry.getKey();
                float score = entry.getValue();
                int count = actionCounts.getOrDefault(action, 0);

                if (count >= 3 && score > bestScore) {
                    bestScore = score;
                    bestAction = action;
                }
            }

            if (bestAction.isEmpty()) {
                ActionRecord latest = actionHistory.getLast();
                bestAction = selectBestAction(latest.videoAction, latest.audioAction,
                        latest.videoConfidence, latest.audioConfidence);
            }

            return bestAction;
        }
    }

    private String selectBestAction(String videoAction, String audioAction, float videoConf, float audioConf) {
        if (!audioAction.isEmpty()) {
            if (!audioAction.equals("Noise") || audioConf > 0.7f) {
                return audioAction;
            }
        }

        if (!videoAction.isEmpty() && !videoAction.equals("Background")) {
            if (!videoAction.equals("Noise") || videoConf > 0.7f) {
                return videoAction;
            }
        }

        if ("Noise".equals(audioAction) || "Noise".equals(videoAction)) {
            return "Noise";
        }

        return "";
    }

    private void updateBluetoothState(String newAction, int finalFreq /* 0..10 */) {
        long currentTime = System.currentTimeMillis();

        // =====================[ NEW: 档位确认（迟滞 + 短稳 + 最小驻留） ]=====================
        // 仅在该动作支持“变速”时才处理档位；否则清空待确认
        boolean supportsLevel = isSexAction(newAction) && currentStateSupportsSpeed();
        if (supportsLevel) {
            int latestLevel = finalFreq; // 你已经 map 到 0..10 的整数

            // 迟滞：只有越过上下阈时才认为“有变动”的价值
            if (latestLevel >= upThreshold(currentLevel) || latestLevel <= downThreshold(currentLevel)) {
                if (pendingLevel == null || !pendingLevel.equals(latestLevel)) {
                    pendingLevel = latestLevel;
                    pendingLevelSinceMs = currentTime;
                } else {
                    long dwell = currentTime - pendingLevelSinceMs;
                    boolean stableOk = (dwell >= LEVEL_STABLE_MS);                      // CHANGED: 仅用短稳
                    if (stableOk && (currentTime - currentLevelSinceMs >= LEVEL_MIN_DUR_MS)) {
                        // 切换“已确认生效”的档位
                        currentLevel = pendingLevel;
                        currentLevelSinceMs = currentTime;
                        // 不清空 pendingLevel 也可以，保持即可
                    }
                }
            } else {
                // 回到“无变化”状态，避免无意义计时
                pendingLevel = null;
            }
        } else {
            pendingLevel = null;
            // 可选：若不支持变速，可把 currentLevel 维持在安全档位（例如 0/1）
        }
        // ====================[ 档位确认结束 ]====================
        // 如果是新动作
        if (!newAction.equals(pendingBluetoothState)) {
            pendingBluetoothState = newAction;
            pendingStateStartTime = currentTime;
            Log.d(TAG, String.format("[蓝牙] 检测到新动作: %s, 等待确认...", newAction));
        }

        // 检查待确认动作是否已经稳定足够长时间（1600ms）
        if (pendingBluetoothState.equals(newAction) &&
                (currentTime - pendingStateStartTime) >= 1600) {

            // ===== 情况 A：切换到“不同动作” =====
            if (!pendingBluetoothState.equals(currentBluetoothState)) {

                // 检查当前状态是否已经持续了最小时间
                if (currentBluetoothState.isEmpty() ||
                        (currentTime - currentStateStartTime) >= BLUETOOTH_MIN_DURATION) {

                    // 控制发送频率
                    if ((currentTime - lastBluetoothSendTime) >= BLUETOOTH_SEND_INTERVAL) {
                        // 无论蓝牙是否连接，都更新状态和UI
                        Log.i(TAG, String.format("[蓝牙] [同步分析] ✅ 发送指令: %s (已稳定%dms)",
                                pendingBluetoothState, currentTime - pendingStateStartTime));

                        // 更新UI显示的动作（不管蓝牙是否连接）
                        latestBluetoothAction.set(pendingBluetoothState);

                        // CHANGED: 发送时携带“已确认档位”，而非原始 finalFreq
                        int levelToSend = supportsLevel ? currentLevel : finalFreq; // NEW/CHANGED

                        // 发送新动作（带档位），通过BLEManager发送动作
                        if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()) {
                            BLEManager.globalManager.sendAction(pendingBluetoothState, levelToSend); // CHANGED
                            Log.i(TAG, "[蓝牙] 已通过BLE发送指令(动作切换/含档位)");
                            lastSentLevel = levelToSend; // NEW: 记录本次已下发的档位
                        } else {
                            Log.w(TAG, "[蓝牙] BLE未连接，仅更新UI显示");
                        }

                        currentBluetoothState = pendingBluetoothState;
                        currentStateStartTime = currentTime;
                        lastBluetoothSendTime = currentTime;
                    }
                } else {
                    // 当前动作还未持续足够时间，继续等待
                    long remainingTime = BLUETOOTH_MIN_DURATION - (currentTime - currentStateStartTime);
                    Log.d(TAG, String.format("[蓝牙] 当前动作%s需继续保持%dms",
                            currentBluetoothState, remainingTime));
                }
            }

            // ===== 情况 B：动作未变，但档位已确认变化 → 允许“重复发送同一动作以更新档位” =====
            // 说明：你的协议没有“单独更新速度”的帧，因此我们复用同一动作命令携带新档位，
            // 同时依然受 BLUETOOTH_SEND_INTERVAL 节流控制。
            else { // pendingBluetoothState.equals(currentBluetoothState)
                // 仅当支持变速、档位确实变化、达到发送节流间隔时才重发
                boolean levelChanged = supportsLevel && (currentLevel != lastSentLevel);
                boolean gapOk = (currentTime - lastBluetoothSendTime) >= BLUETOOTH_SEND_INTERVAL;

                if (levelChanged && gapOk) {
                    int levelToSend = currentLevel;
                    Log.i(TAG, String.format("[蓝牙] 同动作更新档位：%s -> level=%d", currentBluetoothState, levelToSend));

                    if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()) {
                        BLEManager.globalManager.sendAction(currentBluetoothState, levelToSend);
                        Log.i(TAG, "[蓝牙] 已通过BLE发送指令(同动作/更新档位)");
                        lastSentLevel = levelToSend;
                        lastBluetoothSendTime = currentTime;
                    } else {
                        Log.w(TAG, "[蓝牙] BLE未连接，无法更新档位（同动作）");
                    }
                }
            }
        }
    }


    // （可选）门控：当前动作是否支持“变速”（你若已有类似函数，可直接替换）
    // 根据你的动作命名规则自行实现判断逻辑
    private boolean currentStateSupportsSpeed() {                              // NEW
        return currentBluetoothState.startsWith("do");
    }

    // （可选）门控：newAction 是否属于“做爱大类”（你若已有类似函数，可直接替换）
    private boolean isSexAction(String action) {                               // NEW
        return action != null && action.startsWith("do");
    }

    // NEW: 迟滞门限辅助
    private int upThreshold(int cur)   { return Math.min(10, cur + 1); }
    private int downThreshold(int cur) { return Math.max(0,  cur - 1); }

    private void pauseAnalysis() {
        isAnalysisPaused.set(true);
        Log.i(TAG, "暂停分析");
    }

    private void resumeAnalysis() {
        isAnalysisPaused.set(false);
        Log.i(TAG, "恢复分析");
    }

    // 工具方法
    private float[][][] convertPoseWindowToInput(ArrayDeque<float[][]> window) {
        int size = window.size();
        float[][][] input = new float[size][17][3];
        int idx = 0;
        for (float[][] kpts : window) {
            input[idx++] = kpts;
        }
        return input;
    }

    private float[] softmax(float[] logits) {
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float l : logits) maxLogit = Math.max(maxLogit, l);
        float sum = 0f;
        float[] expVals = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            expVals[i] = (float) Math.exp(logits[i] - maxLogit);
            sum += expVals[i];
        }
        for (int i = 0; i < logits.length; i++) expVals[i] /= sum;
        return expVals;
    }

    // 在 OnlineAnalysisActivity 中添加
    private void resetAnalysisBuffers() {
        // 清空 pose 和 pcm 缓冲，避免读取到“前一个时间点”的旧数据
        synchronized (videoLock) {
            poseWindow.clear();
            poseWindow8.clear();
            framesSinceLastMulti = 0;
        }

        synchronized (audioLock) {
            pcmBuffer.reset();
        }

        // 清空动作历史记录
        synchronized (historyLock) {
            actionHistory.clear();
        }

        // 清空蓝牙控制动作缓存
        currentBluetoothState = "";
        pendingBluetoothState = "";
        latestBluetoothAction.set("");
        currentStateStartTime = 0;
        pendingStateStartTime = 0;

        //清空音频频率控制
        rhythmEstimator.reset();
        latestAudioRhythmHz.set(Float.NaN);
        latestAudioRhythmConf.set(0f);
        latestAudioRhythmTsMs.set(0);
        latestAudioRhythmValid.set(false);
        //清空视频频率控制
        videoRhythmEstimator.reset();
        latestVideoFreqHz.set(Float.NaN);
        latestVideoFreqConf.set(0f);
        latestVideoFreqTsMs.set(0);

        Log.d(TAG, "[在线分析] [同步分析] 已重置所有分析缓冲");
    }

    @Override
    protected void onDestroy() {
        shouldStop.set(true);

        // 停止服务
        Intent serviceIntent = new Intent(this, OnlineAnalysisService.class);
        stopService(serviceIntent);

        // 停止所有线程
        if (videoThread != null) {
            videoThread.quitSafely();
        }

        if (audioThread != null) {
            audioThread.quitSafely();
        }

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        if (inferenceHelper != null) {
            inferenceHelper.close();
        }

        if (audioHelper != null) {
            audioHelper.close();
        }
        super.onDestroy();
    }
}