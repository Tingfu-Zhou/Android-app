// NEW file: UpdateChecker.java
package com.example.helloworld;

import android.content.Context;
import android.os.Build;

import com.google.android.datatransport.backend.cct.BuildConfig;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简版本检查器：
 * - 拉取远端 version.json
 * - 对比 BuildConfig.VERSION_CODE
 * - 返回需要更新的信息（包含是否强制）
 */
public final class UpdateChecker {

    // TODO: 替换为你 CDN 上的固定地址（HTTPS）
    public static final String VERSION_URL = "https://cdn.xswy.tech/app/version.json"; // NEW

    public interface Callback {
        /** info == null 表示无更新或拉取失败 */
        void onResult(UpdateInfo info);
    }

    public static final class UpdateInfo {
        public int latestCode;
        public int minSupported;
        public String latestName;
        public String notes;
        public String landingUrl; // 推荐：下载落地页（外部浏览器打开）
        public boolean force;     // 当前版本低于 minSupported → 强更
    }

    public static void check(Context ctx, Callback cb) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                conn.setConnectTimeout(5000);  // 超时
                conn.setReadTimeout(5000);     // 超时
                int http = conn.getResponseCode();
                if (http != 200) {
                    // 网络失败策略——静默：返回 null
                    cb.onResult(null);
                    return;
                }

                String json = readAll(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));

                // MOD: 解析统一 JSON：先拿到 android 分区
                JSONObject root = new JSONObject(json);
                JSONObject a = root.getJSONObject("android");

                UpdateInfo info = new UpdateInfo();
                info.latestCode   = a.getInt("latestVersionCode");
                info.minSupported = a.getInt("minSupportedCode");
                info.latestName   = a.optString("latestVersionName", "");
                info.notes        = a.optString("notes", "");
                info.landingUrl   = a.getString("landingUrl");

                int current = BuildConfig.VERSION_CODE;
                info.force = current < info.minSupported;

                if (current < info.latestCode) cb.onResult(info);
                else cb.onResult(null);

            } catch (Exception e) {
                // NEW: 任意异常 → 静默跳过
                cb.onResult(null);
            }
        }).start();
    }

    private static String readAll(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        return sb.toString();
    }
}
