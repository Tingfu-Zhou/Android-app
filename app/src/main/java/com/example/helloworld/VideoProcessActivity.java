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

import android.widget.FrameLayout;
import android.view.Gravity;
import android.content.res.Configuration;

public class VideoProcessActivity extends AppCompatActivity {

    private static final String TAG = "VideoProcessActivity";
    private VideoView videoView;
    private TextView tvOverlay;
    private TextView tvVideoAction;
    private TextView tvAudioAction;
    private VideoFrameExtractor videoFrameExtractor;
    private InferenceHelper inferenceHelper;
    //private BluetoothHelper bluetoothHelper;
    private Handler playStateHandler;
    private Runnable playStateChecker;

    private boolean isVideoCompleted = false;

    // ✅ 音频推理相关变量
    private AudioDecoder audioDecoder;
    private AudioInferenceHelper audioHelper;

    private FrameLayout videoContainer;  // UI新增
    private int originalWidth = 0;       // UI新增
    private int originalHeight = 0;      // UI新增

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
    private static final int SMOOTH_WINDOW_SIZE = 10; // 融合分析平滑窗口的大小 10
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
    private static final long BLUETOOTH_SEND_INTERVAL = 1600; // 蓝牙发送间隔1600ms
    private long lastBluetoothSendTime = 0;
    private String currentBluetoothState = "";
    private long currentStateStartTime = 0;
    private String pendingBluetoothState = "";
    private long pendingStateStartTime = 0;

    // ★ 新增：暂停时挂起的蓝牙动作与档位
    private String suspendedBluetoothState = "";
    private int suspendedLevel = 0;

    // NEW: 频率档位确认与节流相关
    private int currentLevel = 1;                 // 已生效档位（0..10）
    private long currentLevelSinceMs = 0;         // 当前档位生效起始时间
    private Integer pendingLevel = null;          // 待确认档位
    private long pendingLevelSinceMs = 0;         // 待确认计时

    // NEW: 参数（可调）
    private static final int LEVEL_STABLE_MS   = 0;   // 新档位需稳定的最短时间
    private static final int LEVEL_MIN_DUR_MS  = 0;  // 生效档位的最小驻留

    // 最近一次“已发送”档位（用于判断是否需要重发同一动作以更新档位）
    private int lastSentLevel = 0;                  // NEW: 记录上次发送出去的档位

    // ✅ 音频分析计时
    private long audioStartTime = 0;
    private boolean isAudioCompleted = false;
    private PcmCircularBuffer pcmBuffer;
    private Handler seekHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSeekRunnable;
    private boolean isFullscreen = false;
    private ImageButton btnFullscreen;
    private ImageButton btnBack;

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
    //private static final float BINARY_TH = 0.30f;
    // 主线程融合循环间隔（毫秒）
    private static final long MAIN_FUSION_INTERVAL = 800;
    //视频线程间隔
    private static final long VIDEO_LOOP_INTERVAL_MS = 100;
    //音频线程间隔
    private static final long AUDIO_LOOP_TICK_MS = 1000;

    /*
    // 音频节奏器
    private final AudioRhythmEstimator rhythmEstimator = new AudioRhythmEstimator(16000);

    // 音频节奏,使用原子引用来安全地在线程间共享结果
    private final java.util.concurrent.atomic.AtomicReference<Float> latestAudioRhythmHz =
            new java.util.concurrent.atomic.AtomicReference<>(Float.NaN);
    private final java.util.concurrent.atomic.AtomicReference<Float> latestAudioRhythmConf =
            new java.util.concurrent.atomic.AtomicReference<>(0f);
    private final java.util.concurrent.atomic.AtomicLong latestAudioRhythmTsMs =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicBoolean latestAudioRhythmValid =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    */
    // [12.30] Loudness 档位估计器（替代原 AudioRhythmEstimator）
    private final AudioLoudnessLevelEstimator loudnessEstimator = new AudioLoudnessLevelEstimator(16000);

    // [12.30] 音频“响度档位”结果（线程安全共享）
    private final java.util.concurrent.atomic.AtomicInteger latestAudioLoudLevel =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicReference<Float> latestAudioLoudConf =
            new java.util.concurrent.atomic.AtomicReference<>(0f);
    private final java.util.concurrent.atomic.AtomicLong latestAudioLoudTsMs =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicBoolean latestAudioLoudValid =
            new java.util.concurrent.atomic.AtomicBoolean(false);


    // 视频节律器实例初始化
    private final VideoRhythmEstimator videoRhythmEstimator = new VideoRhythmEstimator();

    // 视频节奏
    private final java.util.concurrent.atomic.AtomicReference<Float> latestVideoFreqHz =
            new java.util.concurrent.atomic.AtomicReference<>(Float.NaN);
    private final java.util.concurrent.atomic.AtomicReference<Float> latestVideoFreqConf =
            new java.util.concurrent.atomic.AtomicReference<>(0f);
    private final java.util.concurrent.atomic.AtomicLong latestVideoFreqTsMs =
            new java.util.concurrent.atomic.AtomicLong(0);


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: 正在初始化布局...");
        setContentView(R.layout.activity_video_process);

        // 初始化UI组件
        videoView = findViewById(R.id.videoView);
        videoContainer = findViewById(R.id.videoContainer);  // 🔴 新增这行
        tvOverlay = findViewById(R.id.tvOverlay);
        tvVideoAction = findViewById(R.id.tvVideoAction);
        tvAudioAction = findViewById(R.id.tvAudioAction);
        btnFullscreen = findViewById(R.id.btnFullscreen);

        // 返回主页面的初始化和点击监听
        btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // 正式版隐藏悬浮窗调试UI
        // tvOverlay.setVisibility(View.GONE);
        // tvVideoAction.setVisibility(View.GONE);
        // tvAudioAction.setVisibility(View.GONE);

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
            // 获取视频原始尺寸
            originalWidth = mp.getVideoWidth();
            originalHeight = mp.getVideoHeight();
            Log.d(TAG, "视频原始尺寸: " + originalWidth + "x" + originalHeight);

            // 设置初始视频缩放
            adjustVideoSize(false);
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
        pcmBuffer = new PcmCircularBuffer(16000, 20); // 采样率 16kHz，最多缓冲 20 秒
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
                    //Log.d(TAG, "[视频线程] 当前播放时间: " + currentMs + "ms");

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
                /** 因为视频链路是跨线程 + 异步的, 抽帧真正执行在主线程, 抽到帧后再 videoHandler.post(...) 回视频线程。
                 * MLKit 姿态检测是异步，主要耗时发生在 MLKit 的内部线程与回调阶段。
                 * ST-GCN++ 推理是在回调/后续处理里触发 (回调线程)
                 * 视频线程这轮 run() 只是“发起请求”，几乎不做重计算**/

                // 继续下一轮
                long nextDelay = Math.max(0, VIDEO_LOOP_INTERVAL_MS - elapsed);
                videoHandler.postDelayed(this, nextDelay);
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
            // [MOD] 归一化关键点：对齐 Windows 的 preNormalize2D 前置假设（先到 [0,1]）
            // 注意：keypoints[i][2] (conf) 保持不变
            for (int i = 0; i < keypoints.length; i++) {
                keypoints[i][0] = keypoints[i][0] / (float) TARGET_WIDTH;
                keypoints[i][1] = keypoints[i][1] / (float) TARGET_HEIGHT;
            }


            // === 将归一化后的关键点推入“视频节律器” ===
            // 注意：只在“本帧有人体关键点可用”时调用；若你的管线里可能出现 kp==null，就先判空。
            if (keypoints != null) {
                // framePtsMs：请用你当前这一帧对应的播放/展示时间戳（你已有的变量）
                long framePtsMs = System.currentTimeMillis();

                videoRhythmEstimator.onPoseFrame(keypoints, framePtsMs);

                // 拉取最新估计值并存储（先不进主融合）
                float f = videoRhythmEstimator.getLatestFreqHz();
                float c = videoRhythmEstimator.getLatestConf();
                long  t = videoRhythmEstimator.getLatestTsMs();
                Log.d(TAG, String.format("[频率测试] 视频节奏 - 频率:%.2f Hz, 置信度:%.2f", f, c));

                latestVideoFreqHz.set(f);
                latestVideoFreqConf.set(c);
                latestVideoFreqTsMs.set(t);
            } else {
                // === Seek/镜头切换时同步重置节律器与缓存 ===
                videoRhythmEstimator.reset();
                latestVideoFreqHz.set(Float.NaN);
                latestVideoFreqConf.set(0f);
                latestVideoFreqTsMs.set(0);
            }
            // === 原有流程继续 ===

            // 8帧二分类
            poseWindow8.add(keypoints);
            if (poseWindow8.size() > BINARY_WINDOW) poseWindow8.poll();

            if (poseWindow8.size() == BINARY_WINDOW) {
                /*
                float[][][] binInput = convertPoseWindowToInput(poseWindow8);
                long tStgcnStart = System.currentTimeMillis();
                float prob = inferenceHelper.runBinary(binInput);
                long tStgcnEnd = System.currentTimeMillis();
                Log.d(TAG, "[计时] [视频线程] 🧠 二分类ST-GCN++ 推理耗时: " + (tStgcnEnd - tStgcnStart) + " ms");
                // 修改：注释掉二分类判断，让skipMulti始终为false
                if (prob < BINARY_TH) {
                    Log.d(TAG, "[视频线程] [同步分析] 二分类判定为Background");
                    latestVideoAction.set("Background");
                    latestVideoConfidence.set(prob);
                    latestVideoTimestamp.set(System.currentTimeMillis());
                    skipMulti = true;
                }
                 */
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
                    // [MOD] 对齐 Windows：窗口级 bbox 归一化（MMAction2 PreNormalize2D）
                    input = preNormalize2D(input);
                    long tStgcnStart = System.currentTimeMillis();
                    float[] scores = inferenceHelper.runStgcnModel(input);
                    long tStgcnEnd = System.currentTimeMillis();
                    Log.d(TAG, "[计时] [视频线程] 🧠 ST-GCN++ 推理耗时: " + (tStgcnEnd - tStgcnStart) + " ms");

                    if (scores != null) {
                        float[] probs = softmax(scores);

                        // 新增：合并同类概率
                        float probOral = probs[0] + probs[1]; // oral = label 0 + label 1
                        float probDoslow = probs[2] + probs[3] + probs[4] + probs[5]; // doslow = label 2 + label 3 + label 4 + label 5
                        // 噪音类分开
                        float probNoiseStand = probs[6];
                        float probNoiseSit = probs[7];

                        // 计算目标类和噪声类的概率和
                        float TargetProb = probOral + probDoslow;
                        float NoiseProb = probNoiseStand + probNoiseSit;
                        //Log.d(TAG, String.format("[同步分析] probOral=%.4f, probDoslow=%.4f, NoiseProb=%.4f", probOral, probDoslow, NoiseProb));

                        float NOISE_RATIO_THRESHOLD = 1.5f; // β=1.5，噪声需要比目标类高50%才被认定
                        // 比例阈值参数,根据目标类的置信度动态调整比例阈值
                        /*
                        if (maxTargetProb > 0.7f) {
                            // 目标类置信度高时，噪声需要更显著才能胜出
                            NOISE_RATIO_THRESHOLD = 1.8f;
                        } else if (maxTargetProb > 0.5f) {
                            NOISE_RATIO_THRESHOLD = 1.5f;
                        } else {
                            // 目标类置信度低时，降低比例要求
                            NOISE_RATIO_THRESHOLD = 1.3f;
                        }
                        */

                        String actionClass;
                        float bestScore;

                        // 判定逻辑
                        if (NoiseProb > TargetProb * NOISE_RATIO_THRESHOLD) {
                            // 噪声显著高于目标类
                            if (probNoiseStand > probNoiseSit) {
                                actionClass = "Noise";
                                bestScore = 0.0f;
                            } else {
                                actionClass = "Noise";
                                bestScore = 0.0f;
                            }
                            //Log.d(TAG, String.format("噪声显著优于目标类 (噪声:%.3f > 目标:%.3f × %.1f)",
                                    //maxNoiseProb, maxTargetProb, NOISE_RATIO_THRESHOLD));
                        } else {
                            // 在目标类中选择
                            if (probOral > probDoslow) {
                                actionClass = "oral";
                                bestScore = probOral;
                            } else {
                                actionClass = "do";
                                bestScore = probDoslow;
                            }
                        }


                        //采用置信度阈值法，若最大概率 < 阈值 T，则强制判为"杂音"类别,否则按照原有 argmax 判别类别。
                        float threshold = 0.0f;
                        if (bestScore < threshold) {
                            actionClass = "Noise";
                            bestScore = 1.0f; // 低置信度统一视为噪音
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

                        // 原子更新结果
                        latestVideoAction.set(actionClass);
                        latestVideoConfidence.set(bestScore);
                        latestVideoTimestamp.set(System.currentTimeMillis());

                        // 创建 final 变量用于 lambda
                        final String finalActionClass = actionClass;
                        final float finalBestScore = bestScore;

                        // UI更新需要在主线程
                        mainHandler.post(() ->
                                tvVideoAction.setText(String.format("V: %s (p=%.2f)", finalActionClass, finalBestScore))
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
                    audioHandler.postDelayed(this, 1000);
                    return;
                }
                long t0 = System.currentTimeMillis();

                long currentMs = videoView.getCurrentPosition();
                long currentSystemTime = System.currentTimeMillis();

                // 控制音频推理频率, 每1秒执行一次
                if ((currentSystemTime - lastAudioInferenceTime) >= AUDIO_INFERENCE_INTERVAL) {
                    synchronized (audioLock) {
                        if ((currentMs - audioStartTime) >= 4000) { // seek后等待4秒，让缓冲区有时间积累足够的数据
                            // 读取音频数据
                            float[] audioSegment = pcmBuffer.readWindowRelaxed(currentMs, 32000);

                            /*
                            // **************音频频率分析**************
                            float[] last1s = pcmBuffer.readWindowRelaxed(currentMs,16000); // 1秒 @16 kHz
                            if (last1s != null && last1s.length > 0) {
                                rhythmEstimator.push(last1s); // 将~1秒追加到4秒内部缓冲区
                            }
                            // 仅在预热时(累积>=4秒)且**每~1秒节拍一次**时估计。
                            if (rhythmEstimator.isWarm()) {
                                AudioRhythmEstimator.Result rr = rhythmEstimator.estimate(System.currentTimeMillis());
                                // 仅存储(根据您的要求，不在此处融合)
                                Log.d(TAG, String.format("[频率测试] 音频节奏 - 有效:%s, 频率:%.2f Hz, 置信度:%.2f",
                                        rr.valid, rr.frequencyHz, rr.confidence));
                                latestAudioRhythmHz.set(rr.frequencyHz);
                                latestAudioRhythmConf.set(rr.confidence);
                                latestAudioRhythmTsMs.set(rr.timestampMs);
                                latestAudioRhythmValid.set(rr.valid);
                            }
                            //******************************************
                            */

                            // **************[12.30] 音频 Loudness → 档位分析（替代频率估计）**************
                            float[] last1s = pcmBuffer.readWindowRelaxed(currentMs, 8000); // 0.5秒, 16 kHz/s
                            if (last1s != null && last1s.length > 0) {
                                loudnessEstimator.push(last1s);
                                AudioLoudnessLevelEstimator.Result lr = loudnessEstimator.estimate(System.currentTimeMillis());

                                Log.d(TAG, String.format("[响度档位] valid:%s, level:%d, conf:%.2f, db:%.1f",
                                        lr.valid, lr.level, lr.confidence, lr.db));

                                latestAudioLoudLevel.set(lr.level);
                                latestAudioLoudConf.set(lr.confidence);
                                latestAudioLoudTsMs.set(lr.timestampMs);
                                latestAudioLoudValid.set(lr.valid);
                            }
                            //********************************************************************

                            if (audioSegment != null) {
                                // 音频推理
                                // ⏱️ 音频推理计时
                                long tAudioInferStart = System.currentTimeMillis();
                                AudioInferenceHelper.AudioInferenceResult result = audioHelper.predict(audioSegment);
                                long tAudioInferEnd = System.currentTimeMillis();
                                Log.d(TAG, "[计时] 🔊 音频推理耗时: " + (tAudioInferEnd - tAudioInferStart) + " ms");

                                String[] audioClasses = {"do", "oral", "Noise"};;
                                float threshold = 0.0f; // 置信度阈值

                                int index = result.index;
                                float confidence = result.confidence;

                                if (index >= 0 && index < audioClasses.length) {
                                    // 如果最大概率小于阈值，则归为"杂音"类(003)
                                    if (confidence < threshold) {
                                        index = audioClasses.length - 1;
                                        confidence = 1.0f; // 可选：视为"完全属于杂音"
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

                                    // 原子更新结果
                                    latestAudioAction.set(action);
                                    latestAudioConfidence.set(confidence);
                                    latestAudioTimestamp.set(System.currentTimeMillis());

                                    // UI更新
                                    final String displayText = String.format("A: %s (p=%.2f)", action, confidence);
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

                // 计算本轮耗时并做动态延迟
                long elapsed = System.currentTimeMillis() - t0;
                Log.d(TAG, "[音频线程] 本轮调度耗时: " + elapsed + "ms");
                long nextDelay = Math.max(0, AUDIO_LOOP_TICK_MS - elapsed);
                // 继续下一轮
                audioHandler.postDelayed(this, nextDelay);
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

                // 检查BLE是否被本地按键暂停
                if (BLEManager.globalManager != null && BLEManager.globalManager.isPausedByLocal()) {
                    Log.d(TAG, "[融合线程] BLE被本地按键暂停，跳过蓝牙发送");
                    // 仍然进行分析但不发送蓝牙命令
                }

                // 检查是否暂停
                if (isAnalysisPaused.get()) {
                    Log.d(TAG, "[融合线程] 当前处于暂停状态，跳过本轮融合");
                    mainHandler.postDelayed(this, MAIN_FUSION_INTERVAL);
                    return;
                }
                long t0 = System.currentTimeMillis();

                // 读取最新的分析结果（原子操作，线程安全）
                String videoAction = latestVideoAction.get();
                String audioAction = latestAudioAction.get();
                float videoConf = latestVideoConfidence.get();
                float audioConf = latestAudioConfidence.get();
                long videoTime = latestVideoTimestamp.get();
                long audioTime = latestAudioTimestamp.get();

                // 音频节奏
                boolean audioFreqValid = latestAudioLoudValid.get();
                float audioFreq = (float) latestAudioLoudLevel.get();    // [MOD] 用 level 伪装成“freq输入”
                float audioFreqConf = latestAudioLoudConf.get();
                long audioFreqTs = latestAudioLoudTsMs.get();
                /*
                boolean audioFreqValid = latestAudioRhythmValid.get();
                float audioFreq = latestAudioRhythmHz.get();
                float audioFreqConf = latestAudioRhythmConf.get();
                long audioFreqTs = latestAudioRhythmTsMs.get();
                */

                // 视频节奏
                float videoFreq = latestVideoFreqHz.get();
                float videoFreqConf = latestVideoFreqConf.get();
                long  videoFreqTs = latestVideoFreqTsMs.get();

                // 计算结果的新鲜度（毫秒）
                long currentTime = System.currentTimeMillis();
                long videoAge = videoTime > 0 ? currentTime - videoTime : Long.MAX_VALUE;
                long audioAge = audioTime > 0 ? currentTime - audioTime : Long.MAX_VALUE;
                long videoFreqAge = videoFreqTs > 0 ? currentTime - videoFreqTs : Long.MAX_VALUE;
                long audioFreqAge = audioFreqTs > 0 ? currentTime - audioFreqTs : Long.MAX_VALUE;

                // 过滤超过2秒的过期数据
                final long MAX_AGE = 2000; // 2秒

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

                // 动作类型归一化："oral" 统一处理为 "do"
                if ("oral".equals(videoAction)) {
                    videoAction = "do";
                    Log.d(TAG, "[融合] 视频动作类型 oral -> do");
                }
                if ("oral".equals(audioAction)) {
                    audioAction = "do";
                    Log.d(TAG, "[融合] 音频动作类型 oral -> do");
                }

                // 7.19 修改：使用平滑融合替代原有的简单融合逻辑
                String finalAction = smoothedFusion(videoAction, audioAction, videoConf, audioConf);
                // 临时采用音频节律作为最终节律
                // 将“最终节律计算”封装为独立方法，便于后续替换为音视频融合节律
                int finalFreq = computeFinalFreq(audioFreq, audioFreqConf, videoFreq, videoFreqConf);

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

                // 更新UI, 显示蓝牙实际发送的动作
                String bluetoothAction = latestBluetoothAction.get();
                if (!bluetoothAction.isEmpty()) {
                    tvOverlay.setText("蓝牙: " + bluetoothAction + "节奏：" + currentLevel);
                } else {
                    tvOverlay.setText("蓝牙发送等待识别...");
                }

                // 继续下一轮
                long elapsed = System.currentTimeMillis() - t0;
                Log.d(TAG, "[融合线程] 本轮调度耗时: " + elapsed + "ms");
                long nextDelay = Math.max(0, MAIN_FUSION_INTERVAL - elapsed);
                mainHandler.postDelayed(this, nextDelay);
            }
        };

        mainHandler.post(fusionRunnable);
    }

    /* 频率->10档映射占位表（index 1..10：对应档位1~10；0为停止）
    “周期性事件”指的是：一次完整的抽插周期（前推 + 后拉，或一次节律峰到下一次节律峰）
    1 Hz = 1 次/秒 的完整抽插周期
     * 频率 -> 10 档映射（真实机械频率）
     * 说明：
     * - 1 次抽插 = 马达 3 转
     * - freq = RPM / 180
     * - 当前硬件可达范围 ≈ 1.0 – 1.8 Hz
     */
    private static final float[][] LEVEL_RANGES = new float[][]{
            null,                // 0 占位（停止）

            {0.95f, 1.12f},      // 档1 ≈ 190 RPM (1.06 Hz)
            {1.12f, 1.27f},      // 档2 ≈ 220 RPM (1.22 Hz)
            {1.27f, 1.40f},      // 档3 ≈ 240 RPM (1.33 Hz)
            {1.40f, 1.53f},      // 档4 ≈ 270 RPM (1.50 Hz)
            {1.53f, 1.58f},      // 档5 ≈ 280 RPM (1.56 Hz)
            {1.58f, 1.63f},      // 档6 ≈ 290 RPM (1.61 Hz)
            {1.63f, 1.66f},      // 档7 ≈ 295 RPM (1.64 Hz)
            {1.66f, 1.70f},      // 档8 ≈ 300 RPM (1.67 Hz)
            {1.70f, 1.75f},      // 档9 ≈ 310 RPM (1.72 Hz)
            {1.75f, 1.85f}       // 档10 ≈ 320 RPM (1.78 Hz)
    };

    // === NEW: 把 Hz 映射为 0..10 档（0为停止）——等工厂给确定值后替换 LEVEL_RANGES 即可
    private static int mapFreqToLevel(final float hz) {
        if (Float.isNaN(hz) || hz <= 0f) return 0;
        for (int lvl = 1; lvl <= 10; lvl++) {
            float[] r = LEVEL_RANGES[lvl];
            if (r != null && hz >= r[0] && hz < r[1]) {
                return Math.min(lvl, 9);
            }
        }
        return 9; // 超出则钳到最高档
    }

    /**
     * 12.11 计算最终节律档位（0..10）。
     *
     * <p>当前策略：仅使用音频节律映射为档位，并做“置信度阈值 + 方向性门控（涨档更严格，降档更宽松）”。
     * 预留 videoFreq/videoFreqConf 参数，便于未来扩展为音视频融合节律。</p>
     */
    private int computeFinalFreq(float audioFreq, float audioFreqConf, float videoFreq, float videoFreqConf) {
        // 临时采用音频节律作为最终节律
        // int finalFreq = mapFreqToLevel(audioFreq);
        int finalFreq = clampLevelFromLoudness(audioFreq); // [MOD] audioFreq 实际是 level(float)
        Log.d(TAG, "[音频响度] 得到音频档位: " + audioFreq + " 置信度: " + audioFreqConf + " 档位: " + finalFreq);

        int videoFre = mapFreqToLevel(videoFreq);
        Log.d(TAG, "[视频节律] 得到视频节律: " + videoFreq + " 置信度: " + videoFreqConf + " 档位: " + videoFre);
        // Log.d(TAG, "[音频节律] 得到音频节律: " + audioFreq + " 置信度: " + audioFreqConf + " 档位: " + finalFreq);

        // === 12.12: 方向性置信度门控（涨档更严格，降档更宽松） ===
        {
            float conf = audioFreqConf;      // 当前这帧的置信度

            // 三个可调参数
            final float CONF_IGNORE = 0.10f;  // 极低置信度：整体忽略本次节律
            final float CONF_UP     = 0.10f;  // 涨档所需置信度
            final float CONF_DOWN   = 0.10f;  // 降档所需置信度

            int curLevel       = currentLevel;  // 当前已生效档位（0..10）
            int candidateLevel = finalFreq;     // 本次根据 audioFreq 映射出来的档位

            // 1) 极低置信度：直接清空本次节律，维持 currentLevel
            if (Float.isNaN(audioFreq) || conf < CONF_IGNORE) {
                Log.d(TAG, String.format(
                        "[音频节律] conf=%.2f < CONF_IGNORE=%.2f，本次节律整体忽略，freq=NaN，沿用 currentLevel=%d",
                        conf, CONF_IGNORE, curLevel));
                finalFreq = curLevel;  // 不给 updateBluetoothState 提供变档机会
            } else {
                // 2) 根据档位变动方向应用不同门槛
                if (candidateLevel > curLevel && conf < CONF_UP) {
                    // 尝试“涨档”但置信度不足 → 不允许涨档
                    Log.d(TAG, String.format(
                            "[音频节律] 尝试涨档 %d→%d 但 conf=%.2f < CONF_UP=%.2f，本次不生效，沿用 currentLevel=%d",
                            curLevel, candidateLevel, conf, CONF_UP, curLevel));
                    finalFreq = curLevel;
                } else if (candidateLevel < curLevel && conf < CONF_DOWN) {
                    // 尝试“降档”但置信度也太低 → 不允许降档（可视需要放宽）
                    Log.d(TAG, String.format(
                            "[音频节律] 尝试降档 %d→%d 但 conf=%.2f < CONF_DOWN=%.2f，本次不生效，沿用 currentLevel=%d",
                            curLevel, candidateLevel, conf, CONF_DOWN, curLevel));
                    finalFreq = curLevel;
                }
                // candidateLevel == curLevel 时无需处理
            }
        }

        return finalFreq;
    }

    // [ADD] audioFreq 实际承载的是 loudness level（float），这里做 0..9 钳制
    private static int clampLevelFromLoudness(final float levelLike) {
        if (Float.isNaN(levelLike)) return 0;
        int lv = Math.round(levelLike);
        if (lv < 0) lv = 0;
        if (lv > 9) lv = 9;
        return lv;
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
                float weight = (float)(index + 1) / actionHistory.size(); // 时间权重，越新权重越高

                // 处理视频动作
                if (!record.videoAction.isEmpty() && !record.videoAction.equals("Background")) {
                    // 包括Noise在内的所有动作都参与评分
                    String videoKey = record.videoAction;
                    float score = actionScores.getOrDefault(videoKey, 0f);
                    score += record.videoConfidence * weight * 0.7f; // 视频权重稍低
                    actionScores.put(videoKey, score);

                    int count = actionCounts.getOrDefault(videoKey, 0);
                    actionCounts.put(videoKey, count + 1);
                }

                // 处理音频动作
                if (!record.audioAction.isEmpty()) {
                    // 包括Noise在内的所有动作都参与评分
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

    // 简单的动作选择逻辑（用于历史记录不足时）
    private String selectBestAction(String videoAction, String audioAction, float videoConf, float audioConf) {
        // 使用置信度来决定，而不是排除Noise
        // 音频优先策略
        if (!audioAction.isEmpty()) {
            // 如果音频是有效动作（非Noise）或音频置信度很高，使用音频
            if (!audioAction.equals("Noise") || audioConf > 0.7f) {
                return audioAction;
            }
        }
        // 视频作为备选
        if (!videoAction.isEmpty() && !videoAction.equals("Background")) {
            // 如果视频是有效动作（非Noise）或视频置信度很高，使用视频
            if (!videoAction.equals("Noise") || videoConf > 0.7f) {
                return videoAction;
            }
        }
        // 如果音视频都是Noise，返回Noise（而不是空字符串）
        if ("Noise".equals(audioAction) || "Noise".equals(videoAction)) {
            return "Noise";
        }

        return "";
    }


    // 蓝牙发送状态管理器
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
                    boolean stableOk = (dwell >= LEVEL_STABLE_MS);                      // 短稳，设为 0
                    // 因为蓝牙发送管理器中，发送时已经有蓝牙持续时间了，因此在蓝牙发送管理器中，档位的持续时间重复了，这里将其设为0
                    if (stableOk && (currentTime - currentLevelSinceMs >= LEVEL_MIN_DUR_MS)) { //短稳和最小驻留，两者都为0，不会其过滤作用
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

        // [ADD] 动作稳定确认时间：默认 1600ms；若从目标动作(do/oral)切到 Noise，则延长到 2400ms
        int actionStableMs = 1600;
        if (isSexAction(currentBluetoothState) && "Noise".equals(newAction)) {
            actionStableMs = 2400;
        }

        // [MOD] 使用动态稳定确认时间
        if (pendingBluetoothState.equals(newAction) &&
                (currentTime - pendingStateStartTime) >= actionStableMs) {

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
                            Log.i(TAG, "[蓝牙] [同步分析] ✅ 已通过BLE发送指令(动作切换/含档位)");
                            lastSentLevel = levelToSend; // NEW: 记录本次已下发的档位
                            Log.d(TAG,"[音频节律] ✅ 发送节律："+ levelToSend);
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
                        Log.i(TAG, "[蓝牙] [同步分析] ✅ 已通过BLE发送指令(同动作/更新档位)");
                        lastSentLevel = levelToSend;
                        lastBluetoothSendTime = currentTime;
                        Log.d(TAG,"[音频节律] ✅ 发送节律："+ levelToSend);
                    } else {
                        Log.w(TAG, "[蓝牙] BLE未连接，无法更新档位（同动作）");
                    }
                }
            }
        }
    }


    // （可选）门控：当前动作是否支持“变速”（你若已有类似函数，可直接替换）
    // 根据你的动作命名规则自行实现判断逻辑
    private boolean currentStateSupportsSpeed() {
        return currentBluetoothState.startsWith("do")
                || currentBluetoothState.startsWith("oral");
    }

    // （可选）门控：newAction 是否属于“做爱大类”（你若已有类似函数，可直接替换）
    private boolean isSexAction(String action) {
        return action != null && (
                action.startsWith("do") ||
                        action.startsWith("oral")
        );
    }

    // 迟滞门限辅助
    private int upThreshold(int cur)   { return Math.min(10, cur + 1); }
    private int downThreshold(int cur) { return Math.max(0,  cur - 1); }



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
            // decoder 负责重新 seek 解码
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
            // 重置档位确认状态
            currentLevel = 1;
            currentLevelSinceMs = 0;
            pendingLevel = null;
            pendingLevelSinceMs = 0;

            // ★ 新增：清空挂起状态（seek后旧动作已无意义）
            suspendedBluetoothState = "";
            suspendedLevel = 0;

            //清空音频频率控制
            /*
            rhythmEstimator.reset();
            latestAudioRhythmHz.set(Float.NaN);
            latestAudioRhythmConf.set(0f);
            latestAudioRhythmTsMs.set(0);
            latestAudioRhythmValid.set(false);
            */
            // [12.30] reset loudness estimator
            loudnessEstimator.reset();
            // [12.30] 清空 loudness 输出
            latestAudioLoudLevel.set(0);
            latestAudioLoudConf.set(0f);
            latestAudioLoudTsMs.set(0L);
            latestAudioLoudValid.set(false);

            //清空视频频率控制
            videoRhythmEstimator.reset();
            latestVideoFreqHz.set(Float.NaN);
            latestVideoFreqConf.set(0f);
            latestVideoFreqTsMs.set(0);
        };

        seekHandler.postDelayed(pendingSeekRunnable, 500);
    }

    private void pauseAnalysis() {
        isAnalysisPaused.set(true);

        // ★ 新增：挂起当前蓝牙动作并发送停止信号
        if (!currentBluetoothState.isEmpty() && !"Noise".equals(currentBluetoothState)) {
            suspendedBluetoothState = currentBluetoothState;
            suspendedLevel = currentLevel;
            Log.i(TAG, String.format("[暂停] 挂起动作: %s, 档位: %d",
                    suspendedBluetoothState, suspendedLevel));
        }
        if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()
                && !BLEManager.globalManager.isPausedByLocal()) {
            BLEManager.globalManager.sendAction("Noise", 0);
            Log.i(TAG, "[暂停] 已发送停止信号(Noise)");
        }

        Log.i(TAG, "暂停分析");
    }

    private void resumeAnalysis() {
        isAnalysisPaused.set(false);

        // ★ 新增：恢复挂起的蓝牙动作
        if (!suspendedBluetoothState.isEmpty()) {
            Log.i(TAG, String.format("[恢复] 恢复动作: %s, 档位: %d",
                    suspendedBluetoothState, suspendedLevel));
            currentBluetoothState = suspendedBluetoothState;
            currentLevel = suspendedLevel;
            currentStateStartTime = System.currentTimeMillis();
            currentLevelSinceMs = System.currentTimeMillis();
            latestBluetoothAction.set(suspendedBluetoothState);

            if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()
                    && !BLEManager.globalManager.isPausedByLocal()) {
                BLEManager.globalManager.sendAction(suspendedBluetoothState, suspendedLevel);
                lastBluetoothSendTime = System.currentTimeMillis();
                lastSentLevel = suspendedLevel;
                Log.i(TAG, "[恢复] 已发送恢复动作: " + suspendedBluetoothState
                        + " 档位: " + suspendedLevel);
            }

            suspendedBluetoothState = "";
            suspendedLevel = 0;
        }

        Log.i(TAG, "恢复分析");
    }

    private void enterFullscreen() {
        isFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |           // 🔴 新增
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |  // 🔴 新增
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN        // 🔴 新增
        );

        // 🔴 新增：隐藏状态栏和导航栏
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit);

        // 🔴 新增：延迟调整视频尺寸，确保布局完成
        videoContainer.postDelayed(() -> adjustVideoSize(true), 100);
    }

    private void exitFullscreen() {
        isFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);

        // 显示状态栏
        if (getSupportActionBar() != null) {
            getSupportActionBar().show();
        }

        btnFullscreen.setImageResource(R.drawable.ic_fullscreen);

        // 延迟调整视频尺寸，确保布局完成
        videoContainer.postDelayed(() -> adjustVideoSize(false), 100);
    }

    // 添加调整视频尺寸的方法
    private void adjustVideoSize(boolean isFullscreen) {
        if (originalWidth == 0 || originalHeight == 0) return;

        // 获取容器尺寸
        int containerWidth = videoContainer.getWidth();
        int containerHeight = videoContainer.getHeight();

        if (containerWidth == 0 || containerHeight == 0) {
            // 如果容器尺寸还未确定，延迟执行
            videoContainer.post(() -> adjustVideoSize(isFullscreen));
            return;
        }

        float videoAspectRatio = (float) originalWidth / originalHeight;
        float containerAspectRatio = (float) containerWidth / containerHeight;

        int finalWidth;
        int finalHeight;

        if (isFullscreen) {
            // 全屏模式：尽可能填充屏幕
            if (videoAspectRatio > containerAspectRatio) {
                // 视频更宽，以宽度为准
                finalWidth = containerWidth;
                finalHeight = (int) (containerWidth / videoAspectRatio);
            } else {
                // 视频更高，以高度为准
                finalHeight = containerHeight;
                finalWidth = (int) (containerHeight * videoAspectRatio);
            }
        } else {
            // 竖屏模式：保持原有逻辑
            if (videoAspectRatio > containerAspectRatio) {
                finalWidth = containerWidth;
                finalHeight = (int) (containerWidth / videoAspectRatio);
            } else {
                finalHeight = containerHeight;
                finalWidth = (int) (containerHeight * videoAspectRatio);
            }
        }

        // 更新 VideoView 的布局参数
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        params.width = finalWidth;
        params.height = finalHeight;
        params.gravity = Gravity.CENTER;
        videoView.setLayoutParams(params);

        Log.d(TAG, String.format("调整视频尺寸 - 容器: %dx%d, 视频: %dx%d, 最终: %dx%d",
                containerWidth, containerHeight, originalWidth, originalHeight, finalWidth, finalHeight));
    }

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // 屏幕方向改变时重新调整视频尺寸
        videoContainer.post(() -> adjustVideoSize(isFullscreen));
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

    // =========================
    // [ADD] 对齐 Windows(ActionUtils.preNormalize2D)：窗口级 bbox 中心化 + 等比缩放
    // 输入: float[T][V][3]  (x,y,conf) 其中 conf<=0 视为无效点
    // 输出: 同维度
    // =========================
    private static float[][][] preNormalize2D(float[][][] window) {
        float xMin = Float.MAX_VALUE, yMin = Float.MAX_VALUE;
        float xMax = Float.MIN_VALUE, yMax = Float.MIN_VALUE;

        for (float[][] frame : window) {
            for (float[] kp : frame) {
                if (kp[2] <= 0f) continue;      // 仅统计有效关键点
                xMin = Math.min(xMin, kp[0]);
                yMin = Math.min(yMin, kp[1]);
                xMax = Math.max(xMax, kp[0]);
                yMax = Math.max(yMax, kp[1]);
            }
        }

        if (xMax < xMin) { // 全无有效点
            xMin = 0f; yMin = 0f; xMax = 1f; yMax = 1f;
        }

        float cx = 0.5f * (xMin + xMax);
        float cy = 0.5f * (yMin + yMax);
        float scale = Math.max(xMax - xMin, yMax - yMin);
        if (scale < 1e-6f) scale = 1e-6f;

        float[][][] out = new float[window.length][window[0].length][3];
        for (int t = 0; t < window.length; t++) {
            for (int v = 0; v < window[0].length; v++) {
                out[t][v][0] = (window[t][v][0] - cx) / scale;
                out[t][v][1] = (window[t][v][1] - cy) / scale;
                out[t][v][2] =  window[t][v][2];
            }
        }
        return out;
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

        // ★ 新增：退出时发送停止信号
        if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()) {
            BLEManager.globalManager.sendAction("Noise", 0);
            Log.i(TAG, "[退出] 已发送停止信号(Noise)");
        }

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

        if (audioDecoder != null) {
            audioDecoder.stop();
        }

        if (audioHelper != null) {
            audioHelper.close();
        }

        super.onDestroy();
    }
}