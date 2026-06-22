package com.example.helloworld;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * "网页视频"模式专用播放器引擎。
 *
 * <ul>
 *   <li>HTTP 数据源带 Referer / User-Agent / Cookie，绕过 CDN 校验</li>
 *   <li>{@link DefaultMediaSourceFactory} 自动识别 mp4 / HLS m3u8（依赖 media3-exoplayer-hls）</li>
 *   <li>把 {@link PcmCaptureAudioProcessor} 串到默认 AudioProcessor 链尾，
 *       PCM 一边送扬声器一边送进 {@link PcmCircularBuffer} 给我们的模型用</li>
 * </ul>
 *
 * <p>离线模式和在线模式完全不会触碰这个类。</p>
 */
@OptIn(markerClass = UnstableApi.class)
public class ExoPlayerEngine {
    private static final String TAG = "ExoPlayerEngine";

    private ExoPlayer player;
    private final PcmCaptureAudioProcessor pcmCapture;
    /**
     * 主线程每 50ms 抓一次 player.getCurrentPosition() 缓存到这里。
     * 所有非主线程（音频渲染线程 / 视频分析线程 / 音频分析线程）只读这个 volatile 字段，
     * 避免 ExoPlayer 的"必须主线程访问"约束。
     */
    private volatile long lastKnownPositionMs = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable positionPoller;

    public ExoPlayerEngine(Context context,
                           PcmCircularBuffer pcmBuffer,
                           Map<String, String> httpHeaders) {
        // PCM 抓取处理器读缓存好的 position，不直接碰 ExoPlayer
        this.pcmCapture = new PcmCaptureAudioProcessor(pcmBuffer, () -> lastKnownPositionMs);

        // 自定义 RenderersFactory，注入我们的 AudioProcessor
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
            @Override
            protected AudioSink buildAudioSink(Context ctx,
                                               boolean enableFloatOutput,
                                               boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(ctx)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setAudioProcessorChain(
                                new DefaultAudioSink.DefaultAudioProcessorChain(pcmCapture))
                        .build();
            }
        };

        // HTTP 数据源工厂，带嗅探阶段拿到的 headers
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000);

        if (httpHeaders != null && !httpHeaders.isEmpty()) {
            Map<String, String> reqProps = new HashMap<>(httpHeaders);
            String ua = reqProps.remove("User-Agent");
            if (ua != null && !ua.isEmpty()) {
                httpFactory.setUserAgent(ua);
            }

            // 关键：把请求伪装成"浏览器里页面发起的跨站拉流"。
            // 像 missav 的 CDN（surrit.com）这类防盗链升级后会校验 Referer/Origin：
            //   - 浏览器跨站请求按默认 referrer-policy 只发 origin（https://host/），不发完整路径
            //   - hls.js 用 XHR/fetch 拉流，浏览器会自动带 Origin 头
            // 之前我们只发了"完整路径的 Referer"且没有 Origin，CDN 收紧校验后直接 403。
            // 这里据 Referer 反推 origin：Referer 归一化成 origin 形式，并补一个 Origin 头。
            // 对不校验这俩的站（如 Pornhub）是无害的——浏览器本来也是这么发的。
            String origin = originOf(reqProps.get("Referer"));
            if (origin != null) {
                reqProps.put("Referer", origin + "/");
                putIfAbsent(reqProps, "Origin", origin);
            }

            // 再贴近 hls.js 的跨站 fetch：补一组标准浏览器头，抬高 Cloudflare 的"像浏览器"评分。
            // 注意别手动设 Accept-Encoding——交给底层处理 gzip 透明解压。
            putIfAbsent(reqProps, "Accept", "*/*");
            putIfAbsent(reqProps, "Accept-Language", "en-US,en;q=0.9");
            putIfAbsent(reqProps, "Sec-Fetch-Dest", "empty");
            putIfAbsent(reqProps, "Sec-Fetch-Mode", "cors");
            putIfAbsent(reqProps, "Sec-Fetch-Site", "cross-site");

            if (!reqProps.isEmpty()) {
                httpFactory.setDefaultRequestProperties(reqProps);
            }
            Log.d(TAG, "网页视频 HTTP 头: " + reqProps.keySet()
                    + (origin != null ? " (origin=" + origin + ")" : ""));
        }

        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory((DataSource.Factory) httpFactory);

        this.player = new ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

        // 让 ExoPlayer 申请音频焦点，跟系统其它播放器友好共存
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(), /* handleAudioFocus= */ true);

        // 启动 position 轮询：主线程读，volatile 字段对外
        positionPoller = new Runnable() {
            @Override
            public void run() {
                ExoPlayer p = player;
                if (p != null) {
                    try {
                        long pos = p.getCurrentPosition();
                        if (pos >= 0) lastKnownPositionMs = pos;
                    } catch (IllegalStateException ignored) {
                        // 播放器还没准备好，跳过
                    }
                    mainHandler.postDelayed(this, 50);
                }
            }
        };
        mainHandler.post(positionPoller);

        Log.d(TAG, "ExoPlayerEngine 初始化完成");
    }

    public ExoPlayer getPlayer() {
        return player;
    }

    /** 线程安全的当前播放位置（ms）。任何线程都能调。 */
    public long getCachedPositionMs() {
        return lastKnownPositionMs;
    }

    public void setUri(String url) {
        if (player == null) return;
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
    }

    public void release() {
        if (positionPoller != null) {
            mainHandler.removeCallbacks(positionPoller);
            positionPoller = null;
        }
        if (player != null) {
            try {
                player.release();
            } catch (Exception e) {
                Log.w(TAG, "release", e);
            }
            player = null;
        }
    }

    private static void putIfAbsent(Map<String, String> map, String key, String value) {
        if (!map.containsKey(key)) map.put(key, value);
    }

    /**
     * 从一个完整 URL 反推出 origin（scheme://host[:port]）。
     * 用来把 Referer 归一化成浏览器跨站请求的形式，并据此补 Origin 头。
     * 默认端口（http:80 / https:443）不写出。解析失败返回 null。
     */
    private static String originOf(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null || host == null) return null;
            StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
            int port = u.getPort();
            boolean defaultPort = (port < 0)
                    || ("https".equals(scheme) && port == 443)
                    || ("http".equals(scheme) && port == 80);
            if (!defaultPort) sb.append(':').append(port);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
