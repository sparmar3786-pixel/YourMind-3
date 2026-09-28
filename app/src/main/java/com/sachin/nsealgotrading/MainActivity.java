package com.sachin.nsealgotrading;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.net.Uri;
import android.content.Intent;
import android.net.Uri;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    WebView w;
    ExecutorService net = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        WebViewAssetLoader l = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();

        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, String u) {
                return l.shouldInterceptRequest(Uri.parse(u));
            }
        });
        w.setWebChromeClient(new WebChromeClient());
        w.addJavascriptInterface(new GrowwBridge(), "AndroidGroww");
        setContentView(w);
        w.loadUrl("https://appassets.androidplatform.net/assets/algo_dashboard.html");
    }

    public class GrowwBridge {
        @JavascriptInterface public void testUserProfile(final String token) {
            request(token, "https://api.groww.in/v1/user/detail", "USER PROFILE");
        }
        @JavascriptInterface public void testQuote(final String token) {
            request(token, "https://api.groww.in/v1/live-data/quote?exchange=NSE&segment=CASH&trading_symbol=NIFTY", "NIFTY LIVE QUOTE");
        }
    }

    private void request(final String token, final String url, final String label) {
        if (token == null || token.trim().isEmpty()) {
            post(false, label + ": token required");
            return;
        }
        net.execute(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(url);
                c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + token.trim());
                c.setRequestProperty("X-API-VERSION", "1.0");
                int code = c.getResponseCode();
                InputStream is = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
                String body = read(is);
                String status = "HTTP " + code;
                try {
                    JSONObject o = new JSONObject(body);
                    String apiStatus = o.optString("status", "");
                    if (!apiStatus.isEmpty()) status += " • " + apiStatus;
                } catch (Exception ignored) {}
                boolean ok = code >= 200 && code < 300;
                String msg = label + ": " + status + (ok ? " • connection OK" : " • request rejected");
                post(ok, msg);
            } catch (Exception e) {
                post(false, label + ": connection error • " + e.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
            }
        });
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

    private void post(final boolean ok, final String msg) {
        runOnUiThread(() -> {
            String safe = msg.replace("\\", "\\\\").replace("'", "\\'");
            w.evaluateJavascript("window.growwResult(" + ok + ",'" + safe + "')", null);
        });
    }

    @Override public void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (w.canGoBack()) w.goBack(); else super.onBackPressed();
    }
}