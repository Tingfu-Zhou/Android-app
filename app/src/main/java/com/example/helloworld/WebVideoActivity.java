package com.example.helloworld;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网页视频模式：
 *  - 内置 WebView 加载用户输入的视频网页
 *  - 嗅探页面里的视频流（.mp4 / .m3u8 / .m3u）
 *  - 嗅到后把 URL（含必要 headers）交给 VideoProcessActivity 走离线分析链路
 *  - 通过"蜜罐 WebView"模式吸收广告 window.open() 弹窗
 *  - 拦截跨站跳转，避免主窗口被甩到广告落地页
 *  - 拦截常见广告域名，清理页面
 */
public class WebVideoActivity extends AppCompatActivity {
    private static final String TAG = "WebVideoActivity";

    /** Intent extras 用来把嗅探到的 headers 传给 VideoProcessActivity */
    public static final String EXTRA_IS_WEB_VIDEO = "IS_WEB_VIDEO";
    public static final String EXTRA_HTTP_REFERER = "HTTP_REFERER";
    public static final String EXTRA_HTTP_UA = "HTTP_USER_AGENT";
    public static final String EXTRA_HTTP_COOKIE = "HTTP_COOKIE";

    /** 常见广告/分析域名黑名单。命中即在 shouldInterceptRequest 里返回空响应。 */
    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
            "ero-labs.top",
            "trafficstars.com",
            "tsyndicate.com",
            "juicyads.com",
            "exoclick.com",
            "exosrv.com",
            "adsterra.net",
            "adsterra.com",
            "popads.net",
            "revcontent.com",
            "doubleclick.net",
            "googlesyndication.com"
    ));

    /** 桌面 Chrome UA — 多数视频站会按 UA 区分 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private static final long JS_POLL_INTERVAL_MS = 2000;

    private WebView webView;
    private TextInputEditText etUrl;
    private MaterialButton btnOpen;
    private MaterialButton btnAnalyze;
    private TextView tvStatus;

    private final AtomicReference<String> detectedVideoUrl = new AtomicReference<>(null);
    private final AtomicReference<String> detectedReferer = new AtomicReference<>(null);
    private final AtomicReference<String> detectedUa = new AtomicReference<>(null);

    private Handler jsPollHandler;
    private Runnable jsPollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_video);

        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        etUrl = findViewById(R.id.etUrl);
        btnOpen = findViewById(R.id.btnOpen);
        btnAnalyze = findViewById(R.id.btnAnalyze);
        tvStatus = findViewById(R.id.tvStatus);

        configureWebView();

        btnOpen.setOnClickListener(v -> openCurrentUrl());

        etUrl.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                openCurrentUrl();
                return true;
            }
            return false;
        });

        btnAnalyze.setOnClickListener(v -> launchAnalysis());

        jsPollHandler = new Handler(Looper.getMainLooper());
        jsPollRunnable = new Runnable() {
            @Override
            public void run() {
                pollDomForVideoSrc();
                jsPollHandler.postDelayed(this, JS_POLL_INTERVAL_MS);
            }
        };
        jsPollHandler.postDelayed(jsPollRunnable, JS_POLL_INTERVAL_MS);
    }

    private void openCurrentUrl() {
        CharSequence raw = etUrl.getText();
        if (raw == null) return;
        String url = raw.toString().trim();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, R.string.web_video_status_idle, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        resetDetection();
        tvStatus.setText(R.string.web_video_status_loading);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
        }
        webView.loadUrl(url);
    }

    private void resetDetection() {
        detectedVideoUrl.set(null);
        detectedReferer.set(null);
        detectedUa.set(null);
        btnAnalyze.setVisibility(View.GONE);
    }

    private void launchAnalysis() {
        String videoUrl = detectedVideoUrl.get();
        if (videoUrl == null) return;

        // 把网页视频暂停掉，避免 WebView 和 VideoProcessActivity 同时拉流
        webView.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(function(v){try{v.pause();}catch(e){}});",
                null);

        Intent intent = new Intent(this, VideoProcessActivity.class);
        intent.setData(Uri.parse(videoUrl));
        intent.putExtra(EXTRA_IS_WEB_VIDEO, true);

        String referer = detectedReferer.get();
        if (referer != null) intent.putExtra(EXTRA_HTTP_REFERER, referer);

        String ua = detectedUa.get();
        if (ua == null) ua = DESKTOP_UA;
        intent.putExtra(EXTRA_HTTP_UA, ua);

        try {
            String cookie = CookieManager.getInstance().getCookie(videoUrl);
            if (!TextUtils.isEmpty(cookie)) {
                intent.putExtra(EXTRA_HTTP_COOKIE, cookie);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 Cookie 失败", e);
        }

        Log.i(TAG, "启动 VideoProcessActivity, url=" + videoUrl
                + ", referer=" + detectedReferer.get()
                + ", ua=" + ua);
        Toast.makeText(this, "正在打开分析页…", Toast.LENGTH_SHORT).show();
        startActivity(intent);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(DESKTOP_UA);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // 多窗口蜜罐
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // 每次进入新页面都重置嗅探状态
                resetDetection();
                tvStatus.setText(R.string.web_video_status_loading);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri reqUri = request.getUrl();
                if (reqUri == null) return false;
                String reqHost = reqUri.getHost();
                String pageUrl = view.getUrl();
                if (reqHost == null || pageUrl == null) return false;
                Uri pageUri = Uri.parse(pageUrl);
                String pageHost = pageUri.getHost();
                if (pageHost == null) return false;

                String pageRoot = extractRootDomain(pageHost);
                String reqRoot = extractRootDomain(reqHost);
                if (!reqRoot.equalsIgnoreCase(pageRoot)) {
                    Log.d(TAG, "拦截跨站跳转: " + reqUri + " (page=" + pageHost + ")");
                    return true;
                }
                return false;
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri reqUri = request.getUrl();
                if (reqUri == null) return null;
                String reqHost = reqUri.getHost();
                String reqUrl = reqUri.toString();

                // 1) 广告域名直接屏蔽
                if (reqHost != null && isAdHost(reqHost)) {
                    Log.d(TAG, "拦截广告域名: " + reqUri);
                    return blankResponse();
                }

                // 2) 视频流嗅探
                if (looksLikeVideoUrl(reqUrl) && detectedVideoUrl.get() == null) {
                    String referer = null;
                    String ua = null;
                    try {
                        if (request.getRequestHeaders() != null) {
                            referer = request.getRequestHeaders().get("Referer");
                            ua = request.getRequestHeaders().get("User-Agent");
                        }
                    } catch (Exception ignored) { }

                    if (TextUtils.isEmpty(referer)) {
                        // fallback：用主页 URL
                        runOnUiThread(() -> {
                            String pageUrl = webView.getUrl();
                            if (pageUrl != null) detectedReferer.set(pageUrl);
                        });
                    } else {
                        detectedReferer.set(referer);
                    }
                    detectedUa.set(TextUtils.isEmpty(ua) ? DESKTOP_UA : ua);
                    detectedVideoUrl.set(reqUrl);

                    Log.i(TAG, "嗅到视频流: " + reqUrl);
                    final String displayUrl = reqUrl;
                    runOnUiThread(() -> {
                        tvStatus.setText(getString(R.string.web_video_status_found)
                                + "\n" + displayUrl);
                        btnAnalyze.setVisibility(View.VISIBLE);
                    });
                }

                // 返回 null = 不拦截，让请求正常进行
                return null;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // "蜜罐 WebView"：把弹窗导向一个临时 WebView，并立刻吃掉里面任何跳转
                final WebView popup = new WebView(view.getContext());
                WebSettings ps = popup.getSettings();
                ps.setJavaScriptEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        Log.d(TAG, "拦截弹窗跳转: " + req.getUrl());
                        // 推迟到下一拍销毁，避免在 WebView 自己的回调里 destroy
                        v.post(popup::destroy);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                window.post(window::destroy);
            }
        });
    }

    /** 周期性查询页面里的 <video> src（覆盖那些走 src 直接挂的简单视频） */
    private void pollDomForVideoSrc() {
        if (detectedVideoUrl.get() != null) return;
        try {
            webView.evaluateJavascript(
                    "(function(){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){"
                            + "var v=vs[i];if(v.src&&v.src.length>0)return v.src;"
                            + "if(v.currentSrc&&v.currentSrc.length>0)return v.currentSrc;}return '';})()",
                    value -> {
                        if (value == null) return;
                        String src = value;
                        if (src.length() >= 2 && src.startsWith("\"") && src.endsWith("\"")) {
                            src = src.substring(1, src.length() - 1);
                        }
                        src = src.replace("\\/", "/");
                        if (src.startsWith("http") && looksLikeVideoUrl(src)
                                && detectedVideoUrl.get() == null) {
                            detectedVideoUrl.set(src);
                            String pageUrl = webView.getUrl();
                            if (pageUrl != null) detectedReferer.set(pageUrl);
                            detectedUa.set(DESKTOP_UA);
                            tvStatus.setText(getString(R.string.web_video_status_found)
                                    + "\n" + src);
                            btnAnalyze.setVisibility(View.VISIBLE);
                            Log.i(TAG, "JS 嗅到视频: " + src);
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "JS 嗅探异常", e);
        }
    }

    private static boolean looksLikeVideoUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        int qIdx = lower.indexOf('?');
        String path = qIdx > 0 ? lower.substring(0, qIdx) : lower;
        return path.endsWith(".mp4")
                || path.endsWith(".m3u8")
                || path.endsWith(".m3u")
                || path.contains(".mp4/")
                || path.contains(".m3u8/");
    }

    private static boolean isAdHost(String host) {
        String lower = host.toLowerCase(Locale.US);
        for (String ad : AD_HOSTS) {
            if (lower.equals(ad) || lower.endsWith("." + ad)) {
                return true;
            }
        }
        return false;
    }

    private static String extractRootDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return host;
    }

    private static WebResourceResponse blankResponse() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.resumeTimers();
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (jsPollHandler != null && jsPollRunnable != null) {
            jsPollHandler.removeCallbacks(jsPollRunnable);
        }
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
