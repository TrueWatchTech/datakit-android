package com.ft.sdk;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ft.sdk.garble.gesture.WindowCallbackTracker;
import com.ft.sdk.garble.utils.Constants;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;


/**
 * BY huangDianHua
 * DATE:2019-12-06 11:18
 * Description: {@link Activity} lifecycle callback class
 * <p>
 * Used to listen to {@link Activity} lifecycle, combined with {@link LifeCircleTraceCallback}, in
 * {@link #onActivityPreStarted(Activity)},{@link #onActivityPreCreated(Activity, Bundle)}, {@link #onActivityPostCreated(Activity, Bundle)}
 * to output {@link Activity} as {@link  Constants#FT_MEASUREMENT_RUM_VIEW} metric
 * Page load time: {@link Constants#KEY_RUM_VIEW_LOAD}
 * Startup time: {@link Constants#ACTION_TYPE_LAUNCH_COLD},
 * {@link Constants#ACTION_TYPE_LAUNCH_WARM}, {@link Constants#ACTION_TYPE_LAUNCH_HOT}
 * <p>
 * These can be viewed through TrueWatch Studio <a href="https://docs.truewatch.com/real-user-monitoring/explorer/view/">Viewer View</a>
 */
public class FTActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = Constants.LOG_TAG_PREFIX + "FTActivityLifecycleCallbacks";
    private final LifeCircleTraceCallback mAppRestartCallback = new LifeCircleTraceCallback();
    private final WindowCallbackTracker mDispatcherReceiver = new WindowCallbackTracker();

    private static int activityCount = 0;

    public FTActivityLifecycleCallbacks() {
        FTAppStateMonitor.get().setLaunchCounter(FTAppStartCounter.get());
        FTAppStartCounter.get().appStateMonitor.setBackgroundListener(mAppRestartCallback::onEnterBackground);
    }
    private final Handler legacyHandler = new Handler(Looper.getMainLooper());
    private final Map<Activity, Runnable> legacyResumes = new IdentityHashMap<>();

    private final FTFragmentLifecycleHelper mFragmentLifecycleHelper = new FTFragmentLifecycleHelper();
    private volatile boolean released;

    void release() {
        released = true;
        mFragmentLifecycleHelper.release();
        mDispatcherReceiver.release();
        for (Activity activity : new java.util.ArrayList<>(legacyResumes.keySet())) {
            cancelLegacyResume(activity);
        }
        mAppRestartCallback.release();
    }

    void restoreForegroundActivities(java.util.List<Activity> startedActivities,
                                     java.util.List<Activity> resumedActivities) {
        if (released) return;
        FTAppStateMonitor monitor = FTAppStartCounter.get().appStateMonitor;
        monitor.restoreForeground(startedActivities, resumedActivities);
        activityCount = monitor.startedCount();
        mAppRestartCallback.onForegroundRestored(startedActivities, resumedActivities);
        if (resumedActivities != null && !resumedActivities.isEmpty()) {
            FTActivityManager.get().setCurrentActivity(resumedActivities.get(resumedActivities.size() - 1));
        }
        attachActiveActivitiesForRum();
    }

    void attachActiveActivitiesForRum() {
        if (released) return;
        FTRUMConfigManager manager = FTRUMConfigManager.get();
        if (!manager.isRumEnable()) return;
        Runnable attach = () -> {
            if (released) return;
            FTRUMConfigManager currentManager = FTRUMConfigManager.get();
            if (!currentManager.isRumEnable()) return;
            FTAppStateMonitor monitor = FTAppStartCounter.get().appStateMonitor;
            if (currentManager.getConfig().isEnableTraceUserViewInFragment()) {
                for (Activity activity : monitor.snapshotStarted()) {
                    if (monitor.isStarted(activity) && !activity.isFinishing() && !activity.isDestroyed()) {
                        mFragmentLifecycleHelper.register(activity);
                    }
                }
            }
            if (currentManager.getConfig().isEnableTraceUserAction()) {
                for (Activity activity : monitor.snapshotResumed()) {
                    if (monitor.isResumed(activity) && !activity.isFinishing() && !activity.isDestroyed()) {
                        mDispatcherReceiver.startTrack(activity.getWindow());
                    }
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attach.run();
        } else {
            legacyHandler.post(attach);
        }
    }

    /**
     * {@link Activity} created
     * <p>
     * no use
     *
     * @param activity           {@link Activity}.
     * @param savedInstanceState {@link Activity#onSaveInstanceState(Bundle)}
     */
    @Override
    public void onActivityCreated(@NonNull Activity activity, @NonNull Bundle savedInstanceState) {
        FTAppStartCounter.get().appStateMonitor.onCreated(activity);
        if (Build.VERSION.SDK_INT < 29) {
            mAppRestartCallback.onLegacyCreated(activity);
        }
    }

    /**
     * Called before {@link Activity#onStart()} , handled by {@link LifeCircleTraceCallback#onPreStart()},
     * logic in integrated app {@link Activity#onStart()}
     *
     * @param activity {@link Activity}.
     */
    @Override
    public void onActivityPreStarted(@NonNull Activity activity) {
        mAppRestartCallback.onPreStart(activity);

    }

    /**
     * Called after {@link Activity#onStart()}, no use
     *
     * @param activity {@link Activity}.
     */
    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        FTAppStartCounter.get().appStateMonitor.onStarted(activity);
        if (Build.VERSION.SDK_INT < 29) {
            mAppRestartCallback.onPreStart(activity);
        }
        activityCount = FTAppStartCounter.get().appStateMonitor.startedCount();
        mFragmentLifecycleHelper.register(activity);
    }

    /**
     * {@link LifeCircleTraceCallback#onPreOnCreate(Context)} used to record app start time
     *
     * @param activity           {@link Activity}.
     * @param savedInstanceState {@link Activity#onSaveInstanceState(Bundle)}.
     */
    @Override
    public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        mAppRestartCallback.onPreOnCreate(activity);
    }

    /**
     * {@link LifeCircleTraceCallback#onPostOnCreate(Context)} used to record app creation completion time
     *
     * @param activity           {@link Activity }.
     * @param savedInstanceState {@link Activity#onSaveInstanceState(Bundle)}.
     */
    @Override
    public void onActivityPostCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        mAppRestartCallback.onPostOnCreate(activity);

    }

    @Override
    public void onActivityPostStarted(@NonNull Activity activity) {
        mAppRestartCallback.onPostOnStart(activity);
    }

    /**
     * {@link Activity#onResume()} resume event
     * <p>
     * When {@link FTRUMConfigManager#isRumEnable()} is enabled, use {@link FTRUMGlobalManager#startView(String, HashMap)}
     *
     * @param activity {@link Activity }.
     */
    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        FTAppStartCounter.get().appStateMonitor.onResumed(activity);
        if (Build.VERSION.SDK_INT < 29) {
            mAppRestartCallback.onPreResume(activity);
            cancelLegacyResume(activity);
            // Observe a draw immediately; the fallback also retries roots installed after super.onResume().
            mAppRestartCallback.registerFirstFrame(activity);
            Runnable completion = () -> {
                legacyResumes.remove(activity);
                mAppRestartCallback.onPostResume(activity);
            };
            legacyResumes.put(activity, completion);
            legacyHandler.postAtFrontOfQueue(completion);
        }

        //page open data insertion
        FTRUMConfigManager manager = FTRUMConfigManager.get();
        if (manager.isRumEnable() && manager.getConfig().isEnableTraceUserView()) {
            FTViewActivityTrackingHandler handler = manager.getConfig().getViewActivityTrackingHandler();
            if (handler != null) {
                HandlerView view = handler.resolveHandlerView(activity);
                if (view != null) {
                    FTRUMInnerManager.get().startAutomaticView(activity, view.getViewName(), view.getProperty());
                }
            } else {
                FTRUMInnerManager.get().startAutomaticView(activity, activity.getClass().getSimpleName(), null);
            }
        }

        if (manager.isRumEnable() && manager.getConfig().isEnableTraceUserAction()) {
            mDispatcherReceiver.startTrack(activity.getWindow());
        }

        //start sync
        if (FTSdk.checkInstallState()) {
            SyncTaskManager.get().executeSyncPoll();
        }


    }

    @Override
    public void onActivityPreResumed(@NonNull Activity activity) {
        Application.ActivityLifecycleCallbacks.super.onActivityPreResumed(activity);
        mAppRestartCallback.onPreResume(activity);
    }

    /**
     * Called after {@link Activity#onPostResume()}, {@link  LifeCircleTraceCallback#onPostResume(Context)}
     *
     * @param activity {@link Activity }.
     */
    @Override
    public void onActivityPostResumed(@NonNull Activity activity) {
        mAppRestartCallback.onPostResume(activity);
    }

    /**
     * Called when {@link Activity#onPause()}, when {@link FTRUMConfigManager#isRumEnable()} is enabled,
     * use {@link FTRUMInnerManager#stopView()}
     *
     * @param activity {@link Activity }.
     */
    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        FTAppStartCounter.get().appStateMonitor.onPaused(activity);
        cancelLegacyResume(activity);
        mAppRestartCallback.cancelFirstFrame(activity);
        //page close data insertion
        FTRUMConfigManager manager = FTRUMConfigManager.get();
        if (manager.isRumEnable() && manager.getConfig().isEnableTraceUserView()) {
            FTViewActivityTrackingHandler handler = manager.getConfig().getViewActivityTrackingHandler();
            if (handler != null) {
                HandlerView view = handler.resolveHandlerView(activity);
                if (view != null) {
                    FTRUMInnerManager.get().stopAutomaticView(activity);
                }
            } else {
                FTRUMInnerManager.get().stopAutomaticView(activity);
            }
        }

        mDispatcherReceiver.stopTrack(activity.getWindow());

    }

    /**
     * Called when {@link Activity#onStop()}, {@link  LifeCircleTraceCallback#onEnterBackground()} handles page sleep processing
     *
     * @param activity {@link Activity }.
     */
    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        FTAppStartCounter.get().appStateMonitor.onStopped(activity);
        activityCount = FTAppStartCounter.get().appStateMonitor.startedCount();
        mFragmentLifecycleHelper.unregister(activity);
    }


    /**
     * {@link Activity} save state
     * <p>
     * no use
     *
     * @param activity {@link Activity }.
     * @param outState {@link Activity#onSaveInstanceState(Bundle)}.
     */
    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    /**
     * {@link Activity} destroyed
     * <p>
     * no use
     *
     * @param activity {@link Activity }.
     */
    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        FTAppStartCounter.get().appStateMonitor.onDestroyed(activity);
        if (Build.VERSION.SDK_INT < 29) {
            cancelLegacyResume(activity);
            mAppRestartCallback.onPostDestroy(activity);
            if (FTActivityManager.get().curentActivity() == activity) {
                FTActivityManager.get().setCurrentActivity(null);
            }
        }
    }

    private void cancelLegacyResume(Activity activity) {
        Runnable pending = legacyResumes.remove(activity);
        if (pending != null) legacyHandler.removeCallbacks(pending);
    }

    /**
     * Called after {@link Activity#onDestroy()}, with {@link LifeCircleTraceCallback#onPostDestroy(Context)}
     * to handle some logic after {@link Activity} lifecycle ends
     *
     * @param activity {@link Activity }.
     */
    @Override
    public void onActivityPostDestroyed(Activity activity) {
        mAppRestartCallback.onPostDestroy(activity);

    }

    /** Returns whether this process has foreground UI. */
    public static boolean isAppInForeground() {
        return FTAppStartCounter.get().appStateMonitor.isForeground();
    }
}
