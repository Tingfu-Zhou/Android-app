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

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class OnlineAnalysisActivity extends AppCompatActivity implements OnlineAnalysisService.OnlineDataCallback {
    private static final String TAG = "OnlineAnalysisActivity";
    private static final int REQUEST_CODE_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_CODE_AUDIO_PERMISSION = 1003;

    // UI组件
    private TextView tvStatus;

    // 分析相关
    private VideoClassifierHelper videoClassifier;
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

    // 帧缓存环形缓冲：最近 3 秒、间隔 250ms 采样的 12 帧
    private final ArrayDeque<Bitmap> frameWindow = new ArrayDeque<>();
    private static final int FRAME_WINDOW_SIZE = VideoClassifierHelper.NUM_FRAMES;

    // 视频推理步长：每 500ms 推理一次（与离线模式 VideoProcessActivity 保持一致）
    private long lastVideoInferenceTime = 0;
    private static final long VIDEO_INFERENCE_INTERVAL = 500;

    // [分组概率决策] 下游只关心“转/不转”，在 oral+sex 合并后的概率上做判定，
    // 避免转场期“总证据充分但单类不过线”被误判为 unclear（与离线模式一致）。
    // P(do) = P(oral)+P(sex) >= DO 阈值 -> "do"；P(plot/noise) >= 阈值 -> "Noise"；其余 -> unclear
    private static final float VIDEO_DO_PROB_THRESHOLD = 0.65f;
    private static final float VIDEO_PLOT_PROB_THRESHOLD = 0.60f;
    private static final float AUDIO_DO_PROB_THRESHOLD = 0.55f;
    private static final float AUDIO_NOISE_PROB_THRESHOLD = 0.60f;

    // 音频缓冲 - 使用统一的PcmCircularBuffer
    private PcmCircularBuffer pcmBuffer;
    private long audioStartTime = 0;
    private boolean hasAudioData = false;

    // 帧缓冲
    private Bitmap latestFrame = null;
    private final Object frameLock = new Object();

    // 时间窗口平滑相关变量
    // [延迟优化] 窗口从 10 收缩到 6：融合循环现为 500ms 一次，6 条记录约覆盖 3 秒。
    // 稳定性保护统一交给 updateBluetoothState 的状态机（与离线模式一致）。
    private static final int SMOOTH_WINDOW_SIZE = 6;
    // 动作在窗口内至少出现的次数（音视频各计一次），低于该次数不参与投票
    private static final int SMOOTH_MIN_COUNT = 3;
    // [快通道] 音视频同一 tick 一致且双双高置信度时，绕过投票直接输出
    private static final float FUSION_FASTPATH_VIDEO_CONF = 0.70f;
    private static final float FUSION_FASTPATH_AUDIO_CONF = 0.70f;
    // [启动仲裁] 实测音频模型准确度高于视频模型，视频不对启动拥有否决权，
    // 只在“视频剧情证据明显强于音频 do 证据”时短暂延迟启动（参数与离线模式一致）
    private static final float VIDEO_PLOT_CONFLICT_CONF = 0.80f;
    private static final float FUSION_CONFLICT_MARGIN = 0.15f;
    private static final int CONFLICT_MAX_HOLD_TICKS = 4;
    private static final float AUDIO_STRONG_START_CONF = 0.75f;
    private static final int AUDIO_ONLY_START_TICKS = 2;
    private int audioOnlyDoStreak = 0;  // 连续纯音频 do 的融合 tick 计数（仅主线程融合循环访问）
    private int conflictHoldTicks = 0;  // 连续音视频强冲突的 tick 计数（仅主线程融合循环访问）
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
    private int currentLevel = 1;                 // 已生效档位（0..10）
    private long currentLevelSinceMs = 0;         // 当前档位生效起始时间
    private Integer pendingLevel = null;          // 待确认档位
    private long pendingLevelSinceMs = 0;         // 待确认计时

    // NEW: 参数（可调）
    private static final int LEVEL_STABLE_MS   = 0;   // 新档位需稳定的最短时间
    private static final int LEVEL_MIN_DUR_MS  = 0;  // 生效档位的最小驻留

    // 最近一次“已发送”档位（用于判断是否需要重发同一动作以更新档位）
    private int lastSentLevel = 0;                  // NEW: 记录上次发送出去的档位

    // 主线程融合循环间隔
    // [延迟优化] 1000 -> 500：蓝牙实际发送频率仍由状态机节流（与离线模式一致）
    private static final long MAIN_FUSION_INTERVAL = 500;
    // 视频线程间隔（帧缓存间隔 250ms）
    private static final long VIDEO_LOOP_INTERVAL_MS = 250;
    //音频线程间隔
    private static final long AUDIO_LOOP_TICK_MS = 1000;

    // [12.30] Loudness 档位估计器（音频节律输出）
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

        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        if (topAppBar != null) {
            topAppBar.setNavigationOnClickListener(v -> finish());
        }

        // 初始化推理助手
        videoClassifier = new VideoClassifierHelper(this);
        audioHelper = new AudioInferenceHelper(this);

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

        // 请求屏幕录制权限
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
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

                tvStatus.setText(R.string.online_status_running);
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
                    videoHandler.postDelayed(this, VIDEO_LOOP_INTERVAL_MS);
                    return;
                }

                long t0 = System.currentTimeMillis();

                try {
                    Bitmap frame;
                    synchronized (frameLock) {
                        frame = latestFrame;
                        latestFrame = null;
                    }

                    if (frame != null && !frame.isRecycled()) {
                        cacheFrameAndInfer(frame);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[视频线程] 异常：", e);
                }

                long elapsed = System.currentTimeMillis() - t0;
                long nextDelay = Math.max(0, VIDEO_LOOP_INTERVAL_MS - elapsed);
                videoHandler.postDelayed(this, nextDelay);
            }
        };

        videoHandler.post(videoRunnable);
    }

    /**
     * 缓存一帧到滑动窗口；窗口满 12 帧且距上次推理 ≥ 1000ms 时执行一次推理。
     */
    private void cacheFrameAndInfer(Bitmap frame) {
        synchronized (videoLock) {
            // 缩放到模型输入尺寸后缓存
            Bitmap scaled = Bitmap.createScaledBitmap(
                    frame, VideoClassifierHelper.INPUT_SIZE, VideoClassifierHelper.INPUT_SIZE, true);
            frameWindow.add(scaled);
            while (frameWindow.size() > FRAME_WINDOW_SIZE) {
                frameWindow.poll();
            }

            // 滑动窗口未满 3 秒，暂不推理
            if (frameWindow.size() < FRAME_WINDOW_SIZE) {
                Log.d(TAG, "[视频线程] 帧窗口预热中 (" + frameWindow.size()
                        + "/" + FRAME_WINDOW_SIZE + ")");
                return;
            }

            // 推理步长 1000ms
            long now = System.currentTimeMillis();
            if (now - lastVideoInferenceTime < VIDEO_INFERENCE_INTERVAL) {
                return;
            }
            lastVideoInferenceTime = now;

            Bitmap[] frames = frameWindow.toArray(new Bitmap[0]);
            long tInferStart = System.currentTimeMillis();
            VideoClassifierHelper.Result result = videoClassifier.predict(frames);
            Log.d(TAG, "[计时] MobileNetV3Small 推理耗时: "
                    + (System.currentTimeMillis() - tInferStart) + " ms");

            applyVideoResult(result);
        }
    }

    /**
     * 将三分类结果映射为视频动作并写入共享结果（分组概率决策）。
     * P(do)=P(oral)+P(sex) >= 阈值 -> "do"（转）；P(plot) >= 阈值 -> "Noise"（不转）；
     * 其余 -> unclear -> ""（置信度 0）。置信度取分组后的概率。
     */
    private void applyVideoResult(VideoClassifierHelper.Result result) {
        String actionClass;
        float confidence;

        if (result == null || result.index < 0 || result.probs == null || result.probs.length < 3) {
            actionClass = "";
            confidence = 0f;
            Log.w(TAG, "[同步分析] [视频线程] 推理结果无效，判为 unclear");
        } else {
            float pPlot = result.probs[0];
            float pDo = result.probs[1] + result.probs[2]; // oral + sex 合并为“转”的总证据

            if (pDo >= VIDEO_DO_PROB_THRESHOLD) {
                actionClass = "do";      // 转
                confidence = pDo;
            } else if (pPlot >= VIDEO_PLOT_PROB_THRESHOLD) {
                actionClass = "Noise";   // 不转
                confidence = pPlot;
            } else {
                actionClass = "";
                confidence = 0f;
                Log.d(TAG, String.format("[同步分析] [视频线程] P(do)=%.2f / P(plot)=%.2f 均未过阈值，判为 unclear",
                        pDo, pPlot));
            }
        }

        // 原子更新结果
        latestVideoAction.set(actionClass);
        latestVideoConfidence.set(confidence);
        latestVideoTimestamp.set(System.currentTimeMillis());

        String displayText = actionClass.isEmpty()
                ? "V: unclear"
                : String.format("V: %s (%.2f)", actionClass, confidence);
        Log.d(TAG, "[同步分析] [视频线程] " + displayText
                + (result != null ? " probs=" + java.util.Arrays.toString(result.probs) : ""));
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
                long t0 = System.currentTimeMillis();

                long currentSystemTime = System.currentTimeMillis();

                if ((currentSystemTime - lastAudioInferenceTime) >= AUDIO_INFERENCE_INTERVAL) {
                    synchronized (audioLock) {
                        // 读取2秒音频数据 - 使用统一的接口
                        float[] audioSegment = pcmBuffer.getLatestData(32000);

                        // **************[MOD] 音频 Loudness → 档位分析（替代频率估计）**************
                        float[] last1s = pcmBuffer.getLatestData(8000); // 0.5秒, 16 kHz/s
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
                            long tAudioInferStart = System.currentTimeMillis();
                            AudioInferenceHelper.AudioInferenceResult result = audioHelper.predict(audioSegment);
                            long tAudioInferEnd = System.currentTimeMillis();
                            //Log.d(TAG, "[计时] 音频推理耗时: " + (tAudioInferEnd - tAudioInferStart) + " ms");

                            // 音频模型类别: 0=sex(做爱), 1=oral(口交), 2=noise(杂音)
                            // [分组概率决策] P(do)=P(sex)+P(oral) >= 阈值 -> "do"(转);
                            // P(noise) >= 阈值 -> "Noise"(不转); 其余 -> ""(unclear)
                            String action;
                            float confidence;
                            if (result.index < 0 || result.probs.length < 3) {
                                action = "";
                                confidence = 0f;
                                Log.w(TAG, "[同步分析] [音频线程] 推理结果无效，判为 unclear");
                            } else {
                                float pDo = result.probs[0] + result.probs[1]; // sex + oral 合并
                                float pNoise = result.probs[2];

                                if (pDo >= AUDIO_DO_PROB_THRESHOLD) {
                                    action = "do";      // 转
                                    confidence = pDo;
                                } else if (pNoise >= AUDIO_NOISE_PROB_THRESHOLD) {
                                    action = "Noise";   // 不转
                                    confidence = pNoise;
                                } else {
                                    action = "";
                                    confidence = 0f;
                                    Log.d(TAG, String.format("[同步分析] [音频线程] P(do)=%.2f / P(noise)=%.2f 均未过阈值，判为 unclear",
                                            pDo, pNoise));
                                }
                            }

                            latestAudioAction.set(action);
                            latestAudioConfidence.set(confidence);
                            latestAudioTimestamp.set(System.currentTimeMillis());

                            final String displayText = action.isEmpty()
                                    ? "A: unclear"
                                    : String.format("A: %s (%.2f)", action, confidence);
                            Log.d(TAG, "[同步分析] [音频线程] " + displayText);

                            lastAudioInferenceTime = currentSystemTime;
                        }
                    }
                }

                long elapsed = System.currentTimeMillis() - t0;
                long nextDelay = Math.max(0, AUDIO_LOOP_TICK_MS - elapsed);
                audioHandler.postDelayed(this, nextDelay);
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
                long t0 = System.currentTimeMillis();

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
                boolean audioFreqValid = latestAudioLoudValid.get();
                float audioFreq = (float) latestAudioLoudLevel.get();    // 用 level 作为 freq 输入
                float audioFreqConf = latestAudioLoudConf.get();
                long audioFreqTs = latestAudioLoudTsMs.get();

                long currentTime = System.currentTimeMillis();
                long videoAge = videoTime > 0 ? currentTime - videoTime : Long.MAX_VALUE;
                long audioAge = audioTime > 0 ? currentTime - audioTime : Long.MAX_VALUE;
                long audioFreqAge = audioFreqTs > 0 ? currentTime - audioFreqTs : Long.MAX_VALUE;

                final long MAX_AGE = 2000;

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

                // 注：oral/sex 的归并已在各分析线程内完成（分组概率决策只输出 "do"/"Noise"/""），
                // 此处无需再做 oral->do 归一化。

                String finalAction = smoothedFusion(videoAction, audioAction, videoConf, audioConf);

                // [启动仲裁] 音视频强冲突时短暂延迟启动（有上限）；音频足够自信则直接启动
                finalAction = applyDoStartGate(finalAction, videoAction, videoConf, audioAction, audioConf);
                // 临时采用音频节律作为最终节律
                // 将“最终节律计算”封装为独立方法，便于后续替换为音视频融合节律
                int finalFreq = computeFinalFreq(audioFreq, audioFreqConf);

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

                long elapsed = System.currentTimeMillis() - t0;
                long nextDelay = Math.max(0, MAIN_FUSION_INTERVAL - elapsed);
                mainHandler.postDelayed(this, nextDelay);
            }
        };

        mainHandler.post(fusionRunnable);
    }

    // audioFreq 实际承载的是 loudness level（float），这里做 0..8 钳制
    private static int clampLevelFromLoudness(final float levelLike) {
        if (Float.isNaN(levelLike)) return 0;
        int lv = Math.round(levelLike);
        if (lv < 0) lv = 0;
        if (lv > 8) lv = 8;
        return lv;
    }


    /**
     * 计算最终节律档位（0..8）。
     *
     * <p>策略：使用音频响度档位，并做“置信度阈值 + 方向性门控（涨档更严格，降档更宽松）”。</p>
     */
    private int computeFinalFreq(float audioFreq, float audioFreqConf) {
        int finalFreq = clampLevelFromLoudness(audioFreq); // audioFreq 实际是 loudness level(float)
        Log.d(TAG, "[音频响度] 得到音频档位: " + audioFreq + " 置信度: " + audioFreqConf + " 档位: " + finalFreq);

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

    private String smoothedFusion(String videoAction, String audioAction, float videoConf, float audioConf) {
        synchronized (historyLock) {
            actionHistory.add(new ActionRecord(videoAction, audioAction, videoConf, audioConf));

            while (actionHistory.size() > SMOOTH_WINDOW_SIZE) {
                actionHistory.poll();
            }

            // [快通道] 音视频本 tick 一致且双双高置信度 -> 绕过投票直接输出。
            // 停转方向仍有蓝牙状态机兜底（稳定 1000ms + 最短持续 2000ms）。
            if (!videoAction.isEmpty() && videoAction.equals(audioAction)
                    && videoConf >= FUSION_FASTPATH_VIDEO_CONF
                    && audioConf >= FUSION_FASTPATH_AUDIO_CONF) {
                Log.d(TAG, String.format("[平滑融合] 快通道命中: %s (V:%.2f, A:%.2f)",
                        videoAction, videoConf, audioConf));
                return videoAction;
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
                    score += record.videoConfidence * weight * 0.7f;
                    actionScores.put(videoKey, score);

                    int count = actionCounts.getOrDefault(videoKey, 0);
                    actionCounts.put(videoKey, count + 1);
                }

                if (!record.audioAction.isEmpty()) {
                    String audioKey = record.audioAction;
                    float score = actionScores.getOrDefault(audioKey, 0f);
                    score += record.audioConfidence * weight * 1.2f;
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

                if (count >= SMOOTH_MIN_COUNT && score > bestScore) {
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

    /**
     * [启动仲裁] 对融合结果为 "do" 的<b>启动</b>过程做冲突仲裁；仅影响“从非 do 状态启动 do”，
     * 已处于 do 状态时（维持阶段）音频可以单独维持，不做任何限制。
     * 规则与离线模式 VideoProcessActivity#applyDoStartGate 完全一致：
     * 1. 强冲突延迟：视频判剧情且置信度 ≥ 0.80 并比音频 do 置信度高出 0.15 以上 -> 本 tick 不启动；
     *    连续冲突超过 CONFLICT_MAX_HOLD_TICKS 后强制放行，避免视频误判永久锁死音频；
     * 2. 强音频直通：音频 do 置信度 ≥ AUDIO_STRONG_START_CONF -> 立即放行；
     * 3. 视频证据放行：平滑窗口内存在任一条视频 "do" -> 放行；
     * 4. 弱证据兜底：以上都不满足时需连续 AUDIO_ONLY_START_TICKS 个 tick 才启动；
     * 被拦下时返回 ""（本 tick 不驱动蓝牙状态机，维持现状）。
     */
    private String applyDoStartGate(String finalAction, String videoAction, float videoConf,
                                    String audioAction, float audioConf) {
        if (!"do".equals(finalAction)) {
            audioOnlyDoStreak = 0;
            conflictHoldTicks = 0;
            return finalAction;
        }
        // 维持阶段：当前蓝牙状态已是 do，音频单独维持即可
        if (isSexAction(currentBluetoothState)) {
            audioOnlyDoStreak = 0;
            conflictHoldTicks = 0;
            return finalAction;
        }

        // 音频本 tick 支持 do 时的置信度；音频不支持 do 则记为 0
        float audioDoConf = "do".equals(audioAction) ? audioConf : 0f;

        // 规则 1：强冲突延迟（有上限，绝不永久阻塞）
        boolean strongConflict = "Noise".equals(videoAction)
                && videoConf >= VIDEO_PLOT_CONFLICT_CONF
                && (videoConf - audioDoConf) >= FUSION_CONFLICT_MARGIN;
        if (strongConflict) {
            conflictHoldTicks++;
            if (conflictHoldTicks <= CONFLICT_MAX_HOLD_TICKS) {
                Log.d(TAG, String.format(
                        "[启动仲裁] 音视频强冲突(V:Noise %.2f vs A:do %.2f)，延迟启动 (%d/%d tick)",
                        videoConf, audioDoConf, conflictHoldTicks, CONFLICT_MAX_HOLD_TICKS));
                return "";
            }
            Log.d(TAG, String.format(
                    "[启动仲裁] 强冲突已持续 %d tick 超过上限，按音频优先放行启动", conflictHoldTicks));
        } else {
            conflictHoldTicks = 0;
        }

        // 规则 2：强音频直通
        if (audioDoConf >= AUDIO_STRONG_START_CONF) {
            audioOnlyDoStreak = 0;
            Log.d(TAG, String.format("[启动仲裁] 音频 do 置信度 %.2f 达直通门槛，放行启动", audioDoConf));
            return finalAction;
        }

        // 规则 3：窗口内有视频 do 证据 -> 放行
        synchronized (historyLock) {
            for (ActionRecord record : actionHistory) {
                if ("do".equals(record.videoAction)) {
                    audioOnlyDoStreak = 0;
                    return finalAction;
                }
            }
        }

        // 规则 4：弱证据兜底，需要连续多个 tick 支持
        audioOnlyDoStreak++;
        if (audioOnlyDoStreak >= AUDIO_ONLY_START_TICKS) {
            Log.d(TAG, "[启动仲裁] 弱证据 do 已连续 " + audioOnlyDoStreak + " tick，放行启动");
            return finalAction;
        }
        Log.d(TAG, String.format("[启动仲裁] 弱证据 do (%d/%d tick)，暂不启动",
                audioOnlyDoStreak, AUDIO_ONLY_START_TICKS));
        return "";
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

    // 蓝牙发送状态管理器
    private void updateBluetoothState(String newAction, int finalFreq /* 0..8 */) {
        long currentTime = System.currentTimeMillis();

        // =====================[ 档位确认：立即生效版 ]=====================
        // 只要 newAction 是 do/oral，就允许处理档位。
        // 不再依赖 currentStateSupportsSpeed()，避免从 Noise -> do 时 currentBluetoothState 还不是 do，导致档位不同步。
        boolean supportsLevel = isSexAction(newAction);

        if (supportsLevel) {
            int latestLevel = finalFreq;

            // 安全钳制到 0..8
            if (latestLevel < 0) latestLevel = 0;
            if (latestLevel > 8) latestLevel = 8;

            // 迟滞：只有跨过 currentLevel ± 1 才处理
            boolean worthHandling =
                    latestLevel >= upThreshold(currentLevel) ||
                            latestLevel <= downThreshold(currentLevel);

            if (worthHandling) {
                // 关键修复：
                // LEVEL_STABLE_MS = 0 时，第一次看到新档位就立即生效，不再等下一轮融合循环。
                if (LEVEL_STABLE_MS <= 0 &&
                        (currentTime - currentLevelSinceMs >= LEVEL_MIN_DUR_MS)) {

                    if (latestLevel != currentLevel) {
                        Log.d(TAG, String.format(
                                "[档位确认] 立即生效: %d -> %d",
                                currentLevel, latestLevel
                        ));
                    }

                    currentLevel = latestLevel;
                    currentLevelSinceMs = currentTime;
                    pendingLevel = null;
                    pendingLevelSinceMs = 0;

                } else {
                    // 如果以后你把 LEVEL_STABLE_MS 改成 >0，则走这个短稳确认逻辑
                    if (pendingLevel == null || !pendingLevel.equals(latestLevel)) {
                        pendingLevel = latestLevel;
                        pendingLevelSinceMs = currentTime;

                        Log.d(TAG, String.format(
                                "[档位确认] 新 pendingLevel=%d，开始等待稳定",
                                latestLevel
                        ));

                    } else {
                        long dwell = currentTime - pendingLevelSinceMs;
                        boolean stableOk = dwell >= LEVEL_STABLE_MS;
                        boolean minDurOk = currentTime - currentLevelSinceMs >= LEVEL_MIN_DUR_MS;

                        if (stableOk && minDurOk) {
                            if (pendingLevel != currentLevel) {
                                Log.d(TAG, String.format(
                                        "[档位确认] pending 生效: %d -> %d, dwell=%dms",
                                        currentLevel, pendingLevel, dwell
                                ));
                            }

                            currentLevel = pendingLevel;
                            currentLevelSinceMs = currentTime;
                            pendingLevel = null;
                            pendingLevelSinceMs = 0;
                        }
                    }
                }
            } else {
                // 没有跨过迟滞门槛，清空 pending，避免旧 pending 干扰后续判断
                pendingLevel = null;
                pendingLevelSinceMs = 0;
            }
        } else {
            // Noise 或其他不支持变速的动作，不处理档位
            pendingLevel = null;
            pendingLevelSinceMs = 0;
        }
        // =====================[ 档位确认结束 ]=====================


        // =====================[ 动作待确认 ]=====================
        if (!newAction.equals(pendingBluetoothState)) {
            pendingBluetoothState = newAction;
            pendingStateStartTime = currentTime;
            Log.d(TAG, String.format("[蓝牙] 检测到新动作: %s, 等待确认...", newAction));
        }

        // 动作稳定确认时间：
        // 默认 0ms；如果从 do/oral 切到 Noise，则延长到 1000ms，避免动作中突然停。
        int actionStableMs = 0;
        if (isSexAction(currentBluetoothState) && "Noise".equals(newAction)) {
            actionStableMs = 1000;
        }

        boolean actionStableOk =
                pendingBluetoothState.equals(newAction) &&
                        (currentTime - pendingStateStartTime) >= actionStableMs;

        if (!actionStableOk) {
            Log.d(TAG, String.format(
                    "[蓝牙] 动作尚未稳定: pending=%s, new=%s, 已稳定=%dms, 需要=%dms",
                    pendingBluetoothState,
                    newAction,
                    currentTime - pendingStateStartTime,
                    actionStableMs
            ));
            return;
        }


        // =====================[ 情况 A：动作发生变化 ]=====================
        if (!pendingBluetoothState.equals(currentBluetoothState)) {

            boolean minDurationOk =
                    currentBluetoothState.isEmpty() ||
                            (currentTime - currentStateStartTime) >= BLUETOOTH_MIN_DURATION;

            if (!minDurationOk) {
                long remainingTime = BLUETOOTH_MIN_DURATION - (currentTime - currentStateStartTime);
                Log.d(TAG, String.format(
                        "[蓝牙] 当前动作 %s 需继续保持 %dms，暂不切换到 %s",
                        currentBluetoothState,
                        remainingTime,
                        pendingBluetoothState
                ));
                return;
            }

            boolean gapOk = (currentTime - lastBluetoothSendTime) >= BLUETOOTH_SEND_INTERVAL;

            if (!gapOk) {
                Log.d(TAG, String.format(
                        "[蓝牙] 动作切换等待发送间隔: gap=%dms, need=%dms",
                        currentTime - lastBluetoothSendTime,
                        BLUETOOTH_SEND_INTERVAL
                ));
                return;
            }

            int levelToSend;
            if (isSexAction(pendingBluetoothState)) {
                // 动作切换到 do/oral 时，发送已确认的 currentLevel
                levelToSend = currentLevel;
            } else {
                // 切到 Noise 时，档位归 0
                levelToSend = 0;
            }

            Log.i(TAG, String.format(
                    "[蓝牙] [同步分析] ✅ 发送指令: action=%s, level=%d, 已稳定=%dms",
                    pendingBluetoothState,
                    levelToSend,
                    currentTime - pendingStateStartTime
            ));

            latestBluetoothAction.set(pendingBluetoothState);

            if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()) {
                BLEManager.globalManager.sendAction(pendingBluetoothState, levelToSend);
                Log.i(TAG, "[蓝牙] [同步分析] ✅ 已通过BLE发送指令(动作切换/含档位)");
                Log.d(TAG, "[音频节律] ✅ 发送节律：" + levelToSend);
            } else {
                Log.w(TAG, "[蓝牙] BLE未连接，仅更新UI显示");
            }

            currentBluetoothState = pendingBluetoothState;
            currentStateStartTime = currentTime;
            lastBluetoothSendTime = currentTime;

            // 关键同步：
            // 保证 currentLevel / lastSentLevel / 实际发送档位一致。
            if (isSexAction(currentBluetoothState)) {
                currentLevel = levelToSend;
                currentLevelSinceMs = currentTime;
                lastSentLevel = levelToSend;
            } else {
                lastSentLevel = 0;
            }

            return;
        }


        // =====================[ 情况 B：动作未变，但档位变化 ]=====================
        // 动作不变，例如 do -> do，但档位从 8 降到 4。
        // 这种情况需要重发同一个动作，携带新档位。
        boolean levelChanged = supportsLevel && (currentLevel != lastSentLevel);
        boolean gapOk = (currentTime - lastBluetoothSendTime) >= BLUETOOTH_SEND_INTERVAL;

        Log.d(TAG, String.format(
                "[档位发送判断] action=%s, supportsLevel=%s, currentLevel=%d, lastSentLevel=%d, levelChanged=%s, gapOk=%s, gap=%dms",
                currentBluetoothState,
                supportsLevel,
                currentLevel,
                lastSentLevel,
                levelChanged,
                gapOk,
                currentTime - lastBluetoothSendTime
        ));

        if (levelChanged && gapOk) {
            int levelToSend = currentLevel;

            Log.i(TAG, String.format(
                    "[蓝牙] 同动作更新档位：%s -> level=%d",
                    currentBluetoothState,
                    levelToSend
            ));

            if (BLEManager.globalManager != null && BLEManager.globalManager.isConnected()) {
                BLEManager.globalManager.sendAction(currentBluetoothState, levelToSend);
                Log.i(TAG, "[蓝牙] [同步分析] ✅ 已通过BLE发送指令(同动作/更新档位)");
                Log.d(TAG, "[音频节律] ✅ 发送节律：" + levelToSend);
            } else {
                Log.w(TAG, "[蓝牙] BLE未连接，无法更新档位（同动作）");
            }

            lastSentLevel = levelToSend;
            lastBluetoothSendTime = currentTime;
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

    private void resetAnalysisBuffers() {
        // 清空帧缓存和 pcm 缓冲，避免读取到“前一个时间点”的旧数据
        synchronized (videoLock) {
            frameWindow.clear();
            lastVideoInferenceTime = 0;
        }

        synchronized (audioLock) {
            pcmBuffer.reset();
        }

        // 清空动作历史记录
        synchronized (historyLock) {
            actionHistory.clear();
        }
        // [启动仲裁] 重置启动计数
        audioOnlyDoStreak = 0;
        conflictHoldTicks = 0;

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

        // [12.20] reset loudness estimator
        loudnessEstimator.reset();
        // [12.30] 清空 loudness 输出
        latestAudioLoudLevel.set(0);
        latestAudioLoudConf.set(0f);
        latestAudioLoudTsMs.set(0L);
        latestAudioLoudValid.set(false);

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

        if (videoClassifier != null) {
            videoClassifier.close();
        }

        if (audioHelper != null) {
            audioHelper.close();
        }
        super.onDestroy();
    }
}