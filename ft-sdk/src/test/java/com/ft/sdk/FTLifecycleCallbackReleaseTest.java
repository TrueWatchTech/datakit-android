package com.ft.sdk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Fragment;
import android.content.Context;
import android.os.Process;
import android.view.KeyEvent;
import android.view.Window;

import com.ft.sdk.garble.bean.AppState;
import com.ft.sdk.garble.gesture.NoOpWindowCallback;
import com.ft.sdk.garble.gesture.WindowCallbackTracker;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.reflect.Whitebox;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class FTLifecycleCallbackReleaseTest {
    @After public void resetConfig() {
        FTAutoTrack.stopLifecycle();
        Whitebox.setInternalState(FTAutoTrack.class, "lifecycleStopped", false);
        Whitebox.setInternalState(FTAutoTrack.class, "stoppedStartedActivities", (Object) null);
        Whitebox.setInternalState(FTAutoTrack.class, "stoppedResumedActivities", (Object) null);
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", (Object) null);
    }

    @Test public void detachesFragmentCallbacksFromAllRegisteredActivitiesEvenAfterConfigChanges() {
        AtomicInteger calls = new AtomicInteger();
        FTRUMConfig config = new FTRUMConfig().setRumAppId("test").setEnableTraceUserViewInFragment(true)
                .setViewFragmentTrackingHandler(fragment -> {
                    calls.incrementAndGet();
                    return null;
                });
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", config);
        Activity first = Robolectric.buildActivity(Activity.class).setup().get();
        Activity second = Robolectric.buildActivity(Activity.class).setup().get();
        FTFragmentLifecycleHelper helper = new FTFragmentLifecycleHelper();
        helper.register(first);
        helper.register(first);
        helper.register(second);
        first.getFragmentManager().beginTransaction().add(new Fragment(), "before").commitNow();
        assertEquals(2, calls.get()); // Created and Resumed, once each.

        config.setEnableTraceUserViewInFragment(false);
        helper.unregister(first);
        first.getFragmentManager().beginTransaction().add(new Fragment(), "after-stop").commitNow();
        assertEquals(2, calls.get());
        helper.release();
        config.setEnableTraceUserViewInFragment(true);
        helper.register(second);
        second.getFragmentManager().beginTransaction().add(new Fragment(), "after-release").commitNow();
        assertEquals(2, calls.get());
    }

    @Test public void restoresOwnedWindowsAndDisablesTrackingBeneathAHostWrapper() {
        AtomicInteger keyHandlers = new AtomicInteger();
        FTRUMConfig config = new FTRUMConfig().setRumAppId("test").setActionTrackingHandler(action -> {
            keyHandlers.incrementAndGet();
            return null;
        });
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", config);
        Window first = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        Window second = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        Window.Callback original = first.getCallback();
        AtomicInteger hostKeys = new AtomicInteger();
        second.setCallback(new NoOpWindowCallback() {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                hostKeys.incrementAndGet();
                return true;
            }
        });
        WindowCallbackTracker tracker = new WindowCallbackTracker();
        tracker.startTrack(first);
        tracker.startTrack(second);
        Window.Callback installed = second.getCallback();
        tracker.startTrack(second);
        assertSame(installed, second.getCallback());
        Window.Callback outer = new NoOpWindowCallback() {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                return installed.dispatchKeyEvent(event);
            }
        };
        second.setCallback(outer);
        KeyEvent key = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU);
        assertTrue(outer.dispatchKeyEvent(key));
        assertEquals(1, keyHandlers.get());
        tracker.release();
        assertSame(original, first.getCallback());
        assertSame(outer, second.getCallback());
        assertTrue(second.getCallback().dispatchKeyEvent(key));
        assertEquals(1, keyHandlers.get());
        assertEquals(2, hostKeys.get());
    }

    @Test public void foregroundReinstallRestoresForegroundStateAndCurrentActivityCallbacksOnce() {
        Application app = RuntimeEnvironment.getApplication();
        setProcessImportance(app, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        AtomicInteger fragmentCalls = new AtomicInteger();
        AtomicInteger keyCalls = new AtomicInteger();
        FTRUMConfig config = new FTRUMConfig().setRumAppId("reinstall-test")
                .setEnableTraceUserViewInFragment(true)
                .setEnableTraceUserAction(true)
                .setViewFragmentTrackingHandler(fragment -> {
                    fragmentCalls.incrementAndGet();
                    return null;
                })
                .setActionTrackingHandler(action -> {
                    keyCalls.incrementAndGet();
                    return null;
                });
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", config);

        FTAutoTrack.startApp(app);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        assertTrue(FTActivityLifecycleCallbacks.isAppInForeground());

        FTAutoTrack.stopLifecycle();
        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", (Object) null);
        assertFalse(FTActivityLifecycleCallbacks.isAppInForeground());

        // Reinstall on the same foreground Activity without new Activity lifecycle callbacks.
        FTAutoTrack.restartLifecycleIfStopped();
        assertTrue(FTActivityLifecycleCallbacks.isAppInForeground());
        assertEquals(AppState.RUN, FTActivityManager.get().getAppState());

        Whitebox.setInternalState(FTRUMConfigManager.get(), "config", config);
        FTAutoTrack.syncActiveActivitiesForRum();
        // Repeated sync must remain idempotent.
        FTAutoTrack.syncActiveActivitiesForRum();

        activity.getFragmentManager().beginTransaction().add(new Fragment(), "after-reinstall").commitNow();
        assertEquals(2, fragmentCalls.get()); // Created + Resumed once each.

        KeyEvent menuKey = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU);
        activity.getWindow().getCallback().dispatchKeyEvent(menuKey);
        assertEquals(1, keyCalls.get());

        // Subsequent shutdown must detach restored callbacks cleanly.
        FTAutoTrack.stopLifecycle();
        activity.getFragmentManager().beginTransaction().add(new Fragment(), "after-second-stop").commitNow();
        assertEquals(2, fragmentCalls.get());
        activity.getWindow().getCallback().dispatchKeyEvent(menuKey);
        assertEquals(1, keyCalls.get());
    }

    @Test public void reinstallWhileProcessMovedToBackgroundOrActivityFinishedDoesNotRestoreForeground() {
        Application app = RuntimeEnvironment.getApplication();
        setProcessImportance(app, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        FTAutoTrack.startApp(app);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(FTActivityLifecycleCallbacks.isAppInForeground());

        FTAutoTrack.stopLifecycle();
        setProcessImportance(app, ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE);
        FTAutoTrack.restartLifecycleIfStopped();
        assertFalse(FTActivityLifecycleCallbacks.isAppInForeground());

        // Also verify finished Activity is never restored even with foreground importance.
        setProcessImportance(app, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        controller.pause().stop().destroy();
        FTAutoTrack.stopLifecycle();
        FTAutoTrack.restartLifecycleIfStopped();
        assertFalse(FTActivityLifecycleCallbacks.isAppInForeground());
    }

    private static void setProcessImportance(Application app, int importance) {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        info.pid = Process.myPid();
        info.uid = Process.myUid();
        info.importance = importance;
        Shadows.shadowOf((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE))
                .setProcesses(Collections.singletonList(info));
    }
}
