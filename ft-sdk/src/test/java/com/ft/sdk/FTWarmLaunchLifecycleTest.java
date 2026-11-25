package com.ft.sdk;

import android.app.Activity;
import android.os.Build;
import android.os.Looper;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.reflect.Whitebox;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {23, 31})
@LooperMode(LooperMode.Mode.PAUSED)
public class FTWarmLaunchLifecycleTest {
    private FTAppStartCounter counter;
    private LifeCircleTraceCallback life;
    private final List<ActionSourceType> actions = new ArrayList<>();
    private final List<ActivityController<Activity>> controllers = new ArrayList<>();

    @Before public void setup() {
        counter = FTAppStartCounter.get();
        counter.appStateMonitor.stop();
        for (String field : new String[]{"coldStartDuration", "applicationOnCreateTimeLine",
                "applicationOnCreateCompletedTimeLine", "firstActivityPreOnActivityTimeline",
                "firstActivityObservedTimeline", "processStartTimeline", "legacyStartTime"}) {
            Whitebox.setInternalState(counter, field, 0L);
        }
        ((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).set(true);
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", new FTRUMConfig()
                .setRumAppId("warm-test").setEnableTraceUserAction(true)
                .setActionTrackingHandler(event -> { actions.add(event.getSourceType()); return null; }));
        life = new LifeCircleTraceCallback();
    }

    @After public void cleanup() {
        life.release();
        for (ActivityController<Activity> controller : controllers) controller.pause().stop().destroy();
        counter.appStateMonitor.stop();
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", (Object) null);
    }

    private Activity create() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)
                .create().start().resume().visible();
        controllers.add(controller);
        Activity activity = controller.get();
        activity.setContentView(new View(activity));
        if (Build.VERSION.SDK_INT >= 29) life.onPreOnCreate(activity);
        else life.onLegacyCreated(activity);
        return activity;
    }

    private void resume(Activity activity) {
        life.onPreStart();
        life.onPreResume(activity);
        life.onPostResume(activity);
    }

    private void draw(Activity activity) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS);
        activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnDraw();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void background(boolean longSleep) {
        life.onEnterBackground();
        Whitebox.setInternalState(life, "alreadySleep", longSleep);
    }

    @Test public void existingActivityReturnRemainsHotAndDoesNotWaitForDraw() {
        Activity page = create();
        resume(page);
        background(true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS);
        resume(page);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_HOT), actions);
        draw(page);
        assertEquals(1, actions.size());
    }

    @Test public void shortReturnToExistingActivityDoesNotProduceLaunch() {
        Activity page = create();
        resume(page);
        background(false);
        resume(page);
        draw(page);
        assertTrue(actions.isEmpty());
    }

    @Test public void recreatedActivityReportsWarmAfterFirstDrawWithoutHotDuplicate() {
        Activity old = create();
        resume(old);
        background(true);
        life.onPostDestroy(old);
        Activity replacement = create();
        resume(replacement);
        assertTrue(actions.isEmpty());
        draw(replacement);
        draw(replacement);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_WARM), actions);
    }

    @Test public void newActivityOnShortBackgroundReturnIsWarmWhileOldActivityExists() {
        Activity old = create();
        resume(old);
        background(false);
        Activity replacement = create();
        resume(replacement);
        assertTrue(actions.isEmpty());
        draw(replacement);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_WARM), actions);
    }

    @Test public void foregroundNavigationAndConfigurationRecreationDoNotProduceLaunch() {
        Activity first = create();
        resume(first);
        Activity second = create();
        resume(second);
        draw(second);
        life.onPostDestroy(first);
        life.onPostDestroy(second);
        Activity recreated = create();
        resume(recreated);
        draw(recreated);
        assertTrue(actions.isEmpty());
    }

    @Test public void undrawnWarmRouterTransfersMeasurementToTargetOnce() {
        Activity old = create();
        resume(old);
        background(true);
        life.onPostDestroy(old);
        Activity router = create();
        Activity target = create();
        life.onPostDestroy(router);
        resume(target);
        draw(target);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_WARM), actions);
    }

    @Test public void pausedWarmCandidateCanResumeAndCompleteOnce() {
        Activity old = create();
        resume(old);
        background(true);
        Activity target = create();
        resume(target);
        life.cancelFirstFrame(target);
        draw(target);
        assertTrue(actions.isEmpty());
        background(true);
        resume(target);
        draw(target);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_WARM), actions);
    }

    @Test public void releaseCancelsWarmListenersAndPendingMeasurement() {
        Activity old = create();
        resume(old);
        background(true);
        Activity target = create();
        resume(target);
        life.release();
        draw(target);
        assertTrue(actions.isEmpty());
        assertTrue(((Map<?, ?>) Whitebox.getInternalState(life, "warmDrawListeners")).isEmpty());
        assertEquals(0L, (long) Whitebox.getInternalState(life, "warmStartClockTimeNano"));
    }

    @Test public void warmDrawBeforePostResumeCannotFallThroughToHot() {
        Activity old = create();
        resume(old);
        background(true);
        Activity target = create();
        life.onPreStart();
        life.onPreResume(target);
        life.registerFirstFrame(target);
        draw(target);
        life.onPostResume(target);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_WARM), actions);
    }

    @Test public void delayedRumRetainsWarmClassificationAndRebasedOrigin() {
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", (Object) null);
        long origin = System.nanoTime();
        ((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).set(false);
        Whitebox.setInternalState(counter, "processStartTimeline", origin);
        counter.checkFirstActivityPreCreate(origin + TimeUnit.SECONDS.toNanos(30));
        counter.coldStart(origin + TimeUnit.SECONDS.toNanos(32));
        assertEquals(TimeUnit.SECONDS.toNanos(2),
                (long) Whitebox.getInternalState(counter, "coldStartDuration"));
        assertEquals(origin + TimeUnit.SECONDS.toNanos(30),
                (long) Whitebox.getInternalState(counter, "coldStartTimeLineForNanoDuration"));
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", new FTRUMConfig()
                .setRumAppId("warm-test").setEnableTraceUserAction(true)
                .setActionTrackingHandler(event -> { actions.add(event.getSourceType()); return null; }));
        counter.onAppBackground();
        counter.checkToReUpload();
        counter.checkToReUpload();
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_WARM), actions);
    }

    @Test public void firstActivityGapUsesExclusiveTenSecondBoundaryAndRebasesWarmDuration() {
        long origin = System.nanoTime();
        for (long gap : new long[]{TimeUnit.SECONDS.toNanos(10), TimeUnit.SECONDS.toNanos(10) + 1}) {
            ((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).set(false);
            Whitebox.setInternalState(counter, "processStartTimeline", origin);
            Whitebox.setInternalState(counter, "applicationOnCreateTimeLine", origin);
            Whitebox.setInternalState(counter, "firstActivityPreOnActivityTimeline", 0L);
            Whitebox.setInternalState(counter, "firstActivityObservedTimeline", 0L);
            counter.checkFirstActivityPreCreate(origin + gap);
            counter.coldStart(origin + gap + TimeUnit.SECONDS.toNanos(1));
            long duration = Whitebox.getInternalState(counter, "coldStartDuration");
            assertEquals(gap == TimeUnit.SECONDS.toNanos(10)
                    ? TimeUnit.SECONDS.toNanos(11) : TimeUnit.SECONDS.toNanos(1), duration);
            counter.checkToReUpload();
            counter.checkToReUpload();
        }
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_COLD, ActionSourceType.LAUNCH_WARM), actions);
    }

    @Test public void longWarmAndHotLaunchesAreReportedAndNonPositiveDurationsAreSkipped() {
        long origin = System.nanoTime();
        long slowDuration = TimeUnit.SECONDS.toNanos(61);
        counter.warmStart(slowDuration, origin);
        counter.hotStart(slowDuration, origin);
        counter.warmStart(0, origin);
        counter.hotStart(0, origin);
        counter.hotStart(-1, origin);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_WARM, ActionSourceType.LAUNCH_HOT), actions);
    }

    @Test public void pendingFirstDrawDoesNotEmitAnAdditionalHotOnReturn() {
        ((AtomicBoolean) Whitebox.getInternalState(counter, "firstDrawDone")).set(false);
        Whitebox.setInternalState(counter, "processStartTimeline", System.nanoTime());
        Activity page = create();
        resume(page);
        background(true);
        resume(page);
        assertTrue(actions.isEmpty());
        draw(page);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_COLD), actions);
    }

    @Test public void destroyedUndrawnRouterFollowedByBackgroundDoesNotPolluteLaterHotReturn() {
        Activity existing = create();
        resume(existing);
        background(true);

        Activity router = create();
        life.onPostDestroy(router);
        background(true);

        life.onPreStart(existing);
        life.onPreResume(existing);
        life.onPostResume(existing);
        draw(existing);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_HOT), actions);
    }

    @Test public void backgroundInterruptedWarmCandidateDoesNotHijackExistingActivityHotReturn() {
        Activity existing = create();
        resume(existing);
        background(true);

        Activity pendingNew = create();
        life.onPreStart(pendingNew);
        life.onPreResume(pendingNew);
        life.cancelFirstFrame(pendingNew);
        background(true);

        life.onPreStart(existing);
        life.onPreResume(existing);
        life.onPostResume(existing);
        draw(existing);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_HOT), actions);
    }

    @Test public void expiredWarmCandidateExpiresEvenWhenReturningToAlreadyResumedActivity() {
        Activity existing = create();
        resume(existing);
        background(true);

        create();
        Whitebox.setInternalState(life, "warmStartClockTimeNano",
                System.nanoTime() - TimeUnit.SECONDS.toNanos(61));

        life.onPreStart(existing);
        life.onPreResume(existing);
        life.onPostResume(existing);
        draw(existing);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_HOT), actions);
    }

    @Test public void undrawnRouterOpeningExistingActivityWithoutBackgroundRemainsWarm() {
        Activity existing = create();
        resume(existing);
        background(true);

        Activity router = create();
        life.onPostDestroy(router);

        life.onPreStart(existing);
        life.onPreResume(existing);
        life.onPostResume(existing);
        draw(existing);
        assertEquals(Arrays.asList(ActionSourceType.LAUNCH_WARM), actions);
    }
}
