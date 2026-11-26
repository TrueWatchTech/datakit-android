package com.ft.sdk;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;

import androidx.annotation.NonNull;

import com.ft.sdk.garble.utils.AopUtils;
import com.ft.sdk.garble.utils.FirstDrawDoneListener;
import com.ft.sdk.garble.utils.Utils;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * create: by huangDianHua
 * time: 2020/6/17 17:50:45
 * description: Separate Activity creation on a background return from reuse of an existing Activity.
 */
class LifeCircleTraceCallback {
    private static final long WARM_START_CANDIDATE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(60);
    /**
     * Message channel
     */
    public static final int MSG_CHECK_SLEEP_STATUS = 1;
    /**
     * Sleep delay time
     */
    public static final int DELAY_SLEEP_MILLIS = 10000;//10 seconds
    /**
     * Whether already in sleep state, here going to background for 10 seconds is considered as sleep
     */
    private boolean alreadySleep = true;
    /**
     * Determine if the first page is created for the first time
     */
    private boolean mInited = false;//
    /**
     * Application startup time point
     */
    private long startTimeNanoTime = 0;

    private long startClockTimeNano = 0;
    private boolean hotResumePending;

    /**
     * Cache creation time point
     */
    private final LinkedHashMap<Context, long[]> mCreateMap = new LinkedHashMap<>();
    private final Map<Context, LaunchActivityState> launchActivities = new IdentityHashMap<>();
    private final Map<Context, Boolean> warmCandidateActivities = new IdentityHashMap<>();
    private final Map<Activity, FirstDrawDoneListener> warmDrawListeners = new IdentityHashMap<>();
    private boolean backgroundSinceResume;
    private boolean warmInterruptedByBackground;
    private long warmStartClockTimeNano;
    private long warmStartTimeNano;

    private static final class LaunchActivityState {
        final long createdNanoTime;
        final long createdTime;
        boolean resumed;

        LaunchActivityState(long createdNanoTime, long createdTime) {
            this.createdNanoTime = createdNanoTime;
            this.createdTime = createdTime;
        }
    }

    /**
     * Used to send delayed messages, execute sleep {@link #alreadySleep} = true operation after 10 seconds
     */
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == MSG_CHECK_SLEEP_STATUS) {
                checkLongTimeSleep();
            }
        }
    };

    void release() {
        handler.removeCallbacksAndMessages(null);
        for (Context context : mCreateMap.keySet()) {
            if (context instanceof Activity) FTAppStartCounter.get().cancelFirstFrame((Activity) context);
        }
        mCreateMap.clear();
        launchActivities.clear();
        clearWarmStart();
        hotResumePending = false;
    }

    void onForegroundRestored(java.util.List<Activity> startedActivities,
                              java.util.List<Activity> resumedActivities) {
        long now = System.nanoTime();
        long time = Utils.getCurrentNanoTime();
        if (startedActivities != null) {
            for (Activity activity : startedActivities) {
                if (activity != null && !launchActivities.containsKey(activity)) {
                    LaunchActivityState state = new LaunchActivityState(now, time);
                    state.resumed = true;
                    launchActivities.put(activity, state);
                }
            }
        }
        if (resumedActivities != null) {
            for (Activity activity : resumedActivities) {
                if (activity != null) {
                    LaunchActivityState state = launchActivities.get(activity);
                    if (state == null) {
                        state = new LaunchActivityState(now, time);
                        launchActivities.put(activity, state);
                    }
                    state.resumed = true;
                }
            }
        }
        mInited = true;
        alreadySleep = false;
        backgroundSinceResume = false;
        hotResumePending = false;
    }

    /**
     * Before {@link Activity#onStart()}
     */
    public void onPreStart() {
        onPreStart(null);
    }

    public void onPreStart(Activity activity) {
        expireWarmStartIfNeeded();
        if (activity != null) {
            clearStaleWarmStartForExistingActivity(activity);
        }
        hotResumePending = false;
        if (alreadySleep) {// Indicates returning from background
            if (mInited && FTAppStartCounter.get().hasCompletedFirstDraw()
                    && warmStartClockTimeNano == 0) {
                //hot start
                hotResumePending = true;
                startTimeNanoTime = Utils.getCurrentNanoTime();
                startClockTimeNano = System.nanoTime();
            }
        }


    }

    /**
     * Before {@link Activity#onCreate(Bundle)}
     *
     * @param context
     */
    public void onPreOnCreate(Context context) {
        long now = System.nanoTime();
        long time = Utils.getCurrentNanoTime();
        mCreateMap.put(context, new long[]{now, time});
        recordLaunchActivity(context, now, time);
        FTAppStartCounter.get().checkFirstActivityPreCreate(now);
        if (!mInited) {
//            FTAutoTrack.startApp();
            // warn start
            startTimeNanoTime = Utils.getCurrentNanoTime();
            startClockTimeNano = System.nanoTime();
        }
    }

    void onLegacyCreated(Activity activity) {
        // Track ownership without inventing an onCreate entry timestamp.
        mCreateMap.put(activity, new long[]{0, 0});
        recordLaunchActivity(activity, System.nanoTime(), Utils.getCurrentNanoTime());
        FTAppStartCounter.get().onLegacyActivityCreated();
    }

    private void recordLaunchActivity(Context context, long now, long time) {
        launchActivities.put(context, new LaunchActivityState(now, time));
        maybeStartWarm(context);
    }

    private void maybeStartWarm(Context context) {
        expireWarmStartIfNeeded();
        if (!backgroundSinceResume || !FTAppStartCounter.get().hasCompletedFirstDraw()) return;
        clearStaleWarmStartForExistingActivity(context);
        LaunchActivityState state = launchActivities.get(context);
        if (state == null || state.resumed) return;
        warmCandidateActivities.put(context, Boolean.TRUE);
        if (warmStartClockTimeNano == 0) {
            warmStartClockTimeNano = state.createdNanoTime;
            warmStartTimeNano = state.createdTime;
            warmInterruptedByBackground = false;
        }
    }

    private void expireWarmStartIfNeeded() {
        // Expire stale candidates so a later Activity can be classified independently.
        // If the first draw completes the active candidate after 60 seconds, report its full
        // duration. The Studio viewer keeps the raw event. Only the analytics dashboard
        // filters out warm starts longer than 60 seconds.
        if (warmStartClockTimeNano > 0
                && System.nanoTime() - warmStartClockTimeNano > WARM_START_CANDIDATE_TIMEOUT_NS) {
            clearWarmStart();
        }
    }

    private void clearStaleWarmStartForExistingActivity(Context context) {
        if (warmStartClockTimeNano == 0 || !warmInterruptedByBackground) return;
        if (warmCandidateActivities.containsKey(context)) return;
        LaunchActivityState state = launchActivities.get(context);
        if (state != null && state.resumed) {
            clearWarmStart();
        }
    }

    /**
     * After {@link Activity#onCreate(Bundle)}
     *
     * @param context
     */
    public void onPostOnCreate(Context context) {
        FTRUMConfigManager manager = FTRUMConfigManager.get();
        FTRUMConfig config = manager.getConfig();

        if (manager.isRumEnable()) {
            if (config.isEnableTraceUserView()) {
                long[] startTime = mCreateMap.get(context);
                if (startTime != null) {
                    long durationNS = System.nanoTime() - startTime[0];
                    String viewName = AopUtils.getClassName(context);
                    FTViewActivityTrackingHandler handler = config.getViewActivityTrackingHandler();
                    if (handler != null) {
                        HandlerView handlerView = config.getViewActivityTrackingHandler()
                                .resolveHandlerView((Activity) context);
                        if (handlerView != null) {
                            FTRUMInnerManager.get().onCreateView(handlerView.getViewName(), durationNS, startTime[1]);
                        }
                    } else {
                        FTRUMInnerManager.get().onCreateView(viewName, durationNS, startTime[1]);
                    }

                }
            }
        }
    }

    /**
     * @param context
     */
    public void onPostOnStart(Context context) {

    }

    /**
     * see <a href="https://developer.android.com/topic/performance/vitals/launch-time?hl=zh-cn#warm">Launch time calculation rules</a>
     * After {@link Activity#onResume() }
     *
     * @param activity
     */
    public void onPostResume(Activity activity) {
        FTRUMConfigManager manager = FTRUMConfigManager.get();
        FTRUMConfig config = manager.getConfig();

        maybeStartWarm(activity);
        registerFirstFrame(activity);

        // Already sleeping
        if (alreadySleep) {
            if (mInited) {
                if (hotResumePending && warmStartClockTimeNano == 0
                        && config.isRumEnable() && config.isEnableTraceUserAction()) {
                    if (startTimeNanoTime > 0) {
                        long now = System.nanoTime();
                        FTAppStartCounter.get().hotStart(now - startClockTimeNano,
                                startTimeNanoTime);

                    }

                }
                FTSdk.updateRemoteConfig();
            }
            alreadySleep = false;
        }
        // Avoid duplicate calculation of page startup statistics
        if (!mInited) {
            mInited = true;
        }
        LaunchActivityState state = launchActivities.get(activity);
        if (state != null) state.resumed = true;
        backgroundSinceResume = false;
        hotResumePending = false;
    }

    void registerFirstFrame(Activity activity) {
        expireWarmStartIfNeeded();
        clearStaleWarmStartForExistingActivity(activity);
        if (warmStartClockTimeNano > 0) {
            registerWarmFirstFrame(activity);
            return;
        }
        FTAppStartCounter.get().checkFirstFrameStart(activity, new Runnable() {
            @Override
            public void run() {
                FTAppStartCounter.get().coldStart(System.nanoTime());
                // RUM may have been initialized after this Activity resumed.
                FTRUMConfigManager currentManager = FTRUMConfigManager.get();
                if (currentManager.isRumEnable()
                        && currentManager.getConfig().isEnableTraceUserAction()) {
                    FTAppStartCounter.get().coldStartUpload();
                }
            }
        });

    }

    private void registerWarmFirstFrame(Activity activity) {
        if (warmDrawListeners.containsKey(activity)) return;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        long origin = warmStartClockTimeNano;
        warmDrawListeners.put(activity, FirstDrawDoneListener.registerForNextDrawCancellable(root, () -> {
            if (origin != warmStartClockTimeNano) return;
            long duration = System.nanoTime() - origin;
            long startTime = warmStartTimeNano;
            clearWarmStart();
            FTRUMConfig config = FTRUMConfigManager.get().getConfig();
            if (config.isRumEnable() && config.isEnableTraceUserAction()) {
                FTAppStartCounter.get().warmStart(duration, startTime);
            }
        }));
    }

    void cancelFirstFrame(Activity activity) {
        FTAppStartCounter.get().cancelFirstFrame(activity);
        FirstDrawDoneListener listener = warmDrawListeners.remove(activity);
        if (listener != null) listener.cancel();
    }

    private void clearWarmStart() {
        warmStartClockTimeNano = 0;
        warmStartTimeNano = 0;
        warmInterruptedByBackground = false;
        warmCandidateActivities.clear();
        for (FirstDrawDoneListener listener : warmDrawListeners.values()) listener.cancel();
        warmDrawListeners.clear();
    }

    public void onPreResume(Activity activity) {
        maybeStartWarm(activity);
        FTActivityManager.get().setCurrentActivity(activity);
    }

    /**
     * Triggered when all Activities are onStop
     */
    public void onEnterBackground() {
        backgroundSinceResume = true;
        if (warmStartClockTimeNano > 0) {
            if (warmCandidateActivities.isEmpty()) {
                clearWarmStart();
            } else {
                warmInterruptedByBackground = true;
            }
        }
        if (FTSdk.checkInstallState()) {
            handler.removeMessages(MSG_CHECK_SLEEP_STATUS);
            // Execute after sleeping for a while to distinguish short-term wake-up behaviors
            handler.sendEmptyMessageDelayed(MSG_CHECK_SLEEP_STATUS, DELAY_SLEEP_MILLIS);
        }
    }

    /**
     * {@link Activity#onDestroy() }
     *
     * @param context
     */
    public void onPostDestroy(Context context) {
        if (context instanceof Activity) {
            cancelFirstFrame((Activity) context);
        }
        mCreateMap.remove(context);
        launchActivities.remove(context);
        warmCandidateActivities.remove(context);
        if (warmStartClockTimeNano > 0 && warmInterruptedByBackground && warmCandidateActivities.isEmpty()) {
            clearWarmStart();
        }
    }

    /**
     * Check if long-term sleep
     */
    private void checkLongTimeSleep() {
        boolean appForeground = FTActivityLifecycleCallbacks.isAppInForeground();
        if (!appForeground) {
            alreadySleep = true;
        }
    }

}
