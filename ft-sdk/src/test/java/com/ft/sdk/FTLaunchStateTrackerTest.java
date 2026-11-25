package com.ft.sdk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.view.View;

import com.ft.sdk.garble.utils.Constants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.reflect.Whitebox;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Regression coverage for background intervals before the automatic first draw. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 31})
@LooperMode(LooperMode.Mode.PAUSED)
public class FTLaunchStateTrackerTest {
    private Application application;
    private FTActivityLifecycleCallbacks callbacks;
    private FTAppStartCounter counter;
    private final Map<Activity, ActivityController<?>> controllers = new java.util.IdentityHashMap<>();

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        counter = FTAppStartCounter.get();
        counter.appStateMonitor.stop();
        FTApplication.APP_START_TIME = System.nanoTime() - 1000000L;
        Whitebox.setInternalState(counter, "legacyActivityObserved", false);
        ((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).set(false);
        Map<Activity, ?> pending = Whitebox.getInternalState(counter, "firstDrawListeners");
        for (Activity activity : new java.util.ArrayList<>(pending.keySet())) {
            counter.cancelFirstFrame(activity);
        }
        for (String field : new String[]{"coldStartDuration", "applicationOnCreateTimeLine",
                "applicationOnCreateCompletedTimeLine", "firstActivityPreOnActivityTimeline",
                "processStartTimeline", "firstActivityObservedTimeline"}) {
            Whitebox.setInternalState(counter, field, 0L);
        }
        for (String field : new String[]{"foregroundDuringLaunch", "backgroundDuringLaunch",
                "launchFromBackground"}) {
            Whitebox.setInternalState(counter, field, false);
        }
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", (Object) null);
        Whitebox.setInternalState(FTActivityLifecycleCallbacks.class, "activityCount", 0);
        callbacks = new FTActivityLifecycleCallbacks();
        application.registerActivityLifecycleCallbacks(callbacks);
    }

    @After
    public void tearDown() {
        application.unregisterActivityLifecycleCallbacks(callbacks);
        counter.appStateMonitor.stop();
        Map<Activity, ?> pending = Whitebox.getInternalState(counter, "firstDrawListeners");
        for (Activity activity : new java.util.ArrayList<>(pending.keySet())) {
            counter.cancelFirstFrame(activity);
        }
    }

    @Test
    public void ordinaryForegroundBackgroundReturnBeforeDrawIsMixed() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        counter.appOnCreateCompleted(System.nanoTime());
        page.pause().stop();
        page.restart().start().resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND,
                counter.getColdStartLaunchType());
    }

    @Test
    public void applicationCompletionBeforeActivityDoesNotInventBackgroundOrigin() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        counter.appOnCreateCompleted(System.nanoTime());
        // Pixel 10 dispatches the launcher Activity in a later main-loop message.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS);
        ActivityController<Activity> page = page(Activity.class);
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test
    public void delayedActivityWithoutBackgroundEvidenceRemainsForeground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        counter.appOnCreateCompleted(System.nanoTime());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS);
        ActivityController<Activity> page = page(Activity.class);
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test
    public void laterActivityStillRecordsBackgroundBeforeFirstDraw() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        counter.appOnCreateCompleted(System.nanoTime());
        ActivityController<Activity> page = page(Activity.class);
        page.pause().stop();
        page.restart().start().resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND,
                counter.getColdStartLaunchType());
    }

    @Test
    public void backgroundComponentDemotionBeforeActivityPreservesBackgroundOrigin() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        counter.appOnCreateCompleted(System.nanoTime());
        // A receiver/provider can initially have foreground importance and then become a service.
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);
        assertSame(application, Whitebox.getInternalState(counter.appStateMonitor, "application"));
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        ActivityController<Activity> page = page(Activity.class);
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test
    public void lateBackgroundDemotionBeforeFirstActivityStillRecordsBackground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        counter.appOnCreateCompleted(System.nanoTime());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS);
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());

        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS);
        assertEquals(Constants.APP_LAUNCH_TYPE_BACKGROUND, counter.getColdStartLaunchType());
        assertEquals(com.ft.sdk.garble.bean.AppState.BACKGROUND, FTActivityManager.get().getAppState());

        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        ActivityController<Activity> page = page(Activity.class);
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test
    public void backgroundOriginRemainsBackgroundAfterForegroundAndBackground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
        counter.appOnCreateCompleted(System.nanoTime());
        ActivityController<Activity> page = page(Activity.class);
        page.pause().stop();
        page.restart().start().resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test
    public void backgroundAfterCompletedDrawDoesNotRewriteForeground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        counter.appOnCreateCompleted(System.nanoTime());
        finishDraw(page.get());
        page.pause().stop();
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test
    public void configurationRecreationBeforeFirstDrawDoesNotInventBackground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<RecreatingActivity> old = page(RecreatingActivity.class);
        counter.appOnCreateCompleted(System.nanoTime());
        old.pause().stop().destroy();
        ActivityController<Activity> replacement = page(Activity.class);
        finishDraw(replacement.get());
        assertEquals("Configuration recreation is not an app background interval",
                Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test
    public void createdOnlyRouterEndingBeforeDrawDoesNotHideBackgroundInterval() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<FinishInCreateActivity> router =
                Robolectric.buildActivity(FinishInCreateActivity.class).create();
        assertTrue(router.get().isFinishing());
        assertEquals(0, (int) Whitebox.getInternalState(
                FTActivityLifecycleCallbacks.class, "activityCount"));
        router.destroy();
        counter.appOnCreateCompleted(System.nanoTime());
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertFalse(FTActivityLifecycleCallbacks.isAppInForeground());
        assertFalse(((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).get());

        ActivityController<Activity> laterPage = page(Activity.class);
        finishDraw(laterPage.get());
        assertEquals("A finished router left no UI while cold-start timing was still pending",
                Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND,
                counter.getColdStartLaunchType());
    }

    @Test
    public void localStopIsRecordedBeforeFirstDrawWhileSiblingRemainsForeground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        counter.appOnCreateCompleted(System.nanoTime());
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        page.pause().stop();
        assertLaunchPending();
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND,
                counter.getColdStartLaunchType());

        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        page.restart().start().resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND,
                counter.getColdStartLaunchType());
    }

    private void startOrigin(int importance) {
        processes(importance, ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        counter.appOnCreate(System.nanoTime(), application);
    }

    @Test public void serviceProcessWithVisibleSiblingKeepsItsOwnBackgroundOrigin() {
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        counter.appOnCreate(System.nanoTime(), application);
        counter.appOnCreateCompleted(System.nanoTime());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertLaunchPending();
        assertEquals(Constants.APP_LAUNCH_TYPE_BACKGROUND, counter.getColdStartLaunchType());

        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> localPage = page(Activity.class);
        finishDraw(localPage.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test public void visibleSiblingDoesNotStartLocalForegroundHistoryBeforeFirstActivity() {
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
        counter.appOnCreate(System.nanoTime(), application);
        counter.appOnCreateCompleted(System.nanoTime());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertFalse(FTActivityLifecycleCallbacks.isAppInForeground());
        assertLaunchPending();

        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);
        assertEquals(Constants.APP_LAUNCH_TYPE_BACKGROUND, counter.getColdStartLaunchType());
        assertLaunchPending();
    }

    @Test public void pausedHiddenProcessWithoutStopIsMixed() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        counter.appOnCreateCompleted(System.nanoTime());
        page.pause();
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(54, TimeUnit.SECONDS);
        assertSame(application, Whitebox.getInternalState(counter.appStateMonitor, "application"));
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        page.resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test public void visiblePausedActivityDoesNotInventBackground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        page.pause();
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);
        page.resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test public void hiddenUiSignalWorksEvenWithForegroundServiceImportance() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        page.pause();
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
        application.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        page.resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test public void lateHiddenUiSignalAfterResumeDoesNotRewriteForeground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        application.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test public void normalRouterNavigationDoesNotInventBackground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<FinishInCreateActivity> router =
                Robolectric.buildActivity(FinishInCreateActivity.class).create();
        router.destroy();
        counter.appOnCreateCompleted(System.nanoTime());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        ActivityController<Activity> target = page(Activity.class);
        finishDraw(target.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test public void routingSplashToDelayedHomeRemainsForeground() {
        ActivityController<RoutingSplashActivity> router = routingSplash();
        ActivityController<LaunchHomeActivity> home = page(LaunchHomeActivity.class);
        // Splash finishes without Start/Resume/Stop; its destruction may arrive after Main starts.
        router.destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS);
        assertLaunchPending();
        finishDraw(home.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test public void routingSplashToHomeBackgroundBeforeDrawCompletesOnceOnReturn() {
        ActivityController<RoutingSplashActivity> router = routingSplash();
        ActivityController<LaunchHomeActivity> home = page(LaunchHomeActivity.class);
        home.pause().stop();
        router.destroy();
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS);
        assertLaunchPending();
        assertTrue(((Map<?, ?>) Whitebox.getInternalState(counter, "firstDrawListeners")).isEmpty());

        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        home.restart().start().resume();
        finishDraw(home.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());
        long firstDuration = Whitebox.getInternalState(counter, "coldStartDuration");
        assertTrue(firstDuration > 0);
        long firstTimeline = Whitebox.getInternalState(counter, "coldStartTimeLine");

        home.pause().stop();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS);
        home.restart().start().resume();
        finishDraw(home.get());
        assertEquals(firstDuration, (long) Whitebox.getInternalState(counter, "coldStartDuration"));
        assertEquals(firstTimeline, (long) Whitebox.getInternalState(counter, "coldStartTimeLine"));
        assertTrue(((Map<?, ?>) Whitebox.getInternalState(counter, "firstDrawListeners")).isEmpty());
    }

    @Test public void routingSplashDestroyedWhileHomePausedRecordsBackgroundBeforeDelayedStop() {
        ActivityController<RoutingSplashActivity> router = routingSplash();
        ActivityController<LaunchHomeActivity> home = page(LaunchHomeActivity.class);
        home.pause();
        router.destroy();
        // Pause/Destroy alone are not proof. The process snapshot confirms no visible app UI.
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertLaunchPending();
        assertEquals("Background must be remembered even before Main's delayed Stop arrives",
                Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());

        home.stop();
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        home.restart().start().resume();
        finishDraw(home.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test public void routingSplashToHomeDrawBeforeBackgroundKeepsForeground() {
        ActivityController<RoutingSplashActivity> router = routingSplash();
        ActivityController<LaunchHomeActivity> home = page(LaunchHomeActivity.class);
        finishDraw(home.get());
        router.destroy();
        home.pause().stop();
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS);
        home.restart().start().resume();
        finishDraw(home.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test public void siblingPageStoppingDoesNotChangeLocalLaunchClassification() {
        ActivityController<RoutingSplashActivity> router = routingSplash();
        ActivityController<LaunchHomeActivity> home = page(LaunchHomeActivity.class);
        // Device reproduction: Main stops while the independent page has foreground importance.
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        home.pause().stop();
        router.destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertLaunchPending();
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());

        // The sibling now stops. The local launch is still waiting for its own first draw.
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS);
        assertLaunchPending();
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());

        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        home.restart().start().resume();
        finishDraw(home.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());
    }

    private ActivityController<RoutingSplashActivity> routingSplash() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<RoutingSplashActivity> router =
                Robolectric.buildActivity(RoutingSplashActivity.class).create();
        Intent destination = Shadows.shadowOf(router.get()).getNextStartedActivity();
        assertNotNull(destination);
        assertEquals(LaunchHomeActivity.class.getName(), destination.getComponent().getClassName());
        assertTrue(router.get().isFinishing());
        assertEquals(0, (int) Whitebox.getInternalState(FTActivityLifecycleCallbacks.class, "activityCount"));
        assertTrue(((Map<?, ?>) Whitebox.getInternalState(counter, "firstDrawListeners")).isEmpty());
        counter.appOnCreateCompleted(System.nanoTime());
        assertLaunchPending();
        return router;
    }

    private void assertLaunchPending() {
        assertFalse(((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).get());
        assertEquals(0L, (long) Whitebox.getInternalState(counter, "coldStartDuration"));
    }

    @Test public void overlappingActivityNavigationDoesNotInventBackground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> first = page(Activity.class);
        first.pause();
        ActivityController<Activity> next = page(Activity.class);
        first.stop().destroy();
        finishDraw(next.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    @Test public void staleGeneralActivityCountDoesNotSuppressLaunchBackground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        Whitebox.setInternalState(FTActivityLifecycleCallbacks.class, "activityCount", 3);
        page.pause().stop();
        page.restart().start().resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test public void visibleSiblingCannotCompleteLocalFirstDrawOrMaskLocalBackground() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
        page.pause().stop();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);
        assertLaunchPending();
        page.restart().start().resume();
        finishDraw(page.get());
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND_AND_BACKGROUND, counter.getColdStartLaunchType());
    }

    @Test public void completedDrawKeepsGlobalObservationWithoutRewritingLaunch() {
        startOrigin(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        ActivityController<Activity> page = page(Activity.class);
        page.pause();
        page.resume();
        finishDraw(page.get());
        assertSame(application, Whitebox.getInternalState(counter.appStateMonitor, "application"));
        assertEquals(1, ((java.util.Set<?>) Whitebox.getInternalState(counter.appStateMonitor, "started")).size());
        page.pause().stop();
        processes(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS);
        assertEquals(Constants.APP_LAUNCH_TYPE_FOREGROUND, counter.getColdStartLaunchType());
    }

    private <T extends Activity> ActivityController<T> page(Class<T> type) {
        ActivityController<T> controller = Robolectric.buildActivity(type).create();
        controller.get().setContentView(new View(controller.get()));
        controllers.put(controller.get(), controller);
        return controller.start().resume();
    }

    private void finishDraw(Activity activity) {
        controllers.get(activity).visible();
        activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnDraw();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("The automatic first-draw endpoint must have run",
                ((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).get());
    }

    private void processes(int mainImportance, int siblingImportance) {
        ActivityManager manager = (ActivityManager) application.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningAppProcessInfo main = new ActivityManager.RunningAppProcessInfo();
        main.pid = Process.myPid();
        main.uid = Process.myUid();
        main.processName = application.getPackageName();
        main.pkgList = new String[]{application.getPackageName()};
        main.importance = mainImportance;
        ActivityManager.RunningAppProcessInfo sibling = new ActivityManager.RunningAppProcessInfo();
        sibling.pid = Process.myPid() + 100;
        sibling.uid = Process.myUid();
        sibling.processName = application.getPackageName() + ":pushservice";
        sibling.pkgList = new String[]{application.getPackageName()};
        sibling.importance = siblingImportance;
        Shadows.shadowOf(manager).setProcesses(Arrays.asList(main, sibling));
    }

    public static class RecreatingActivity extends Activity {
        @Override public boolean isChangingConfigurations() { return true; }
    }

    public static class FinishInCreateActivity extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            finish();
        }
    }

    public static class RoutingSplashActivity extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            startActivity(new Intent(this, LaunchHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        }
    }

    public static class LaunchHomeActivity extends Activity { }
}
