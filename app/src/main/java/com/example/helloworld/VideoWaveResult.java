package com.example.helloworld;

/**
 * VideoWaveResult
 *
 * 由 {@link VideoMotionWaveEstimator} 输出的「视频运动波形」估计结果。
 *
 * 字段为纯 Java 基本类型，避免引用 android.graphics.* 等平台类型，
 * 便于后续整体迁移到 iOS / Windows（仅替换前端 Bitmap 读取部分即可）。
 *
 * 字段说明：
 *  - timestampMs   : 该结果对应的帧时间戳（视频线程中通常使用 System.currentTimeMillis()）。
 *  - freqHz        : 估计的主频（Hz）。
 *  - confidence    : 综合置信度 0..1，融合 periodicity / motionEnergy / 有效块比例 / 频率稳定性。
 *  - position01    : 当前归一化位置 0..1（基于 rawPosition 的滑窗 envelope 归一化）。
 *  - rawPosition   : 泄漏积分后的原始位置（无单位，仅用于内部归一化或调试）。
 *  - motionEnergy  : 当前帧 ROI 内运动强度（block dy 的平均绝对值，越大越剧烈）。
 *  - periodicity   : 自相关主峰的相对强度，0..1。
 *  - roiX/Y/W/H    : ROI 在原始视频帧像素坐标系中的位置与大小（int 像素）。
 *  - locked        : 当前是否处于稳定锁定状态（连续帧 ROI 都成功且窗口已预热）。
 *  - valid         : 当前结果是否可用（false 表示尚未预热完成或被 reset）。
 *  - debugInfo     : 调试用日志字符串，包含 ROI / blocks / signedDy / freq 等关键中间量。
 */
public class VideoWaveResult {
    public long  timestampMs;
    public float freqHz;
    public float confidence;
    public float position01;
    public float rawPosition;
    public float motionEnergy;
    public float periodicity;
    public int   roiX;
    public int   roiY;
    public int   roiW;
    public int   roiH;
    public boolean locked;
    public boolean valid;
    public String debugInfo;

    /** 返回一个未预热的空结果。 */
    public static VideoWaveResult empty() {
        VideoWaveResult r = new VideoWaveResult();
        r.timestampMs = 0L;
        r.freqHz      = Float.NaN;
        r.confidence  = 0f;
        r.position01  = 0.5f;
        r.rawPosition = 0f;
        r.motionEnergy = 0f;
        r.periodicity  = 0f;
        r.roiX = r.roiY = r.roiW = r.roiH = 0;
        r.locked = false;
        r.valid  = false;
        r.debugInfo = "empty";
        return r;
    }

    /** 复制一份（确保 AtomicReference 不被外部 mutate 影响）。 */
    public VideoWaveResult copy() {
        VideoWaveResult r = new VideoWaveResult();
        r.timestampMs = this.timestampMs;
        r.freqHz      = this.freqHz;
        r.confidence  = this.confidence;
        r.position01  = this.position01;
        r.rawPosition = this.rawPosition;
        r.motionEnergy = this.motionEnergy;
        r.periodicity  = this.periodicity;
        r.roiX = this.roiX; r.roiY = this.roiY; r.roiW = this.roiW; r.roiH = this.roiH;
        r.locked = this.locked;
        r.valid  = this.valid;
        r.debugInfo = this.debugInfo;
        return r;
    }
}
