package com.webhtml.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

public class Background {

    private final Activity activity;
    private final FrameLayout rootLayout;
    private WebView lockScreenWebView;

    private float initialY = 0;
    private boolean isScreenBlack = false;     
    private boolean isLockScreenVisible = false; 

    private final Handler screenOffHandler = new Handler(Looper.getMainLooper());
    private final Runnable screenOffRunnable = new Runnable() {
        @Override
        public void run() {
            // Posle 5 sekundi neaktivnosti na lockscreen-u, vraća u totalnu crninu
            if (isLockScreenVisible) {
                setScreenBlack();
            }
        }
    };

    public Background(Activity activity, FrameLayout rootLayout) {
        this.activity = activity;
        this.rootLayout = rootLayout;
        initLockScreenOverlay();
    }

    private void initLockScreenOverlay() {
        lockScreenWebView = new WebView(activity);
        lockScreenWebView.setBackgroundColor(Color.BLACK);
        
        WebSettings webSettings = lockScreenWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        lockScreenWebView.setVisibility(View.GONE);
        lockScreenWebView.setAlpha(0f);

        // Most za dvoklik sa lockscreen-a nazad u crninu
        lockScreenWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void handleDoubleTap() {
                activity.runOnUiThread(() -> {
                    if (isLockScreenVisible) {
                        setScreenBlack();
                    }
                });
            }
        }, "ScreenBridge");

        // Touch listener za praćenje Swipe-Up pokreta za otključavanje
        lockScreenWebView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Ako je prevlačenje nagore veće od 150px, otključavamo aplikaciju
                    if (initialY - event.getY() > 150) {
                        hideLockScreen();
                        return true;
                    }
                    break;
            }
            return false;
        });

        rootLayout.addView(lockScreenWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    // Metoda koju poziva MainActivity na dvoklik iz index.html
    public void handleDoubleTapTrigger() {
        if (!isScreenBlack && !isLockScreenVisible) {
            // 1. Korak: Dvoklik iz aplikacije -> gasi ekran u potpunu crninu
            setScreenBlack();
        } else if (isScreenBlack && !isLockScreenVisible) {
            // 2. Korak: Dvoklik sa crnog ekrana -> PALI lockscreen.html
            showLockScreen();
        } else if (isLockScreenVisible) {
            // 3. Korak: Dvoklik sa lockscreen-a -> vraća u crninu
            setScreenBlack();
        }
    }

    private void setScreenBlack() {
        isScreenBlack = true;
        isLockScreenVisible = false;
        screenOffHandler.removeCallbacks(screenOffRunnable);

        if (lockScreenWebView != null) {
            lockScreenWebView.setVisibility(View.VISIBLE);
            lockScreenWebView.setBackgroundColor(Color.BLACK);
            lockScreenWebView.loadData("<html><body style='background:black;margin:0;'></body></html>", "text/html", "UTF-8");
            lockScreenWebView.setAlpha(1.0f);

            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }
    }

    private void showLockScreen() {
        isScreenBlack = false;
        isLockScreenVisible = true;

        if (lockScreenWebView != null) {
            lockScreenWebView.setVisibility(View.VISIBLE);
            lockScreenWebView.setBackgroundColor(Color.BLACK);
            
            // Učitavamo originalni lockscreen.html iz assets-a
            lockScreenWebView.loadUrl("file:///android_asset/lockscreen.html");
            
            lockScreenWebView.setAlpha(0f);
            lockScreenWebView.setScaleX(1.05f);
            lockScreenWebView.setScaleY(1.05f);
            
            lockScreenWebView.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(300)
                .start();
            
            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );

            resetScreenOffTimer();
        }
    }

    public void hideLockScreen() {
        if (lockScreenWebView != null) {
            screenOffHandler.removeCallbacks(screenOffRunnable);
            
            lockScreenWebView.animate()
                .alpha(0f)
                .scaleX(1.12f)
                .scaleY(1.12f)
                .setDuration(350)
                .withEndAction(() -> {
                    lockScreenWebView.setVisibility(View.GONE);
                    lockScreenWebView.setScaleX(1.0f);
                    lockScreenWebView.setScaleY(1.0f);
                    isScreenBlack = false;
                    isLockScreenVisible = false;
                })
                .start();
            
            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE
            );
        }
    }

    private void resetScreenOffTimer() {
        screenOffHandler.removeCallbacks(screenOffRunnable);
        screenOffHandler.postDelayed(screenOffRunnable, 5000); // 5 sekundi
    }
}
