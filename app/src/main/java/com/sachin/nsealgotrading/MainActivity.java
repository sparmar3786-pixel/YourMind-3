package com.sachin.nsealgotrading;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.net.Uri;
import android.content.Intent;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    WebView w;
    ValueCallback<Uri[]> fileCallback;
    ExecutorService net = Executors.newSingleThreadExecutor();
    static final int FILE_PICKER = 4101;

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
        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv","application/csv","application/vnd.ms-excel"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, FILE_PICKER);
                return true;
            }
        });
        w.addJavascriptInterface(new GrowwBridge(), "AndroidGroww");
        setContentView(w);
        w.loadUrl("https://appassets.androidplatform.net/assets/algo_dashboard.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_PICKER || fileCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i=0;i<n;i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) results = new Uri[]{data.getData()};
        }
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    public class GrowwBridge {
        @JavascriptInterface public void testUserProfile(final String token) {
            request(token, "https://api.groww.in/v1/user/detail", "USER PROFILE");
        }
        @JavascriptInterface public void testQuote(final String token) {
            request(token, "https://api.groww.in/v1/live-data/quote?exchange=NSE&segment=CASH&trading_symbol=NIFTY", "NIFTY LIVE QUOTE");
        }
        @JavascriptInterface public void testOptionChain(final String token, final String expiry) {
            String e = expiry == null ? "" : expiry.trim();
            if (!e.matches("\\d{4}-\\d{2}-\\d{2}")) {
                post(false, "OPTION CHAIN: expiry must be YYYY-MM-DD");
                return;
            }
            request(token, "https://api.groww.in/v1/option-chain/exchange/NSE/underlying/NIFTY?expiry_date="+e, "NIFTY OPTION CHAIN");
        }
    }

    private void request(final String token, final String url, final String label) {
        if (token == null || token.trim().isEmpty()) { post(false, label + ": token required"); return; }
        net.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection)new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Accept","application/json");
                c.setRequestProperty("Authorization","Bearer "+token.trim());
                c.setRequestProperty("X-API-VERSION","1.0");
                int code=c.getResponseCode();
                InputStream is=code>=200&&code<400?c.getInputStream():c.getErrorStream();
                String body=read(is);
                String status="HTTP "+code;
                String details="";
                try {
                    JSONObject o=new JSONObject(body);
                    String s=o.optString("status","");
                    if(!s.isEmpty()) status+=" • "+s;
                    JSONObject p=o.optJSONObject("payload");
                    if (p != null) {
                        if (label.contains("LIVE QUOTE")) {
                            details = " • LTP="+p.optDouble("last_price", Double.NaN)
                                    + " • OI="+p.optDouble("open_interest", Double.NaN)
                                    + " • OIΔ="+p.optDouble("oi_day_change", Double.NaN)
                                    + " • Vol="+p.optDouble("volume", Double.NaN);
                        } else if (label.contains("OPTION CHAIN")) {
                            JSONObject strikes=p.optJSONObject("strikes");
                            details = " • strikes="+(strikes==null?0:strikes.length())
                                    + " • underlying LTP="+p.optDouble("underlying_ltp", Double.NaN);
                        } else if (label.contains("USER PROFILE")) {
                            details = " • NSE="+p.optBoolean("nse_enabled",false)
                                    + " • BSE="+p.optBoolean("bse_enabled",false)
                                    + " • segments="+p.optJSONArray("active_segments");
                        }
                    }
                } catch(Exception ignored){}
                boolean ok=code>=200&&code<300;
                post(ok,label+": "+status+details+(ok?" • connection OK":" • request rejected"));
            } catch(Exception e) {
                post(false,label+": connection error • "+e.getClass().getSimpleName());
            } finally { if(c!=null)c.disconnect(); }
        });
    }

    private String read(InputStream is)throws IOException{
        if(is==null)return "";
        BufferedReader r=new BufferedReader(new InputStreamReader(is));
        StringBuilder b=new StringBuilder(); String line;
        while((line=r.readLine())!=null)b.append(line);
        r.close(); return b.toString();
    }

    private void post(final boolean ok, final String msg){
        runOnUiThread(()->{
            String safe=msg.replace("\\","\\\\").replace("'","\\'");
            w.evaluateJavascript("window.growwResult("+ok+",'"+safe+"')",null);
        });
    }

    @Override public void onDestroy(){net.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(w.canGoBack())w.goBack();else super.onBackPressed();}
}