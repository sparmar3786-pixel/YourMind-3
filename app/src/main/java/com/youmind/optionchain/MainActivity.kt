package com.youmind.optionchain
import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11,15,20)
        window.navigationBarColor = Color.rgb(11,15,20)
        val client=LocalAssetWebViewClient()
        val assetLoader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this)).build()
        client.assetLoader=assetLoader
        webView=WebView(this).apply {
            setBackgroundColor(Color.rgb(11,15,20))
            setLayerType(View.LAYER_TYPE_HARDWARE,null)
            webViewClient=client
            webChromeClient=object:WebChromeClient(){
                override fun onShowFileChooser(view:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean{
                    filePathCallback?.onReceiveValue(null);filePathCallback=callback;openDocuments.launch(arrayOf("*/*"));return true
                }
            }
        }
        setContentView(webView);configureWebView(webView)
        if(savedInstanceState==null)webView.loadUrl("https://appassets.androidplatform.net/assets/option-chain-analyzer.html") else webView.restoreState(savedInstanceState)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(webView.canGoBack())webView.goBack() else finish()}})
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view:WebView){
        view.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;databaseEnabled=false;allowFileAccess=false;allowContentAccess=true;allowFileAccessFromFileURLs=false;allowUniversalAccessFromFileURLs=false;cacheMode=WebSettings.LOAD_CACHE_ELSE_NETWORK;mediaPlaybackRequiresUserGesture=true;builtInZoomControls=false;displayZoomControls=false;setSupportZoom(false);loadWithOverviewMode=false;useWideViewPort=true}
        view.overScrollMode=View.OVER_SCROLL_NEVER;view.isVerticalScrollBarEnabled=false;view.isHorizontalScrollBarEnabled=false
    }
    override fun onSaveInstanceState(outState:Bundle){webView.saveState(outState);super.onSaveInstanceState(outState)}
    override fun onDestroy(){filePathCallback?.onReceiveValue(null);filePathCallback=null;webView.stopLoading();webView.webChromeClient=null;webView.webViewClient=WebViewClient();webView.destroy();super.onDestroy()}
    private class LocalAssetWebViewClient:WebViewClient(){
        var assetLoader:WebViewAssetLoader?=null
        override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?=assetLoader?.shouldInterceptRequest(request.url)?:super.shouldInterceptRequest(view,request)
        override fun shouldInterceptRequest(view:WebView,url:String):WebResourceResponse?=assetLoader?.shouldInterceptRequest(Uri.parse(url))?:super.shouldInterceptRequest(view,url)
    }
}