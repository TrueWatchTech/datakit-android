package com.ft;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

/** Bounded, non-blocking first-frame delay for manual cold-launch testing only. */
final class DebugLaunchFrameGate {
    private static final int MAX_DELAY_MS = 3000;
    private static final String PREFERENCES = "launch_router_scenario";
    private static final String DELAY_ENABLED = "delay_first_home_frame";
    // Recreating or reopening the home Activity must not restart the cold-launch delay.
    private static boolean firstHomeCreated;
    private static long readyAtMs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View root;
    private final ViewTreeObserver.OnPreDrawListener gate = this::allowDraw;
    private final ViewTreeObserver.OnDrawListener drawObserver = this::onDraw;
    private final Runnable release = this::releaseGate;
    private boolean observingDraw = true;
    private boolean waiting;

    DebugLaunchFrameGate(Activity activity) {
        if (!firstHomeCreated) {
            firstHomeCreated = true;
            int delayMs = activity.getIntent().getIntExtra(
                    SplashActivity.EXTRA_FIRST_FRAME_DELAY_MS, isEnabled(activity) ? MAX_DELAY_MS : 0);
            delayMs = Math.max(0, Math.min(MAX_DELAY_MS, delayMs));
            readyAtMs = SystemClock.elapsedRealtime() + delayMs;
            SplashActivity.logEvent("home first-frame delayMs=" + delayMs);
        }
        root = activity.findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnDrawListener(drawObserver);
        long remainingMs = readyAtMs - SystemClock.elapsedRealtime();
        if (remainingMs > 0) {
            waiting = true;
            root.getViewTreeObserver().addOnPreDrawListener(gate);
            handler.postDelayed(release, remainingMs);
        }
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(DELAY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(DELAY_ENABLED, enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private boolean allowDraw() {
        if (SystemClock.elapsedRealtime() < readyAtMs) return false;
        releaseGate();
        return true;
    }

    private void releaseGate() {
        if (!waiting) return;
        waiting = false;
        handler.removeCallbacks(release);
        if (root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnPreDrawListener(gate);
        }
        root.invalidate();
        SplashActivity.logEvent("home initialization ready");
    }

    private void onDraw() {
        if (!observingDraw) return;
        observingDraw = false;
        SplashActivity.logEvent("home first draw");
        // OnDrawListener removal must happen outside the draw traversal.
        handler.post(this::removeDrawObserver);
    }

    private void removeDrawObserver() {
        if (root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnDrawListener(drawObserver);
        }
    }

    void close() {
        releaseGate();
        handler.removeCallbacksAndMessages(null);
        removeDrawObserver();
    }
}
