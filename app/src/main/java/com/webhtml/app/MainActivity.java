package com.webhtml.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // --- DINAMIČKE PROMENLJIVE IZ appConfig.js ---
    private List<String> externalDomains = new ArrayList<>();
    private String startURL = "";
    private String broAgent = "";
    private boolean portrait = true;
    private boolean screenOn = false;
    private boolean allowZoom = false; 
    private boolean forceDark = false;
    private boolean backgroundOpt = false; // <-- Nova promenljiva

    private WebView webView;
    private android.view.View dimOverlayView; // <-- Prekrivač za gašenje ekrana
    private float initialY = 0; // Za praćenje swipe-up pokreta
    
    private ValueCallback<Uri[]> uploadMessage;
    private final static int FILE_CHOOSER_RESULT_CODE = 1;
    private DownloadHelper downloadHelper;

    private final static int LOCATION_PERMISSION_REQUEST_CODE = 100;
    private final static int MEDIA_PERMISSION_REQUEST_CODE = 101;
    
    private String pendingGeolocationOrigin;
    private GeolocationPermissions.Callback pendingGeolocationCallback;
    private PermissionRequest pendingPermissionRequest;

    @SuppressLint({"SetJavaScriptEnabled", "QueryPermissionsNeeded"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        loadConfigFromAssets();

        if (forceDark) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            );
        }

        super.onCreate(savedInstanceState);

        if (portrait) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        if (screenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#070707"));

        // Umesto samo setContentView(webView), pravimo FrameLayout da bismo stavili dimOverlay preko
        android.widget.FrameLayout rootLayout = new android.widget.FrameLayout(this);
        rootLayout.addView(webView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        // Kreiranje prekrivača za lažno gašenje ekrana
        dimOverlayView = new android.view.View(this);
        dimOverlayView.setBackgroundColor(Color.BLACK);
        dimOverlayView.setAlpha(0f);
        dimOverlayView.setVisibility(android.view.View.GONE);

        // Dodirni gestovi na crnom ekranu (Swipe Up za otključavanje)
        dimOverlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    initialY = event.getY();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    // Ako korisnik povuče nagore više od 150 piksela, vraćamo ekran u normalu
                    if (initialY - event.getY() > 150) {
                        hideDimOverlay();
                        return true;
                    }
                    break;
            }
            return false;
        });

        rootLayout.addView(dimOverlayView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);

        downloadHelper = new DownloadHelper(this);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setSupportMultipleWindows(false);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        webSettings.setSupportZoom(allowZoom);
        webSettings.setBuiltInZoomControls(allowZoom);
        webSettings.setDisplayZoomControls(false);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);

        if (broAgent != null && !broAgent.trim().isEmpty()) {
            webSettings.setUserAgentString(broAgent);
        }

        if (forceDark) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, true);
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @SuppressWarnings("deprecation")
                boolean fallback = true;
                WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_ON);
            }
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(downloadHelper, "AndroidBridge");

        // Ako je background opcija uključena, izlažemo metod za gašenje ekrana ka JS-u
        if (backgroundOpt) {
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void triggerScreenOff() {
                    runOnUiThread(() -> showDimOverlay());
                }
            }, "ScreenBridge");
        }

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // Ostali client i chrome client listeneri ostaju isti...
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String rawSuggestedName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
            String extension = "txt";
            if (rawSuggestedName != null && rawSuggestedName.contains(".")) {
                extension = rawSuggestedName.substring(rawSuggestedName.lastIndexOf(".") + 1);
            }
            final String suggestedFileName = "download." + extension;

            if (url.startsWith("blob:") || url.startsWith("data:")) {
                String js = "(function() {" +
                        "  fetch('" + url + "')" +
                        "    .then(res => res.blob())" +
                        "    .then(blob => {" +
                        "      var reader = new FileReader();" +
                        "      reader.onload = function() {" +
                        "        window.AndroidBridge.cacheData(reader.result);" +
                        "      };" +
                        "      reader.readAsDataURL(blob);" +
                        "    }).catch(err => window.AndroidBridge.cacheData('ERROR'));" +
                        "})();";
                webView.evaluateJavascript(js, null);

                webView.postDelayed(() -> DialogHelper.showNativeDownloadDialog(
                        MainActivity.this, suggestedFileName, url, mimetype, true,
                        finalName -> downloadHelper.executeDownloadTask(finalName, url, mimetype, true)
                ), 300);
            } else {
                DialogHelper.showNativeDownloadDialog(
                        MainActivity.this, suggestedFileName, url, mimetype, false,
                        finalName -> downloadHelper.executeDownloadTask(finalName, url, mimetype, false)
                );
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrlLoading(view, request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlLoading(view, url);
            }

            private boolean handleUrlLoading(WebView view, String url) {
                if (isExternalDomain(url)) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        view.getContext().startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        return true;
                    }
                }
                if (url.startsWith("file://") || url.startsWith("http://") || url.startsWith("https://")) {
                    return false; 
                }
                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            intent.addCategory(Intent.CATEGORY_BROWSABLE);
                            try {
                                startActivity(intent);
                                return true;
                            } catch (Exception e) {
                                String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                                if (fallbackUrl != null) {
                                    view.loadUrl(fallbackUrl);
                                    return true;
                                }
                            }
                        }
                    } catch (Exception e) {}
                    return true;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    view.getContext().startActivity(intent);
                    return true;
                } catch (Exception e) {
                    return true;
                }
            }

            private boolean isExternalDomain(String url) {
                if (externalDomains == null || externalDomains.isEmpty()) return false;
                try {
                    Uri uri = Uri.parse(url);
                    String host = uri.getHost();
                    if (host == null) return false;
                    for (String domainEntry : externalDomains) {
                        String cleanDomain = domainEntry.trim().toLowerCase();
                        if (cleanDomain.startsWith("https://")) cleanDomain = cleanDomain.substring(8);
                        if (cleanDomain.startsWith("http://")) cleanDomain = cleanDomain.substring(7);
                        if (cleanDomain.endsWith("/")) cleanDomain = cleanDomain.substring(0, cleanDomain.length() - 1);
                        if (host.equalsIgnoreCase(cleanDomain) || host.toLowerCase().endsWith("." + cleanDomain)) {
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("file://")) {
                    view.evaluateJavascript("window.webhtml = true;", null);
                }
            }
        });

        // Pokretanje aplikacije
        Intent intent = getIntent();
        Uri data = intent != null ? intent.getData() : null;
        if (data != null) {
            String targetUrl = data.getQueryParameter("url");
            if (targetUrl != null && !targetUrl.isEmpty()) {
                webView.loadUrl(targetUrl);
            } else {
                webView.loadUrl(data.toString());
            }
        } else {
            if (startURL != null && !startURL.trim().isEmpty()) {
                webView.loadUrl(startURL);
            } else {
                webView.loadUrl("file:///android_asset/index.html");
            }
        }
    }

    private void showDimOverlay() {
        if (dimOverlayView != null) {
            dimOverlayView.setVisibility(android.view.View.VISIBLE);
            dimOverlayView.animate().alpha(1.0f).setDuration(250).start();
            
            // Sakrivamo i navigacioni/status bar da ekran bude potpuno crn preko celog telefona
            getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }
    }

    private void hideDimOverlay() {
        if (dimOverlayView != null) {
            dimOverlayView.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                dimOverlayView.setVisibility(android.view.View.GONE);
            }).start();
        }
    }

    private void loadConfigFromAssets() {
        try {
            InputStream inputStream = getAssets().open("appConfig.js");
            int size = inputStream.available();
            byte[] buffer = new byte[size];
            inputStream.read(buffer);
            inputStream.close();
            String jsContent = new String(buffer, "UTF-8");

            portrait = parseBoolean(jsContent, "portrait", true);
            screenOn = parseBoolean(jsContent, "screenOn", false);
            allowZoom = parseBoolean(jsContent, "allowZoom", false); 
            forceDark = parseBoolean(jsContent, "forceDark", false);
            backgroundOpt = parseBoolean(jsContent, "background", false); // <-- Čitanje opcije
            startURL = parseString(jsContent, "startURL", "");
            broAgent = parseString(jsContent, "broAgent", "");
            externalDomains = parseArray(jsContent, "external");
        } catch (Exception e) {}
    }

    private boolean parseBoolean(String content, String key, boolean defaultValue) {
        try {
            Pattern pattern = Pattern.compile(key + "\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return Boolean.parseBoolean(matcher.group(1));
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private String parseString(String content, String key, String defaultValue) {
        try {
            Pattern pattern = Pattern.compile(key + "\\s*:\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private List<String> parseArray(String content, String key) {
        List<String> list = new ArrayList<>();
        try {
            Pattern pattern = Pattern.compile(key + "\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                String arrayContent = matcher.group(1).trim();
                if (!arrayContent.isEmpty()) {
                    Pattern itemPattern = Pattern.compile("[\"']([^\"']+)[\"']");
                    Matcher itemMatcher = itemPattern.matcher(arrayContent);
                    while (itemMatcher.find()) {
                        list.add(itemMatcher.group(1));
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
