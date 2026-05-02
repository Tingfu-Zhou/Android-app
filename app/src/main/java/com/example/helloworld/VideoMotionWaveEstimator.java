package com.example.helloworld;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VideoMotionWaveEstimator
 *
 * 视频 ROI 运动波形估计器，用于替代旧版基于 COCO17 关键点位移的
 * {@link VideoRhythmEstimator}。
 *
 * 设计原则：
 *  1. COCO17 / 人体 bbox 只用来定 ROI（优先 pelvis / lower-body 区域），不直接作为节律信号。
 *  2. ROI 中心和尺寸做 EMA 稳定，抑制关键点抖动带来的 ROI 抖动。
 *  3. ROI crop 后转灰度并 resize 到固定尺寸（默认 64x64）。
 *  4. ROI 内 8x8 网格做局部 SAD 块匹配，得到每块 (dx_i, dy_i)；过滤低纹理块、MAD 异常块。
 *  5. 主运动方向 (ux, uy)：跨帧 EMA 累积块向量的 2 阶矩矩阵 M=[[Sxx,Sxy],[Sxy,Syy]]，
 *     PCA 取主特征向量；做符号稳定，保证跨帧连续。这样躺/站/侧位等任意方向都适用。
 *  6. signedMotion = median(dx_i*ux + dy_i*uy)（中位数 + MAD 滤波）；
 *     对 signedMotion 做泄漏积分得到 position(t)。
 *  7. 最近 ~6s position(t) 滑窗 → 去趋势 → 自相关主频估计（默认 0.95–1.85 Hz）。
 *  8. 输出 {@link VideoWaveResult}（freqHz / confidence / position01 / motionEnergy / mainDir* 等）。
 *
 * 接口设计为「pushFrame + getLatestResult」，便于以后迁移到 iOS（CGImage / CVPixelBuffer）
 * 与 Windows（Bitmap / GDI / Direct2D）：仅需替换前端 Bitmap 读取部分。
 *
 * 线程安全：所有 public 方法都加 synchronized；最新结果通过 AtomicReference 暴露。
 */
public class VideoMotionWaveEstimator {

    private static final String TAG = "VMWaveEst";

    // ===================== 可调参数 =====================
    public static class Params {
        // ---- ROI ----
        public float roiAlpha          = 0.30f;   // ROI center/size EMA 系数（0.25–0.35）
        public float roiWidthFactor    = 1.6f;    // 在 hip 宽度基础上的横向放大
        public float roiHeightFactor   = 1.6f;    // 在 hip-knee 距离基础上的纵向放大
        public float roiVerticalShift  = 0.20f;   // ROI 中心相对 hip 中心向下偏移（按 ROI 高度比例）
        public float roiAspectMin      = 0.50f;   // h/w 下限
        public float roiAspectMax      = 2.40f;   // h/w 上限
        public float roiMinFracW       = 0.10f;   // ROI 最小宽度（占帧宽比例）
        public float roiMinFracH       = 0.12f;   // ROI 最小高度（占帧高比例）
        public float roiMaxFracW       = 0.85f;   // ROI 最大宽度（占帧宽比例）
        public float roiMaxFracH       = 0.85f;   // ROI 最大高度（占帧高比例）
        public float kpConfTh          = 0.30f;   // 普通关键点置信度阈值（用于膝/肩等辅助点）
        public float pelvisKpConfTh    = 0.30f;   // 骨盆（左右 hip）置信度门控；任一低于该值则本轮直接输出 NaN

        // ---- Gray ROI ----
        public int grayW = 64;
        public int grayH = 64;

        // ---- Block matching ----
        public int   gridX         = 8;       // 横向块数
        public int   gridY         = 8;       // 纵向块数
        public int   searchRadius  = 4;       // 搜索半径(px)，应 < 边缘 padding
        public float texVarTh      = 25.0f;   // 块方差低于该值视为低纹理，跳过
        public float outlierMadK   = 2.5f;    // MAD 异常块剔除系数

        // ---- Main motion direction (PCA on running 2-nd moment matrix) ----
        public boolean enableMotionDirection = true;   // false → 退化为旧的纯 dy 投影
        public float   motionDirAlpha        = 0.10f;  // 2 阶矩 EMA 系数，~1s 时间常数
        public float   motionDirMinEnergy    = 0.50f;  // 帧 sqrt(Sxx+Syy) 低于该值则不更新方向

        // ---- Leaky integrator & window ----
        public float leak              = 0.95f;   // position 泄漏系数
        public int   windowSize        = 60;      // 6s @ ~10Hz
        public int   minWarmSamples    = 30;      // 至少 ~3s 才允许出高置信结果
        public int   estimateEveryNFrames = 3;    // 每 ~300ms 估计一次

        // ---- Frequency band ----
        public float minFreqHz = 0.95f;
        public float maxFreqHz = 1.85f;

        // ---- Confidence ----
        public float minMotionEnergyFull = 0.50f; // motionEnergy ≥ 该值时 motionEnergyFactor=1
        public float minMotionEnergyZero = 0.05f; // motionEnergy ≤ 该值时 motionEnergyFactor=0
        public float minValidBlockRatio  = 0.30f; // 有效块比例下限
        public float maxFreqJumpHz       = 0.40f; // 频率瞬时跳变上限（超出 → 稳定性下降）

        // ---- Position 归一化 ----
        public int   posNormWindow   = 20;        // envelope 窗口（最近 N 个样本）
        public float pos01SmoothAlpha = 0.40f;    // position01 一阶平滑

        // ---- 其它 ----
        public int   maxConsecutiveLostBeforeReset = 4; // 连续多少帧无人 → reset
        public boolean verbose = false;
    }

    private final Params P;

    // ===================== 输出 =====================
    private final AtomicReference<VideoWaveResult> latest =
            new AtomicReference<>(VideoWaveResult.empty());

    // ===================== 内部状态 =====================
    // ROI EMA
    private boolean roiInitialized = false;
    private float roiCx, roiCy, roiW, roiH;            // 像素

    // 灰度 ROI 缓冲区（前一帧 / 当前帧），长度 = grayW * grayH
    private byte[] prevGray = null;
    private byte[] currGray = null;

    // ROI 像素缓冲区（动态扩容）
    private int[]  pixelBuf = null;

    // 用于 estimate 的 position 滑窗（环形）
    private final float[] posWindow;
    private final long[]  posTsWindow;
    private int posCount = 0;
    private int posWriteIdx = 0;

    // 泄漏积分得到的位置
    private float rawPosition = 0f;

    // 估计调度
    private int framesSinceEst = 0;
    private float lastFreqHz = Float.NaN;
    private float lastPos01 = 0.5f;

    // 跟踪/丢失计数
    private boolean roiLost = true;     // 当前帧的 ROI 是否新建立 / 不可用于块匹配
    private int consecutiveLost = 0;    // 连续无关键点的帧数

    // 主运动方向：跨帧 EMA 累积 2 阶矩 [[Mxx,Mxy],[Mxy,Myy]]，PCA 取主特征向量。
    // mainDirX/Y 为单位向量；默认 (0,1) 即竖直向下，等价于旧的 dy-only 行为。
    private float   dirMxx = 0f, dirMxy = 0f, dirMyy = 0f;
    private boolean dirInitialized = false;
    private float   mainDirX = 0f, mainDirY = 1f;

    // ===================== 构造 / Reset =====================
    public VideoMotionWaveEstimator() { this(new Params()); }

    public VideoMotionWaveEstimator(Params p) {
        this.P = p;
        this.posWindow   = new float[Math.max(8, p.windowSize)];
        this.posTsWindow = new long[Math.max(8, p.windowSize)];
        Arrays.fill(this.posWindow, 0f);
        Arrays.fill(this.posTsWindow, 0L);
    }

    /** 重置（Seek / 镜头切换 / 长时间无人 时调用）。 */
    public synchronized void reset() {
        roiInitialized = false;
        roiCx = roiCy = roiW = roiH = 0f;
        prevGray = null;
        currGray = null;
        Arrays.fill(posWindow, 0f);
        Arrays.fill(posTsWindow, 0L);
        posCount = 0;
        posWriteIdx = 0;
        rawPosition = 0f;
        framesSinceEst = 0;
        lastFreqHz = Float.NaN;
        lastPos01 = 0.5f;
        roiLost = true;
        consecutiveLost = 0;
        // 主方向状态：清空累积矩阵，方向退化到 (0,1)（竖直）
        dirMxx = dirMxy = dirMyy = 0f;
        dirInitialized = false;
        mainDirX = 0f;
        mainDirY = 1f;
        latest.set(VideoWaveResult.empty());
        if (P.verbose) Log.d(TAG, "[reset]");
    }

    /** 获取最新结果（线程安全）。 */
    public VideoWaveResult getLatestResult() {
        return latest.get();
    }

    // ===================== 主入口 =====================
    /**
     * 推入一帧。
     *
     * @param timestampMs   该帧的时间戳（毫秒）
     * @param frame         源 Bitmap（建议 ARGB_8888；可 null = 当前帧无图像）
     * @param keypointsNorm COCO17 关键点 (17x3)，x,y ∈ [0,1]，conf ∈ [0,1]；可 null = 本帧未检测到人
     */
    public synchronized void pushFrame(long timestampMs, Bitmap frame, float[][] keypointsNorm) {
        // ---- (a) keypoints == null：判定为镜头切换 / 无人体 ----
        if (keypointsNorm == null) {
            consecutiveLost++;
            if (consecutiveLost >= P.maxConsecutiveLostBeforeReset) {
                if (P.verbose) Log.d(TAG, "[reset] no keypoints x" + consecutiveLost);
                reset();
            } else {
                roiLost = true;
                decayConfidence(timestampMs, "no-keypoints");
            }
            return;
        }
        if (frame == null || frame.isRecycled() || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            consecutiveLost++;
            roiLost = true;
            decayConfidence(timestampMs, "no-frame");
            return;
        }

        // ---- (a.1) Pelvis 置信度门控 ----
        // 关键点格式 [x, y, conf]；左右 hip 任一 conf 低于阈值，本轮直接输出 NaN，
        // 不更新 ROI/position 窗口。长时间命中 -> reset()，与无人体路径一致。
        final int LHIP = 11, RHIP = 12;
        if (keypointsNorm.length < 17
                || keypointsNorm[LHIP] == null || keypointsNorm[LHIP].length < 3
                || keypointsNorm[RHIP] == null || keypointsNorm[RHIP].length < 3
                || keypointsNorm[LHIP][2] < P.pelvisKpConfTh
                || keypointsNorm[RHIP][2] < P.pelvisKpConfTh) {
            float cL = (keypointsNorm.length > LHIP && keypointsNorm[LHIP] != null
                        && keypointsNorm[LHIP].length >= 3) ? keypointsNorm[LHIP][2] : 0f;
            float cR = (keypointsNorm.length > RHIP && keypointsNorm[RHIP] != null
                        && keypointsNorm[RHIP].length >= 3) ? keypointsNorm[RHIP][2] : 0f;
            consecutiveLost++;
            roiLost = true;
            if (consecutiveLost >= P.maxConsecutiveLostBeforeReset) {
                if (P.verbose) Log.d(TAG, "[reset] low-pelvis-conf x" + consecutiveLost);
                reset();
            } else {
                publishNaN(timestampMs, String.format(java.util.Locale.US,
                        "low-pelvis-conf Lhip=%.2f Rhip=%.2f th=%.2f",
                        cL, cR, P.pelvisKpConfTh));
            }
            return;
        }
        consecutiveLost = 0;

        int frameW = frame.getWidth();
        int frameH = frame.getHeight();

        // ---- (b) 计算目标 ROI ----
        RoiBox newRoi = computeRoi(keypointsNorm, frameW, frameH);
        if (newRoi == null) {
            roiLost = true;
            decayConfidence(timestampMs, "no-roi");
            return;
        }

        // ---- (c) ROI EMA 平滑 ----
        if (!roiInitialized) {
            roiCx = newRoi.cx; roiCy = newRoi.cy;
            roiW  = newRoi.w;  roiH  = newRoi.h;
            roiInitialized = true;
            roiLost = true; // 第一帧没有 prev，需要等下一帧才能 block-match
        } else {
            float a = P.roiAlpha;
            roiCx = a * newRoi.cx + (1f - a) * roiCx;
            roiCy = a * newRoi.cy + (1f - a) * roiCy;
            roiW  = a * newRoi.w  + (1f - a) * roiW;
            roiH  = a * newRoi.h  + (1f - a) * roiH;
        }

        // ---- (d) Crop & grayscale ----
        int rx = Math.max(0, Math.round(roiCx - roiW * 0.5f));
        int ry = Math.max(0, Math.round(roiCy - roiH * 0.5f));
        int rw = Math.min(frameW - rx, Math.round(roiW));
        int rh = Math.min(frameH - ry, Math.round(roiH));
        if (rw < 16 || rh < 16) {
            roiLost = true;
            decayConfidence(timestampMs, "roi-too-small");
            return;
        }

        try {
            cropAndGrayscale(frame, rx, ry, rw, rh);
        } catch (Throwable t) {
            Log.w(TAG, "cropAndGrayscale failed: " + t.getMessage());
            roiLost = true;
            decayConfidence(timestampMs, "crop-fail");
            return;
        }

        // ---- (e) Block matching ----
        float signedMotion = 0f;
        float motionEnergy = 0f;
        int validBlocks = 0;
        int totalBlocks = P.gridX * P.gridY;
        boolean haveMatch = (prevGray != null) && !roiLost;

        if (haveMatch) {
            BlockResult br = blockMatch();
            signedMotion = br.signedMotion;   // 沿主方向投影后的 median
            motionEnergy = br.energy;         // 沿主方向投影后的 mean(|m_i|)
            validBlocks  = br.validCount;
        }
        roiLost = false;

        // 交换 prev/curr 灰度缓冲（避免分配）
        byte[] tmp = prevGray;
        prevGray = currGray;
        currGray = tmp;

        // ---- (f) 泄漏积分 ----
        int minBlocks = Math.max(4, Math.round(totalBlocks * P.minValidBlockRatio));
        if (haveMatch && validBlocks >= minBlocks) {
            rawPosition = P.leak * rawPosition + signedMotion;
        } else {
            // 块匹配数据不足：仅做衰减，不引入噪声
            rawPosition = P.leak * rawPosition;
        }

        // ---- (g) 推入滑窗 ----
        posWindow[posWriteIdx]   = rawPosition;
        posTsWindow[posWriteIdx] = timestampMs;
        posWriteIdx = (posWriteIdx + 1) % posWindow.length;
        if (posCount < posWindow.length) posCount++;

        framesSinceEst++;

        // ---- (h) 周期性触发主频估计 ----
        if (posCount >= P.minWarmSamples && framesSinceEst >= P.estimateEveryNFrames) {
            framesSinceEst = 0;
            VideoWaveResult result = doEstimate(timestampMs, rx, ry, rw, rh,
                    signedMotion, motionEnergy, validBlocks, totalBlocks);
            latest.set(result);
            if (P.verbose) Log.d(TAG, "[est] " + result.debugInfo);
        } else {
            // 未到估计节拍：仅刷新最新调试字段，不算 valid
            VideoWaveResult prev = latest.get();
            VideoWaveResult upd = (prev != null) ? prev.copy() : VideoWaveResult.empty();
            upd.timestampMs = timestampMs;
            upd.rawPosition = rawPosition;
            upd.motionEnergy = motionEnergy;
            upd.roiX = rx; upd.roiY = ry; upd.roiW = rw; upd.roiH = rh;
            upd.mainDirX = mainDirX;
            upd.mainDirY = mainDirY;
            upd.locked = roiInitialized && consecutiveLost == 0 && posCount >= P.minWarmSamples;
            upd.debugInfo = String.format(java.util.Locale.US,
                    "[warm] roi=(%d,%d,%d,%d) blk=%d/%d sM=%.2f rawP=%.2f mE=%.3f " +
                    "dir=(%.2f,%.2f,%.0f°) posN=%d",
                    rx, ry, rw, rh, validBlocks, totalBlocks, signedMotion, rawPosition, motionEnergy,
                    mainDirX, mainDirY,
                    (float) (Math.atan2(mainDirY, mainDirX) * 180.0 / Math.PI),
                    posCount);
            latest.set(upd);
        }
    }

    // ===================== 内部：估计核心 =====================
    private VideoWaveResult doEstimate(long timestampMs,
                                       int rx, int ry, int rw, int rh,
                                       float signedMotion, float motionEnergy,
                                       int validBlocks, int totalBlocks) {
        // 1) 取连续序列
        float[] seq = snapshotPosWindow();

        // 2) 估计采样周期
        float dt = estimateAvgDt();

        // 3) 主频 + 周期性
        FreqEst fe = estimateByAutocorr(seq, dt);

        // 4) 计算各置信度因子
        float periodicity = clamp01(fe.periodicity);

        float meFactor;
        if (motionEnergy <= P.minMotionEnergyZero) meFactor = 0f;
        else if (motionEnergy >= P.minMotionEnergyFull) meFactor = 1f;
        else meFactor = (motionEnergy - P.minMotionEnergyZero)
                       / Math.max(1e-6f, P.minMotionEnergyFull - P.minMotionEnergyZero);

        float blockRatio = totalBlocks > 0 ? (float) validBlocks / totalBlocks : 0f;
        float blockFactor;
        if (blockRatio <= P.minValidBlockRatio) blockFactor = 0f;
        else if (blockRatio >= 0.7f) blockFactor = 1f;
        else blockFactor = (blockRatio - P.minValidBlockRatio)
                          / Math.max(1e-6f, 0.7f - P.minValidBlockRatio);

        float roiLockFactor;
        if (consecutiveLost == 0 && posCount >= P.minWarmSamples) roiLockFactor = 1f;
        else if (posCount < P.minWarmSamples) {
            roiLockFactor = (float) posCount / Math.max(1, P.minWarmSamples);
        } else roiLockFactor = 0.5f;

        float freqStability;
        if (Float.isNaN(lastFreqHz) || Float.isNaN(fe.freqHz)) freqStability = 0.7f;
        else {
            float jump = Math.abs(fe.freqHz - lastFreqHz);
            freqStability = clamp01(1f - jump / Math.max(1e-6f, P.maxFreqJumpHz));
            // 有部分包容：即使跳变较大也保留 0.3
            freqStability = Math.max(0.3f, freqStability);
        }

        float confidence = clamp01(periodicity * meFactor * blockFactor
                                   * roiLockFactor * freqStability);
        if (Float.isNaN(fe.freqHz)) confidence = 0f;

        // 5) position01 envelope 归一化
        float pos01 = computePosition01(motionEnergy);

        // 6) 写结果
        VideoWaveResult r = new VideoWaveResult();
        r.timestampMs  = timestampMs;
        r.freqHz       = fe.freqHz;
        r.confidence   = confidence;
        r.position01   = pos01;
        r.rawPosition  = rawPosition;
        r.motionEnergy = motionEnergy;
        r.periodicity  = periodicity;
        r.roiX = rx; r.roiY = ry; r.roiW = rw; r.roiH = rh;
        r.mainDirX = mainDirX;
        r.mainDirY = mainDirY;
        r.locked = (consecutiveLost == 0 && posCount >= P.minWarmSamples);
        r.valid  = !Float.isNaN(fe.freqHz) && posCount >= P.minWarmSamples;
        float dirAngleDeg = (float) (Math.atan2(mainDirY, mainDirX) * 180.0 / Math.PI);
        r.debugInfo = String.format(java.util.Locale.US,
                "roi=(%d,%d,%d,%d) blk=%d/%d (%.0f%%) sM=%.2f rawP=%.2f " +
                "f=%.2fHz per=%.2f mE=%.3f conf=%.2f " +
                "dir=(%.2f,%.2f,%.0f°) " +
                "[me=%.2f blk=%.2f lock=%.2f stab=%.2f] dt=%.0fms",
                rx, ry, rw, rh, validBlocks, totalBlocks, blockRatio * 100f,
                signedMotion, rawPosition, fe.freqHz, periodicity, motionEnergy, confidence,
                mainDirX, mainDirY, dirAngleDeg,
                meFactor, blockFactor, roiLockFactor, freqStability, dt * 1000f);

        if (!Float.isNaN(fe.freqHz)) lastFreqHz = fe.freqHz;
        return r;
    }

    /** 衰减置信度（保留 freq/pos01 等便于 UI 平滑）。 */
    private void decayConfidence(long timestampMs, String reason) {
        VideoWaveResult prev = latest.get();
        if (prev == null) return;
        VideoWaveResult upd = prev.copy();
        upd.timestampMs = timestampMs;
        upd.confidence  = upd.confidence * 0.6f;
        upd.locked = false;
        upd.debugInfo = "[decay] reason=" + reason + " prev=" + prev.debugInfo;
        latest.set(upd);
    }

    /** 显式把本轮结果置为 NaN（调用方：pelvis 置信度门控未通过等情形）。 */
    private void publishNaN(long timestampMs, String reason) {
        VideoWaveResult r = new VideoWaveResult();
        r.timestampMs  = timestampMs;
        r.freqHz       = Float.NaN;
        r.confidence   = 0f;
        r.position01   = lastPos01;
        r.rawPosition  = rawPosition;
        r.motionEnergy = 0f;
        r.periodicity  = 0f;
        r.roiX = r.roiY = r.roiW = r.roiH = 0;
        r.mainDirX = mainDirX;
        r.mainDirY = mainDirY;
        r.locked = false;
        r.valid  = false;
        r.debugInfo = "[NaN] " + reason;
        latest.set(r);
    }

    // ===================== ROI 计算 =====================
    private static class RoiBox { float cx, cy, w, h; }

    private RoiBox computeRoi(float[][] kp, int frameW, int frameH) {
        // COCO 关键点索引
        final int LSHO=5, RSHO=6, LHIP=11, RHIP=12, LKNEE=13, RKNEE=14;
        if (kp == null || kp.length < 17) return null;

        // 调用方（pushFrame）已经做过 pelvis 置信度门控，此处只保留防御性检查。
        // 不再走 bbox fallback：若骨盆点不可用，本轮已直接 publishNaN 返回。
        if (kp[LHIP][2] < P.pelvisKpConfTh || kp[RHIP][2] < P.pelvisKpConfTh) return null;

        boolean lkneeOK = kp[LKNEE][2] >= P.kpConfTh;
        boolean rkneeOK = kp[RKNEE][2] >= P.kpConfTh;
        boolean lshoOK  = kp[LSHO][2]  >= P.kpConfTh;
        boolean rshoOK  = kp[RSHO][2]  >= P.kpConfTh;

        // ---- 主路径（也是唯一路径）：基于 hip / lower-body ----
        float hipCx = 0.5f * (kp[LHIP][0] + kp[RHIP][0]);
        float hipCy = 0.5f * (kp[LHIP][1] + kp[RHIP][1]);
        float hipDx = kp[RHIP][0] - kp[LHIP][0];
        float hipDy = kp[RHIP][1] - kp[LHIP][1];
        float hipDist = (float) Math.sqrt(hipDx * hipDx + hipDy * hipDy);

        float refSize = hipDist;
        // 兜底（两 hip 像素距太近时）：改用肩宽估计参考尺度
        if (refSize < 0.04f && lshoOK && rshoOK) {
            float sdx = kp[RSHO][0] - kp[LSHO][0];
            float sdy = kp[RSHO][1] - kp[LSHO][1];
            refSize = (float) Math.sqrt(sdx * sdx + sdy * sdy);
        }
        if (refSize < 0.03f) refSize = 0.03f;

        // 估计纵向尺度：优先 hip-knee 距离
        float vert = refSize * 1.6f;
        if (lkneeOK || rkneeOK) {
            float kneeY = 0f; int n = 0;
            if (lkneeOK) { kneeY += kp[LKNEE][1]; n++; }
            if (rkneeOK) { kneeY += kp[RKNEE][1]; n++; }
            kneeY /= n;
            float v = Math.abs(kneeY - hipCy);
            if (v > vert * 0.5f) vert = v * 1.4f;
        }

        float cx = hipCx;
        float cy = hipCy + P.roiVerticalShift * vert;
        float w  = refSize * P.roiWidthFactor;
        float h  = vert    * P.roiHeightFactor;

        // 归一化 → 像素
        cx *= frameW; cy *= frameH;
        w  *= frameW; h  *= frameH;

        // 强制最小 / 最大尺寸
        float minW = P.roiMinFracW * frameW;
        float minH = P.roiMinFracH * frameH;
        float maxW = P.roiMaxFracW * frameW;
        float maxH = P.roiMaxFracH * frameH;
        if (w < minW) w = minW;
        if (h < minH) h = minH;
        if (w > maxW) w = maxW;
        if (h > maxH) h = maxH;

        // 强制 aspect 范围
        if (w > 1f) {
            if (h / w < P.roiAspectMin) h = w * P.roiAspectMin;
            if (h / w > P.roiAspectMax) h = w * P.roiAspectMax;
        }

        // 把中心 clamp 到帧内
        if (cx - w * 0.5f < 0) cx = w * 0.5f;
        if (cy - h * 0.5f < 0) cy = h * 0.5f;
        if (cx + w * 0.5f > frameW) cx = frameW - w * 0.5f;
        if (cy + h * 0.5f > frameH) cy = frameH - h * 0.5f;

        if (w < minW * 0.5f || h < minH * 0.5f) return null;

        RoiBox box = new RoiBox();
        box.cx = cx; box.cy = cy; box.w = w; box.h = h;
        return box;
    }

    // ===================== Crop & Grayscale =====================
    /**
     * 从 frame 中读取 (rx,ry,rw,rh) 区域，转为灰度并 nearest-neighbor 缩放到 grayW x grayH，
     * 写入 currGray。
     */
    private void cropAndGrayscale(Bitmap frame, int rx, int ry, int rw, int rh) {
        int gw = P.grayW, gh = P.grayH;
        int graySize = gw * gh;
        if (currGray == null || currGray.length != graySize) currGray = new byte[graySize];
        if (prevGray != null && prevGray.length != graySize) prevGray = null;

        int need = rw * rh;
        if (pixelBuf == null || pixelBuf.length < need) pixelBuf = new int[need];

        // ARGB 8888
        frame.getPixels(pixelBuf, 0, rw, rx, ry, rw, rh);

        // nearest-neighbor 下采样 + 灰度
        for (int gy = 0; gy < gh; gy++) {
            int srcY = (gy * rh) / gh;
            int srcRowOff = srcY * rw;
            int dstRowOff = gy * gw;
            for (int gx = 0; gx < gw; gx++) {
                int srcX = (gx * rw) / gw;
                int p = pixelBuf[srcRowOff + srcX];
                int r = (p >> 16) & 0xFF;
                int g = (p >>  8) & 0xFF;
                int b = (p      ) & 0xFF;
                // 近似亮度：Y = 0.30R + 0.59G + 0.11B
                int y = (r * 30 + g * 59 + b * 11) / 100;
                currGray[dstRowOff + gx] = (byte) y;
            }
        }
    }

    // ===================== Block Matching =====================
    private static class BlockResult {
        /** 中位数 + MAD 滤波后的「沿主方向」位移（单位：ROI 灰度图像素 / 帧）。 */
        float signedMotion;
        /** 平均 |projected motion|（沿主方向投影后的绝对值），衡量本帧 ROI 内运动强度。 */
        float energy;
        int   validCount;
    }

    private final float[] _scratchDx  = new float[256];
    private final float[] _scratchDy  = new float[256];
    private final float[] _scratchAbs = new float[256];

    private BlockResult blockMatch() {
        BlockResult r = new BlockResult();
        final int W = P.grayW, H = P.grayH;
        final int gx = P.gridX, gy = P.gridY;
        final int bw = W / gx, bh = H / gy;
        final int R  = P.searchRadius;
        final int total = gx * gy;
        if (total > _scratchDy.length) {
            // 极少触发：参数被改大时安全兜底
            return r;
        }

        float[] dxs = _scratchDx;
        float[] dys = _scratchDy;
        boolean[] valid = new boolean[total];
        int validCount = 0;

        // 本帧 2 阶矩（不去均值）：用于跨帧 EMA + PCA 估计主方向
        float Sxx = 0f, Sxy = 0f, Syy = 0f;

        // 左/上/右/下都需要至少 R 像素 padding
        for (int by = 0; by < gy; by++) {
            int y0 = by * bh;
            if (y0 < R || y0 + bh + R > H) continue;
            for (int bxIdx = 0; bxIdx < gx; bxIdx++) {
                int x0 = bxIdx * bw;
                if (x0 < R || x0 + bw + R > W) continue;
                int idx = by * gx + bxIdx;

                // 1) 块方差（在 prev 上算，便于跳过低纹理）
                int sum = 0, sum2 = 0;
                for (int yy = 0; yy < bh; yy++) {
                    int rowOff = (y0 + yy) * W + x0;
                    for (int xx = 0; xx < bw; xx++) {
                        int v = prevGray[rowOff + xx] & 0xFF;
                        sum  += v;
                        sum2 += v * v;
                    }
                }
                int npx = bw * bh;
                float mean = sum / (float) npx;
                float var  = sum2 / (float) npx - mean * mean;
                if (var < P.texVarTh) continue;

                // 2) ±R 邻域 SAD 搜索（参考块来自 prev，搜索 curr）
                int bestSAD = Integer.MAX_VALUE;
                int bestDx = 0;
                int bestDy = 0;
                for (int dy = -R; dy <= R; dy++) {
                    for (int dx = -R; dx <= R; dx++) {
                        int sad = 0;
                        for (int yy = 0; yy < bh; yy++) {
                            int prevOff = (y0 + yy) * W + x0;
                            int currOff = (y0 + yy + dy) * W + x0 + dx;
                            for (int xx = 0; xx < bw; xx++) {
                                int diff = (prevGray[prevOff + xx] & 0xFF)
                                         - (currGray[currOff + xx] & 0xFF);
                                sad += diff < 0 ? -diff : diff;
                            }
                            if (sad >= bestSAD) break; // 早停
                        }
                        if (sad < bestSAD) {
                            bestSAD = sad;
                            bestDx  = dx;
                            bestDy  = dy;
                        }
                    }
                }

                dxs[idx] = bestDx;
                dys[idx] = bestDy;
                valid[idx] = true;
                validCount++;
                Sxx += (float) bestDx * bestDx;
                Sxy += (float) bestDx * bestDy;
                Syy += (float) bestDy * bestDy;
            }
        }

        if (validCount == 0) {
            r.signedMotion = 0f;
            r.energy       = 0f;
            r.validCount   = 0;
            return r;
        }

        // 3) 更新主方向 (mainDirX, mainDirY)
        updateMainDirection(Sxx, Sxy, Syy, validCount);

        // 4) 把每个有效块的运动向量投影到主方向 → m_i
        float[] m = new float[validCount];
        int j = 0;
        float energySum = 0f;
        for (int i = 0; i < total; i++) {
            if (valid[i]) {
                float proj = dxs[i] * mainDirX + dys[i] * mainDirY;
                m[j++] = proj;
                energySum += proj < 0 ? -proj : proj;
            }
        }

        // 5) 中位数
        Arrays.sort(m);
        float median = m[validCount / 2];

        // 6) MAD 异常剔除后再算一次中位数
        for (int i = 0; i < validCount; i++) _scratchAbs[i] = Math.abs(m[i] - median);
        Arrays.sort(_scratchAbs, 0, validCount);
        float mad = _scratchAbs[validCount / 2];
        float thresh = Math.max(1.0f, P.outlierMadK * mad);

        int fc = 0;
        float[] filtered = new float[validCount];
        for (int i = 0; i < validCount; i++) {
            if (Math.abs(m[i] - median) <= thresh) {
                filtered[fc++] = m[i];
            }
        }
        if (fc == 0) {
            r.signedMotion = median;
        } else {
            Arrays.sort(filtered, 0, fc);
            r.signedMotion = filtered[fc / 2];
        }
        r.energy = energySum / Math.max(1, validCount);
        r.validCount = validCount;
        return r;
    }

    // ===================== Main motion direction =====================
    /**
     * 用本帧 2 阶矩矩阵 S = [[Sxx,Sxy],[Sxy,Syy]] 跨帧 EMA 累积到 dirM***，
     * 再 PCA 取主特征向量作为主运动方向。
     *
     * 关键设计：
     *  - 不做均值居中：因为我们要的是「motion 总能量最大的轴」，PCA 在原始 2 阶矩
     *    上做即可；如果做均值居中，单帧内（块都朝同方向走时）会把 mean 减掉，
     *    剩下的反而是噪声方向。
     *  - 符号稳定：特征向量天生有 ± 号歧义。每次新算出 (ux,uy) 后，与上次
     *    (mainDirX, mainDirY) 做点积，<0 就翻号 → 跨帧连续。
     *  - frameEnergy 太低（人体几乎不动）时不更新方向，避免噪声拉偏轴。
     *  - enableMotionDirection=false 时方向永远是 (0,1)，等价旧的 dy-only 行为。
     */
    private void updateMainDirection(float Sxx, float Sxy, float Syy, int validBlocks) {
        if (!P.enableMotionDirection) {
            mainDirX = 0f;
            mainDirY = 1f;
            return;
        }
        float frameEnergy = (float) Math.sqrt(Sxx + Syy);
        if (validBlocks < Math.max(4, Math.round(P.gridX * P.gridY * P.minValidBlockRatio))
                || frameEnergy < P.motionDirMinEnergy) {
            // 不更新，保持上次方向
            return;
        }

        if (!dirInitialized) {
            dirMxx = Sxx; dirMxy = Sxy; dirMyy = Syy;
            dirInitialized = true;
        } else {
            float a = P.motionDirAlpha;
            dirMxx = (1f - a) * dirMxx + a * Sxx;
            dirMxy = (1f - a) * dirMxy + a * Sxy;
            dirMyy = (1f - a) * dirMyy + a * Syy;
        }

        float[] ev = principalEigenvector2x2(dirMxx, dirMxy, dirMyy);
        float newUx = ev[0], newUy = ev[1];
        // 符号稳定
        if (newUx * mainDirX + newUy * mainDirY < 0f) {
            newUx = -newUx;
            newUy = -newUy;
        }
        mainDirX = newUx;
        mainDirY = newUy;
    }

    /** 2x2 对称矩阵 [[a,b],[b,d]] 的主特征向量（单位化）。返回 {ex, ey}。 */
    private static float[] principalEigenvector2x2(float a, float b, float d) {
        // 闭式特征值: λ = (a+d)/2 ± sqrt(((a-d)/2)^2 + b^2)
        float half = (a - d) * 0.5f;
        float disc = (float) Math.sqrt(half * half + b * b);
        float lambda1 = (a + d) * 0.5f + disc;   // 较大特征值

        float ex, ey;
        if (Math.abs(b) < 1e-9f) {
            // 矩阵已对角化：主轴对齐到 a,d 较大的那个轴
            if (a >= d) { ex = 1f; ey = 0f; }
            else        { ex = 0f; ey = 1f; }
        } else {
            // (a - λ) ex + b ey = 0  →  ex : ey = b : (λ - a)
            ex = b;
            ey = lambda1 - a;
        }
        float norm = (float) Math.sqrt(ex * ex + ey * ey);
        if (norm < 1e-9f) {
            return new float[] { 0f, 1f };
        }
        return new float[] { ex / norm, ey / norm };
    }

    // ===================== Frequency Estimation =====================
    private static class FreqEst {
        float freqHz;
        float periodicity;
    }

    private FreqEst estimateByAutocorr(float[] seq, float dt) {
        FreqEst e = new FreqEst();
        e.freqHz = Float.NaN;
        e.periodicity = 0f;
        int n = seq.length;
        if (n < 16 || dt <= 0f) return e;

        // 1) 去趋势（线性回归）
        float[] x = detrend(seq);

        // 2) 总能量
        float r0 = 0f;
        for (float v : x) r0 += v * v;
        if (r0 < 1e-9f) return e;

        // 3) 自相关搜索
        int kMin = Math.max(1, (int) Math.floor(1.0f / (P.maxFreqHz * dt)));
        int kMax = Math.min(n - 2, (int) Math.ceil(1.0f / (P.minFreqHz * dt)));
        if (kMax <= kMin) return e;

        // 同时计算 kMin-1 .. kMax+1 的 R[k]，便于抛物线插值
        int lo = Math.max(1, kMin - 1);
        int hi = Math.min(n - 1, kMax + 1);
        float[] R = new float[hi + 2];
        for (int k = lo; k <= hi; k++) {
            float s = 0f;
            int m = n - k;
            for (int i = 0; i < m; i++) s += x[i] * x[i + k];
            // 不归一化，方便后续 R[k]/R[0] 算 periodicity
            R[k] = s;
        }

        int bestK = -1;
        float bestVal = -Float.MAX_VALUE;
        for (int k = kMin; k <= kMax; k++) {
            if (R[k] > bestVal) { bestVal = R[k]; bestK = k; }
        }
        if (bestK < 0) return e;

        // 4) 抛物线插值
        float refK = bestK;
        if (bestK - 1 >= lo && bestK + 1 <= hi) {
            float a = R[bestK - 1], b = R[bestK], c = R[bestK + 1];
            float denom = (a - 2f * b + c);
            if (Math.abs(denom) > 1e-9f) {
                float delta = 0.5f * (a - c) / denom;
                if (delta > 0.5f) delta = 0.5f;
                if (delta < -0.5f) delta = -0.5f;
                refK = bestK + delta;
            }
        }

        float tau = refK * dt;
        if (tau < 1e-6f) return e;
        e.freqHz = 1.0f / tau;
        e.periodicity = clamp01(bestVal / r0);
        // 限制频率范围（防止抛物线插值越界）
        if (e.freqHz < P.minFreqHz * 0.9f || e.freqHz > P.maxFreqHz * 1.1f) {
            e.freqHz = Float.NaN;
            e.periodicity = 0f;
        }
        return e;
    }

    private float[] detrend(float[] seq) {
        int n = seq.length;
        float[] out = new float[n];
        // 线性回归: y = a + b*i
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX  += i;
            sumY  += seq[i];
            sumXY += (double) i * seq[i];
            sumX2 += (double) i * i;
        }
        double meanX = sumX / n, meanY = sumY / n;
        double varX = sumX2 / n - meanX * meanX;
        double slope = 0;
        if (varX > 1e-12) slope = (sumXY / n - meanX * meanY) / varX;
        double intercept = meanY - slope * meanX;
        for (int i = 0; i < n; i++) {
            out[i] = (float) (seq[i] - (slope * i + intercept));
        }
        return out;
    }

    // ===================== Position 归一化 =====================
    private float computePosition01(float motionEnergy) {
        // 运动能量极低时不要剧烈变化
        if (motionEnergy < P.minMotionEnergyZero) {
            lastPos01 = 0.95f * lastPos01 + 0.05f * 0.5f;
            return lastPos01;
        }
        int n = Math.min(P.posNormWindow, posCount);
        if (n < 5) return lastPos01;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        int W = posWindow.length;
        for (int i = 0; i < n; i++) {
            int idx = (posWriteIdx - 1 - i + W) % W;
            float v = posWindow[idx];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float range = max - min;
        if (range < 1e-3f) return lastPos01;
        float v01 = (rawPosition - min) / range;
        if (v01 < 0f) v01 = 0f;
        if (v01 > 1f) v01 = 1f;
        lastPos01 = (1f - P.pos01SmoothAlpha) * lastPos01 + P.pos01SmoothAlpha * v01;
        return lastPos01;
    }

    // ===================== 工具 =====================
    private float[] snapshotPosWindow() {
        int n = posCount;
        int W = posWindow.length;
        float[] out = new float[n];
        int start = (posWriteIdx - n + W) % W;
        for (int i = 0; i < n; i++) out[i] = posWindow[(start + i) % W];
        return out;
    }

    private float estimateAvgDt() {
        if (posCount < 2) return 0.1f;
        int W = posWindow.length;
        long t0 = posTsWindow[(posWriteIdx - posCount + W) % W];
        long t1 = posTsWindow[(posWriteIdx - 1 + W) % W];
        if (t1 <= t0) return 0.1f;
        float dt = (t1 - t0) / 1000f / Math.max(1, posCount - 1);
        if (dt < 0.02f || dt > 0.5f) dt = 0.1f;
        return dt;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
