package com.ft.sdk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.Process;

import com.ft.sdk.garble.bean.AppState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.reflect.Whitebox;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
@LooperMode(LooperMode.Mode.PAUSED)
public class FTAppStateMonitorTest {
    private FTAppStateMonitor monitor;
    private FTAppStartCounter counter;
    private Application application;
    private AppStateHistoryTest.TestClock clock;

    @Before public void setup() {
        application = RuntimeEnvironment.getApplication();
        counter = FTAppStartCounter.get();
        monitor = counter.appStateMonitor;
        monitor.stop();
        monitor.setLaunchCounter(counter);
        Whitebox.setInternalState(FTActivityManager.class, "instance", (Object) null);
        clock = new AppStateHistoryTest.TestClock();
        Whitebox.setInternalState(FTActivityManager.get(), "appStateHistory", new AppStateHistory(clock));
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", (Object) null);
        ((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).set(false);
        Whitebox.setInternalState(counter, "backgroundDuringLaunch", false);
        Whitebox.setInternalState(counter, "launchFromBackground", false);
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
    }

    @After public void cleanup() { monitor.stop(); }

    @Test public void localStopUpdatesAllReadersWhileSiblingStaysForegroundAfterFirstDraw() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        monitor.onStarted(activity);
        monitor.onResumed(activity);
        long start = clock.now;
        clock.advance(20);
        // Completing a launch freezes its classification. RUM still tracks local transitions.
        counter.coldStart(System.nanoTime());
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        monitor.onPaused(activity);
        monitor.onStopped(activity);
        assertFalse(FTActivityLifecycleCallbacks.isAppInForeground());
        assertEquals(AppState.BACKGROUND, FTActivityManager.get().getAppState());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);
        assertFalse(FTActivityLifecycleCallbacks.isAppInForeground());
        assertEquals(AppState.BACKGROUND, FTActivityManager.get().getAppState());
        clock.advance(20);
        assertEquals(AppStateHistory.MIXED, FTActivityManager.get().appStateHistory
                .between(start, clock.now));
        assertEquals("foreground", counter.getColdStartLaunchType());
        monitor.onStarted(activity);
        assertTrue(FTActivityLifecycleCallbacks.isAppInForeground());
        assertEquals(AppState.RUN, FTActivityManager.get().getAppState());
    }

    @Test public void duplicateLifecycleCallbacksDoNotHideFinalStop() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        monitor.onStarted(activity);
        monitor.onStarted(activity);
        monitor.onResumed(activity);
        monitor.onPaused(activity);
        // Paused but still visible is not a background transition.
        assertTrue(monitor.isForeground());
        monitor.onStopped(activity);
        monitor.onStopped(activity);
        assertEquals(0, monitor.startedCount());
        assertFalse(monitor.isForeground());
        assertEquals(AppState.BACKGROUND, FTActivityManager.get().getAppState());
    }

    @Test public void visiblePausedStateStopsBackgroundPollingAfterBoundedRetries() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        monitor.onStarted(activity);
        monitor.onResumed(activity);
        setMainImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
        monitor.onPaused(activity);

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS);
        assertTrue(monitor.isForeground());
        int attemptsAfterOneMinute = Whitebox.getInternalState(monitor, "checkAttempts");
        assertEquals(8, attemptsAfterOneMinute);

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MINUTES);
        assertEquals(attemptsAfterOneMinute, (int) Whitebox.getInternalState(monitor, "checkAttempts"));
    }

    @Test public void higherTrimMemoryLevelAlsoMarksPausedUiHidden() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        monitor.onStarted(activity);
        monitor.onResumed(activity);
        monitor.onPaused(activity);
        monitor.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
        assertFalse(monitor.isForeground());
        assertEquals(AppState.BACKGROUND, FTActivityManager.get().getAppState());
    }

    private void setMainImportance(int mainImportance) {
        ActivityManager.RunningAppProcessInfo main = new ActivityManager.RunningAppProcessInfo();
        main.pid = Process.myPid();
        main.uid = Process.myUid();
        main.importance = mainImportance;
        Shadows.shadowOf((ActivityManager) application.getSystemService(Context.ACTIVITY_SERVICE))
                .setProcesses(java.util.Collections.singletonList(main));
    }

    private void processes(int siblingImportance) {
        ActivityManager.RunningAppProcessInfo main = new ActivityManager.RunningAppProcessInfo();
        main.pid = Process.myPid(); main.uid = Process.myUid();
        main.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        ActivityManager.RunningAppProcessInfo sibling = new ActivityManager.RunningAppProcessInfo();
        sibling.pid = main.pid + 100; sibling.uid = main.uid; sibling.importance = siblingImportance;
        Shadows.shadowOf((ActivityManager) application.getSystemService(Context.ACTIVITY_SERVICE))
                .setProcesses(Arrays.asList(main, sibling));
    }
}
