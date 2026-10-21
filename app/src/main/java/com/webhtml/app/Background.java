package com.webhtml.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
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

        // U STARTU JE SKRIVEN I BEZ IKAKVOG SADRŽAJA (nema blica sata!)
        lockScreenWebView.setVisibility(View.GONE);
        lockScreenWebView.setAlpha(0f);

        String overlayHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover'>" +
                "<style>" +
                "body { margin: 0; height: 100vh; background-color: #000000; font-family: Inter, Arial, sans-serif; color: #ffffff; display: flex; flex-direction: column; align-items: center; justify-content: space-between; padding: 40px 20px; box-sizing: border-box; user-select: none; overflow: hidden; }" +
                ".content-container { opacity: 0; transition: opacity 0.3s ease-in-out; display: flex; flex-direction: column; align-items: center; justify-content: space-between; height: 100%; width: 100%; pointer-events: none; }" +
                "body.lockscreen-active .content-container { opacity: 1; pointer-events: auto; }" +
                ".top-section { display: flex; flex-direction: column; align-items: center; margin-top: 50px; gap: 20px; }" +
                ".lock-icon { width: 24px; height: 24px; fill: #dadada; }" +
                ".clock { font-size: 48px; font-weight: 300; letter-spacing: 1px; color: #f0f0f0; }" +
                ".middle-section { display: flex; flex-direction: column; align-items: center; justify-content: center; }" +
                ".bottom-section { margin-bottom: 20px; text-align: center; }" +
                ".swipe-text { font-size: 14px; color: #888888; letter-spacing: 0.5px; animation: pulseText 2s infinite ease-in-out; }" +
                "@keyframes pulseText { 0%, 100% { opacity: 0.4; } 50% { opacity: 1; } }" +
                "</style></head><body>" +
                "<div class='content-container' id='contentContainer'>" +
                "  <div class='top-section'>" +
                "    <svg class='lock-icon' viewBox='0 0 24 24'><path d='M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z'/></svg>" +
                "    <div class='clock' id='clock'>00:00</div>" +
                "  </div>" +
                "  <div class='middle-section'></div>" +
                "  <div class='bottom-section'><div class='swipe-text'>Swipe up to unlock</div></div>" +
                "</div>" +
                "<script>" +
                "function updateClock() {" +
                "  const now = new Date();" +
                "  let h = String(now.getHours()).padStart(2, '0');" +
                "  let m = String(now.getMinutes()).padStart(2, '0');" +
                "  document.getElementById('clock').innerText = h + ':' + m;" +
                "}" +
                "setInterval(updateClock, 1000);" +
                "updateClock();" +
                "window.setMode = function(isLock) {" +
                "  if(isLock) { document.body.classList.add('lockscreen-active'); }" +
                "  else { document.body.classList.remove('lockscreen-active'); }" +
                "}" +
                "</script></body></html>";

        lockScreenWebView.loadDataWithBaseURL(null, overlayHtml, "text/html", "UTF-8", null);

        lockScreenWebView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialY = event.getY();
                    
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastTouchTime < 300) {
                        if (isScreenBlack && !isLockScreenVisible) {
                            showLockScreen();
                            lastTouchTime = 0;
                            return true;
                        } else if (isLockScreenVisible) {
                            setScreenBlack();
                            lastTouchTime = 0;
                            return true;
                        }
                    }
                    lastTouchTime = currentTime;
                    return true;

                case MotionEvent.ACTION_MOVE:
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
            // 1. PRVO SAKRIVAMO SISTEMSKE BAROVE BEZ TRZANJA
            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );

            // 2. ISKLJUČUJEMO LOCKSCREEN INTERFEJS PREKO JS-a JOŠ DOK JE PROVIDORAN/SAKRIVEN
            lockScreenWebView.evaluateJavascript("window.setMode(false);", null);
            lockScreenWebView.setAlpha(1.0f);
            lockScreenWebView.setScaleX(1.0f);
            lockScreenWebView.setScaleY(1.0f);
            
            // 3. TEK TADA GA PALIMO - EKRAN JE ODMAH ČISTO CRN BEZ BLICA!
            lockScreenWebView.setVisibility(View.VISIBLE);
        }
    }

    private void showLockScreen() {
        isScreenBlack = false;
        isLockScreenVisible = true;

        if (lockScreenWebView != null) {
            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );

            lockScreenWebView.setVisibility(View.VISIBLE);
            lockScreenWebView.setAlpha(1.0f);
            lockScreenWebView.setScaleX(1.0f);
            lockScreenWebView.setScaleY(1.0f);
            lockScreenWebView.evaluateJavascript("window.setMode(true);", null);
            
            resetScreenOffTimer();
        }
    }

    public void hideLockScreen() {
        if (lockScreenWebView != null) {
            screenOffHandler.removeCallbacks(screenOffRunnable);
            
            lockScreenWebView.setPivotX(lockScreenWebView.getWidth() / 2f);
            lockScreenWebView.setPivotY(lockScreenWebView.getHeight() / 2f);

            lockScreenWebView.animate()
                .alpha(0f)
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(300)
                .withEndAction(() -> {
                    lockScreenWebView.setVisibility(View.GONE);
                    lockScreenWebView.setAlpha(1.0f);
                    lockScreenWebView.setScaleX(1.0f);
                    lockScreenWebView.setScaleY(1.0f);
                    lockScreenWebView.evaluateJavascript("window.setMode(false);", null);
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
        screenOffHandler.postDelayed(screenOffRunnable, 5000);
    }
}
