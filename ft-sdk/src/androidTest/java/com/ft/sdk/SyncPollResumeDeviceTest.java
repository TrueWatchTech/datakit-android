package com.ft.sdk;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ft.sdk.garble.bean.DataType;
import com.ft.sdk.garble.bean.SyncData;
import com.ft.sdk.garble.db.FTDataStoreManager;
import com.ft.sdk.garble.threadpool.DataUploadThreadPool;
import com.ft.sdk.garble.utils.Utils;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Uses real HTTP failures, the production retry loop and Android lifecycle callbacks. */
@RunWith(AndroidJUnit4.class)
public class SyncPollResumeDeviceTest {
    @Test
    public void resumeAndFocusCompleteWhileUploadIsInEightSecondBackoff() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        MockWebServer server = new MockWebServer();
        Activity activity = null;
        ThreadPoolExecutor uploadExecutor = null;
        boolean installed = false;
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("retry fixture"));
        }
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        }
        server.play();
        String endpoint = "http://127.0.0.1:" + server.getPort() + "/";
        try {
            instrumentation.runOnMainSync(() -> {
                FTAutoTrack.startApp((Application) context.getApplicationContext());
                FTSdk.install(FTSDKConfig.builder(endpoint)
                        .setOnlySupportMainProcess(false).setAutoSync(false)
                        .setRemoteConfiguration(false).setEnableDataFilter(false)
                        .setNeedTransformOldCache(false)
                        .setDataSyncRetryCount(5).setDebug(false));
            });
            installed = true;
            assertNotNull("SDK must install", FTSdk.get());
            uploadExecutor = DataUploadThreadPool.get().getExecutor();
            // This is the isolated library test APK's cache, not a customer app's data.
            FTDataStoreManager.get().delete();
            SyncData data = new SyncData(DataType.LOG);
            data.setUuid("sync_resume_probe");
            data.setTime(Utils.getCurrentNanoTime());
            data.setDataString("custom_log,sdk_data_id=sync_resume_probe message=\"retry probe\" "
                    + data.getTime());
            assertTrue(FTDataStoreManager.get().insertFtOptList(Collections.singletonList(data), false));

            SyncPollResumeTestActivity.resetProbe();
            Intent intent = new Intent(context, SyncPollResumeTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = instrumentation.startActivitySync(intent);
            assertTrue("initial window must focus", SyncPollResumeTestActivity.focused.await(5, TimeUnit.SECONDS));
            Activity host = activity;
            instrumentation.runOnMainSync(() -> assertTrue(host.moveTaskToBack(true)));
            assertTrue("Activity must enter background", SyncPollResumeTestActivity.stopped.await(5, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(SyncPollResumeTestActivity::resetProbe);

            SyncTaskManager.get().setAutoSync(true);
            SyncTaskManager.get().executePoll();
            Thread upload = awaitFifthRetryBackoff(server);
            String uploadStack = stack(upload.getStackTrace());
            long foregroundStartedMs = SystemClock.elapsedRealtime();
            context.startActivity(intent);
            SystemClock.sleep(300);
            Thread main = Looper.getMainLooper().getThread();
            String mainState = main.getState().name();
            String mainStack = stack(main.getStackTrace());
            boolean blockedInPoll = "BLOCKED".equals(mainState)
                    && mainStack.contains("SyncTaskManager.executePoll");
            boolean resumed = SyncPollResumeTestActivity.resumed.await(15, TimeUnit.SECONDS);
            boolean focused = SyncPollResumeTestActivity.focused.await(5, TimeUnit.SECONDS);
            long focusElapsedMs = SystemClock.elapsedRealtime() - foregroundStartedMs;
            boolean resumedDuringBackoff = server.getRequestCount() == 5 && isRetryBackoff(upload);

            // Wait for the successful sixth request and verify retry/upload still works.
            SyncTaskManager.get().setAutoSync(false);
            long drainDeadline = SystemClock.elapsedRealtime() + 12000;
            while (SystemClock.elapsedRealtime() < drainDeadline
                    && (server.getRequestCount() < 6
                    || !FTDataStoreManager.get().queryDataByDataByTypeLimit(1, DataType.LOG).isEmpty())) {
                SystemClock.sleep(20);
            }
            boolean cacheDrained = FTDataStoreManager.get()
                    .queryDataByDataByTypeLimit(1, DataType.LOG).isEmpty();
            while (SystemClock.elapsedRealtime() < drainDeadline
                    && (uploadExecutor.getActiveCount() != 0 || !uploadExecutor.getQueue().isEmpty())) {
                SystemClock.sleep(20);
            }
            JSONObject result = new JSONObject();
            result.put("api", Build.VERSION.SDK_INT);
            result.put("device", Build.MODEL);
            result.put("failures_before_resume", 5);
            result.put("retry_backoff_ms", 8000);
            result.put("resume_duration_ms", SyncPollResumeTestActivity.resumeDurationMs);
            result.put("foreground_to_focus_probe_ms", focusElapsedMs);
            result.put("main_state_at_300ms", mainState);
            result.put("main_blocked_in_executePoll", blockedInPoll);
            result.put("resumed_during_backoff", resumedDuringBackoff);
            result.put("request_count", server.getRequestCount());
            result.put("cache_drained_after_success", cacheDrained);
            result.put("upload_stack", uploadStack);
            result.put("main_stack", mainStack);
            Bundle status = new Bundle();
            status.putString("stream", "\nSYNC_POLL_RESUME " + result + "\n");
            instrumentation.sendStatus(0, status);
            assertTrue("Activity must resume", resumed);
            assertTrue("window must focus", focused);
            assertFalse("main must not wait for SyncTaskManager during retry backoff", blockedInPoll);
            assertTrue("Activity must resume before the upload backoff ends", resumedDuringBackoff);
            assertTrue("resume must not inherit the 8-second upload wait",
                    SyncPollResumeTestActivity.resumeDurationMs < 1000);
            assertEquals("five failures followed by one successful upload", 6, server.getRequestCount());
            assertTrue("successful retry must remove the cached event", cacheDrained);
        } finally {
            // Let the bounded retry finish before tearing down SDK state, even on assertion failure.
            SyncTaskManager.get().setAutoSync(false);
            if (uploadExecutor != null) {
                uploadExecutor.shutdown();
                if (!uploadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    uploadExecutor.shutdownNow();
                }
                assertTrue("upload workers must terminate", uploadExecutor.awaitTermination(5, TimeUnit.SECONDS));
            }
            if (activity != null) {
                Activity host = activity;
                instrumentation.runOnMainSync(host::finish);
                instrumentation.waitForIdleSync();
            }
            if (installed) instrumentation.runOnMainSync(FTSdk::shutDown);
            server.shutdown();
        }
    }

    private static Thread awaitFifthRetryBackoff(MockWebServer server) {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (server.getRequestCount() == 5) {
                for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                    if (isRetryBackoff(entry.getKey())) return entry.getKey();
                }
            }
            SystemClock.sleep(10);
        }
        throw new AssertionError("upload did not enter fifth failure backoff; requests=" + server.getRequestCount());
    }

    private static boolean isRetryBackoff(Thread thread) {
        if (!"FTDataUpload".equals(thread.getName()) || thread.getState() != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().startsWith("com.ft.sdk.SyncTaskManager$")
                    && "onResponse".equals(frame.getMethodName())) return true;
        }
        return false;
    }

    private static String stack(StackTraceElement[] frames) {
        StringBuilder result = new StringBuilder();
        for (StackTraceElement frame : frames) result.append(frame).append('\n');
        return result.toString();
    }
}
