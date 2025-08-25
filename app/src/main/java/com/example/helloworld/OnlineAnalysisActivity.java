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
    private BluetoothHelper bluetoothHelper;

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

    // 主线程融合循环间隔
    private static final long MAIN_FUSION_INTERVAL = 800;

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_CODE_AUDIO_PERMISSION);
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
                Toast.makeText(this, "需要音频录制权限才能捕获系统音频", Toast.LENGTH_LONG).show();
                finish();
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

                    float maxTargetProb = Math.max(probOral, probDoslow);
                    float maxNoiseProb = Math.max(probNoiseStand, probNoiseSit);

                    float NOISE_RATIO_THRESHOLD = 1.5f;

                    String actionClass;
                    float bestScore;

                    if (maxNoiseProb > maxTargetProb * NOISE_RATIO_THRESHOLD) {
                        actionClass = "Noise";
                        bestScore = Math.max(probNoiseStand, probNoiseSit);
                    } else {
                        if (probOral > probDoslow) {
                            actionClass = "oral";
                            bestScore = probOral;
                        } else {
                            actionClass = "doslow";
                            bestScore = probDoslow;
                        }
                    }

                    float threshold = 0.2f;
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
                        Log.d(TAG, String.format("[视频线程] 概率分布: oral=%.3f, doslow=%.3f, noise_stand=%.3f, noise_sit=%.3f",
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

                        if (audioSegment != null) {
                            long tAudioInferStart = System.currentTimeMillis();
                            AudioInferenceHelper.AudioInferenceResult result = audioHelper.predict(audioSegment);
                            long tAudioInferEnd = System.currentTimeMillis();
                            //Log.d(TAG, "[计时] 音频推理耗时: " + (tAudioInferEnd - tAudioInferStart) + " ms");

                            String[] audioClasses = {"dofast", "doslow", "oral", "Noise"};
                            float threshold = 0.4f;

                            int index = result.index;
                            float confidence = result.confidence;

                            if (index >= 0 && index < audioClasses.length) {
                                if (confidence < threshold) {
                                    index = audioClasses.length - 1;
                                    confidence = 1.0f;
                                    Log.d(TAG, "[同步分析] 音频分析最大概率小于阈值，视为噪音");
                                }

                                String action = audioClasses[index];
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

                String videoAction = latestVideoAction.get();
                String audioAction = latestAudioAction.get();
                float videoConf = latestVideoConfidence.get();
                float audioConf = latestAudioConfidence.get();
                long videoTime = latestVideoTimestamp.get();
                long audioTime = latestAudioTimestamp.get();

                long currentTime = System.currentTimeMillis();
                long videoAge = videoTime > 0 ? currentTime - videoTime : Long.MAX_VALUE;
                long audioAge = audioTime > 0 ? currentTime - audioTime : Long.MAX_VALUE;

                final long MAX_AGE = 2000;

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

                String finalAction = smoothedFusion(videoAction, audioAction, videoConf, audioConf);

                if (!finalAction.isEmpty()) {
                    updateBluetoothState(finalAction);
                }

                // 通过Service更新悬浮窗UI
                String bluetoothAction = latestBluetoothAction.get();
                String displayText;
                if (!bluetoothAction.isEmpty()) {
                    displayText = "融合: " + bluetoothAction;
                } else {
                    displayText = "融合: 等待...";
                }

                if (OnlineAnalysisService.getInstance() != null) {
                    OnlineAnalysisService.getInstance().updateFloatingFusionResult(displayText);
                }

                mainHandler.postDelayed(this, MAIN_FUSION_INTERVAL);
            }
        };

        mainHandler.post(fusionRunnable);
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

    private void updateBluetoothState(String newAction) {
        long currentTime = System.currentTimeMillis();

        if (!newAction.equals(pendingBluetoothState)) {
            pendingBluetoothState = newAction;
            pendingStateStartTime = currentTime;
        }

        if (pendingBluetoothState.equals(newAction) &&
                (currentTime - pendingStateStartTime) >= 1600) {

            if (!pendingBluetoothState.equals(currentBluetoothState)) {
                if (currentBluetoothState.isEmpty() ||
                        (currentTime - currentStateStartTime) >= BLUETOOTH_MIN_DURATION) {

                    if ((currentTime - lastBluetoothSendTime) >= BLUETOOTH_SEND_INTERVAL) {
                        Log.i(TAG, String.format("[蓝牙] [同步分析] ✅ 发送指令: %s (已稳定%dms)",
                                pendingBluetoothState, currentTime - pendingStateStartTime));
                        latestBluetoothAction.set(pendingBluetoothState);

                        if (BluetoothHelper.globalHelper != null) {
                            BluetoothHelper.globalHelper.sendData(pendingBluetoothState);
                        }

                        currentBluetoothState = pendingBluetoothState;
                        currentStateStartTime = currentTime;
                        lastBluetoothSendTime = currentTime;
                    }
                }
            }
        }
    }

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

        if (bluetoothHelper != null) {
            bluetoothHelper.close();
        }

        super.onDestroy();
    }
}