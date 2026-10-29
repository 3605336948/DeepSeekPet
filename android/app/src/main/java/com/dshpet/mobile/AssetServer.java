package com.dshpet.mobile;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * dsh-pet 移动版资源服务：把 WebView 对 https://pet.local/dsh-pet-7340/... 的全部请求
 * 从 APK assets 返回（SPEC §2.6），页面/配置/视频同源，无 CORS、无网络。
 *
 * 路由（前缀 /dsh-pet-7340）：
 *   / 或 /index.html            → assets/index.html
 *   /config                     → assets/config.json（运行时替换 __PET_SIZE__/__MARGIN_X__/__MARGIN_Y__）
 *   /state                      → assets/state.json
 *   /config/meta                → {"mobile":true}
 *   /thumb/<root>/<名称>.webm   → assets/webm/<名称>.webm（video/webm，支持 Range）
 *   /font/<名称>.ttf            → assets/fonts/<名称>.ttf
 *   /pic/memes/<root>/<名称>.png→ assets/memes/<名称>.png
 *   /pic/<文件>                 → assets/pic/<文件>
 *   /balance /whisper /chat /anim → 降级 JSON（手机版未连接 DSH）；
 *                                  其中 /whisper、/chat 额外弹一次原生 Toast 说明（2s 节流），
 *                                  避免用户以为点击"没反应"（不改窗口焦点/IME，不假装成功）
 *   /<其它文件>                 → assets/<其它文件>（页面脚本 shared-core.js 等直接命中）
 */
final class AssetServer {

    private static final String TAG = "DSHPet";

    private static final String DEFAULT_STATE =
            "{\"sections\":{\"balance\":{\"counter\":1,\"data\":{\"ok\":false,\"provider\":\"android\","
                    + "\"reason\":\"unsupported\"}},\"workStatus\":{\"counter\":0,\"data\":null},"
                    + "\"notify\":{\"counter\":0,\"data\":null}},\"pets\":{}}";

    private static final String UNSUPPORTED =
            "{\"ok\":false,\"reason\":\"unsupported\",\"message\":\"手机版未连接电脑上的 DeepSeek Harness\"}";

    /** whisper/chat 不可用时给用户的原生提示（手机版没有 DSH 宿主，不假装成功） */
    private static final String TOAST_UNSUPPORTED =
            "手机版未连接电脑上的 DeepSeek Harness，碎碎念/对话暂不可用";
    /** 同一请求类型的 Toast 节流间隔：避免页面重试/连点导致刷屏 */
    private static final long TOAST_MIN_INTERVAL_MS = 2000L;

    private final Context context;
    private final AssetManager assets;
    private final int petSizeDp;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object toastLock = new Object();
    private long lastWhisperToastMs;
    private long lastChatToastMs;

    volatile int requestCount;
    volatile int missCount;
    private volatile boolean placeholderServed;

    /** 是否正在用调试占位页（T1 的 assets/index.html 还没就绪） */
    boolean isPlaceholder() {
        return placeholderServed;
    }

    AssetServer(Context context, int petSizeDp) {
        this.context = context;
        this.assets = context.getAssets();
        this.petSizeDp = petSizeDp;
    }

    /** WebViewClient.shouldInterceptRequest 入口（后台线程调用） */
    WebResourceResponse respond(WebResourceRequest request) {
        if (request == null) return null;
        Uri uri = request.getUrl();
        if (uri == null) return null;
        String host = uri.getHost();
        if (!"pet.local".equals(host)) return null; // 非本页资源：交给 WebView 默认处理
        requestCount++;
        String path;
        try {
            path = uri.getPath();
        } catch (Throwable t) {
            path = null;
        }
        if (path == null || path.isEmpty()) path = "/";
        if (path.startsWith(Geometry.PREFIX)) {
            String rel = path.substring(Geometry.PREFIX.length());
            return route(rel, request);
        }
        // pet.local 下的其它路径（favicon 等）：直接 404，避免 WebView 走网络等超时
        return notFound("pet.local " + path);
    }

    private WebResourceResponse route(String rel, WebResourceRequest request) {
        if (rel.isEmpty() || rel.equals("/")) rel = "/index.html";

        if (rel.equals("/config")) {
            return text("application/json", buildMobileConfig());
        }
        if (rel.equals("/state")) {
            return text("application/json", readAssetText("state.json", DEFAULT_STATE));
        }
        if (rel.equals("/config/meta")) {
            return text("application/json", "{\"mobile\":true}");
        }
        if (rel.equals("/whisper") || rel.equals("/chat")) {
            // 碎碎念/对话需要电脑上的 DSH 宿主；手机版返回结构化失败，并给用户一次原生提示。
            // （shouldInterceptRequest 在后台线程调用，Toast 必须 post 到主线程）
            notifyUnsupportedFeature(rel.equals("/whisper") ? "whisper" : "chat");
            return text("application/json", UNSUPPORTED);
        }
        if (rel.equals("/balance") || rel.equals("/anim") || rel.equals("/reload")) {
            return text("application/json", UNSUPPORTED);
        }
        if (rel.startsWith("/thumb/")) {
            // /thumb/<素材根>/<名称>.webm → assets/webm/<名称>.webm
            String rest = rel.substring("/thumb/".length());
            int slash = rest.indexOf('/');
            String name = slash >= 0 ? rest.substring(slash + 1) : rest;
            return streamAsset("webm/" + name, "video/webm", rangeOf(request));
        }
        if (rel.startsWith("/font/")) {
            String name = rel.substring("/font/".length());
            return streamAsset("fonts/" + name, "font/ttf", rangeOf(request));
        }
        if (rel.startsWith("/pic/memes/")) {
            // /pic/memes/<素材根>/<名称>.png → assets/memes/<名称>.png
            String rest = rel.substring("/pic/memes/".length());
            int slash = rest.indexOf('/');
            String name = slash >= 0 ? rest.substring(slash + 1) : rest;
            return streamAsset("memes/" + name, "image/png", null);
        }
        if (rel.startsWith("/pic/")) {
            String name = rel.substring("/pic/".length());
            return streamAsset("pic/" + name, mimeFor(name, "image/png"), null);
        }

        // 通用兜底：页面脚本/样式/README 等直接按 assets 根路径取（index.html、shared-core.js ...）
        String assetPath = rel.startsWith("/") ? rel.substring(1) : rel;
        if (assetPath.equals("index.html")) {
            String html = readAssetText("index.html", null);
            if (html == null) {
                // T1 的 web/ 还没就绪：给一个能跑通窗口/桥/触摸区域的最小占位页
                html = readAssetText("debug/placeholder.html", fallbackPlaceholder());
                placeholderServed = true;
                if (!placeholderLogged) {
                    placeholderLogged = true;
                    Log.w(TAG, "[asset] assets/index.html 不存在，使用调试占位页（T1 web/ 未就绪）");
                }
            }
            return text("text/html", html);
        }
        String mime = mimeFor(assetPath, null);
        if (mime == null) mime = "application/octet-stream";
        WebResourceResponse r = streamAsset(assetPath, mime, rangeOf(request));
        if (r == null) {
            missCount++;
            return notFound(assetPath);
        }
        return r;
    }

    private volatile boolean placeholderLogged;

    // ---------- 降级提示（whisper / chat） ----------

    /**
     * 页面请求 /whisper 或 /chat 时弹一次原生 Toast：手机版没有连接电脑上的 DSH，功能不可用。
     * 同一类型 2 秒内只弹一次（防页面重试/连点刷屏）；返回 JSON 仍是结构化失败，不假装成功。
     * 不改窗口焦点/IME（悬浮窗是 FLAG_NOT_FOCUSABLE，也拿不到输入法焦点）。
     */
    private void notifyUnsupportedFeature(String kind) {
        long now = SystemClock.elapsedRealtime();
        synchronized (toastLock) {
            if ("whisper".equals(kind)) {
                if (now - lastWhisperToastMs < TOAST_MIN_INTERVAL_MS) return;
                lastWhisperToastMs = now;
            } else if ("chat".equals(kind)) {
                if (now - lastChatToastMs < TOAST_MIN_INTERVAL_MS) return;
                lastChatToastMs = now;
            } else {
                return;
            }
        }
        Log.i(TAG, "[asset] " + kind + " 请求：手机版无 DSH 宿主 → Toast 提示 + unsupported JSON");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context.getApplicationContext(), TOAST_UNSUPPORTED,
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.w(TAG, "[asset] Toast 失败: " + t);
                }
            }
        });
    }

    // ---------- config.json 占位符替换 ----------

    /**
     * 手机版 config：assets/config.json 里的裸占位符必须做纯字符串替换后才返回给页面
     * （原始文件不是合法 JSON，不能先 parse）：
     *   __PET_SIZE__ / __MARGIN_X__ / __MARGIN_Y__ → 宠物大小 / 边距（CSS px）
     *   __ENABLE_DSH__ → true/false，取决于用户是否「已连接电脑 DSH」（余额/碎碎念/对话/工作状态开关）
     * 替换后再做一次防御性校正：不管 web/ 里写的是占位符还是写死，三个功能开关都以连接态为准，
     * 未连接时绝不能让页面以为功能开着（否则会冒出假余额/假气泡，破坏 v1.1.1 的干净降级体验）。
     */
    private String buildMobileConfig() {
        String raw = readAssetText("config.json", null);
        if (raw == null) {
            Log.e(TAG, "[asset] assets/config.json 缺失（T1 web/ 未就绪？）");
            return "{\"error\":\"config.json missing\",\"reason\":\"android assets/config.json not found\"}";
        }
        final boolean harness = Prefs.isHarnessConnected(context);
        String out = raw
                .replace("__PET_SIZE__", String.valueOf(petSizeDp))
                .replace("__MARGIN_X__", String.valueOf(Geometry.MARGIN_X_CSS))
                .replace("__MARGIN_Y__", String.valueOf(Geometry.MARGIN_Y_CSS))
                .replace("__ENABLE_DSH__", harness ? "true" : "false");
        if (out.contains("__PET_SIZE__") || out.contains("__MARGIN_X__")
                || out.contains("__MARGIN_Y__") || out.contains("__ENABLE_DSH__")) {
            Log.w(TAG, "[asset] config.json 占位符替换后仍有残留");
        }
        try {
            JSONObject root = new JSONObject(out);
            JSONObject main = root.optJSONObject("main");
            if (main != null) {
                JSONArray pets = main.optJSONArray("pets");
                if (pets != null && pets.length() > 0) {
                    JSONObject pet = pets.optJSONObject(0);
                    if (pet != null) {
                        pet.put("balanceEnabled", harness);
                        pet.put("whisperEnabled", harness);
                        pet.put("workStatusEnabled", harness);
                    }
                }
                // 手机版不弹系统通知（前台服务通知由 App 自己管）
                main.put("notificationsEnabled", false);
            }
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "[asset] config.json 连接态校正失败（按替换后原文返回）: " + t);
            return out;
        }
    }

    // ---------- 文本 / 二进制响应 ----------

    private WebResourceResponse text(String mime, String body) {
        String pure = pureMime(mime);
        String encoding = isTextMime(pure) ? "utf-8" : null;
        byte[] data = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        headers.put("Content-Length", String.valueOf(data.length));
        // 注意：WebResourceResponse 的 mimeType 必须是**不带 charset 参数的纯 mime**，
        // charset 只能走 encoding 参数；把 charset 拼进 mimeType + encoding=null 会让 Chromium
        // 不识别响应类型（实测主文档被当纯文本显示，viewport meta 也不生效）。
        return new WebResourceResponse(pure, encoding, 200, "OK", headers, new ByteArrayInputStream(data));
    }

    private WebResourceResponse notFound(String what) {
        byte[] data = ("404: " + what).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        headers.put("Content-Length", String.valueOf(data.length));
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
                new ByteArrayInputStream(data));
    }

    /**
     * 返回 assets 文件流。webm/字体等已在 build.gradle 里设置 noCompress，
     * 因此可以拿到真实长度并支持 Range（<video> 需要 206）。
     * 文本类资源用 encoding="utf-8"、mimeType 保持纯 mime（见 text() 的注释）。
     */
    private WebResourceResponse streamAsset(String assetPath, String mime, String rangeHeader) {
        if (assetPath == null || assetPath.isEmpty() || assetPath.contains("..")) return notFound(assetPath);
        final String pureMime = pureMime(mime);
        final String encoding = isTextMime(pureMime) ? "utf-8" : null;
        AssetFileDescriptor afd = null;
        try {
            afd = assets.openFd(assetPath);
        } catch (Throwable ignored) {
            // 压缩存放的文件（js/html 等）拿不到 fd，走内存读取
        }
        if (afd != null) {
            try {
                long total = afd.getLength();
                if (total > 0) {
                    long[] range = rangeHeader == null ? null : parseRange(rangeHeader, total);
                    long start = range == null ? 0 : range[0];
                    long end = range == null ? total - 1 : range[1];
                    InputStream in = afd.createInputStream();
                    if (in == null) throw new IOException("openFd createInputStream == null");
                    long skipped = 0;
                    while (skipped < start) {
                        long s = in.skip(start - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                    long len = end - start + 1;
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Accept-Ranges", "bytes");
                    headers.put("Cache-Control", "no-store");
                    headers.put("Content-Length", String.valueOf(len));
                    if (range != null) {
                        headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
                    }
                    // 关键：返回流的生命周期归 WebView 管——AFD/InputStream 必须等 WebView 读完并
                    // close 这个流之后再关。若在 return 前 close（原来 finally 里的写法），
                    // WebView 拿到的流立刻失效，所有 noCompress 资产（webm/ttf/png）都会 net::ERR_FAILED。
                    final AssetFileDescriptor afdRef = afd;
                    return new WebResourceResponse(pureMime, encoding, range != null ? 206 : 200,
                            range != null ? "Partial Content" : "OK", headers,
                            new FilterInputStream(new LimitedInputStream(in, len)) {
                                @Override
                                public void close() throws IOException {
                                    try {
                                        super.close();
                                    } finally {
                                        try {
                                            afdRef.close();
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            });
                }
            } catch (Throwable t) {
                Log.w(TAG, "[asset] openFd 流式读取失败，退化内存读取: " + assetPath + " (" + t + ")");
            }
            // 只有“没有成功返回流”的路径才需要在这里关 AFD
            try {
                afd.close();
            } catch (Throwable ignored) {
            }
        }
        InputStream in = openAsset(assetPath);
        if (in == null) return null;
        try {
            byte[] data = readAll(in);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("Content-Length", String.valueOf(data.length));
            return new WebResourceResponse(pureMime, encoding, 200, "OK", headers,
                    new ByteArrayInputStream(data));
        } catch (Throwable t) {
            Log.w(TAG, "[asset] 读取失败: " + assetPath + " (" + t + ")");
            return notFound(assetPath);
        }
    }

    // ---------- assets 读取工具 ----------

    private InputStream openAsset(String path) {
        try {
            return assets.open(path);
        } catch (Throwable t) {
            return null;
        }
    }

    private String readAssetText(String path, String fallback) {
        InputStream in = openAsset(path);
        if (in == null) return fallback;
        try {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "[asset] 文本读取失败: " + path + " (" + t + ")");
            return fallback;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String rangeOf(WebResourceRequest request) {
        try {
            Map<String, String> h = request.getRequestHeaders();
            if (h == null) return null;
            for (Map.Entry<String, String> e : h.entrySet()) {
                if ("Range".equalsIgnoreCase(e.getKey())) return e.getValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static long[] parseRange(String header, long total) {
        try {
            String h = header.trim();
            if (!h.startsWith("bytes=")) return null;
            h = h.substring("bytes=".length()).trim();
            int comma = h.indexOf(',');
            if (comma >= 0) h = h.substring(0, comma);
            int dash = h.indexOf('-');
            if (dash < 0) return null;
            String a = h.substring(0, dash).trim();
            String b = h.substring(dash + 1).trim();
            long start;
            long end;
            if (a.isEmpty()) {
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                start = Math.max(0, total - n);
                end = total - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? total - 1 : Long.parseLong(b);
            }
            if (start < 0 || start >= total) return null;
            if (end >= total) end = total - 1;
            if (end < start) return null;
            return new long[]{start, end};
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = in.skip(Math.min(n, remaining));
            if (s > 0) remaining -= s;
            return s;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    // ---------- mime ----------

    private static boolean isTextMime(String mime) {
        return mime != null && (mime.startsWith("text/") || mime.contains("javascript")
                || mime.contains("json") || mime.contains("xml") || mime.contains("svg"));
    }

    /** 去掉 charset 等参数，只留纯 mimeType（WebResourceResponse.mimeType 的硬要求） */
    private static String pureMime(String mime) {
        if (mime == null) return "application/octet-stream";
        int semi = mime.indexOf(';');
        return (semi >= 0 ? mime.substring(0, semi) : mime).trim();
    }

    static String mimeFor(String path, String fallback) {
        if (path == null) return fallback;
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".txt") || p.endsWith(".md")) return "text/plain";
        if (p.endsWith(".wasm")) return "application/wasm";
        return fallback;
    }

    private static String fallbackPlaceholder() {
        return "<!doctype html><meta charset=\"utf-8\"><body style=\"margin:0;background:transparent;"
                + "font-family:sans-serif\"><div style=\"position:fixed;left:0;top:0;right:0;bottom:0;"
                + "display:flex;align-items:center;justify-content:center;color:#fff\">assets/index.html 缺失</div></body>";
    }
}
