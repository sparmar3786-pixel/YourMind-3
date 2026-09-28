package com.sachin.nsealgotrading;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;
import android.net.Uri;
import android.content.Intent;
import android.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import okhttp3.*;

public class MainActivity extends Activity {
    private WebView w;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private String pendingAngelAuthToken = "";
    private String pendingAngelFeedToken = "";
    private String pendingAngelState = "";
    private static final int FILE_PICKER = 4101;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, String u) {
                return loader.shouldInterceptRequest(Uri.parse(u));
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                deliverPendingAngelCallback();
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/csv","application/csv","application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/pdf","image/*"
                });
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, FILE_PICKER);
                return true;
            }
        });

        w.addJavascriptInterface(new AngelOneBridge(), "AndroidAngel");
        w.addJavascriptInterface(new NseBridge(), "AndroidNSE");
        w.addJavascriptInterface(new NseMcpBridge(), "AndroidNseMcp");
        setContentView(w);
        w.loadUrl("https://appassets.androidplatform.net/assets/algo_dashboard.html");;
        handleAngelCallback(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAngelCallback(intent);
    }

    private void handleAngelCallback(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (!"nsealgosignal".equalsIgnoreCase(uri.getScheme()) ||
            !"angel-callback".equalsIgnoreCase(uri.getHost())) return;

        pendingAngelAuthToken = valueOrEmpty(uri.getQueryParameter("auth_token"));
        pendingAngelFeedToken = valueOrEmpty(uri.getQueryParameter("feed_token"));
        pendingAngelState = valueOrEmpty(uri.getQueryParameter("state"));
        deliverPendingAngelCallback();
    }

    private void deliverPendingAngelCallback() {
        if (w == null || pendingAngelAuthToken.isEmpty()) return;
        final String auth = pendingAngelAuthToken;
        final String feed = pendingAngelFeedToken;
        final String state = pendingAngelState;
        String js = "window.handleAngelCallback && window.handleAngelCallback(" +
            JSONObject.quote(auth) + "," + JSONObject.quote(feed) + "," +
            JSONObject.quote(state) + ");";
        w.post(() -> w.evaluateJavascript(js, null));
        pendingAngelAuthToken = "";
        pendingAngelFeedToken = "";
        pendingAngelState = "";
    }

    private String valueOrEmpty(String v) {
        return v == null ? "" : v;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_PICKER || fileCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    public class AngelOneBridge {
        private static final String LOGIN_URL =
            "https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword";
        private static final String PROFILE_URL =
            "https://apiconnect.angelone.in/rest/secure/angelbroking/user/v1/getProfile";

        private volatile String jwtToken = "";
        private volatile String refreshToken = "";
        private volatile String feedToken = "";
        private volatile String clientCode = "";
        private volatile String apiKey = "";
        private volatile OkHttpClient wsClient;
        private volatile WebSocket marketSocket;
        private final ConcurrentHashMap<String, LiveRow> liveRows = new ConcurrentHashMap<>();
        private final Handler heartbeat = new Handler(Looper.getMainLooper());
        private volatile String selectedIndex = "NIFTY";

        @JavascriptInterface public String generateTotp(final String secret) {
            try { return totp(secret); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public void login(final String client, final String pin,
                                               final String totp, final String key) {
            if (client == null || pin == null || totp == null || key == null ||
                client.trim().isEmpty() || pin.trim().isEmpty() ||
                totp.trim().isEmpty() || key.trim().isEmpty()) {
                postAngel(false, "Login failed • Client Code, PIN, TOTP and API Key are required");
                return;
            }
            net.execute(() -> {
                HttpURLConnection c = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("clientcode", client.trim());
                    body.put("password", pin.trim());
                    body.put("totp", totp.trim());

                    c = (HttpURLConnection) new URL(LOGIN_URL).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setDoOutput(true);
                    setAngelHeaders(c, key.trim());

                    OutputStream os = c.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int code = c.getResponseCode();
                    String raw = read(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
                    JSONObject root = parseJson(raw);
                    boolean ok = code >= 200 && code < 300 && root != null &&
                        "true".equalsIgnoreCase(root.optString("status"));

                    if (ok) {
                        JSONObject data = root.optJSONObject("data");
                        jwtToken = data == null ? "" : data.optString("jwtToken", "");
                        refreshToken = data == null ? "" : data.optString("refreshToken", "");
                        feedToken = data == null ? "" : data.optString("feedToken", "");
                        clientCode = client.trim();
                        apiKey = key.trim();
                        postAngel(true, "Login successful • JWT="+state(jwtToken)+" • Feed Token="+state(feedToken));
                    } else {
                        clearSession();
                        postAngel(false, "Login failed • HTTP "+code+" • "+extractError(raw));
                    }
                } catch (Exception e) {
                    clearSession();
                    postAngel(false, "Login error • "+e.getClass().getSimpleName()+" • "+safe(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface public void profile() {
            final String token = jwtToken;
            if (token.isEmpty()) {
                postAngel(false, "Profile check failed • Login first");
                return;
            }
            net.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(PROFILE_URL).openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("Authorization", "Bearer "+token);
                    c.setRequestProperty("X-API-VERSION", "1.0");
                    int code = c.getResponseCode();
                    String raw = read(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
                    JSONObject root = parseJson(raw);
                    boolean ok = code >= 200 && code < 300 &&
                        root != null && "true".equalsIgnoreCase(root.optString("status"));
                    postAngel(ok, ok
                        ? "Profile verified • API access active • NSE/BSE permissions returned"
                        : "Profile check failed • HTTP "+code+" • "+extractError(raw));
                } catch (Exception e) {
                    postAngel(false, "Profile error • "+e.getClass().getSimpleName());
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface public void feedInfo() {
            postAngel(!feedToken.isEmpty(), feedToken.isEmpty()
                ? "Feed token not available • Login first"
                : "Feed token available • "+state(feedToken)+" • ready for feed integration");
        }

        @JavascriptInterface public void logout() {
            clearSession();
            postAngel(true, "Session cleared • Logged out");
        }

        private void clearSession() {
            jwtToken = "";
            refreshToken = "";
            feedToken = "";
            clientCode = "";
            apiKey = "";
        }

        private void setAngelHeaders(HttpURLConnection c, String key) {
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-UserType", "USER");
            c.setRequestProperty("X-SourceID", "WEB");
            c.setRequestProperty("X-ClientLocalIP", "127.0.0.1");
            c.setRequestProperty("X-ClientPublicIP", "127.0.0.1");
            c.setRequestProperty("X-MACAddress", "00:00:00:00:00:00");
            c.setRequestProperty("X-PrivateKey", key);
        }

        private String state(String v) {
            return v == null || v.isEmpty() ? "NO" : "READY";
        }

        private String totp(String secret) throws Exception {
            String s = secret == null ? "" : secret.replace(" ", "").replace("-", "").trim().toUpperCase();
            if (s.isEmpty()) return "";
            byte[] key = base32Decode(s);
            long counter = System.currentTimeMillis() / 1000L / 30L;
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) { msg[i] = (byte)(counter & 0xff); counter >>= 8; }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) |
                      ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            return String.format(java.util.Locale.US, "%06d", bin % 1000000);
        }

        private byte[] base32Decode(String input) {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int buffer = 0, bits = 0;
            for (int i = 0; i < input.length(); i++) {
                char ch = input.charAt(i);
                if (ch == '=') break;
                int val = alphabet.indexOf(ch);
                if (val < 0) throw new IllegalArgumentException("Invalid TOTP secret");
                buffer = (buffer << 5) | val;
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 0xff);
                }
            }
            return out.toByteArray();
        }
    }

    public class NseBridge {
        @JavascriptInterface public void checkServer() {
            net.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection)new URL("https://www.nseindia.com/").openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent","Mozilla/5.0 (Android) NSE-Algo-Signal");
                    c.setRequestProperty("Accept","text/html,application/xhtml+xml");
                    int code = c.getResponseCode();
                    postNse(code >= 200 && code < 500,
                        "NSE SERVER: HTTP "+code+" • web server reachable; real-time market feed is not implied");
                } catch(Exception e) {
                    postNse(false, "NSE SERVER: connection error • "+e.getClass().getSimpleName());
                } finally {
                    if(c != null) c.disconnect();
                }
            });
        }
    }

    public class NseMcpBridge {
        private volatile String sessionId = null;
        private static final String MCP_VERSION = "2025-06-18";

        @JavascriptInterface public void connect(final String url) {
            sessionId = null;
            initialize(url);
        }

        private void initialize(final String url) {
            try {
                JSONObject p = new JSONObject();
                p.put("protocolVersion", MCP_VERSION);
                p.put("capabilities", new JSONObject());
                JSONObject client = new JSONObject();
                client.put("name", "NSE-Algo-Signal");
                client.put("version", "1.0");
                p.put("clientInfo", client);
                mcpRequest(url, "initialize", p, true);
            } catch(Exception ex) {
                postMcp(false,"initialize",0,ex.toString(),sessionId);
            }
        }

        @JavascriptInterface public void listTools(final String url) {
            mcpRequest(url, "tools/list", new JSONObject(), false);
        }

        @JavascriptInterface public void callTool(final String url, final String name, final String argsJson) {
            try {
                JSONObject p = new JSONObject();
                p.put("name", name);
                p.put("arguments", new JSONObject(argsJson == null || argsJson.trim().isEmpty() ? "{}" : argsJson));
                mcpRequest(url, "tools/call", p, false);
            } catch(Exception ex) {
                postMcp(false,"tools/call",0,"Invalid arguments: "+ex.getMessage(),sessionId);
            }
        }

        private void mcpRequest(final String url, final String method, final JSONObject params, final boolean init) {
            net.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection)new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(45000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type","application/json");
                    c.setRequestProperty("Accept","application/json, text/event-stream");
                    c.setRequestProperty("MCP-Protocol-Version",MCP_VERSION);
                    c.setRequestProperty("User-Agent","NSE-Algo-Signal-Android/1.0");
                    if(sessionId != null && !init) c.setRequestProperty("Mcp-Session-Id",sessionId);

                    JSONObject req = new JSONObject();
                    req.put("jsonrpc","2.0");
                    req.put("id",String.valueOf(System.currentTimeMillis()));
                    req.put("method",method);
                    req.put("params",params);

                    OutputStream os = c.getOutputStream();
                    os.write(req.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int code = c.getResponseCode();
                    String sid = c.getHeaderField("Mcp-Session-Id");
                    if(sid != null && !sid.isEmpty()) sessionId = sid;
                    String response = read(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
                    postMcp(code >= 200 && code < 300,method,code,response,sessionId);

                    if(init && code >= 200 && code < 300) {
                        sendInitialized(url);
                        new Handler(Looper.getMainLooper()).postDelayed(() -> listTools(url), 300);
                    }
                } catch(Exception ex) {
                    postMcp(false,method,0,ex.getClass().getSimpleName()+": "+ex.getMessage(),sessionId);
                } finally {
                    if(c != null) c.disconnect();
                }
            });
        }

        private void sendInitialized(final String url) {
            net.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection)new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type","application/json");
                    c.setRequestProperty("Accept","application/json, text/event-stream");
                    c.setRequestProperty("MCP-Protocol-Version",MCP_VERSION);
                    if(sessionId != null) c.setRequestProperty("Mcp-Session-Id",sessionId);
                    JSONObject req = new JSONObject();
                    req.put("jsonrpc","2.0");
                    req.put("method","notifications/initialized");
                    OutputStream os = c.getOutputStream();
                    os.write(req.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    c.getResponseCode();
                } catch(Exception ignored) {
                } finally {
                    if(c != null) c.disconnect();
                }
            });
        }

        private void postMcp(final boolean ok, final String method, final int code, final String raw, final String sid) {
            runOnUiThread(() -> {
                String safe = JSONObject.quote(raw == null ? "" : raw);
                String safeSid = JSONObject.quote(sid == null ? "" : sid);
                w.evaluateJavascript("window.nseMcpNativeResult("+ok+",'"+method+"',"+code+","+safe+","+safeSid+")",null);
            });
        }
    }

    private JSONObject parseJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception ignored) { return null; }
    }

    private String extractError(String primary) {
        if (primary == null || primary.trim().isEmpty()) return "no error body returned";
        JSONObject o = parseJson(primary);
        if (o != null) {
            String[] keys = {"message","error","remark","reason","code"};
            for (String k : keys) {
                String v = o.optString(k,"");
                if (!v.isEmpty()) return k+"="+v;
            }
            JSONObject p = o.optJSONObject("payload");
            if (p != null) {
                for (String k : keys) {
                    String v = p.optString(k,"");
                    if (!v.isEmpty()) return k+"="+v;
                }
            }
        }
        return primary.length() > 180 ? primary.substring(0,180) : primary;
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\n"," ").replace("\r"," ");
    }

    private String read(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line);
        r.close();
        return b.toString();
    }

    private void postAngel(final boolean ok, final String msg) {
        runOnUiThread(() -> {
            String safe = msg == null ? "" : msg.replace("\\","\\\\").replace("'","\\'");
            w.evaluateJavascript("window.angelResult("+ok+",'"+safe+"')",null);
        });
    }

    private void postNse(final boolean ok, final String msg) {
        runOnUiThread(() -> {
            String safe = msg == null ? "" : msg.replace("\\","\\\\").replace("'","\\'");
            w.evaluateJavascript("window.nseResult("+ok+",'"+safe+"')",null);
        });
    }

    @Override public void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (w.canGoBack()) w.goBack();
        else super.onBackPressed();
    }
}
