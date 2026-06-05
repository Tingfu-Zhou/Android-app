package com.example.helloworld;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

/**
 * 把 ExoPlayer 渲染出来的 PCM 抄一份送进 {@link PcmCircularBuffer}，
 * 给我们的 YAMNet / 响度档位估计 / VAD 使用；原数据原封不动透传到下游，
 * 不影响实际的音频播放。
 *
 * <p>插入位置：{@code DefaultAudioSink} 的默认 AudioProcessor 链条之后，
 * 所以这里收到的已经是 16-bit PCM（不需要再处理浮点 / 24bit 等格式）。
 * 声道数 / 采样率不固定，由设备和源格式决定，我们自己做"下混到 mono +
 * 转 float"，{@code PcmCircularBuffer.write(...)} 会再帮我们重采样到 16kHz。</p>
 *
 * <p>每个 chunk 的写入时间戳取 {@code positionProviderMs.get()}，
 * 也就是调 ExoPlayer.getCurrentPosition() —— 由于 audio sink 缓冲只有几十毫秒，
 * 这个近似对 1 秒分析窗口完全够用。</p>
 */
@OptIn(markerClass = UnstableApi.class)
public class PcmCaptureAudioProcessor extends BaseAudioProcessor {
    private static final String TAG = "PcmCaptureAP";

    private final PcmCircularBuffer pcmBuffer;
    private final Supplier<Long> positionProviderMs;

    public PcmCaptureAudioProcessor(PcmCircularBuffer pcmBuffer,
                                    Supplier<Long> positionProviderMs) {
        this.pcmBuffer = pcmBuffer;
        this.positionProviderMs = positionProviderMs;
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat inputAudioFormat)
            throws AudioProcessor.UnhandledAudioFormatException {
        // 仅支持 16-bit PCM；其它格式应由上游 ResamplingAudioProcessor 已经统一处理过
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new AudioProcessor.UnhandledAudioFormatException(inputAudioFormat);
        }
        Log.d(TAG, "onConfigure: sr=" + inputAudioFormat.sampleRate
                + " ch=" + inputAudioFormat.channelCount
                + " encoding=" + inputAudioFormat.encoding);
        // 透传：输出格式 = 输入格式
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int byteCount = inputBuffer.remaining();
        if (byteCount == 0) return;

        final int channels = inputAudioFormat.channelCount;
        final int sampleRate = inputAudioFormat.sampleRate;
        if (channels <= 0 || sampleRate <= 0) {
            // 没配置好，原样直通
            int startPos = inputBuffer.position();
            ByteBuffer output = replaceOutputBuffer(byteCount);
            output.put(inputBuffer);
            output.flip();
            // BaseAudioProcessor 约定：queueInput 返回后 inputBuffer.position 必须推进到 limit
            // output.put 已经做到了这一点，这里不需要再 set position
            return;
        }

        // 保留入参原状态，最后再透传
        final int startPos = inputBuffer.position();
        final ByteOrder oldOrder = inputBuffer.order();
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN);

        final int totalShorts = byteCount / 2;
        final int monoSamples = totalShorts / channels;
        if (monoSamples > 0) {
            float[] mono = new float[monoSamples];
            for (int i = 0; i < monoSamples; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += inputBuffer.getShort();
                }
                mono[i] = (sum / (float) channels) / 32768f;
            }

            long ts = 0L;
            try { ts = positionProviderMs.get(); } catch (Exception ignored) { }
            // 离线模式 buffer：内部会从 sampleRate 重采样到 16kHz
            pcmBuffer.write(mono, ts, sampleRate);
        }

        // 还原 input 状态，做透传
        inputBuffer.order(oldOrder);
        inputBuffer.position(startPos);

        ByteBuffer output = replaceOutputBuffer(byteCount);
        output.put(inputBuffer);
        output.flip();
    }
}
