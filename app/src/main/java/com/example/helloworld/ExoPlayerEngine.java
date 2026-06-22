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
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.chromium.net.CronetEngine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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

    /** Cronet 引擎/执行器进程内复用：构建开销大，且单例可避免多实例的生命周期问题。 */
    private static volatile CronetEngine sCronetEngine;
    private static volatile boolean sCronetInitTried;
    private static volatile Executor sCronetExecutor;

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

        // ===== HTTP 数据源 =====
        // 先把要发的 UA / 请求头都算出来，再决定底层用 Cronet 还是默认实现。
        String ua = null;
        Map<String, String> reqProps = new HashMap<>();
        if (httpHeaders != null && !httpHeaders.isEmpty()) {
            reqProps.putAll(httpHeaders);
            ua = reqProps.remove("User-Agent");

            // 把请求伪装成"浏览器里页面发起的跨站拉流"：origin 形式的 Referer + Origin 头。
            // hls.js 用 XHR/fetch 拉流，浏览器会自动带 Origin，且跨站默认只发 origin 形式 Referer。
            String origin = originOf(reqProps.get("Referer"));
            if (origin != null) {
                reqProps.put("Referer", origin + "/");
                putIfAbsent(reqProps, "Origin", origin);
            }
            // 贴近 hls.js 的跨站 fetch：补一组标准浏览器头（别手动设 Accept-Encoding，交给底层透明解压）。
            putIfAbsent(reqProps, "Accept", "*/*");
            putIfAbsent(reqProps, "Accept-Language", "en-US,en;q=0.9");
            putIfAbsent(reqProps, "Sec-Fetch-Dest", "empty");
            putIfAbsent(reqProps, "Sec-Fetch-Mode", "cors");
            putIfAbsent(reqProps, "Sec-Fetch-Site", "cross-site");
        }

        // 默认实现（HttpURLConnection）。既是 Cronet 不可用时的兜底，
        // 也作为 Cronet 处理不了某个请求时的 fallbackFactory。
        DefaultHttpDataSource.Factory defaultHttpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000);

        // 优先 Cronet：它就是 Chromium 的网络栈，TLS/HTTP 指纹与 WebView 一致，
        // 能过 missav 的 CDN（surrit.com）那层 Cloudflare 机器人校验。引擎进程内复用。
        HttpDataSource.Factory httpFactory;
        CronetEngine cronetEngine = getCronetEngine(context);
        if (cronetEngine != null) {
            CronetDataSource.Factory cronetFactory =
                    new CronetDataSource.Factory(cronetEngine, getCronetExecutor())
                            .setConnectionTimeoutMs(15_000)
                            .setReadTimeoutMs(15_000)
                            .setHandleSetCookieRequests(true)   // 处理 Cloudflare 重定向里 Set-Cookie 的 __cf_bm
                            .setFallbackFactory(defaultHttpFactory);
            if (ua != null && !ua.isEmpty()) cronetFactory.setUserAgent(ua);
            if (!reqProps.isEmpty()) cronetFactory.setDefaultRequestProperties(reqProps);
            httpFactory = cronetFactory;
            Log.d(TAG, "网页视频数据源: Cronet（Chromium 网络栈）, 头=" + reqProps.keySet());
        } else {
            if (ua != null && !ua.isEmpty()) defaultHttpFactory.setUserAgent(ua);
            if (!reqProps.isEmpty()) defaultHttpFactory.setDefaultRequestProperties(reqProps);
            httpFactory = defaultHttpFactory;
            Log.w(TAG, "网页视频数据源: DefaultHttpDataSource（Cronet 不可用，CDN 可能 403）, 头="
                    + reqProps.keySet());
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
     * 进程内单例 CronetEngine（内嵌 Chromium 网络栈）。构建失败返回 null，调用方回退默认实现。
     * 用 applicationContext 避免静态字段泄漏 Activity。只尝试构建一次，失败后不反复重试。
     */
    private static CronetEngine getCronetEngine(Context context) {
        if (sCronetEngine == null && !sCronetInitTried) {
            synchronized (ExoPlayerEngine.class) {
                if (!sCronetInitTried) {
                    sCronetInitTried = true;
                    try {
                        sCronetEngine = new CronetEngine.Builder(context.getApplicationContext())
                                .enableHttp2(true)
                                .build();
                        Log.d(TAG, "Cronet 引擎已构建: " + sCronetEngine.getVersionString());
                    } catch (Throwable t) {
                        Log.w(TAG, "Cronet 引擎构建失败，网页视频将回退 DefaultHttpDataSource", t);
                    }
                }
            }
        }
        return sCronetEngine;
    }

    /** Cronet 回调用的单线程执行器（进程内复用）。 */
    private static synchronized Executor getCronetExecutor() {
        if (sCronetExecutor == null) {
            sCronetExecutor = Executors.newSingleThreadExecutor();
        }
        return sCronetExecutor;
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
