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
            // 4. Ako ne diraš nakon 5 sekundi, gasi se ekran (vraća u čistu crninu)
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

        // HTML početno podešen da bude skriven (crn ekran) dok se ne pozove setMode(true)
        String overlayHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover'>" +
                "<style>" +
                "body { margin: 0; height: 100vh; background-color: #000000; font-family: Inter, Arial, sans-serif; color: #ffffff; display: flex; flex-direction: column; align-items: center; justify-content: space-between; padding: 40px 20px; box-sizing: border-box; user-select: none; overflow: hidden; }" +
                ".content-container { opacity: 0; transition: opacity 0.3s ease-in-out; display: flex; flex-direction: column; align-items: center; justify-content: space-between; height: 100%; width: 100%; pointer-events: none; }" +
                "body.lockscreen-active .content-container { opacity: 1; pointer-events: auto; }" +
                ".top-section { display: flex; flex-direction: column; align-items: center; margin-top: 20px; gap: 15px; }" +
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
                "    <div class='clock' id='clock'>12:00</div>" +
                "  </div>" +
                "  <div class='middle-section'></div>" +
                "  <div class='bottom-section'><div class='swipe-text'>Swipe up to unlock</div></div>" +
                "</div>" +
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
                "window.setMode = function(isLock) {" +
                "  if(isLock) { document.body.classList.add('lockscreen-active'); }" +
                "  else { document.body.classList.remove('lockscreen-active'); }" +
                "}" +
                "</script></body></html>";

        lockScreenWebView.loadDataWithBaseURL(null, overlayHtml, "text/html", "UTF-8", null);

        // Touch listener za praćenje gestova (3. Dvoklik na crnom ekranu i 5. SwipeUp)
        lockScreenWebView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialY = event.getY();
                    
                    // 3. Dvoklik na crnom ekranu -> prikaži lockscreen ponovo
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
                    // 5. SwipeUp vraća u aplikaciju - bilo da je crn ekran ili lockscreen interfejs
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

    // 1. Dvoklik iz aplikacije
    public void handleDoubleTapTrigger() {
        if (!isScreenBlack && !isLockScreenVisible) {
            setScreenBlack();
        }
    }

    // 2. Zatamni ekran odmah (čista crnina bez prikaza sata)
    private void setScreenBlack() {
        isScreenBlack = true;
        isLockScreenVisible = false;
        screenOffHandler.removeCallbacks(screenOffRunnable);

        if (lockScreenWebView != null) {
            lockScreenWebView.setVisibility(View.VISIBLE);
            lockScreenWebView.setAlpha(1.0f);
            // Osiguravamo da su elementi lockscreen-a sakriveni
            lockScreenWebView.evaluateJavascript("window.setMode(false);", null);

            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }
    }

    // 3. Prikaz lockscreen interfejsa sa satom
    private void showLockScreen() {
        isScreenBlack = false;
        isLockScreenVisible = true;

        if (lockScreenWebView != null) {
            lockScreenWebView.setVisibility(View.VISIBLE);
            lockScreenWebView.setAlpha(1.0f);
            // Palimo vidljivost elemenata lockscreen-a preko JS-a
            lockScreenWebView.evaluateJavascript("window.setMode(true);", null);
            
            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );

            // 4. Pokrećemo tajmer od 5 sekundi neaktivnosti
            resetScreenOffTimer();
        }
    }

    // 5. & 6. Vraćanje u aplikaciju (SwipeUp ili 6. Fizičko zaključavanje via onPause u MainActivity)
    public void hideLockScreen() {
        if (lockScreenWebView != null) {
            screenOffHandler.removeCallbacks(screenOffRunnable);
            
            lockScreenWebView.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> {
                    lockScreenWebView.setVisibility(View.GONE);
                    lockScreenWebView.setAlpha(1.0f);
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
        screenOffHandler.postDelayed(screenOffRunnable, 5000); // 5 sekundi
    }
}
