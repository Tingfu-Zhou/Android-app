package com.example.helloworld;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import android.graphics.Bitmap;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.widget.ImageButton;

import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

public class VideoProcessActivity extends AppCompatActivity {

    private static final String TAG = "VideoProcessActivity";
    private VideoView videoView;
    private TextView tvOverlay;
    private TextView tvVideoAction;
    private TextView tvAudioAction;
    private VideoFrameExtractor videoFrameExtractor;
    private InferenceHelper inferenceHelper;
    private BluetoothHelper bluetoothHelper;
    private Handler playStateHandler;
    private Runnable playStateChecker;

    private boolean isVideoCompleted = false;

    // ✅ 音频推理相关变量
    private AudioDecoder audioDecoder;
    private AudioInferenceHelper audioHelper;

    // 使用原子引用来安全地在线程间共享结果
    private final AtomicReference<String> latestAudioAction = new AtomicReference<>("");
    private final AtomicReference<String> latestVideoAction = new AtomicReference<>("");
    private final AtomicReference<Float> latestAudioConfidence = new AtomicReference<>(0f);
    private final AtomicReference<Float> latestVideoConfidence = new AtomicReference<>(0f);
    // 显示蓝牙发送的动作
    private final AtomicReference<String> latestBluetoothAction = new AtomicReference<>("");
    // 添加时间戳记录
    private final AtomicReference<Long> latestVideoTimestamp = new AtomicReference<>(0L);
    private final AtomicReference<Long> latestAudioTimestamp = new AtomicReference<>(0L);

    // 7.19 新增：时间窗口平滑相关变量
    private static final int SMOOTH_WINDOW_SIZE = 10; // 融合分析平滑窗口的大小 10（10帧 × 100ms = 1000ms = 1秒）
    private final LinkedList<ActionRecord> actionHistory = new LinkedList<>();
    private final Object historyLock = new Object(); // 用于同步访问actionHistory

    // 7.19 新增：内部类定义动作记录
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

    // 蓝牙发送状态管理器变量部分
    private static final long BLUETOOTH_MIN_DURATION = 2000; // 蓝牙动作最小持续时间2秒
    private static final long BLUETOOTH_SEND_INTERVAL = 500; // 蓝牙发送间隔500ms
    private long lastBluetoothSendTime = 0;
    private String currentBluetoothState = "";
    private long currentStateStartTime = 0;
    private String pendingBluetoothState = "";
    private long pendingStateStartTime = 0;

    // ✅ 音频分析计时
    private long audioStartTime = 0;
    private boolean isAudioCompleted = false;
    private PcmCircularBuffer pcmBuffer;
    private Handler seekHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSeekRunnable;
    private boolean isFullscreen = false;
    private ImageButton btnFullscreen;

    // 线程控制
    private final AtomicBoolean isAnalysisPaused = new AtomicBoolean(false);
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

    // 对每个关键点的 x, y 坐标做归一化处理时使用
    private static final int TARGET_WIDTH = 720;
    private static final int TARGET_HEIGHT = 480;

    // 姿态窗口管理（线程安全）
    private final ArrayDeque<float[][]> poseWindow = new ArrayDeque<>();
    private static final int WINDOW_SIZE = 32;
    private static final int MULTI_STEP = 8;
    private int framesSinceLastMulti = 0;

    private static final int BINARY_WINDOW = 8;
    private final ArrayDeque<float[][]> poseWindow8 = new ArrayDeque<>();
    private static final float BINARY_TH = 0.30f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: 正在初始化布局...");
        setContentView(R.layout.activity_video_process);

        // 初始化UI组件
        videoView = findViewById(R.id.videoView);
        tvOverlay = findViewById(R.id.tvOverlay);
        tvVideoAction = findViewById(R.id.tvVideoAction);
        tvAudioAction = findViewById(R.id.tvAudioAction);
        btnFullscreen = findViewById(R.id.btnFullscreen);

        inferenceHelper = new InferenceHelper(this);

        // 初始化ML Kit
        AccuratePoseDetectorOptions options =
                new AccuratePoseDetectorOptions.Builder()
                        .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                        .build();
        PoseDetector detector = PoseDetection.getClient(options);
        Log.d(TAG, "onCreate: [MLKit] ML Kit PoseDetector 初始化完成");
        inferenceHelper.setPoseDetector(detector);

        Uri videoUri = intent.getData();
        if (videoUri != null) {
            videoView.setVideoURI(videoUri);
            Log.d(TAG, "onCreate: 设置用户选择的视频 URI: " + videoUri);
        } else {
            Log.e(TAG, "onCreate: 没有有效的视频 URI，退出！");
            finish();
            return;
        }

        // 初始化VideoFrameExtractor
        try {
            videoFrameExtractor = new VideoFrameExtractor(this, videoUri);
            Log.d(TAG, "onCreate: VideoFrameExtractor 初始化成功");
        } catch (IOException e) {
            Log.e(TAG, "onCreate: VideoFrameExtractor 初始化失败", e);
            finish();
        }

        // 设置视频控制器
        videoView.setMediaController(new MediaController(this));
        videoView.requestFocus();

        // 视频播放完成监听
        videoView.setOnCompletionListener(mp -> {
            isVideoCompleted = true;
            pauseAnalysis();
            Log.d(TAG, "视频播放完成！暂停同步分析，等待用户操作");
        });

        // 视频准备完成监听
        videoView.setOnPreparedListener(mp -> {
            // 设置 seek 完成监听，检测用户拖动视频播放进度
            mp.setOnSeekCompleteListener(seekMp -> handleSeekComplete());
            videoView.start();
            startMultiThreadAnalysis(); // 启动多线程分析
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "视频播放出错 what:" + what + " extra:" + extra);
            return true;
        });

        // 初始化音频相关组件
        Log.d(TAG, "onCreate: 初始化音频推理 AudioInferenceHelper...");
        audioHelper = new AudioInferenceHelper(this);
        // 启动音频解码器，从视频中提取音频流进行实时分析
        pcmBuffer = new PcmCircularBuffer(16000, 10); // 采样率 16kHz，最多缓冲 10 秒
        audioDecoder = new AudioDecoder(this, videoUri, pcmBuffer, () -> videoView.getCurrentPosition());

        audioDecoder.setOnCompleteListener(() -> {
            isAudioCompleted = true;
            Log.d(TAG, "音频解码完成");
        });
        audioDecoder.startDecoding();
        Log.d(TAG, "onCreate: audioDecoder 已启动解码线程");

        // 全屏按钮
        btnFullscreen.setOnClickListener(v -> {
            if (!isFullscreen) {
                enterFullscreen();
            } else {
                exitFullscreen();
            }
        });

        // 播放状态监听
        // 播放状态检测逻辑：每 200ms 监听一次 videoView 播放状态; 检测用户暂停/恢复播放进度
        playStateHandler = new Handler(Looper.getMainLooper());
        playStateChecker = new Runnable() {
            boolean lastPlayingState = true;

            @Override
            public void run() {
                boolean isPlayingNow = videoView.isPlaying();
                if (isPlayingNow != lastPlayingState) {
                    if (isPlayingNow) {
                        resumeAnalysis();
                    } else {
                        pauseAnalysis();
                    }
                    lastPlayingState = isPlayingNow;
                }
                playStateHandler.postDelayed(this, 200);
            }
        };
        playStateHandler.post(playStateChecker);

        // 初始化主线程Handler
        mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启动多线程分析
     */
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

    /**
     * 视频分析循环 - 在独立线程中运行
     */
    private long lastVideoAnalysisTime = -1;  // 记录上次分析时间
    private long totalVideoAnalysisTime = 0;  // 累计分析耗时
    private int videoAnalysisCount = 0;       // 分析次数计数

    /**
     * 视频分析循环 - 在独立线程中运行
     */
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

                // 计算与上次分析的时间间隔
                if (lastVideoAnalysisTime >= 0) {
                    long interval = t0 - lastVideoAnalysisTime;
                    Log.i(TAG, "[视频线程] [计时] 📊 距离上次分析间隔: " + interval + "ms");

                    // 每10次分析输出一次平均值
                    videoAnalysisCount++;
                    if (videoAnalysisCount % 10 == 0) {
                        long avgInterval = totalVideoAnalysisTime / 10;
                        Log.w(TAG, "[视频线程] [计时] ⚡ 最近10次平均间隔: " + avgInterval + "ms");
                        totalVideoAnalysisTime = 0;
                    } else {
                        totalVideoAnalysisTime += interval;
                    }
                }
                lastVideoAnalysisTime = t0;

                try {
                    int currentMs = videoView.getCurrentPosition();
                    Log.d(TAG, "[视频线程] 当前播放时间: " + currentMs + "ms");

                    // ✅ 在主线程抽取视频帧，避免 SurfaceTexture 跨线程问题
                    mainHandler.post(() -> {
                        try {
                            // 在主线程执行抽帧操作
                            long tExtractStart = System.currentTimeMillis(); // ⏱️ 添加抽帧计时
                            Bitmap frame = videoFrameExtractor.getFrameAt(currentMs * 1000); // 参数为ms
                            long tExtractEnd = System.currentTimeMillis();
                            Log.d(TAG, "[计时] 📸 抽帧耗时: " + (tExtractEnd - tExtractStart) + " ms");

                            if (frame != null) {
                                // ✅ 将帧数据传回视频线程继续处理
                                videoHandler.post(() -> {
                                    // 记录抽帧完成后的时间
                                    long frameReadyTime = System.currentTimeMillis();
                                    Log.d(TAG, "[视频线程] 收到帧数据，抽帧耗时: " + (frameReadyTime - t0) + "ms");

                                    // ML Kit姿态检测（异步）
                                    long tMMPoseStart = System.currentTimeMillis();
                                    inferenceHelper.runPoseModelViaMLKit(frame, keypointsRaw -> {
                                        long tMMPoseEnd = System.currentTimeMillis();
                                        Log.d(TAG, "[计时] 🦴 MLKit 关键点检测耗时: " + (tMMPoseEnd - tMMPoseStart) + " ms");
                                        // 这个回调可能在任意线程执行，需要同步
                                        processVideoFrame(keypointsRaw);
                                    });
                                });
                            } else {
                                Log.w(TAG, "[主线程] [同步分析] 未能抽取到视频帧");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "[主线程] 抽帧异常：", e);
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "[视频线程] 异常：", e);
                }

                long elapsed = System.currentTimeMillis() - t0;
                Log.d(TAG, "[视频线程] 本轮调度耗时: " + elapsed + "ms");

                // 继续下一轮
                videoHandler.postDelayed(this, 100);
            }
        };

        videoHandler.post(videoRunnable);
    }

    /**
     * 处理视频帧的姿态检测结果
     * 目标动作（六类）：1.女性全身口（label 0）：女方全身（至少头‑髋可见），可跪可趴。2. 女性特写口（label 1）：头——肩特写，躯干及以下基本被遮挡。
     * 3. 传教士姿势（label 2）：女方仰卧、主要为被动体位 四肢展开或呈“W 型”，但骨架几乎无大幅动态。
     * 4. 经典小狗式（label 3）：女方跪姿、上身俯低、有规律的躯干摆动。5. 女牛仔式（label 4）：女方正面/反面骑乘、上身挺直、节奏较快。
     * 上下动作剧烈、躯干位移明显，骨架动态显著。6. 站立式后入（label 5）：女方下肢站立，身体前倾或水平。
     *
     * 杂音剧情（两类）：1.站/走（label 6）；2.坐凳子/坐地上（label 7）。
     */
    private void processVideoFrame(float[][][][] keypointsRaw) {
        synchronized (videoLock) {
            boolean skipMulti = false;

            if (keypointsRaw == null || keypointsRaw.length == 0) {
                Log.w(TAG, "[视频线程] ML Kit 返回为null");
                return;
            }

            float[][] keypoints = keypointsRaw[0][0];

            // 归一化关键点
            for (int i = 0; i < keypoints.length; i++) {
                keypoints[i][0] = (keypoints[i][0] - TARGET_WIDTH / 2.0f) / (TARGET_WIDTH / 2.0f); // x归一化到[-1, 1]
                keypoints[i][1] = (keypoints[i][1] - TARGET_HEIGHT / 2.0f) / (TARGET_HEIGHT / 2.0f); // y归一化到[-1, 1]
                // keypoints[i][2] 置信度不处理
            }

            // 8帧二分类
            poseWindow8.add(keypoints);
            if (poseWindow8.size() > BINARY_WINDOW) poseWindow8.poll();

            if (poseWindow8.size() == BINARY_WINDOW) {
                float[][][] binInput = convertPoseWindowToInput(poseWindow8);
                long tStgcnStart = System.currentTimeMillis();
                float prob = inferenceHelper.runBinary(binInput);
                long tStgcnEnd = System.currentTimeMillis();
                Log.d(TAG, "[计时] [视频线程] 🧠 二分类ST-GCN++ 推理耗时: " + (tStgcnEnd - tStgcnStart) + " ms");
                if (prob < BINARY_TH) {
                    Log.d(TAG, "[视频线程] [同步分析] 二分类判定为Background");
                    latestVideoAction.set("Background");
                    latestVideoConfidence.set(prob);
                    skipMulti = true;
                }
            }

            // 32帧多分类
            if (!skipMulti) {
                poseWindow.add(keypoints);
                if (poseWindow.size() > WINDOW_SIZE) poseWindow.poll();
                framesSinceLastMulti++; // 累积帧计数
                // 多分类的ST-GCN++ 触发条件：窗口满且已累积 ≥ MULTI_STEP 帧
                if (poseWindow.size() == WINDOW_SIZE && framesSinceLastMulti >= MULTI_STEP) {
                    framesSinceLastMulti = 0; // 归零计数器，开始一次多分类推理

                    float[][][] input = convertPoseWindowToInput(poseWindow);
                    long tStgcnStart = System.currentTimeMillis();
                    float[] scores = inferenceHelper.runStgcnModel(input);
                    long tStgcnEnd = System.currentTimeMillis();
                    Log.d(TAG, "[计时] [视频线程] 🧠 ST-GCN++ 推理耗时: " + (tStgcnEnd - tStgcnStart) + " ms");

                    if (scores != null) {
                        //采用置信度阈值法，若最大概率 < 阈值 T（如0.4），则强制判为"杂音"类别,否则按照原有 argmax 判别类别。
                        float threshold = 0.4f;
                        float[] probs = softmax(scores);

                        // 新增：合并同类概率
                        // oral = label 0 + label 1
                        float probOral = probs[0] + probs[1];
                        // doslow = label 2 + label 3 + label 4 + label 5
                        float probDoslow = probs[2] + probs[3] + probs[4] + probs[5];
                        // noise = label 6 + label 7
                        float probNoise = probs[6] + probs[7];

                        // 创建合并后的概率数组
                        float[] mergedProbs = new float[]{probOral, probDoslow, probNoise};
                        String[] mergedClasses = new String[]{"oral", "doslow", "Noise"};

                        // 找到最大概率的类别
                        int bestIndex = argMax(mergedProbs);
                        float bestScore = mergedProbs[bestIndex];
                        String actionClass = mergedClasses[bestIndex];

                        // 应用阈值判断
                        if (bestScore < threshold) {
                            actionClass = "Noise";
                            bestScore = 1.0f; // 低置信度统一视为噪音
                            Log.d(TAG, String.format(
                                    "[同步分析] 视频分析判定为 Noise (合并后概率=%.3f < 阈值)",
                                    bestScore));
                        } else {
                            Log.d(TAG, String.format("[同步分析] 视频识别结果: %s (p=%.2f)",
                                    actionClass, bestScore));
                            // 可选：输出详细的概率分布
                            Log.d(TAG, String.format("[视频线程] 合并概率分布: oral=%.3f, doslow=%.3f, noise=%.3f",
                                    probOral, probDoslow, probNoise));
                            // 可选：输出原始8类概率分布
                            Log.d(TAG, "[视频线程] 原始概率分布: " + Arrays.toString(probs));
                        }

                        // 原子更新结果
                        latestVideoAction.set(actionClass);
                        latestVideoConfidence.set(bestScore);
                        latestVideoTimestamp.set(System.currentTimeMillis());

                        // 创建 final 变量用于 lambda
                        final String finalActionClass = actionClass;
                        final float finalBestScore = bestScore;

                        // UI更新需要在主线程
                        mainHandler.post(() ->
                                tvVideoAction.setText(String.format("Video Action: %s (p=%.2f)", finalActionClass, finalBestScore))
                        );
                    }
                }
            } else { // 若被判为 Background，清空窗口 & 重置计数
                poseWindow.clear();
                framesSinceLastMulti = 0;
            }
        }
    }

    /**
     * 音频分析循环 - 在独立线程中运行
     * 动作类别：label 0 对应着中出快，label 1 对应着中出慢，label 2 对应着口交，label 3 对应着杂音。
     */
    private long lastAudioInferenceTime = 0; // 记录上次音频推理的时间
    private static final long AUDIO_INFERENCE_INTERVAL = 1000; // 音频推理间隔1秒

    private void startAudioAnalysisLoop() {
        Runnable audioRunnable = new Runnable() {
            @Override
            public void run() {
                if (shouldStop.get()) {
                    Log.d(TAG, "[音频线程] 收到停止信号，退出循环");
                    return;
                }

                if (isAnalysisPaused.get()) {
                    audioHandler.postDelayed(this, 100);
                    return;
                }

                long currentMs = videoView.getCurrentPosition();
                long currentSystemTime = System.currentTimeMillis();

                // 控制音频推理频率, 每1秒执行一次
                if ((currentSystemTime - lastAudioInferenceTime) >= AUDIO_INFERENCE_INTERVAL) {
                    synchronized (audioLock) {
                        if ((currentMs - audioStartTime) >= 4000) { // 提前 4 秒开始解码，确保有足够的音频数据供推理使用
                            // 读取音频数据
                            float[] audioSegment = pcmBuffer.readWindowRelaxed(currentMs, 32000);

                            if (audioSegment != null) {
                                // 音频推理
                                // ⏱️ 音频推理计时
                                long tAudioInferStart = System.currentTimeMillis();
                                AudioInferenceHelper.AudioInferenceResult result = audioHelper.predict(audioSegment);
                                long tAudioInferEnd = System.currentTimeMillis();
                                Log.d(TAG, "[计时] 🔊 音频推理耗时: " + (tAudioInferEnd - tAudioInferStart) + " ms");

                                String[] audioClasses = {"dofast", "doslow", "oral", "Noise"};
                                float threshold = 0.4f; // 置信度阈值

                                int index = result.index;
                                float confidence = result.confidence;

                                if (index >= 0 && index < audioClasses.length) {
                                    // 如果最大概率小于阈值，则归为"杂音"类（004）
                                    if (confidence < threshold) {
                                        index = audioClasses.length - 1;
                                        confidence = 1.0f; // 可选：视为"完全属于杂音"
                                        Log.d(TAG, "[同步分析] 音频分析最大概率小于阈值，视为噪音");
                                    }

                                    String action = audioClasses[index];
                                    Log.d(TAG, String.format("[同步分析] 音频动作识别结果: %s (p=%.2f)", action, confidence));

                                    // 原子更新结果
                                    latestAudioAction.set(action);
                                    latestAudioConfidence.set(confidence);
                                    latestAudioTimestamp.set(System.currentTimeMillis());

                                    // UI更新
                                    final String displayText = String.format("Audio Action: %s (p=%.2f)", action, confidence);
                                    mainHandler.post(() -> tvAudioAction.setText(displayText));

                                    Log.d(TAG, "[音频线程] " + displayText);
                                }
                                // 更新上次音频推理时间
                                lastAudioInferenceTime = currentSystemTime;
                            } else {
                                Log.w(TAG, "[音频线程] [同步分析] 未能读取到音频数据");
                            }
                        } else {
                            Log.w(TAG, "[同步分析] PCM 缓冲启动中，等待解码器填充...");
                        }
                    }
                } else {
                    // 不执行音频推理时的日志
                    Log.d(TAG, "[音频] 距上次推理时间不足1秒，跳过本次音频推理");
                }

                // 继续下一轮
                audioHandler.postDelayed(this, 100);
            }
        };

        audioHandler.post(audioRunnable);
    }

    /**
     * 主线程融合循环 - 负责结果融合、UI更新和蓝牙发送
     */
    private void startMainFusionLoop() {
        Runnable fusionRunnable = new Runnable() {
            @Override
            public void run() {
                if (shouldStop.get()) {
                    return;
                }
                // 检查是否暂停
                if (isAnalysisPaused.get()) {
                    Log.d(TAG, "[融合线程] 当前处于暂停状态，跳过本轮融合");
                    mainHandler.postDelayed(this, 100);
                    return;
                }

                // 读取最新的分析结果（原子操作，线程安全）
                String videoAction = latestVideoAction.get();
                String audioAction = latestAudioAction.get();
                float videoConf = latestVideoConfidence.get();
                float audioConf = latestAudioConfidence.get();
                long videoTime = latestVideoTimestamp.get();
                long audioTime = latestAudioTimestamp.get();

                // 计算结果的新鲜度（毫秒）
                long currentTime = System.currentTimeMillis();
                long videoAge = videoTime > 0 ? currentTime - videoTime : Long.MAX_VALUE;
                long audioAge = audioTime > 0 ? currentTime - audioTime : Long.MAX_VALUE;

                // 过滤超过2秒的过期数据
                final long MAX_AGE = 2000; // 2秒

                // 如果视频结果过期，清空它
                if (videoAge > MAX_AGE) {
                    videoAction = "";
                    videoConf = 0f;
                    Log.d(TAG, "[融合] 视频结果过期（" + videoAge + "ms），已忽略");
                }

                // 如果音频结果过期，清空它
                if (audioAge > MAX_AGE) {
                    audioAction = "";
                    audioConf = 0f;
                    Log.d(TAG, "[融合] 音频结果过期（" + audioAge + "ms），已忽略");
                }

                // 7.19 修改：使用平滑融合替代原有的简单融合逻辑
                String finalAction = smoothedFusion(videoAction, audioAction, videoConf, audioConf);

                // 使用稳定的蓝牙发送策略
                if (!finalAction.isEmpty()) {
                    updateBluetoothState(finalAction);
                    Log.d(TAG, String.format("[融合] finalAction: %s (V:%dms前, A:%dms前)",
                            finalAction, videoAge, audioAge));
                }

                // 更新UI, 显示蓝牙实际发送的动作
                String bluetoothAction = latestBluetoothAction.get();
                if (!bluetoothAction.isEmpty()) {
                    tvOverlay.setText("蓝牙发送动作: " + bluetoothAction);
                } else {
                    tvOverlay.setText("蓝牙发送等待识别...");
                }

                // 继续下一轮
                mainHandler.postDelayed(this, 100); // 延迟为100ms
            }
        };

        mainHandler.post(fusionRunnable);
    }

    // 7.19 新增：时间窗口平滑融合方法
    private String smoothedFusion(String videoAction, String audioAction, float videoConf, float audioConf) {
        synchronized (historyLock) {
            // 添加当前记录到历史
            actionHistory.add(new ActionRecord(videoAction, audioAction, videoConf, audioConf));

            // 保持窗口大小
            while (actionHistory.size() > SMOOTH_WINDOW_SIZE) {
                actionHistory.poll();
            }

            // 如果历史记录太少，使用原始逻辑
            if (actionHistory.size() < 3) {
                return selectBestAction(videoAction, audioAction, videoConf, audioConf);
            }

            // 统计各动作的加权得分
            Map<String, Float> actionScores = new HashMap<>();
            Map<String, Integer> actionCounts = new HashMap<>();

            // 给最近的记录更高的权重
            int index = 0;
            for (ActionRecord record : actionHistory) {
                float weight = (float)(index + 1) / actionHistory.size(); // 越新权重越高

                // 处理视频动作
                // 将视频动作映射到音频类别
                if (!record.videoAction.isEmpty() && !record.videoAction.equals("Background")) {
                    // 将视频动作映射到音频类别
                    // 视频动作现在已经是音频类别，不需要映射
                    String videoKey = record.videoAction;
                    if (!videoKey.equals("Noise")) {  // 排除噪音
                        float score = actionScores.getOrDefault(videoKey, 0f);
                        score += record.videoConfidence * weight * 0.8f; // 视频权重稍低
                        actionScores.put(videoKey, score);

                        int count = actionCounts.getOrDefault(videoKey, 0);
                        actionCounts.put(videoKey, count + 1);
                    }
                }

                // 处理音频动作（如果需要考虑音频）
                // 处理音频动作
                if (!record.audioAction.isEmpty() && !record.audioAction.equals("Noise")) {
                    // 将音频动作转换为统一的key（用于匹配视频映射后的结果）
                    String audioKey = record.audioAction;
                    float score = actionScores.getOrDefault(audioKey, 0f);
                    score += record.audioConfidence * weight * 1.3f; // 音频权重更高
                    actionScores.put(audioKey, score);

                    int count = actionCounts.getOrDefault(audioKey, 0);
                    actionCounts.put(audioKey, count + 1);
                }

                index++;
            }

            // 选择得分最高的动作
            String bestAction = "";
            float bestScore = 0;

            for (Map.Entry<String, Float> entry : actionScores.entrySet()) {
                String action = entry.getKey();
                float score = entry.getValue();
                int count = actionCounts.getOrDefault(action, 0);

                // 需要至少出现3次才考虑（避免偶然噪声）
                if (count >= 3 && score > bestScore) {
                    bestScore = score;
                    bestAction = action;
                }
            }

            // 如果没有找到合适的动作，使用最新的高置信度结果
            if (bestAction.isEmpty()) {
                ActionRecord latest = actionHistory.getLast();
                bestAction = selectBestAction(latest.videoAction, latest.audioAction,
                        latest.videoConfidence, latest.audioConfidence);
            }

            // 记录平滑结果
            Log.d(TAG, String.format("[平滑融合] 窗口大小:%d, 选择动作:%s (得分:%.2f)",
                    actionHistory.size(), bestAction, bestScore));

            return bestAction;
        }
    }

    // 7.19新增：简单的动作选择逻辑（用于历史记录不足时）
    private String selectBestAction(String videoAction, String audioAction, float videoConf, float audioConf) {
        // 先检查音频
        if (!audioAction.isEmpty() && !audioAction.equals("Noise")) {
            return audioAction;
        }
        else if (!videoAction.isEmpty() && !videoAction.equals("Background") && !videoAction.equals("Noise")) {
            return videoAction;
        }
        return "";
    }


    // 蓝牙发送状态管理器
    private void updateBluetoothState(String newAction) {
        long currentTime = System.currentTimeMillis();

        // 如果是新动作
        if (!newAction.equals(pendingBluetoothState)) {
            pendingBluetoothState = newAction;
            pendingStateStartTime = currentTime;
            Log.d(TAG, String.format("[蓝牙] 检测到新动作: %s, 等待确认...", newAction));
        }

        // 检查待确认动作是否已经稳定足够长时间（500ms）
        if (pendingBluetoothState.equals(newAction) &&
                (currentTime - pendingStateStartTime) >= 500) {

            // 如果是不同的动作，且当前动作已持续足够时间
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

                        // 发送新动作
                        if (BluetoothHelper.globalHelper != null) {
                            BluetoothHelper.globalHelper.sendData(pendingBluetoothState);
                            Log.i(TAG, "[蓝牙] 已通过蓝牙发送指令");
                        }else {
                            Log.w(TAG, "[蓝牙] 蓝牙未连接，仅更新UI显示");
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
        }
    }

    /**
     * 处理视频seek完成
     */
    private void handleSeekComplete() {
        int currentMs = videoView.getCurrentPosition();
        Log.w(TAG, "[同步分析] 🟡 onSeekComplete(): 用户完成拖动，跳转到 " + currentMs + "ms，准备延迟处理 seek");

        // 取消之前挂起的 seek 请求（防抖）
        if (pendingSeekRunnable != null) {
            seekHandler.removeCallbacks(pendingSeekRunnable);
        }
        // 延迟 500ms 执行最终 seek 逻辑（只处理最后一次）
        pendingSeekRunnable = () -> {
            Log.i(TAG, "执行最终seek处理，时间点：" + currentMs);

            // 清空 pose 和 pcm 缓冲，避免读取到“前一个时间点”的旧数据
            synchronized (videoLock) {
                poseWindow.clear();
                poseWindow8.clear();
                framesSinceLastMulti = 0;
            }

            synchronized (audioLock) {
                pcmBuffer.reset();
            }

            // 7.19新增：清空动作历史记录
            synchronized (historyLock) {
                actionHistory.clear();
                Log.d(TAG, "[平滑融合] 清空历史记录（因为seek操作）");
            }

            // 重置提取器, 通知视频帧抽取器：从当前时间开始解码
            videoFrameExtractor.seekTo(currentMs * 1000);
            // decoder 负责重新 seek 解码，内部已做提前 4 秒逻辑
            audioDecoder.seekTo(currentMs);
            Log.d(TAG, "🎯 audioDecoder.seekTo() 调用，当前视频播放时间: " + currentMs);
            audioStartTime = currentMs;

            // 如果之前已经播到结尾，清零标记并恢复分析
            if (isVideoCompleted) {
                isVideoCompleted = false;
                // 只有当前已经在播放，才立刻恢复分析；否则继续保持暂停
                if (videoView.isPlaying()) {
                    resumeAnalysis();
                }
            }
            // 清空蓝牙控制动作缓存
            currentBluetoothState = "";
            pendingBluetoothState = "";
            latestBluetoothAction.set("");
            currentStateStartTime = 0;
            pendingStateStartTime = 0;
        };

        seekHandler.postDelayed(pendingSeekRunnable, 500);
    }

    private void pauseAnalysis() {
        isAnalysisPaused.set(true);
        Log.i(TAG, "暂停分析");
    }

    private void resumeAnalysis() {
        isAnalysisPaused.set(false);
        Log.i(TAG, "恢复分析");
    }

    private void enterFullscreen() {
        isFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit);
    }

    private void exitFullscreen() {
        isFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        btnFullscreen.setImageResource(R.drawable.ic_fullscreen);
    }

    // 工具方法保持不变
    private float[][][] convertPoseWindowToInput(ArrayDeque<float[][]> window) {
        int size = window.size();
        float[][][] input = new float[size][17][3];
        int idx = 0;
        for (float[][] kpts : window) {
            input[idx++] = kpts;
        }
        return input;
    }

    private int argMax(float[] scores) {
        int idx = 0;
        float maxVal = scores[0];
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > maxVal) {
                maxVal = scores[i];
                idx = i;
            }
        }
        return idx;
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
        Log.d(TAG, "onDestroy: 准备释放资源...");

        // 设置停止标志
        shouldStop.set(true);

        // 停止所有线程
        if (videoThread != null) {
            videoThread.quitSafely();
            try {
                videoThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "等待视频线程结束时被中断", e);
            }
        }

        if (audioThread != null) {
            audioThread.quitSafely();
            try {
                audioThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "等待音频线程结束时被中断", e);
            }
        }

        // 清理Handler回调
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        if (playStateHandler != null && playStateChecker != null) {
            playStateHandler.removeCallbacks(playStateChecker);
        }

        // 释放资源
        if (videoFrameExtractor != null) {
            videoFrameExtractor.release();
        }

        if (inferenceHelper != null) {
            inferenceHelper.close();
        }

        if (bluetoothHelper != null) {
            bluetoothHelper.close();
        }

        if (audioDecoder != null) {
            audioDecoder.stop();
        }

        if (audioHelper != null) {
            audioHelper.close();
        }

        super.onDestroy();
    }
}