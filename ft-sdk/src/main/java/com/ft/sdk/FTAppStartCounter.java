package com.ft.sdk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.os.Build;
import android.view.View;

import com.ft.sdk.garble.utils.Constants;
import com.ft.sdk.garble.utils.FirstDrawDoneListener;
import com.ft.sdk.garble.utils.LogUtils;
import com.ft.sdk.garble.utils.Utils;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup timing
 *
 * <a href="https://docs.truewatch.com/real-user-monitoring/explorer/">Explorer</a>
 * <p>
 * {@link Constants#MEASUREMENT} is {@link Constants#FT_MEASUREMENT_RUM_ACTION}
 * {@link Constants#KEY_RUM_ACTION_TYPE} identifies cold, warm or hot launch Actions.
 * <p>
 * For the concept of lifecycle, refer to the official documentation
 * <a href="https://developer.android.com/topic/performance/vitals/launch-time?hl=zh-cn">Launch Time</a>
 *
 * <p>
 * Cold start, corresponds to {@link Constants#ACTION_NAME_LAUNCH_COLD}
 * Warm start corresponds to {@link Constants#ACTION_TYPE_LAUNCH_WARM}; an existing
 * Activity returning from background corresponds to {@link Constants#ACTION_TYPE_LAUNCH_HOT}.
 *
 * @author Brandon
 */
class FTAppStartCounter {
    private static final String TAG = Constants.LOG_TAG_PREFIX + "AppStartCounter";
    static final long FIRST_ACTIVITY_GAP_NS = TimeUnit.SECONDS.toNanos(10);
    /**
     * Pending first-launch duration (cold or warm), unit: nanoseconds.
     */
    private long coldStartDuration = 0;

    /**
     * Flag indicating whether the first frame has been drawn
     */
    private final AtomicBoolean firstDrawDone = new AtomicBoolean(false);
    final FTAppStateMonitor appStateMonitor = FTAppStateMonitor.get();

    /** Pending candidates, confined to the main thread and released on pause/destroy or completion. */
    private final Map<Activity, FirstDrawDoneListener> firstDrawListeners = new IdentityHashMap<>();

    /**
     * Startup start timestamp for duration, unit: nanoseconds
     */
    private long coldStartTimeLineForNanoDuration = 0;
    private long processStartTimeline;
    private long firstActivityObservedTimeline;
    private boolean firstLaunchWarm;

    /**
     * Cold start timeline timestamp used for reporting, unit: nanoseconds
     */
    private long coldStartTimeLine;

    /**
     * Application.onCreate() timestamp, unit: nanoseconds
     */
    private long applicationOnCreateTimeLine = 0;

    /**
     * First main-loop timestamp after Application.onCreate() has returned, unit: nanoseconds
     */
    private long applicationOnCreateCompletedTimeLine = 0;

    /**
     * Duration from Application.onCreate() to first Activity preOnCreate(), unit: nanoseconds
     */
    private long applicationOnCreateDuration = 0;

    /**
     * First Activity preOnCreate() timestamp, unit: nanoseconds
     */
    private long firstActivityPreOnActivityTimeline = 0;

    /**
     * Duration from app start to Application.onCreate(), unit: nanoseconds
     */
    private long applicationPreOnCreateDuration = 0;

    /**
     * Duration from first Activity preOnCreate() to first frame drawn, unit: nanoseconds
     */
    private long firsDrawnDuration = 0;

    /**
     * Whether the process was started while the app was not in foreground.
     */
    private boolean launchFromBackground = false;
    private boolean legacyActivityObserved;
    private long legacyStartTime;

    private boolean foregroundDuringLaunch = false;

    private boolean backgroundDuringLaunch = false;


    private FTAppStartCounter() {
        appStateMonitor.setLaunchCounter(this);
    }

    private static class SingletonHolder {
        private static final FTAppStartCounter INSTANCE = new FTAppStartCounter();
    }

    public static FTAppStartCounter get() {
        return FTAppStartCounter.SingletonHolder.INSTANCE;
    }


    /**
     * Record cold start duration
     * * {@link Constants#KEY_RUM_ACTION_TYPE} = {@link  Constants#ACTION_TYPE_LAUNCH_COLD}
     *
     * @param coldStartEndTimeLine
     */
    void coldStart(long coldStartEndTimeLine) {
        firstDrawDone.set(true);
        if (processStartTimeline == 0) {
            processStartTimeline = readProcessStartTimeline();
        }
        firstLaunchWarm = firstActivityObservedTimeline > 0
                && firstActivityObservedTimeline - processStartTimeline > FIRST_ACTIVITY_GAP_NS;
        this.coldStartTimeLineForNanoDuration = firstLaunchWarm
                ? firstActivityObservedTimeline : processStartTimeline;
        this.coldStartDuration = coldStartEndTimeLine - coldStartTimeLineForNanoDuration;
        this.coldStartTimeLine = Utils.getCurrentNanoTime() - coldStartDuration;

        this.applicationPreOnCreateDuration = !firstLaunchWarm && Build.VERSION.SDK_INT >= 24
                ? applicationOnCreateTimeLine - coldStartTimeLineForNanoDuration : 0;
        this.applicationOnCreateDuration = !firstLaunchWarm && firstActivityPreOnActivityTimeline > 0
                ? firstActivityPreOnActivityTimeline - applicationOnCreateTimeLine : 0;
        this.firsDrawnDuration = !firstLaunchWarm && firstActivityPreOnActivityTimeline > 0
                ? coldStartEndTimeLine - firstActivityPreOnActivityTimeline : 0;

        LogUtils.d(TAG, (firstLaunchWarm ? "warmStart:" : "coldStart:") + coldStartDuration
                + ",coldTimeLine:" + coldStartTimeLineForNanoDuration
                + ",firstActivityGap:" + (firstActivityObservedTimeline > 0
                        ? firstActivityObservedTimeline - processStartTimeline : -1)
                + ",launchFromBackground:" + launchFromBackground
                + ",appLaunchType:" + getColdStartLaunchType());
    }

    void checkFirstActivityPreCreate(long nanoTimeLine) {
        if (!firstDrawDone.get() && firstActivityPreOnActivityTimeline == 0) {
            LogUtils.d(TAG, "checkFirstActivityPreCreate:" + nanoTimeLine);
            firstActivityPreOnActivityTimeline = nanoTimeLine;
            if (firstActivityObservedTimeline == 0) firstActivityObservedTimeline = nanoTimeLine;
            appStateMonitor.onFirstActivityObserved();
        }

    }

    void checkFirstFrameStart(Activity activity, Runnable drawDoneCallback) {
        onAppForeground();
        if (firstDrawDone.get() || firstDrawListeners.containsKey(activity)) {
            return;
        }
        View rootView = activity.findViewById(android.R.id.content);
        if (rootView != null) {
            firstDrawListeners.put(activity,
                    FirstDrawDoneListener.registerForNextDrawCancellable(rootView, () -> {
                        // Registration is only an attempt. Any later Activity may supply the first draw.
                        if (firstDrawDone.compareAndSet(false, true)) {
                            for (FirstDrawDoneListener listener : firstDrawListeners.values()) {
                                listener.cancel();
                            }
                            firstDrawListeners.clear();
                            drawDoneCallback.run();
                        }
                    }));
        }
    }

    /** Release an Activity that leaves the foreground without drawing; later candidates may retry. */
    void cancelFirstFrame(Activity activity) {
        FirstDrawDoneListener listener = firstDrawListeners.remove(activity);
        if (listener != null) {
            listener.cancel();
        }
    }

    /**
     * Upload the pending first launch, retaining its cold/warm classification.
     */
    void coldStartUpload() {
        if (coldStartDuration == 0) return;
        if (!isValidLaunchDuration(coldStartDuration) || coldStartTimeLineForNanoDuration <= 0) {
            coldStartDuration = 0;
            return;
        }
        if (firstLaunchWarm) {
            long duration = coldStartDuration;
            coldStartDuration = 0;
            FTAutoTrack.putRUMWarmLaunchPerformance(duration, coldStartTimeLine);
            return;
        }

        HashMap<String, Long> preApplicationInit = null;
        HashMap<String, Long> applicationInit = null;
        HashMap<String, Long> firstFrameInit = null;

        // Build preApplicationInit map: {"start": 0, "duration": applicationPreOnCreateDuration}
        if (coldStartTimeLineForNanoDuration > 0 && applicationPreOnCreateDuration > 0) {
            preApplicationInit = new HashMap<>();
            preApplicationInit.put("start", 0L);
            preApplicationInit.put("duration", applicationPreOnCreateDuration);
        }

        // Build applicationInit map: {"start": relativeStart, "duration": applicationOnCreateDuration}
        if (applicationOnCreateTimeLine > 0 && applicationOnCreateDuration > 0) {
            long relativeStart = applicationOnCreateTimeLine - coldStartTimeLineForNanoDuration;
            applicationInit = new HashMap<>();
            applicationInit.put("start", relativeStart);
            applicationInit.put("duration", applicationOnCreateDuration);
        }

        // Build firstFrameInit map: {"start": relativeStart, "duration": firsDrawnDuration}
        if (firstActivityPreOnActivityTimeline > 0 && firsDrawnDuration > 0) {
            long relativeStart = firstActivityPreOnActivityTimeline - coldStartTimeLineForNanoDuration;
            firstFrameInit = new HashMap<>();
            firstFrameInit.put("start", relativeStart);
            firstFrameInit.put("duration", firsDrawnDuration);
        }

        long duration = coldStartDuration;
        coldStartDuration = 0;
        FTAutoTrack.putRUMLaunchPerformance(true, duration, coldStartTimeLine,
                preApplicationInit,
                applicationInit,
                firstFrameInit,
                getColdStartLaunchType());
    }

    /**
     * Upload hot start time
     * <p>
     * {@link Constants#KEY_RUM_ACTION_TYPE} = {@link  Constants#ACTION_TYPE_LAUNCH_HOT}
     *
     * @param hotStartDuration Hot start duration, unit: nanoseconds
     */
    void hotStart(long hotStartDuration, long startTime) {
        if (!isValidLaunchDuration(hotStartDuration)) return;
        FTAutoTrack.putRUMLaunchPerformance(false, hotStartDuration, startTime);
        LogUtils.d(TAG, "hotStart:" + hotStartDuration);
    }

    void warmStart(long duration, long startTime) {
        if (!isValidLaunchDuration(duration)) return;
        FTAutoTrack.putRUMWarmLaunchPerformance(duration, startTime);
        LogUtils.d(TAG, "warmStart:" + duration);
    }

    boolean hasCompletedFirstDraw() {
        return firstDrawDone.get();
    }

    private boolean isValidLaunchDuration(long duration) {
        if (duration > 0) return true;
        LogUtils.w(TAG, "Skip automatic launch with invalid duration:" + duration);
        return false;
    }

    private long readProcessStartTimeline() {
        return Build.VERSION.SDK_INT < 24 && legacyStartTime > 0
                ? legacyStartTime : Utils.getAppStartTimeNs();
    }


    /**
     * Check for retransmission, applied to lifecycle, in cases earlier than SDK {@link FTSdk#install(FTSDKConfig)}, generally used for third-party frameworks,
     * such as flutter SDK, ReactNative SDK
     */
    void checkToReUpload() {
        if (coldStartDuration > 0) {
            coldStartUpload();
        }
    }

    void captureLegacyStart(long nanoTimeLine) {
        if (legacyStartTime == 0 && !firstDrawDone.get()) {
            legacyStartTime = nanoTimeLine;
        }
    }

    void onLegacyActivityCreated() {
        if (!firstDrawDone.get()) {
            if (firstActivityObservedTimeline == 0) firstActivityObservedTimeline = System.nanoTime();
            legacyActivityObserved = true;
            appStateMonitor.onFirstActivityObserved();
        }
    }

    /**
     * Record Application.onCreate() timestamp
     *
     * @param nanoTimeLine Application.onCreate() timestamp, unit: nanoseconds
     */
    void appOnCreate(long nanoTimeLine, Application application) {
        // Repeated Application hooks must preserve the first launch snapshot.
        if (!firstDrawDone.get() && applicationOnCreateTimeLine == 0) {
            applicationOnCreateTimeLine = nanoTimeLine;
            processStartTimeline = readProcessStartTimeline();
            launchFromBackground = isLaunchFromBackground(application);
            appStateMonitor.onApplicationCreated(application);
            if (launchFromBackground) appStateMonitor.onBackgroundOrigin();
            LogUtils.d(TAG, "appOnCreate:" + nanoTimeLine
                    + ",launchFromBackground:" + launchFromBackground);
        }
    }

    /**
     * Records the first main-loop opportunity after Application.onCreate() returns.
     *
     * @param nanoTimeLine completion timestamp, unit: nanoseconds
     */
    void appOnCreateCompleted(long nanoTimeLine) {
        if (!firstDrawDone.get() && applicationOnCreateCompletedTimeLine == 0) {
            applicationOnCreateCompletedTimeLine = nanoTimeLine;
            LogUtils.d(TAG, "appOnCreateCompleted:" + nanoTimeLine
                    + ",firstActivityPreCreate:" + firstActivityPreOnActivityTimeline);
            // A normal launcher Activity can arrive in a later main-loop message.
            // Missing Activity callbacks here are not evidence of a background origin.
            appStateMonitor.onApplicationReady();
        }
    }

    boolean isWaitingForFirstActivity() {
        return isLaunchStatePending() && firstActivityPreOnActivityTimeline == 0
                && !legacyActivityObserved && !foregroundDuringLaunch;
    }

    void onBackgroundBeforeFirstActivity(int importance) {
        if (isWaitingForFirstActivity()) {
            launchFromBackground = true;
            LogUtils.d(TAG, "launchFromBackground:true,backgroundBeforeFirstActivity,importance:"
                    + importance);
        }
    }

    /**
     * Records this process's lifecycle changes until its cold-start measurement ends.
     */
    void onAppForeground() {
        if (!firstDrawDone.get()) {
            foregroundDuringLaunch = true;
            appStateMonitor.onFirstActivityObserved();
        }
    }

    void onAppBackground() {
        if (!firstDrawDone.get()) {
            backgroundDuringLaunch = true;
        }
    }

    boolean isLaunchStatePending() {
        return !firstDrawDone.get() && !launchFromBackground && !backgroundDuringLaunch;
    }

    String getColdStartLaunchType() {
        // Entering the foreground to draw does not change a background-origin launch.
        if (launchFromBackground) {
            return Constants.APP_LAUNCH_TYPE_BACKGROUND;
        }
        boolean hasForeground = foregroundDuringLaunch || applicationOnCreateTimeLine != 0;
        if (hasForeground && backgroundDuringLaunch) {
            return Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND;
        }
        return backgroundDuringLaunch ? Constants.APP_LAUNCH_TYPE_BACKGROUND
                : Constants.APP_LAUNCH_TYPE_FOREGROUND;
    }

    private boolean isLaunchFromBackground(Application application) {
        if (application == null) {
            return false;
        }
        ActivityManager.RunningAppProcessInfo processInfo =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(processInfo);
        return processInfo.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }


}
