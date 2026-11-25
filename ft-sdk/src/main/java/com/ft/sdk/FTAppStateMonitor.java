package com.ft.sdk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;

import com.ft.sdk.garble.utils.Constants;
import com.ft.sdk.garble.utils.LogUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Visibility of this process for launch actions, RUM history and collection policy. */
final class FTAppStateMonitor implements ComponentCallbacks2 {
    private static final long CHECK_DELAY_MS = 250;
    private static final long MAX_CHECK_DELAY_MS = 4000;
    private static final long PRE_ACTIVITY_MAX_CHECK_DELAY_MS = 10000;
    private static final int MAX_BACKGROUND_CHECKS = 8;
    private static final class Holder {
        static final FTAppStateMonitor INSTANCE = new FTAppStateMonitor();
    }
    static FTAppStateMonitor get() { return Holder.INSTANCE; }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Activity> started = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Activity> resumed = Collections.newSetFromMap(new WeakHashMap<>());
    private Application application;
    private boolean observedActivity;
    private boolean localUiStopped;
    private long checkDelay = CHECK_DELAY_MS;
    private int checkAttempts;
    private final Runnable check = this::checkBackground;
    private volatile String visibility = AppStateHistory.UNKNOWN;
    private Runnable backgroundListener;
    private FTAppStartCounter launchCounter;
    void setLaunchCounter(FTAppStartCounter counter) { launchCounter = counter; }

    void setBackgroundListener(Runnable listener) { backgroundListener = listener; }
    boolean isForeground() { return AppStateHistory.FOREGROUND.equals(visibility); }
    int startedCount() { return started.size(); }
    List<Activity> snapshotStarted() { return new ArrayList<>(started); }
    List<Activity> snapshotResumed() { return new ArrayList<>(resumed); }
    boolean isStarted(Activity activity) { return activity != null && started.contains(activity); }
    boolean isResumed(Activity activity) { return activity != null && resumed.contains(activity); }
    void onBackgroundOrigin() { background(); }

    void restoreForeground(List<Activity> startedActivities, List<Activity> resumedActivities) {
        if ((startedActivities == null || startedActivities.isEmpty())
                && (resumedActivities == null || resumedActivities.isEmpty())) {
            return;
        }
        if (startedActivities != null) {
            for (Activity activity : startedActivities) {
                if (activity != null) {
                    observe(activity);
                    started.add(activity);
                }
            }
        }
        if (resumedActivities != null) {
            for (Activity activity : resumedActivities) {
                if (activity != null) {
                    observe(activity);
                    started.add(activity);
                    resumed.add(activity);
                }
            }
        }
        if (!started.isEmpty()) {
            localUiStopped = false;
            foreground();
            if (!resumed.isEmpty()) {
                handler.removeCallbacks(check);
                resetBackgroundCheck();
            }
        }
    }

    private void foreground() {
        if (AppStateHistory.FOREGROUND.equals(visibility)) return;
        FTActivityManager.get().appForeground();
        visibility = AppStateHistory.FOREGROUND;
        LogUtils.d(Constants.LOG_TAG_PREFIX + "AppStateMonitor", "state:foreground");
        launchCounter.onAppForeground();
        com.ft.sdk.garble.utils.FpsUtils.onAppForeground();
    }

    private void background() {
        if (AppStateHistory.BACKGROUND.equals(visibility)) return;
        long boundary = FTActivityManager.get().appBackGround();
        visibility = AppStateHistory.BACKGROUND;
        LogUtils.d(Constants.LOG_TAG_PREFIX + "AppStateMonitor", "state:background,boundary:" + boundary);
        launchCounter.onAppBackground();
        if (backgroundListener != null) backgroundListener.run();
        if (FTRUMConfigManager.get().isRumEnable()) {
            FTRUMInnerManager.get().onAppBackground(boundary);
        }
        com.ft.sdk.garble.utils.FpsUtils.onAppBackground();
    }

    private FTAppStateMonitor() { }

    void onApplicationCreated(Application app) {
        if (application == null && app != null) {
            application = app;
            application.registerComponentCallbacks(this);
        }
    }

    void onApplicationReady() {
        if (!observedActivity && launchCounter.isWaitingForFirstActivity()) {
            resetBackgroundCheck();
            checkBackground();
        }
    }

    void onFirstActivityObserved() {
        if (!launchCounter.isLaunchStatePending()) return;
        observedActivity = true;
        handler.removeCallbacks(check);
        resetBackgroundCheck();
    }

    private void observe(Activity activity) {
        onApplicationCreated(activity.getApplication());
        observedActivity = true;
    }

    void onCreated(Activity activity) {
        observe(activity);
        // A router may already have queued its replacement. Do not treat creation as an exit.
        localUiStopped = false;
    }

    void onStarted(Activity activity) {
        observe(activity);
        started.add(activity);
        localUiStopped = false;
        foreground();
    }

    void onResumed(Activity activity) {
        observe(activity);
        started.add(activity);
        resumed.add(activity);
        localUiStopped = false;
        foreground();
        handler.removeCallbacks(check);
        resetBackgroundCheck();
    }

    void onPaused(Activity activity) {
        observe(activity);
        resumed.remove(activity);
        resetBackgroundCheck();
        scheduleCheck();
    }

    void onStopped(Activity activity) {
        observe(activity);
        started.remove(activity);
        resumed.remove(activity);
        if (started.isEmpty() && !activity.isChangingConfigurations()) {
            localUiStopped = true;
            checkBackground();
        } else {
            resetBackgroundCheck();
            scheduleCheck();
        }
    }

    void onDestroyed(Activity activity) {
        observe(activity);
        started.remove(activity);
        resumed.remove(activity);
        // onCreate -> finish -> onDestroy has no Stop. Confirm the process state after
        // the router's queued navigation, rather than inventing a background on Destroy.
        resetBackgroundCheck();
        scheduleCheck();
    }

    private void resetBackgroundCheck() {
        checkDelay = CHECK_DELAY_MS;
        checkAttempts = 0;
    }

    private void scheduleCheck() {
        handler.removeCallbacks(check);
        if (application == null || !resumed.isEmpty()
                || AppStateHistory.BACKGROUND.equals(visibility)) {
            return;
        }
        if (observedActivity) {
            if (checkAttempts >= MAX_BACKGROUND_CHECKS) return;
        } else if (launchCounter == null || !launchCounter.isWaitingForFirstActivity()) {
            return;
        }
        handler.postDelayed(check, checkDelay);
    }

    private void checkBackground() {
        handler.removeCallbacks(check);
        if (!resumed.isEmpty() || application == null) return;
        try {
            ActivityManager.RunningAppProcessInfo current = new ActivityManager.RunningAppProcessInfo();
            if (!localUiStopped) ActivityManager.getMyMemoryState(current);
            if (!observedActivity) {
                if (!launchCounter.isWaitingForFirstActivity()) return;
                // Receivers/providers can start with foreground importance. Require an actual
                // background process snapshot before treating a later UI launch as background-origin.
                if (current.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    launchCounter.onBackgroundBeforeFirstActivity(current.importance);
                    background();
                    return;
                }
            }
            // A paused Activity can still be visible (dialogs, split screen). Pause alone
            // is insufficient; a missing Stop requires evidence that our UI is hidden.
            boolean localHidden = localUiStopped
                    || current.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
            if (observedActivity && localHidden) {
                LogUtils.d(Constants.LOG_TAG_PREFIX + "AppStartCounter",
                        "launch background observed,localUiStopped:" + localUiStopped
                                + ",importance:" + current.importance);
                background();
                return;
            }
        } catch (RuntimeException ignored) {
            // An unavailable process snapshot is not evidence of a background interval.
        }
        checkAttempts++;
        long maxDelay = observedActivity ? MAX_CHECK_DELAY_MS : PRE_ACTIVITY_MAX_CHECK_DELAY_MS;
        checkDelay = Math.min(maxDelay, checkDelay * 2);
        scheduleCheck();
    }

    @Override public void onTrimMemory(int level) {
        if (level >= TRIM_MEMORY_UI_HIDDEN && observedActivity && resumed.isEmpty()) {
            localUiStopped = true;
            checkBackground();
        }
    }

    @Override public void onLowMemory() { }
    @Override public void onConfigurationChanged(Configuration configuration) { }

    void stop() {
        handler.removeCallbacks(check);
        started.clear();
        resumed.clear();
        observedActivity = false;
        visibility = AppStateHistory.UNKNOWN;
        localUiStopped = false;
        backgroundListener = null;
        resetBackgroundCheck();
        if (application != null) {
            application.unregisterComponentCallbacks(this);
            application = null;
        }
    }
}
