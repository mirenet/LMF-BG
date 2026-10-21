package com.webhtml.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private boolean backgroundOpt = false;

    private WebView webView;
    private WebView lockScreenWebView; // <-- Prekrivač je sada WebView za lockscreen.html
    private android.widget.FrameLayout rootLayout;
    
    private float initialY = 0;
    private final Handler screenOffHandler = new Handler(Looper.getMainLooper());
    private final Runnable screenOffRunnable = new Runnable() {
        @Override
        public void run() {
            // Posle 5 sekundi neaktivnosti na lockscreen-u, gasimo ekran u totalnu crninu
            if (lockScreenWebView != null && lockScreenWebView.getVisibility() == android.view.View.VISIBLE) {
                lockScreenWebView.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                    lockScreenWebView.setBackgroundColor(Color.BLACK);
                }).start();
            }
        }
    };
    
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
        
        rootLayout = new android.widget.FrameLayout(this);
        rootLayout.addView(webView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        // --- KREIRANJE LOCKSCREEN WEBVIEW-a ---
        lockScreenWebView = new WebView(this);
        lockScreenWebView.setBackgroundColor(Color.TRANSPARENT);
        lockScreenWebView.getSettings().setJavaScriptEnabled(true);
        lockScreenWebView.setVisibility(android.view.View.GONE);
        lockScreenWebView.setAlpha(0f);
        lockScreenWebView.loadUrl("file:///android_asset/lockscreen.html");

        // Praćenje gestova (Tap za buđenje ako se ugasio posle 5s, ili Swipe Up za otključavanje)
        lockScreenWebView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    initialY = event.getY();
                    // Ako je ekran bio ugašen (alpha 0), tap ga budi
                    if (lockScreenWebView.getAlpha() == 0f) {
                        lockScreenWebView.setBackgroundColor(Color.BLACK);
                        lockScreenWebView.animate().alpha(1f).setDuration(200).start();
                        resetScreenOffTimer();
                        return true;
                    }
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    // Swipe up (prevlačenje nagore više od 150 piksela otključava aplikaciju)
                    if (initialY - event.getY() > 150) {
                        hideLockScreen();
                        return true;
                    }
                    break;
            }
            return false;
        });

        rootLayout.addView(lockScreenWebView, new android.widget.FrameLayout.LayoutParams(
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

        if (backgroundOpt) {
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void triggerScreenOff() {
                    runOnUiThread(() -> showLockScreen());
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
            
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String urlStr = request.getUrl().toString();
                if (!urlStr.contains("#")) {
                    return super.shouldInterceptRequest(view, request);
                }
                try {
                    String[] mainParts = urlStr.split("#", 2);
                    String cleanUrlStr = mainParts[0];
                    String fragment = mainParts[1];
                    String customUa = "";
                    if (fragment.contains("ua=")) {
                        try {
                            String[] uaParts = fragment.split("ua=");
                            if (uaParts.length > 1) {
                                customUa = URLDecoder.decode(uaParts[1].split("&")[0], "UTF-8");
                            }
                        } catch (Exception ignored) {}
                    }
                    String finalUa;
                    if (!customUa.isEmpty() && customUa.length() < 300) {
                        finalUa = customUa;
                    } else {
                        finalUa = (!broAgent.isEmpty()) ? broAgent : webView.getSettings().getUserAgentString();
                    }
                    String lowerUa = finalUa.toLowerCase();
                    boolean isHtmlMode = lowerUa.contains("mozilla");

                    URL url = new URL(cleanUrlStr);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("User-Agent", finalUa);
                    if (isHtmlMode) {
                        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
                        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                        connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
                        connection.setRequestProperty("Sec-Fetch-Site", "none");
                        connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
                        connection.setRequestProperty("Sec-Fetch-User", "?1");
                        connection.setRequestProperty("Sec-Fetch-Dest", "document");
                    } else {
                        connection.setRequestProperty("Accept", "application/json, application/ld+json, text/plain, */*");
                    }
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    InputStream inputStream = connection.getInputStream();
                    String mimeType = connection.getContentType();
                    if (mimeType == null) {
                        mimeType = isHtmlMode ? "text/html; charset=UTF-8" : "application/json; charset=UTF-8";
                    }
                    String encoding = "UTF-8";
                    if (mimeType.contains("charset=")) {
                        try {
                            encoding = mimeType.split("charset=")[1].split(";")[0].trim();
                        } catch (Exception ignored) {}
                    }
                    return new WebResourceResponse(mimeType.split(";")[0].trim(), encoding, inputStream);
                } catch (Exception e) {}
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                DialogHelper.showCustomAlert(MainActivity.this, message, result);
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(MainActivity.this, 
                        android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    androidx.core.app.ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION},
                            LOCATION_PERMISSION_REQUEST_CODE);
                    pendingGeolocationOrigin = origin;
                    pendingGeolocationCallback = callback;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String[] requestedResources = request.getResources();
                boolean needsCamera = false;
                boolean needsAudio = false;
                for (String resource : requestedResources) {
                    if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needsCamera = true;
                    if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needsAudio = true;
                }
                java.util.ArrayList<String> permissionsToRequest = new java.util.ArrayList<>();
                if (needsCamera && androidx.core.content.ContextCompat.checkSelfPermission(MainActivity.this, 
                        android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(android.Manifest.permission.CAMERA);
                }
                if (needsAudio && androidx.core.content.ContextCompat.checkSelfPermission(MainActivity.this, 
                        android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO);
                }
                if (!permissionsToRequest.isEmpty()) {
                    pendingPermissionRequest = request;
                    androidx.core.app.ActivityCompat.requestPermissions(MainActivity.this,
                            permissionsToRequest.toArray(new String[0]),
                            MEDIA_PERMISSION_REQUEST_CODE);
                } else {
                    request.grant(requestedResources);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    uploadMessage = null;
                    return false;
                }
                return true;
            }
        });

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

    // --- METODE ZA UPRAVLJANJE LOCKSCREEN-om (SAMSUNG ONE UI STYLE) ---
    private void showLockScreen() {
        if (lockScreenWebView != null) {
            lockScreenWebView.setVisibility(android.view.View.VISIBLE);
            lockScreenWebView.setBackgroundColor(Color.BLACK);
            lockScreenWebView.setAlpha(0f);
            lockScreenWebView.setScaleX(1.05f);
            lockScreenWebView.setScaleY(1.05f);
            
            lockScreenWebView.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(300)
                .start();
            
            // Sakrivamo status i navigation bar da bude čist crn ekran
            getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );

            // Pokrećemo tajmer od 5 sekundi za gašenje ekrana u potpunu crninu
            resetScreenOffTimer();
        }
    }

    private void hideLockScreen() {
        if (lockScreenWebView != null) {
            screenOffHandler.removeCallbacks(screenOffRunnable);
            
            // One UI animacija otključavanja (blago uvećanje i fade-out)
            lockScreenWebView.animate()
                .alpha(0f)
                .scaleX(1.12f)
                .scaleY(1.12f)
                .setDuration(350)
                .withEndAction(() -> {
                    lockScreenWebView.setVisibility(android.view.View.GONE);
                    lockScreenWebView.setScaleX(1.0f);
                    lockScreenWebView.setScaleY(1.0f);
                })
                .start();
            
            // Vraćamo status i navigation bar na glavnom ekranu aplikacije
            getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_VISIBLE
            );
        }
    }

    private void resetScreenOffTimer() {
        screenOffHandler.removeCallbacks(screenOffRunnable);
        screenOffHandler.postDelayed(screenOffRunnable, 5000); // 5 sekundi
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
            backgroundOpt = parseBoolean(jsContent, "background", false);
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (pendingGeolocationCallback != null && pendingGeolocationOrigin != null) {
                boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
                pendingGeolocationCallback.invoke(pendingGeolocationOrigin, granted, false);
                pendingGeolocationCallback = null;
                pendingGeolocationOrigin = null;
            }
        } else if (requestCode == MEDIA_PERMISSION_REQUEST_CODE) {
            if (pendingPermissionRequest != null) {
                boolean allGranted = true;
                for (int res : grantResults) {
                    if (res != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                } else {
                    pendingPermissionRequest.deny();
                }
                pendingPermissionRequest = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        screenOffHandler.removeCallbacks(screenOffRunnable);
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
