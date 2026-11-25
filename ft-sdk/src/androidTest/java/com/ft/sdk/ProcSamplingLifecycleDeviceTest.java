package com.ft.sdk;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ft.sdk.garble.bean.ViewBean;
import com.ft.sdk.garble.utils.CpuUtils;
import com.ft.sdk.garble.utils.DeviceUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ProcSamplingLifecycleDeviceTest {
    @Test public void stopAndReinstallReleaseProcFilesAndResumeSampling() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.runOnMainSync(() ->
                FTAutoTrack.startApp((Application) context.getApplicationContext()));
        int[] baseline = countProcFiles();
        int[][] retained = new int[4][];
        for (int cycle = 0; cycle < 2; cycle++) {
            Activity activity = null;
            try {
                instrumentation.runOnMainSync(() -> {
                    FTSdk.install(FTSDKConfig.builder("http://127.0.0.1:1")
                            .setOnlySupportMainProcess(false).setAutoSync(false));
                    FTSdk.initRUMWithConfig(new FTRUMConfig().setRumAppId("proc-sampling-test")
                            .setEnableTraceUserView(false).setDeviceMetricsMonitorTypes(
                                    DetectFrequency.FREQUENT, DeviceMetricsMonitorType.CPU,
                                    DeviceMetricsMonitorType.MEMORY));
                });
                activity = instrumentation.startActivitySync(new Intent(context, MonitorLifecycleTestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                instrumentation.waitForIdleSync();
                assertTrue(FTActivityLifecycleCallbacks.isAppInForeground());
                FTMonitorManager manager = FTMonitorManager.get();
                Field service = FTMonitorManager.class.getDeclaredField("service");
                service.setAccessible(true);
                ScheduledExecutorService worker = (ScheduledExecutorService) service.get(manager);
                manager.addMonitor("proc-test");
                ViewBean view = new ViewBean();
                view.setId("proc-test");
                worker.schedule(() -> manager.attachMonitorData(view), 250, TimeUnit.MILLISECONDS)
                        .get(5, TimeUnit.SECONDS);
                assertTrue("foreground view must report RSS", view.getMemoryAvg() > 0);
                assertTrue("foreground view must report CPU", view.getCpuTickCount() >= 0);
                manager.removeMonitor("proc-test");
                // A sample already in progress can finish after removeMonitor returns.
                retained[2 * cycle] = worker.submit(ProcSamplingLifecycleDeviceTest::countProcFiles)
                        .get(5, TimeUnit.SECONDS);
            } finally {
                if (activity != null) {
                    instrumentation.runOnMainSync(activity::finish);
                    instrumentation.waitForIdleSync();
                }
                instrumentation.runOnMainSync(FTSdk::shutDown);
            }
            retained[2 * cycle + 1] = countProcFiles();
        }
        Bundle status = new Bundle();
        status.putString("stream", "\nproc [stat,status] baseline=" + Arrays.toString(baseline)
                + " stop/shutdown per cycle=" + Arrays.deepToString(retained) + "\n");
        instrumentation.sendStatus(0, status);
        for (int[] counts : retained) {
            assertEquals("stat after stop/shutdown", baseline[0], counts[0]);
            assertEquals("status after stop/shutdown", baseline[1], counts[1]);
        }
    }

    @Test public void frequentPageSamplingReadsLiveMetrics() {
        long[] times = new long[50];
        for (int i = 0; i < times.length; i++) {
            long start = SystemClock.elapsedRealtimeNanos();
            long ticks = CpuUtils.get().getAppCPUTickCount();
            long rss = DeviceUtils.get().getAppMemoryUseSize();
            times[i] = SystemClock.elapsedRealtimeNanos() - start;
            assertTrue(ticks >= 0);
            assertTrue(rss > 0);
            SystemClock.sleep(DetectFrequency.FREQUENT.getValue());
        }
        Arrays.sort(times);
        Bundle status = new Bundle();
        status.putString("stream", "\nCPU tick + RSS, 50 samples, us: median=" + times[24] / 1000
                + " p95=" + times[47] / 1000 + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private static int[] countProcFiles() throws ErrnoException {
        String prefix = "/proc/" + Process.myPid() + "/";
        File[] descriptors = new File("/proc/self/fd").listFiles();
        assertNotNull(descriptors);
        int[] counts = new int[2];
        for (File fd : descriptors) {
            try {
                String target = Os.readlink(fd.getPath());
                if (target.equals(prefix + "stat")) counts[0]++;
                if (target.equals(prefix + "status")) counts[1]++;
            } catch (ErrnoException error) {
                // The descriptor used by listFiles itself has already closed.
                if (error.errno != OsConstants.ENOENT) throw error;
            }
        }
        return counts;
    }
}
