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
import org.json.JSONArray;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
        private volatile String lastPublicIp = "";
        private volatile String lastLocalIp = "";
        private volatile String lastMac = "";
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
            login(client, pin, totp, key, "", "", "");
        }

        @JavascriptInterface public void login(final String client, final String pin, final String totp, final String key,
                                               final String publicIp, final String localIp, final String mac) {
            if (client == null || pin == null || totp == null || key == null ||
                client.trim().isEmpty() || pin.trim().isEmpty() || totp.trim().isEmpty() || key.trim().isEmpty()) {
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
                    c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setDoOutput(true);
                    String resolvedLocal = empty(localIp) ? detectLocalIp() : localIp.trim();
                    String resolvedPublic = empty(publicIp) ? resolvePublicIp() : publicIp.trim();
                    String resolvedMac = empty(mac) ? detectMacAddress() : mac.trim();
                    if (empty(resolvedPublic) || empty(resolvedMac)) {
                        postAngel(false, "Login blocked • real Public IP and MAC are required. Enter the values registered/visible on your network.");
                        return;
                    }
                    lastLocalIp = resolvedLocal;
                    lastPublicIp = resolvedPublic;
                    lastMac = resolvedMac;
                    setAngelHeaders(c, key.trim(), resolvedPublic, resolvedLocal, resolvedMac);
                    OutputStream os = c.getOutputStream(); os.write(body.toString().getBytes("UTF-8")); os.close();
                    int code = c.getResponseCode();
                    String raw = read(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
                    JSONObject root = parseJson(raw);
                    boolean ok = code >= 200 && code < 300 && root != null && "true".equalsIgnoreCase(root.optString("status"));
                    if (ok) {
                        JSONObject data = root.optJSONObject("data");
                        jwtToken = data == null ? "" : data.optString("jwtToken", "");
                        refreshToken = data == null ? "" : data.optString("refreshToken", "");
                        feedToken = data == null ? "" : data.optString("feedToken", "");
                        clientCode = client.trim(); apiKey = key.trim(); lastPublicIp = resolvedPublic; lastLocalIp = resolvedLocal; lastMac = resolvedMac;
                        postAngel(true, "Login successful • JWT READY • Feed Token READY");
                        loadLive("NIFTY");
                    } else {
                        clearSession();
                        postAngel(false, "Login failed • HTTP "+code+" • "+extractError(raw));
                    }
                } catch (Exception e) {
                    clearSession(); postAngel(false, "Login error • "+e.getClass().getSimpleName()+" • "+safe(e.getMessage()));
                } finally { if (c != null) c.disconnect(); }
            });
        }

        @JavascriptInterface public void setSession(final String client, final String key, final String auth, final String feed) {
            if (client == null || key == null || auth == null || feed == null ||
                client.trim().isEmpty() || key.trim().isEmpty() || auth.trim().isEmpty() || feed.trim().isEmpty()) {
                postAngel(false, "Callback session incomplete • Client Code, API Key, auth token and feed token are required");
                return;
            }
            clientCode = client.trim(); apiKey = key.trim(); jwtToken = normalizeJwt(auth); feedToken = feed.trim();
            refreshToken = "";
            postAngel(true, "Angel One callback session imported • JWT READY • Feed Token READY");
        }

        @JavascriptInterface public void publisherLogin(final String key) {
            if (key == null || key.trim().isEmpty()) {
                postAngel(false, "Publisher login failed • SmartAPI API Key is required");
                return;
            }
            try {
                Uri.Builder b = Uri.parse("https://smartapi.angelone.in/publisher-login").buildUpon();
                b.appendQueryParameter("api_key", key.trim());
                b.appendQueryParameter("redirect_url", "nsealgosignal://angel-callback");
                b.appendQueryParameter("state", "nse-algo-signal");
                startActivity(new Intent(Intent.ACTION_VIEW, b.build()));
                postAngel(true, "SmartAPI publisher login opened • complete login and return through the registered callback");
            } catch (Exception e) {
                postAngel(false, "Publisher login error • "+safe(e.getMessage()));
            }
        }

        @JavascriptInterface public void loadLive(final String index) {
            if (jwtToken.isEmpty() || feedToken.isEmpty()) {
                postMarket(false, "LIVE", "Login to Angel One first");
                return;
            }
            selectedIndex = (index == null || index.trim().isEmpty()) ? "NIFTY" : index.trim().toUpperCase(Locale.US);
            postMarket(false, "SCRIPT", "Loading Angel One scrip master…");
            net.execute(this::loadScriptMasterAndSubscribe);
        }

        @JavascriptInterface public void stopLive() {
            closeMarketSocket();
            postMarket(true, "LIVE", "Live feed disconnected");
        }

        private void loadScriptMasterAndSubscribe() {
            try {
                String raw = httpGet("https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json");
                JSONArray all = new JSONArray(raw);
                String segment = ("SENSEX".equals(selectedIndex) || "BANKEX".equals(selectedIndex)) ? "BFO" : "NFO";
                Date today = new Date();
                SimpleDateFormat df = new SimpleDateFormat("ddMMMyyyy", Locale.ENGLISH);
                df.setLenient(false);
                Date nearest = null; String expiry = "";
                ArrayList<JSONObject> candidates = new ArrayList<>();
                for (int i=0;i<all.length();i++) {
                    JSONObject o=all.optJSONObject(i); if(o==null) continue;
                    if(!segment.equalsIgnoreCase(o.optString("exch_seg",""))) continue;
                    if(!"OPTIDX".equalsIgnoreCase(o.optString("instrumenttype",""))) continue;
                    if(!selectedIndex.equalsIgnoreCase(o.optString("name",""))) continue;
                    String ex=o.optString("expiry",""); if(ex.isEmpty()) continue;
                    Date d; try { d=df.parse(ex); } catch(Exception bad) { continue; }
                    if(d.before(today)) continue;
                    if(nearest==null || d.before(nearest)){nearest=d;expiry=ex;}
                    candidates.add(o);
                }
                if(nearest==null){postMarket(false,"SCRIPT","No active "+selectedIndex+" option contracts found");return;}
                liveRows.clear();
                JSONArray tokens=new JSONArray();
                int count=0;
                for(JSONObject o:candidates){
                    if(!expiry.equalsIgnoreCase(o.optString("expiry",""))) continue;
                    String token=o.optString("token","");
                    if(token.isEmpty()) continue;
                    LiveRow row=new LiveRow();
                    row.token=token; row.symbol=o.optString("symbol","");
                    row.strike=safeNumber(o.optString("strike","0"))/100.0;
                    row.type=row.symbol.endsWith("PE")?"PE":(row.symbol.endsWith("CE")?"CE":"");
                    row.expiry=expiry; liveRows.put(token,row); tokens.put(token);
                    if(++count>=800) break;
                }
                if(tokens.length()==0){postMarket(false,"SCRIPT","No tokens available for "+selectedIndex);return;}
                connectMarketSocket(tokens, segment.equals("BFO") ? 4 : 2);
                loadInitialQuotes(tokens, segment);
                postMarket(true,"SCRIPT","Loaded "+tokens.length()+" contracts • "+selectedIndex+" • expiry "+expiry);
            } catch(Exception e) {
                postMarket(false,"SCRIPT","Scrip master error • "+safe(e.getMessage()));
            }
        }

        private void loadInitialQuotes(JSONArray tokens, String segment) {
            final int batchSize=50;
            for(int start=0; start<tokens.length(); start+=batchSize){
                final int from=start, to=Math.min(start+batchSize,tokens.length());
                net.execute(() -> {
                    try {
                        JSONArray batch=new JSONArray();
                        for(int i=from;i<to;i++) batch.put(tokens.optString(i));
                        JSONObject ex=new JSONObject(); ex.put(segment,batch);
                        JSONObject body=new JSONObject(); body.put("mode","FULL"); body.put("exchangeTokens",ex);
                        HttpURLConnection c=(HttpURLConnection)new URL("https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/").openConnection();
                        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setDoOutput(true);
                        setAngelHeaders(c,apiKey,lastPublicIp,lastLocalIp,lastMac);
                        OutputStream os=c.getOutputStream(); os.write(body.toString().getBytes("UTF-8")); os.flush(); os.close();
                        int code=c.getResponseCode(); String raw=read(code>=400?c.getErrorStream():c.getInputStream());
                        JSONObject root=parseJson(raw);
                        if(code<200||code>=300){postMarket(false,"QUOTE","HTTP "+code+" • "+safe(raw));return;}
                        if(root==null||!root.optBoolean("status",false)){postMarket(false,"QUOTE","API rejected • "+safe(raw));return;}
                        JSONObject data=root.optJSONObject("data"); JSONArray fetched=data==null?null:data.optJSONArray("fetched"); int n=0;
                        if(fetched!=null) for(int i=0;i<fetched.length();i++){
                            JSONObject q=fetched.optJSONObject(i); if(q==null) continue;
                            String token=q.optString("symbolToken","");
                            LiveRow row=liveRows.get(token);
                            if(row!=null){ row.ltp=q.optDouble("ltp",row.ltp); row.volume=q.optLong("tradeVolume",row.volume); row.oi=q.optLong("opnInterest",row.oi); row.priceDelta=q.optDouble("netChange",row.priceDelta); postMarket(true,"TICK",row.toJson().toString()); n++; }
                        }
                        postMarket(true,"QUOTE","Received "+n+" contracts • batch "+from+"-"+(to-1));
                    }catch(Exception e){postMarket(false,"QUOTE","Exception • "+safe(e.getMessage()));}
                });
                try{Thread.sleep(1050);}catch(InterruptedException ignored){}
            }
        }

        private void connectMarketSocket(JSONArray tokens, int exchangeType) {
            closeMarketSocket();
            wsClient = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build();
            Request req = new Request.Builder()
                .url("wss://smartapisocket.angelone.in/smart-stream")
                .addHeader("Authorization",jwtToken)
                .addHeader("x-api-key",apiKey)
                .addHeader("x-client-code",clientCode)
                .addHeader("x-feed-token",feedToken).build();
            marketSocket = wsClient.newWebSocket(req,new WebSocketListener(){
                @Override public void onOpen(WebSocket ws, Response response) {
                    try {
                        JSONObject q=new JSONObject(); q.put("correlationID","nsealgo01"); q.put("action",1);
                        JSONObject p=new JSONObject(); p.put("mode",3);
                        JSONArray list=new JSONArray(); JSONObject group=new JSONObject();
                        group.put("exchangeType",exchangeType); group.put("tokens",tokens); list.put(group);
                        p.put("tokenList",list); q.put("params",p); ws.send(q.toString());
                        postMarket(true,"LIVE","WebSocket connected • SnapQuote subscription sent");
                        heartbeat.removeCallbacksAndMessages(null);
                        heartbeat.postDelayed(new Runnable(){@Override public void run(){
                            try{if(marketSocket!=null)marketSocket.send("ping");}catch(Exception ignored){}
                            if(marketSocket!=null)heartbeat.postDelayed(this,30000);
                        }},30000);
                    }catch(Exception e){postMarket(false,"LIVE","Subscription error • "+safe(e.getMessage()));}
                }
                @Override public void onMessage(WebSocket ws, okio.ByteString bytes){parseLivePacket(bytes.toByteArray());}
                @Override public void onMessage(WebSocket ws,String text){postMarket(false,"WS",text.length()>180?text.substring(0,180):text);}
                @Override public void onFailure(WebSocket ws,Throwable t,Response r){String extra=r==null?"":(" HTTP "+r.code()+" "+safe(r.message()));postMarket(false,"LIVE","WebSocket error • "+safe(t.getMessage())+extra);}
                @Override public void onClosed(WebSocket ws,int code,String reason){postMarket(false,"LIVE","WebSocket closed • "+code);}
            });
        }

        private void parseLivePacket(byte[] b) {
            try {
                if(b==null || b.length<51) return;
                int mode=b[0]&255; String token=readToken(b); LiveRow row=liveRows.get(token); if(row==null)return;
                int ltpRaw=leInt(b,43); double ltp=ltpRaw/100.0; double old=row.ltp;
                row.ltp=ltp; row.priceDelta=old==0?0:ltp-old;
                if(mode>=2 && b.length>=123){row.volume=leLong(b,67);}
                if(mode>=3 && b.length>=147){long oldOi=row.oi;row.oi=leLong(b,131);row.oiDelta=oldOi==0?0:row.oi-oldOi;}
                String js="window.angelMarketTick&&window.angelMarketTick("+JSONObject.quote(row.toJson())+");";
                runOnUiThread(()->{if(w!=null)w.evaluateJavascript(js,null);});
            }catch(Exception ignored){}
        }

        private void closeMarketSocket(){
            heartbeat.removeCallbacksAndMessages(null);
            try{if(marketSocket!=null)marketSocket.close(1000,"stop");}catch(Exception ignored){}
            marketSocket=null;
            try{if(wsClient!=null)wsClient.dispatcher().executorService().shutdown();}catch(Exception ignored){}
            wsClient=null;
        }

        private String readToken(byte[] b){int e=2;while(e<27&&b[e]!=0)e++;try{return new String(b,2,e-2,"UTF-8");}catch(Exception ex){return "";}}
        private int leInt(byte[] b,int p){int v=0;for(int i=0;i<4&&p+i<b.length;i++)v|=(b[p+i]&255)<<(8*i);return v;}
        private long leLong(byte[] b,int p){long v=0;for(int i=0;i<8&&p+i<b.length;i++)v|=((long)b[p+i]&255L)<<(8*i);return v;}
        private double safeNumber(String s){try{return Double.parseDouble(s);}catch(Exception e){return 0;}}
        private String httpGet(String u)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Accept","application/json");try{return read(c.getInputStream());}finally{c.disconnect();}}

        private void clearSession() {
            jwtToken = "";
            refreshToken = "";
            feedToken = "";
            clientCode = "";
            apiKey = "";
            lastPublicIp = "";
            lastLocalIp = "";
            lastMac = "";
        }

        private void setAngelHeaders(HttpURLConnection c, String key, String publicIp, String localIp, String mac) {
            c.setRequestProperty("Content-Type","application/json");
            c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("X-UserType","USER");
            c.setRequestProperty("X-SourceID","WEB");
            c.setRequestProperty("X-ClientLocalIP",empty(localIp)?detectLocalIp():localIp.trim());
            c.setRequestProperty("X-ClientPublicIP",empty(publicIp)?"0.0.0.0":publicIp.trim());
            c.setRequestProperty("X-MACAddress",empty(mac)?"00:00:00:00:00:00":mac.trim());
            c.setRequestProperty("X-PrivateKey",key);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) NSEAlgoSignal/1.1");
        }
        private String detectLocalIp(){
            try{
                Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
                while(en.hasMoreElements()){
                    NetworkInterface ni=en.nextElement();
                    Enumeration<InetAddress> ae=ni.getInetAddresses();
                    while(ae.hasMoreElements()){
                        InetAddress x=ae.nextElement();
                        if(!x.isLoopbackAddress() && x instanceof Inet4Address){
                            String ip=x.getHostAddress();
                            if(ip!=null && !ip.startsWith("127.")) return ip;
                        }
                    }
                }
            }catch(Exception ignored){}
            return "";
        }

        private String resolvePublicIp(){
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL("https://api.ipify.org").openConnection();
                c.setRequestMethod("GET"); c.setConnectTimeout(8000); c.setReadTimeout(8000);
                String ip=read(c.getInputStream()).trim();
                if(ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return ip;
            }catch(Exception ignored){} finally { if(c!=null)c.disconnect(); }
            return "";
        }

        private String detectMacAddress(){
            try{
                Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
                while(en.hasMoreElements()){
                    byte[] mac=en.nextElement().getHardwareAddress();
                    if(mac==null || mac.length!=6) continue;
                    StringBuilder z=new StringBuilder();
                    for(int i=0;i<mac.length;i++){ if(i>0)z.append(":"); z.append(String.format(Locale.US,"%02X",mac[i]&255)); }
                    String out=z.toString();
                    if(!out.equalsIgnoreCase("00:00:00:00:00:00")) return out;
                }
            }catch(Exception ignored){}
            return "";
        }

        private boolean empty(String s){return s==null||s.trim().isEmpty();}

        private void setAngelHeaders(HttpURLConnection c, String key) {
            setAngelHeaders(c,key,lastPublicIp,lastLocalIp,lastMac);
        }

        private String normalizeJwt(String v){ if(v==null)return ""; v=v.trim(); return v.regionMatches(true,0,"Bearer ",0,7)?v:"Bearer "+v; }

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

    static class LiveRow {
        String token="",symbol="",type="",expiry="";
        double strike,ltp,priceDelta; long oi,oiDelta,volume;
        String toJson(){
            JSONObject o=new JSONObject();
            try{o.put("token",token);o.put("symbol",symbol);o.put("type",type);o.put("expiry",expiry);
                o.put("strike",strike);o.put("ltp",ltp);o.put("oi",oi);o.put("oiDelta",oiDelta);
                o.put("priceDelta",priceDelta);o.put("volume",volume);}catch(Exception ignored){}
            return o.toString();
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
        return s == null ? "" : s.replace("\n", " ").replace("\r", " ");
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

    private void postMarket(final boolean ok, final String type, final String msg) {
        runOnUiThread(() -> {
            if(w==null)return;
            String s=msg==null?"":msg.replace("\\","\\\\").replace("'","\\'");
            w.evaluateJavascript("window.angelMarketStatus&&window.angelMarketStatus("+ok+","+JSONObject.quote(type)+",'"+s+"')",null);
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
