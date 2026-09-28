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
        w.addJavascriptInterface(new NseBridge(), "AndroidNSE");
        w.addJavascriptInterface(new NseMcpBridge(), "AndroidNseMcp");
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
                    int code=c.getResponseCode();
                    postNse(code>=200 && code<500, "NSE SERVER: HTTP "+code+" • web server reachable; real-time market feed is not implied");
                } catch(Exception e) {
                    postNse(false, "NSE SERVER: connection error • "+e.getClass().getSimpleName());
                } finally { if(c!=null)c.disconnect(); }
            });
        }
    }


    public class NseMcpBridge {
        private volatile String sessionId = null;
        private static final String MCP_VERSION = "2025-11-25";

        @JavascriptInterface public void connect(final String url) {
            try {
                JSONObject p = new JSONObject();
                p.put("protocolVersion", MCP_VERSION);
                JSONObject caps = new JSONObject();
                caps.put("tools", new JSONObject());
                p.put("capabilities", caps);
                JSONObject client = new JSONObject();
                client.put("name", "NSE-Algo-Signal");
                client.put("version", "1.0");
                p.put("clientInfo", client);
                mcpRequest(url, "initialize", p, true);
            } catch(Exception ex) {
                postMcp(false, "initialize", 0, ex.getMessage(), sessionId);
            }
        }

        @JavascriptInterface public void listTools(final String url) {
            mcpRequest(url, "tools/list", new JSONObject(), false);
        }

        @JavascriptInterface public void callTool(final String url, final String name, final String argsJson) {
            try {
                JSONObject p = new JSONObject();
                p.put("name", name);
                JSONObject args = new JSONObject(argsJson == null || argsJson.trim().isEmpty() ? "{}" : argsJson);
                p.put("arguments", args);
                mcpRequest(url, "tools/call", p, false);
            } catch(Exception ex) {
                postMcp(false, "tools/call", 0, "Invalid tool arguments: " + ex.getMessage(), sessionId);
            }
        }

        private void mcpRequest(final String url, final String method, final JSONObject params, final boolean init) {
            net.execute(() -> {
                HttpURLConnection c=null;
                try {
                    c=(HttpURLConnection)new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type","application/json");
                    c.setRequestProperty("Accept","application/json, text/event-stream");
                    c.setRequestProperty("MCP-Protocol-Version",MCP_VERSION);
                    c.setRequestProperty("User-Agent","NSE-Algo-Signal-Android/1.0");
                    if(sessionId!=null && !init) c.setRequestProperty("Mcp-Session-Id",sessionId);

                    JSONObject req=new JSONObject();
                    req.put("jsonrpc","2.0");
                    req.put("id",System.currentTimeMillis());
                    req.put("method",method);
                    req.put("params",params);

                    OutputStream os=c.getOutputStream();
                    os.write(req.toString().getBytes("UTF-8"));
                    os.flush(); os.close();

                    int code=c.getResponseCode();
                    String sid=c.getHeaderField("Mcp-Session-Id");
                    if(sid!=null && !sid.isEmpty()) sessionId=sid;

                    InputStream is=code>=200&&code<400?c.getInputStream():c.getErrorStream();
                    String response=read(is);

                    if(init && code>=200 && code<300) {
                        sendInitialized(url);
                    }
                    postMcp(code>=200&&code<300, method, code, response, sessionId);
                } catch(Exception ex) {
                    postMcp(false, method, 0, ex.getClass().getSimpleName()+": "+ex.getMessage(), sessionId);
                } finally {
                    if(c!=null)c.disconnect();
                }
            });
        }

        private void sendInitialized(final String url) {
            HttpURLConnection c=null;
            try {
                c=(HttpURLConnection)new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                c.setRequestProperty("Accept","application/json, text/event-stream");
                c.setRequestProperty("MCP-Protocol-Version",MCP_VERSION);
                c.setRequestProperty("User-Agent","NSE-Algo-Signal-Android/1.0");
                if(sessionId!=null)c.setRequestProperty("Mcp-Session-Id",sessionId);
                JSONObject req=new JSONObject();
                req.put("jsonrpc","2.0");
                req.put("method","notifications/initialized");
                OutputStream os=c.getOutputStream();
                os.write(req.toString().getBytes("UTF-8"));
                os.flush(); os.close();
                c.getResponseCode();
            } catch(Exception ignored) {
            } finally {
                if(c!=null)c.disconnect();
            }
        }

        private void postMcp(final boolean ok, final String method, final int code, final String raw, final String sid) {
            runOnUiThread(() -> {
                String safe=JSONObject.quote(raw==null?"":raw);
                String safeSid=JSONObject.quote(sid==null?"":sid);
                w.evaluateJavascript("window.nseMcpNativeResult("+ok+",'"+method+"',"+code+","+safe+","+safeSid+")",null);
            });
        }
    }

    public class GrowwBridge {
        @JavascriptInterface public void testUserProfile(final String token) {
            request(token, "https://api.groww.in/v1/user/detail", "USER PROFILE");
        }
        @JavascriptInterface public void testQuote(final String token) {
            requestQuote(token);
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

    private void requestQuote(final String token) {
        if (token == null || token.trim().isEmpty()) {
            post(false, "NIFTY LIVE QUOTE: token required");
            return;
        }
        net.execute(() -> {
            int code = 0;
            String body = "";
            HttpURLConnection c = null;
            try {
                String quoteUrl = "https://api.groww.in/v1/live-data/quote?exchange=NSE&segment=CASH&trading_symbol=NIFTY";
                c = (HttpURLConnection)new URL(quoteUrl).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Accept","application/json");
                c.setRequestProperty("Authorization","Bearer "+token.trim());
                c.setRequestProperty("X-API-VERSION","1.0");
                code = c.getResponseCode();
                InputStream is=code>=200&&code<400?c.getInputStream():c.getErrorStream();
                body=read(is);
            } catch(Exception e) {
                post(false, "NIFTY LIVE QUOTE: connection error • "+e.getClass().getSimpleName());
                return;
            } finally { if(c!=null)c.disconnect(); }

            if (code >= 200 && code < 300) {
                postQuoteSuccess(body, code);
                return;
            }

            // Groww may reject the full quote endpoint while the simpler LTP endpoint is permitted.
            // Try the documented LTP endpoint so index price can still be displayed.
            HttpURLConnection ltp = null;
            try {
                String ltpUrl = "https://api.groww.in/v1/live-data/ltp?segment=CASH&exchange_symbols=NSE_NIFTY";
                ltp = (HttpURLConnection)new URL(ltpUrl).openConnection();
                ltp.setRequestMethod("GET");
                ltp.setConnectTimeout(10000);
                ltp.setReadTimeout(10000);
                ltp.setRequestProperty("Accept","application/json");
                ltp.setRequestProperty("Authorization","Bearer "+token.trim());
                ltp.setRequestProperty("X-API-VERSION","1.0");
                int ltpCode=ltp.getResponseCode();
                InputStream is=ltpCode>=200&&ltpCode<400?ltp.getInputStream():ltp.getErrorStream();
                String ltpBody=read(is);
                if (ltpCode >= 200 && ltpCode < 300) {
                    JSONObject o=new JSONObject(ltpBody);
                    JSONObject p=o.optJSONObject("payload");
                    double ltpValue=p==null?Double.NaN:p.optDouble("NSE_NIFTY",Double.NaN);
                    post(true, "NIFTY LIVE QUOTE: LTP fallback HTTP "+ltpCode+" • LTP="+ltpValue+" • OI unavailable from LTP endpoint");
                } else {
                    post(false, "NIFTY LIVE QUOTE: HTTP "+code+" • quote rejected • LTP fallback HTTP "+ltpCode+" • "+extractError(ltpBody, body));
                }
            } catch(Exception e) {
                post(false, "NIFTY LIVE QUOTE: HTTP "+code+" • "+extractError(body,"")+
                        " • LTP fallback error • "+e.getClass().getSimpleName());
            } finally { if(ltp!=null)ltp.disconnect(); }
        });
    }

    private void postQuoteSuccess(String body, int code) {
        try {
            JSONObject o=new JSONObject(body);
            String s=o.optString("status","");
            JSONObject p=o.optJSONObject("payload");
            String details="";
            if (p != null) {
                details = " • LTP="+p.optDouble("last_price", Double.NaN)
                        + " • OI="+p.optDouble("open_interest", Double.NaN)
                        + " • OIΔ="+p.optDouble("oi_day_change", Double.NaN)
                        + " • Vol="+p.optDouble("volume", Double.NaN);
            }
            post(true, "NIFTY LIVE QUOTE: HTTP "+code+(s.isEmpty()?"":" • "+s)+details+" • connection OK");
        } catch(Exception e) {
            post(true, "NIFTY LIVE QUOTE: HTTP "+code+" • response received");
        }
    }

    private String extractError(String primary, String secondary) {
        String body = primary == null || primary.trim().isEmpty() ? secondary : primary;
        if (body == null || body.trim().isEmpty()) return "no error body returned";
        try {
            JSONObject o=new JSONObject(body);
            String[] keys={"message","error","remark","reason","code"};
            for(String k:keys) {
                String v=o.optString(k,"");
                if(!v.isEmpty()) return k+"="+v;
            }
            JSONObject p=o.optJSONObject("payload");
            if(p!=null) {
                for(String k:keys) {
                    String v=p.optString(k,"");
                    if(!v.isEmpty()) return k+"="+v;
                }
            }
        } catch(Exception ignored) {}
        return body.length()>180 ? body.substring(0,180) : body;
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

    private void postNse(final boolean ok, final String msg){
        runOnUiThread(()->{
            String safe=msg.replace("\\","\\\\").replace("'","\\'");
            w.evaluateJavascript("window.nseResult("+ok+",'"+safe+"')",null);
        });
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