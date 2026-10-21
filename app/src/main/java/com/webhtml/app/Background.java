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
    private long lastTouchTime = 0;
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

        lockScreenWebView.setVisibility(View.GONE);
        lockScreenWebView.setAlpha(0f);

        // Touch listener za praćenje dvoklika i Swipe-Up pokreta
        lockScreenWebView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialY = event.getY();
                    
                    // Ako je ekran crn, drugi klik na dvoklik otvara Lock Screen interfejs
                    if (isScreenBlack && !isLockScreenVisible) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastTouchTime < 300) {
                            showLockScreen();
                            lastTouchTime = 0;
                            return true;
                        }
                        lastTouchTime = currentTime;
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    // Swipe up (prevlačenje nagore više od 150px) otključava aplikaciju
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

    // Poziva se iz MainActivity kada je korisnik u aplikaciji i napravi dvoklik
    public void handleDoubleTapTrigger() {
        if (!isScreenBlack && !isLockScreenVisible) {
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
            // Čist crni ekran
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
            
            // OVDE JE KOMPLETAN LOCK SCREEN INTERFEJS (HTML/CSS/JS SA SATOM) U JEDNOM STRINGU:
            String lockScreenHtml = "<(!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                    "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover'>" +
                    "<style>" +
                    "body { margin: 0; height: 100vh; background-color: #000000; font-family: Inter, Arial, sans-serif; color: #ffffff; display: flex; flex-direction: column; align-items: center; justify-content: space-between; padding: 40px 20px; box-sizing: border-box; user-select: none; overflow: hidden; opacity: 0; transition: opacity 0.3s ease-in-out; }" +
                    "body.visible { opacity: 1; }" +
                    ".top-section { display: flex; flex-direction: column; align-items: center; margin-top: 20px; gap: 15px; }" +
                    ".lock-icon { width: 24px; height: 24px; fill: #dadada; }" +
                    ".clock { font-size: 48px; font-weight: 300; letter-spacing: 1px; color: #f0f0f0; }" +
                    ".middle-section { display: flex; flex-direction: column; align-items: center; justify-content: center; }" +
                    ".bottom-section { margin-bottom: 20px; text-align: center; }" +
                    ".swipe-text { font-size: 14px; color: #888888; letter-spacing: 0.5px; animation: pulseText 2s infinite ease-in-out; }" +
                    "@keyframes pulseText { 0%, 100% { opacity: 0.4; } 50% { opacity: 1; } }" +
                    "</style></head><body>" +
                    "<div class='top-section'>" +
                    "<svg class='lock-icon' viewBox='0 0 24 24'><path d='M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z'/></svg>" +
                    "<div class='clock' id='clock'>12:00</div>" +
                    "</div>" +
                    "<div class='middle-section'></div>" +
                    "<div class='bottom-section'><div class='swipe-text'>Swipe up to unlock</div></div>" +
                    "<script>" +
                    "function updateClock() {" +
                    "  const now = new Date();" +
                    "  let h = now.getHours();" +
                    "  let m = now.getMinutes();" +
                    "  m = m < 10 ? '0' + m : m;" +
                    "  document.getElementById('clock').innerText = h + ':' + m;" +
                    "}" +
                    "setInterval(updateClock, 1000);" +
                    "updateClock();" +
                    "window.onload = function() { document.body.classList.add('visible'); };" +
                    "</script></body></html>";

            // Popravka za sintakse stringa (uklanjanje zagrade na početku ako je ostala)
            lockScreenHtml = lockScreenHtml.replace("<(!DOCTYPE", "<!DOCTYPE");

            lockScreenWebView.loadDataWithBaseURL(null, lockScreenHtml, "text/html", "UTF-8", null);
            
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
