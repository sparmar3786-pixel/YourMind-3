package com.sachin.nsealgotrading;
import android.app.Activity;import android.os.Bundle;import android.webkit.*;import android.net.Uri;import androidx.webkit.WebViewAssetLoader;
public class MainActivity extends Activity{
 WebView w;
 @Override public void onCreate(Bundle b){super.onCreate(b);w=new WebView(this);WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);WebViewAssetLoader l=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();w.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView v,String u){return l.shouldInterceptRequest(Uri.parse(u));}});setContentView(w);w.loadUrl("https://appassets.androidplatform.net/assets/algo_dashboard.html");}
 @Override public void onBackPressed(){if(w.canGoBack())w.goBack();else super.onBackPressed();}
}