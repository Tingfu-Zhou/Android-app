package com.example.helloworld;


import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import android.graphics.Bitmap;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.ArrayDeque;


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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;


public class VideoProcessActivity extends AppCompatActivity {

    private static final String TAG = "VideoProcessActivity";

    private VideoView videoView;
    private TextView tvOverlay;
    private TextView tvVideoAction;
    private TextView tvAudioAction;
    private VideoFrameExtractor videoFrameExtractor;
    private InferenceHelper inferenceHelper;
    private BluetoothHelper bluetoothHelper;
    private Handler playStateHandler;          // 轮询播放状态
    private Runnable playStateChecker;         // 对应的 Runnable
    private Handler frameHandler;
    private Runnable frameRunnable;

    private boolean isVideoCompleted = false;

    // ✅ 音频推理相关变量
    private AudioDecoder audioDecoder;
    private AudioInferenceHelper audioHelper;
    private volatile String latestAudioAction = "";  // 音频识别结果
    private volatile String latestVideoAction = "";  // 视频识别结果

    // ✅ 音频分析计时
    private long audioStartTime = 0;
    private boolean isAudioCompleted = false;
    private PcmCircularBuffer pcmBuffer;
    private String lastFinalAction = "";// 用于去重：只在动作发生变化时发送蓝牙指令
    private Handler seekHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSeekRunnable;
    private boolean isFullscreen = false;
    private ImageButton btnFullscreen;
    private volatile boolean isAnalysisPaused = false;  // 控制是否暂停同步分析
    // 对每个关键点的 x, y 坐标做归一化处理时使用
    private static final int TARGET_WIDTH = 720;
    private static final int TARGET_HEIGHT = 480;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Intent intent = getIntent();

        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: 正在初始化布局...");
        setContentView(R.layout.activity_video_process);

        videoView = findViewById(R.id.videoView);
        tvOverlay = findViewById(R.id.tvOverlay);
        // ✅ 新增：绑定显示音视频识别结果的 TextView
        tvVideoAction = findViewById(R.id.tvVideoAction);
        tvAudioAction = findViewById(R.id.tvAudioAction);

        inferenceHelper = new InferenceHelper(this);

        // ✅ 初始化 Google ML Kit 的 PoseDetector（实时人体关键点检测器）
        //低精度PoseDetector
        // Base pose detector with streaming frames, when depending on the pose-detection sdk
        //PoseDetectorOptions options =
                //new PoseDetectorOptions.Builder()
                        //.setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                        //.build();
        // 高精度PoseDetector
        AccuratePoseDetectorOptions options =
                new AccuratePoseDetectorOptions.Builder()
                        .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                        .build();

        PoseDetector detector = PoseDetection.getClient(options);
        Log.d(TAG, "onCreate: [MLKit] ML Kit PoseDetector 初始化完成");
        // ✅ 设置给 InferenceHelper，让其内部调用时不为 null
        inferenceHelper.setPoseDetector(detector);


        Uri videoUri = intent.getData();

        if (videoUri != null) {
            videoView.setVideoURI(videoUri);
            Log.d(TAG, "onCreate: 设置用户选择的视频 URI: " + videoUri);
        } else {
            Log.e(TAG, "onCreate: 没有有效的视频 URI，退出！");
            finish(); // 无效启动，直接退出
            return;
        }

        // ✅ 初始化 VideoFrameExtractor
        try {
            videoFrameExtractor = new VideoFrameExtractor(this, videoUri);
            Log.d(TAG, "onCreate: VideoFrameExtractor 初始化成功");
        } catch (IOException e) {
            Log.e(TAG, "onCreate: VideoFrameExtractor 初始化失败", e);
            finish(); // 无法初始化解码器，退出
        }


        // 视频播放控制器
        videoView.setMediaController(new MediaController(this));
        videoView.requestFocus();

      
        // ✅ 记录视频播放结束时间
        videoView.setOnCompletionListener(mp -> {
            //videoEndTime = videoView.getCurrentPosition();
            isVideoCompleted = true; // 标记视频结束
            pauseAnalysis();                  // ⬅️ NEW: 让分析停一下，但循环还在
            Log.d(TAG, "视频播放完成！暂停同步分析，等待用户操作");
            // Log.d(TAG, "视频播放完成！videoEndTime: " + videoEndTime);
            // Log.e(TAG, "【测试】视频播放总时长: " + (videoEndTime - videoStartTime) + " ms");
        });

        videoView.setOnPreparedListener(mp -> {
            //videoStartTime = videoView.getCurrentPosition();
            //Log.d(TAG, "视频已准备好，开始播放！videoStartTime: " + videoStartTime);

            // ✅ 设置 seek 完成监听，检测用户拖动视频播放进度
            mp.setOnSeekCompleteListener(seekMp -> {
                int currentMs = videoView.getCurrentPosition();
                Log.w(TAG, "[同步分析] 🟡 onSeekComplete(): 用户完成拖动，跳转到 " + currentMs + "ms，准备延迟处理 seek");

                // ✅ 取消之前挂起的 seek 请求（防抖）
                if (pendingSeekRunnable != null) {
                    seekHandler.removeCallbacks(pendingSeekRunnable);
                }

                // ✅ 延迟 500ms 执行最终 seek 逻辑（只处理最后一次）
                pendingSeekRunnable = () -> {
                    Log.i(TAG, "✅ 执行最终 seek 处理逻辑，时间点：" + currentMs);

                    // ✅ 清空 pose 和 pcm 缓冲，避免读取到“前一个时间点”的旧数据
                    poseWindow.clear();
                    pcmBuffer.reset();

                    // ✅ 通知视频帧抽取器：从当前时间开始解码
                    videoFrameExtractor.seekTo(currentMs * 1000);

                    // ✅ decoder 负责重新 seek 解码，内部已做提前 4 秒逻辑
                    audioDecoder.seekTo(currentMs);
                    Log.d(TAG, "🎯 audioDecoder.seekTo() 调用，当前视频播放时间: " + currentMs);

                    audioStartTime = currentMs;

                    // ⬇️ NEW —— 如果之前已经播到结尾，清零标记并恢复分析
                    if (isVideoCompleted) {
                        isVideoCompleted = false;
                        // 只有当前已经在播放，才立刻恢复分析；否则继续保持暂停
                        if (videoView.isPlaying()) {
                            resumeAnalysis();
                        }
                    }

                    // ✅ 清空蓝牙控制动作缓存
                    lastFinalAction = "";
                };

                seekHandler.postDelayed(pendingSeekRunnable, 500);
            });


            videoView.start();
            startSynchronizedVideoAnalysis();//在此启动主循环
        });


        videoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "视频播放出错 what:" + what + " extra:" + extra);
            return true;
        });

        // ✅ 开始分析计时
        //analysisStartTime = videoView.getCurrentPosition();
        //Log.d(TAG, "analysisStartTime: " + analysisStartTime);

        // ✅ 初始化音频模型
        Log.d(TAG, "onCreate: 初始化音频推理 AudioInferenceHelper...");
        audioHelper = new AudioInferenceHelper(this);

        // ✅ 启动音频解码器，从视频中提取音频流进行实时分析
        pcmBuffer = new PcmCircularBuffer(16000, 10); // 采样率 16kHz，最多缓冲 10 秒
        audioDecoder = new AudioDecoder(this, videoUri, pcmBuffer, () -> videoView.getCurrentPosition());

        //audioStartTime = videoView.getCurrentPosition(); // ✅ 记录音频分析开始时间
        //Log.d(TAG, "音频解码开始时间 audioStartTime: " + audioStartTime);

        audioDecoder.setOnCompleteListener(() -> {
            //audioEndTime = videoView.getCurrentPosition();
            isAudioCompleted = true;
            //Log.d(TAG, "音频解码完成，耗时: " + (audioEndTime - audioStartTime) + " ms");
        });
        audioDecoder.startDecoding();
        Log.d(TAG, "onCreate: audioDecoder 已启动解码线程");

        btnFullscreen = findViewById(R.id.btnFullscreen);
        btnFullscreen.setOnClickListener(v -> {
            if (!isFullscreen) {
                enterFullscreen();
            } else {
                exitFullscreen();
            }
        });

        // ✅ 启动播放状态检测逻辑：每 200ms 监听一次 videoView 播放状态
        // 检测用户暂停/恢复播放进度
        playStateHandler = new Handler(Looper.getMainLooper());
        playStateChecker = new Runnable() {
            boolean lastPlayingState = true;

            @Override public void run() {
                boolean isPlayingNow = videoView.isPlaying();
                if (isPlayingNow != lastPlayingState) {
                    if (isPlayingNow) {
                        resumeAnalysis();
                    } else {
                        pauseAnalysis();
                    }
                    lastPlayingState = isPlayingNow;
                }
                playStateHandler.postDelayed(this, 200); // 每200ms检测一次播放状态
            }
        };
        playStateHandler.post(playStateChecker);

    }


    private Handler syncHandler = new Handler(Looper.getMainLooper());
    private Runnable syncRunnable;
    // 主循环计时
    private long lastRunStart = -1;
    private final ArrayDeque<float[][]> poseWindow = new ArrayDeque<>();
    // 窗口大小为32帧
    private static final int WINDOW_SIZE = 32;  // 原值: 16
    // 32 帧窗口滑动步长
    private static final int MULTI_STEP = 8;            // 每累积 8 帧触发一次多分类推理
    // 距离上次多分类已累积的帧数
    private int framesSinceLastMulti = 0;

    // Binary-ST-GCN++使用的8-帧窗口
    private static final int BINARY_WINDOW = 8;
    private final ArrayDeque<float[][]> poseWindow8 = new ArrayDeque<>();
    // Binary-ST-GCN++ 推理阈值
    private static final float BINARY_TH = 0.29f;
    // ✅ 动作类别
    //private final String[] actionClasses = {"dofast", "doslow", "oral", "Noise"};
    // ✅ 动作类别（共 9 类；下标 = label）
    private final String[] actionClasses = {
            "Oral-Kneel",         // label 0
            "Oral-Lie",           // label 1
            "Missionary",         // label 2
            "Doggy",              // label 3
            "Cowgirl",            // label 4
            "Standing Rear",      // label 5
            "Noise-Stand/Walk",   // label 6
            "Noise-Sit",          // label 7
            "Noise-Lie"           // label 8
    };

    private void startSynchronizedVideoAnalysis() {
        Log.d(TAG, "启动同步视频分析主循环...");

        syncRunnable = new Runnable() {
            @Override
            public void run() {
                /* =================== 诊断 · 周期打点 =================== */
                long tRunStart = System.currentTimeMillis();
                if (lastRunStart >= 0) {
                    long delta = tRunStart - lastRunStart;
                    Log.d(TAG, String.format("[计时] ▲ run() 周期 = %d ms", delta));
                    //若输出长期接近 100ms → 主线程未被阻塞。
                    //若频繁跳到 150ms、200ms+ → 回调里的 ST-GCN++ / 音频推理正占用主线程，应考虑迁移到后台线程。
                }
                lastRunStart = tRunStart;
                /* ===================================================== */
                if (isAnalysisPaused) {
                    Log.d(TAG, "[同步分析] 当前处于暂停状态，跳过本轮分析");
                    // 延迟为100ms，即0.1秒抽一帧
                    syncHandler.postDelayed(this, 100);  // 继续调度下次执行
                    return;
                }
                long t0 = System.currentTimeMillis();  // ⏱️ 起始时间
                try {
                    Log.d(TAG, "[同步分析] 🌀 开始新一轮同步分析循环");
                    int currentMs = videoView.getCurrentPosition(); // 当前播放位置
                    Log.d(TAG, "[同步分析] 当前播放时间: " + currentMs + "ms");

                    // 1. 视频分析逻辑（ML Kit 检测人体 + 关键点提取 + ST-GCN 推理）
                    // 1. 视频分析逻辑（异步推理）

                    // ⏱️ 添加抽帧计时
                    long tExtractStart = System.currentTimeMillis();
                    Bitmap frame = videoFrameExtractor.getFrameAt(currentMs * 1000);  // 参数为ms
                    long tExtractEnd = System.currentTimeMillis();
                    Log.d(TAG, "[计时] 📸 抽帧耗时: " + (tExtractEnd - tExtractStart) + " ms");

                    if (frame != null) {
                        // ⏱️ 记录帧大小信息（可选）
                        Log.d(TAG, "[抽帧] 帧大小: " + frame.getWidth() + "x" + frame.getHeight());

                        long finalCurrentMs = currentMs; // 用于异步回调
                        long tMMPoseStart = System.currentTimeMillis();

                        inferenceHelper.runPoseModelViaMLKit(frame, keypointsRaw -> {
                            boolean skipMulti = false; // 用于二分器跳过本轮视频分析
                            long tMMPoseEnd = System.currentTimeMillis();
                            Log.d(TAG, "[计时] 🦴 MLKit 关键点检测耗时: " + (tMMPoseEnd - tMMPoseStart) + " ms");

                            if (keypointsRaw == null) {
                                Log.w(TAG, "[同步分析] ML Kit 返回为null，跳过视频动作分析");
                            } else if (keypointsRaw.length > 0) {
                                // ⏱️ 添加关键点归一化计时
                                long tNormStart = System.currentTimeMillis();
                                float[][] keypoints = keypointsRaw[0][0];

                                for (int i = 0; i < keypoints.length; i++) {
                                    // 使用与训练时相同的归一化方式
                                    keypoints[i][0] = (keypoints[i][0] - TARGET_WIDTH / 2.0f) / (TARGET_WIDTH / 2.0f);   // x归一化到[-1, 1]
                                    keypoints[i][1] = (keypoints[i][1] - TARGET_HEIGHT / 2.0f) / (TARGET_HEIGHT / 2.0f); // y归一化到[-1, 1]
                                    // keypoints[i][2] 置信度不处理
                                }
                                long tNormEnd = System.currentTimeMillis();
                                Log.d(TAG, "[计时] 🔄 关键点归一化耗时: " + (tNormEnd - tNormStart) + " ms");


                                // 8帧二分类窗口：
                                poseWindow8.add(keypoints);
                                if (poseWindow8.size() > BINARY_WINDOW) poseWindow8.poll();

                                // ① Binary-ST-GCN++ 二分类
                                if (poseWindow8.size() == BINARY_WINDOW) {
                                    float[][][] binInput = convertPoseWindowToInput(poseWindow8);
                                    long tStgcnStart = System.currentTimeMillis();
                                    float prob = inferenceHelper.runBinary(binInput);   // Sigmoid 输出 0-1
                                    long tStgcnEnd = System.currentTimeMillis();
                                    Log.d(TAG, "[计时] 🧠 二分类ST-GCN++ 推理耗时: " + (tStgcnEnd - tStgcnStart) + " ms");
                                    if (prob < BINARY_TH) {
                                        Log.d(TAG, "[同步分析] p=" + prob + " < TH，判定为 Background，跳过后续视频分析");
                                        runOnUiThread(() -> tvVideoAction.setText(
                                                String.format("Video Action: Background")
                                        ));
                                        // return; // ✋ 直接结束本轮分析
                                        skipMulti = true;
                                    }
                                    Log.d(TAG, "[同步分析] p=" + prob + " ≥ TH，送入多类 ST-GCN++");
                                }


                                /* --------------------- 多分类的 ST-GCN++ --------------------- */
                                // 仅当非 Background 时才继续累计 & 推理
                                if (!skipMulti) {
                                    poseWindow.add(keypoints);
                                    if (poseWindow.size() > WINDOW_SIZE) poseWindow.poll();
                                    // 累积帧计数
                                    framesSinceLastMulti++;
                                    // 多分类的ST-GCN++ 触发条件：窗口满且已累积 ≥ MULTI_STEP 帧
                                    if (poseWindow.size() == WINDOW_SIZE && framesSinceLastMulti >= MULTI_STEP) {
                                        // 归零计数器，开始一次多分类推理
                                        framesSinceLastMulti = 0;
                                        // ⏱️ 添加数据准备计时
                                        long tPrepStart = System.currentTimeMillis();
                                        float[][][] input = convertPoseWindowToInput(poseWindow);
                                        long tPrepEnd = System.currentTimeMillis();
                                        Log.d(TAG, "[计时] 📊 ST-GCN输入准备耗时: " + (tPrepEnd - tPrepStart) + " ms");

                                        long tStgcnStart = System.currentTimeMillis();
                                        float[] scores = inferenceHelper.runStgcnModel(input); // ST-GCN++ 进行动作推理
                                        long tStgcnEnd = System.currentTimeMillis();
                                        Log.d(TAG, "[计时] 🧠 ST-GCN++ 推理耗时: " + (tStgcnEnd - tStgcnStart) + " ms");

                                        if (scores != null) {
                                            // ……（原有后处理逻辑保持不变）
                                            //采用置信度阈值法，若最大概率 < 阈值 T（如0.5），则强制判为"杂音"类别,否则按照原有 argmax 判别类别。
                                            // ⏱️ 添加后处理计时
                                            long tPostStart = System.currentTimeMillis();
                                            float threshold = 0.5f; // 置信度阈值，可自定义
                                            float[] probs = softmax(scores);
                                            Log.d(TAG, "[同步分析] softmax 概率分布: " + Arrays.toString(probs));
                                            int bestIndex = argMax(probs);
                                            float bestScore = probs[bestIndex];

                                            /* -------- 7.5更新: 判断是否属于噪音三类 -------- */
                                            boolean isNoiseLabel = (bestIndex >= 6);     // 6,7,8 都是噪音
                                            boolean isLowConf    = (bestScore < threshold);

                                            /* -------- 新增 ②: 综合判定 -------- */
                                            String actionClass;
                                            if (isNoiseLabel || isLowConf) {             // 任一条件满足 → 统一当 Noise
                                                bestIndex   = 6;                         // “默认”映射为 stand/walk，可自定
                                                actionClass = actionClasses[bestIndex];
                                                Log.d(TAG, String.format(
                                                        "[同步分析] Noise 判定 (label=%d, p=%.3f, 低置信=%b)",
                                                        bestIndex, bestScore, isLowConf));
                                            } else {
                                                actionClass = actionClasses[bestIndex];
                                                Log.d(TAG, "[同步分析] 动作识别概率分布: " + Arrays.toString(probs));
                                            }

                                            latestVideoAction = actionClass;
                                            long tPostEnd = System.currentTimeMillis();
                                            Log.d(TAG, "[计时] 📈 动作分类后处理耗时: " + (tPostEnd - tPostStart) + " ms");

                                            runOnUiThread(() -> tvVideoAction.setText(
                                                    String.format("Video Action: %s (p=%.2f)", actionClass, bestScore)
                                            ));

                                        }
                                    }
                                } else { // ★ 修改 2：若被判为 Background，清空窗口 & 重置计数
                                    poseWindow.clear();
                                    framesSinceLastMulti = 0;
                                }
                                /* ------------------------------------------------------------ */
                            }
                            // ⏱️ 记录视频分析总耗时
                            long tVideoTotal = System.currentTimeMillis() - tMMPoseStart;
                            Log.d(TAG, "[计时] 📹 视频分析总耗时(含异步): " + tVideoTotal + " ms");

                            // ✅ 视频分析完成后 → 继续执行音频分析与融合逻辑
                            runAudioAndMerge(finalCurrentMs);
                        });
                    } else {
                        Log.w(TAG, "[抽帧] 未能提取到帧，跳过本轮分析");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "[同步分析] 运行时异常中断：", e);
                }

                long t1 = System.currentTimeMillis();  // ⏱️ 结束时间
                Log.d(TAG, "[计时] ⏰ 本轮 syncRunnable 总耗时: " + (t1 - t0) + " ms");

                // ✅ 循环控制 —— 永远 100 ms 后再来
                if (isVideoCompleted) {
                    Log.d(TAG, "[同步分析] 视频已播放完，暂停分析等待用户操作");
                }
                // 延迟为100ms
                syncHandler.postDelayed(this, 100);   // 100ms延迟，无条件重排队
            }
        };

        // 启动主循环
        syncHandler.post(syncRunnable);
    }

    // 2. 音频分析逻辑（2000ms PCM 推理）
    // 添加音频推理时间控制变量
    private long lastAudioInferenceTime = 0;  // 记录上次音频推理的时间
    private static final long AUDIO_INFERENCE_INTERVAL = 1000;  // 音频推理间隔1秒
    private void runAudioAndMerge(long currentTime) {
        long tAudioStart = System.currentTimeMillis(); // ⏱️ 音频分析开始

        if ((currentTime - audioStartTime) < 4000) {
            Log.w(TAG, "[同步分析] PCM 缓冲启动中，等待解码器填充...");
            return;
        }

        // 添加音频推理频率控制，每1秒执行一次
        long currentSystemTime = System.currentTimeMillis();
        boolean shouldRunAudioInference = (currentSystemTime - lastAudioInferenceTime) >= AUDIO_INFERENCE_INTERVAL;

        if (shouldRunAudioInference) {
            // ⏱️ 添加音频数据读取计时
            long tReadStart = System.currentTimeMillis();
            float[] audioSegment = pcmBuffer.readWindowRelaxed(currentTime, 32000);
            long tReadEnd = System.currentTimeMillis();

            if (audioSegment != null) {
                Log.d(TAG, "[计时] 🎵 音频数据读取耗时: " + (tReadEnd - tReadStart) + " ms, 数据长度: " + audioSegment.length);

                // ⏱️ 音频推理计时
                long tAudioInferStart = System.currentTimeMillis();
                AudioInferenceHelper.AudioInferenceResult result = audioHelper.predict(audioSegment);
                long tAudioInferEnd = System.currentTimeMillis();
                Log.d(TAG, "[计时] 🔊 音频推理耗时: " + (tAudioInferEnd - tAudioInferStart) + " ms");

                String[] audioClasses = {"dofast", "doslow", "oral", "Noise"};
                float threshold = 0.5f; // 置信度阈值

                int index = result.index;
                float confidence = result.confidence;

                if (index >= 0 && index < audioClasses.length) {
                    // 如果最大概率小于阈值，则归为"杂音"类（004）
                    if (confidence < threshold) {
                        index = audioClasses.length - 1; // "004"
                        confidence = 1.0f; // 可选：视为"完全属于杂音"
                    }
                    latestAudioAction = audioClasses[index];
                    Log.d(TAG, String.format("[同步分析] 音频动作识别结果: %s (p=%.2f)", latestAudioAction, confidence));
                    final String displayText = String.format("Audio Action: %s (p=%.2f)", latestAudioAction, confidence);
                    runOnUiThread(() -> tvAudioAction.setText(displayText));
                }

                // 更新上次音频推理时间
                lastAudioInferenceTime = currentSystemTime;
            } else {
                Log.w(TAG, "[音频] 未能读取到音频数据");
            }
        } else {
            // 不执行音频推理时的日志
            Log.d(TAG, "[音频] 距上次推理时间不足1秒，跳过本次音频推理");
        }

        // ⏱️ 融合逻辑计时
        long tMergeStart = System.currentTimeMillis();

        // 3. 蓝牙融合并发送
        String videoAction = latestVideoAction != null ? latestVideoAction.trim() : "";
        String audioAction = latestAudioAction != null ? latestAudioAction.trim() : "";
        String finalAction = "";

        if (!videoAction.isEmpty() && !audioAction.isEmpty()) {
            if (!videoAction.equals(audioAction)) {
                Log.d(TAG, "[融合] 音视频结果冲突，使用视频结果 → 视频=" + videoAction + ", 音频=" + audioAction);
            }
            finalAction = videoAction;
        } else if (!videoAction.isEmpty()) {
            finalAction = videoAction;
        } else if (!audioAction.isEmpty()) {
            finalAction = audioAction;
        }

        // ✅ 去重避免重复发送蓝牙指令
        if (!finalAction.isEmpty() && !finalAction.equals(lastFinalAction)) {
            // ⏱️ 蓝牙发送计时
            long tBtStart = System.currentTimeMillis();
            if (BluetoothHelper.globalHelper != null) {
                BluetoothHelper.globalHelper.sendData(finalAction);
            }
            long tBtEnd = System.currentTimeMillis();
            lastFinalAction = finalAction;
            Log.d(TAG, "[融合] 发送蓝牙控制指令: " + finalAction);
            //Log.d(TAG, "[计时] 📡 蓝牙发送耗时: " + (tBtEnd - tBtStart) + " ms");
        }

        // ✅ 更新 UI 显示
        if (!finalAction.isEmpty()) {
            String overlayText = "Video: " + videoAction + " | Audio: " + audioAction + " | Final: " + finalAction;
            runOnUiThread(() -> tvOverlay.setText(overlayText));
        }

        long tMergeEnd = System.currentTimeMillis();
        //Log.d(TAG, "[计时] 🔀 融合逻辑耗时: " + (tMergeEnd - tMergeStart) + " ms");

        long tAudioTotal = System.currentTimeMillis() - tAudioStart;
        //Log.d(TAG, "[计时] 🎧 音频分析+融合总耗时: " + tAudioTotal + " ms");

    }


    private void enterFullscreen() {
        isFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit);  // 切换为“退出全屏”图标
    }

    private void exitFullscreen() {
        isFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);

        btnFullscreen.setImageResource(R.drawable.ic_fullscreen);  // 切换为“进入全屏”图标
    }


    /**
     * 转换滑动窗口为 ST-GCN++ 输入
     */
    private float[][][] convertPoseWindowToInput(ArrayDeque<float[][]> window) {
        float[][][] input = new float[WINDOW_SIZE][17][3];
        int idx = 0;
        for (float[][] kpts : window) input[idx++] = kpts;
        return input;
    }

    /**
     * 返回最大得分对应的索引
     */
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

    //将 ST-GCN++ 输出的 logits 转为 softmax 概率分布。
    private float[] softmax(float[] logits) {
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float l : logits) maxLogit = Math.max(maxLogit, l);
        float sum = 0f;
        float[] expVals = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            expVals[i] = (float) Math.exp(logits[i] - maxLogit); // 防止溢出
            sum += expVals[i];
        }
        for (int i = 0; i < logits.length; i++) expVals[i] /= sum;
        return expVals;
    }


    private void pauseAnalysis() {
        isAnalysisPaused = true;
        Log.i(TAG, "✅ 暂停[同步分析]");
    }

    private void resumeAnalysis() {
        isAnalysisPaused = false;
        Log.i(TAG, "✅ 恢复[同步分析]");
    }


    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: 准备释放资源...");
        super.onDestroy();
        if (syncHandler != null && syncRunnable != null) {
            syncHandler.removeCallbacks(syncRunnable);
            Log.d(TAG, "onDestroy: 已停止同步视频分析循环");
        }
        if (frameHandler != null) {
            frameHandler.removeCallbacks(frameRunnable);
            Log.d(TAG, "onDestroy: 移除帧采样回调");
        }
        if (videoFrameExtractor != null) {
            videoFrameExtractor.release();
        }
        if (inferenceHelper != null) {
            inferenceHelper.close();
            Log.d(TAG, "onDestroy: inferenceHelper 已关闭");
        }
        if (bluetoothHelper != null) {
            bluetoothHelper.close();
            Log.d(TAG, "onDestroy: bluetoothHelper 已关闭");
        }
        if (audioDecoder != null) {
            audioDecoder.stop();
            Log.d(TAG, "onDestroy: audioDecoder 已停止");
        }
        if (audioHelper != null) {
            audioHelper.close();
            Log.d(TAG, "onDestroy: audioHelper 已关闭");
        }
        // ⬇️ NEW: 停止播放状态轮询
        if (playStateHandler != null && playStateChecker != null) {
            playStateHandler.removeCallbacks(playStateChecker);
            Log.d(TAG, "onDestroy: 已移除播放状态检测回调");
        }
    }
}
