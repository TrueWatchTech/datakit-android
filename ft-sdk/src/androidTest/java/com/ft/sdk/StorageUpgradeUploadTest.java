package com.ft.sdk;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ft.sdk.garble.bean.AppState;
import com.ft.sdk.garble.bean.DataType;
import com.ft.sdk.garble.bean.Status;
import com.ft.sdk.garble.bean.SyncData;
import com.ft.sdk.garble.db.FTDataStoreManager;
import com.ft.sdk.garble.utils.LogUtils;
import com.ft.sdk.garble.utils.Utils;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.google.mockwebserver.RecordedRequest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

/** Run seed on the source APK, install the destination APK with -r, then run receive with the same run ID. */
@RunWith(AndroidJUnit4.class)
public class StorageUpgradeUploadTest {
    @Test
    public void queuedLogsAndCrashDumpsSurviveAnUpdate() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String phase = args.getString("storagePhase", "standalone");
        String run = args.getString("storageRun", UUID.randomUUID().toString());
        boolean seed = phase.equals("seed");
        boolean standalone = phase.equals("standalone");
        assertTrue("Unknown storagePhase", standalone || seed || phase.equals("receive"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dumpDir = new File(context.getFilesDir(), FTSdk.NATIVE_DUMP_PATH);
        File previousDump = new File(dumpDir, "tombstone_" + run + "_previous.native.log");
        String oldLog = run + "_previous_log";
        String oldError = run + "_previous_error";
        String newLog = run + "_current_log";
        String newError = run + "_current_error";
        File newDump = new File(dumpDir, "tombstone_" + run + "_current.native.log");
        MockWebServer intake = new MockWebServer();
        for (int i = 0; i < 24; i++) intake.enqueue(new MockResponse().setBody("{}"));
        intake.play();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                FTAutoTrack.startApp((Application) context.getApplicationContext());
                FTSdk.install(FTSDKConfig.builder("http://127.0.0.1:" + intake.getPort())
                        .setAutoSync(false).setOnlySupportMainProcess(false).setDebug(true)
                        .setUseFileDataStore("file".equals(args.getString("storageBackend")))
                        .setCompressIntakeRequests(false).setRemoteConfiguration(false)
                        .setEnableDataFilter(false).setNeedTransformOldCache(false));
                FTSdk.initLogWithConfig(new FTLoggerConfig().setEnableCustomLog(true));
            });
            LogUtils.registerInnerLogCacheToFile();
            LogUtils.e("StorageUpgrade", "debug-" + run + "-" + phase);
            if (seed || standalone) {
                FTLogger.getInstance().logBackground(oldLog, Status.INFO);
                await(() -> queued(DataType.LOG, oldLog) == 1, "source log did not persist");
                assertTrue(dumpDir.isDirectory() || dumpDir.mkdirs());
                assertTrue(previousDump.createNewFile());
                Utils.writeToFile(previousDump, oldError);
                if (seed) {
                    assertEquals(0, intake.getRequestCount());
                    return;
                }
            }
            assertTrue("previous installation's dump", previousDump.isFile());
            long previousTimeNs = previousDump.lastModified() * 1_000_000L;
            long capturedTimeNs = 0;
            String capturedStackField = null;
            if ("true".equals(args.getString("checkNativeCapture"))) {
                File[] captured = dumpDir.listFiles((dir, name) -> name.matches("tombstone_[0-9]{20}_.*\\.native\\.log"));
                assertNotNull(captured);
                assertEquals("expected one actual Native report", 1, captured.length);
                String stack = Utils.readFile(captured[0].getPath(), Charset.defaultCharset());
                assertTrue("recorder produced a signal description", stack.contains("signal"));
                capturedStackField = "error_stack=" + Utils.translateFieldValue(stack);
                capturedTimeNs = captured[0].lastModified() * 1_000_000L;
            }
            assertEquals(1, queued(DataType.LOG, oldLog));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    FTSdk.initRUMWithConfig(new FTRUMConfig().setRumAppId("storage-upgrade")
                            .setSessionSamplingRate(100).setEnableTrackAppCrash(true)));
            await(() -> queued(DataType.RUM_APP, oldError) == 1, "startup did not recover old dump");
            FTLogger.getInstance().logBackground(newLog, Status.INFO);
            assertTrue(newDump.createNewFile());
            Utils.writeToFile(newDump, newError);
            long currentTimeNs = newDump.lastModified() * 1_000_000L;
            FTExceptionHandler.get().uploadNativeCrashBackground(newDump, AppState.RUN, false, null);
            await(() -> queued(DataType.RUM_APP, newError) == 1 && queued(DataType.LOG, newLog) == 1,
                    "destination events did not persist");
            FTSdk.flushSyncData();
            await(() -> queued(DataType.RUM_APP, oldError) + queued(DataType.RUM_APP, newError)
                    + queued(DataType.LOG, oldLog) + queued(DataType.LOG, newLog) == 0,
                    "upload did not acknowledge and drain the marked records");
            assertTrue(intake.getRequestCount() > 0);
            StringBuilder logs = new StringBuilder();
            StringBuilder errors = new StringBuilder();
            for (int i = 0, requests = intake.getRequestCount(); i < requests; i++) {
                RecordedRequest request = intake.takeRequest();
                if (request.getPath().contains("/v1/write/rum")) errors.append(request.getUtf8Body()).append('\n');
                if (request.getPath().contains("/v1/write/log")) logs.append(request.getUtf8Body()).append('\n');
            }
            assertSingleRecord(logs, oldLog);
            assertSingleRecord(logs, newLog);
            String oldLine = assertSingleRecord(errors, oldError);
            assertTrue(oldLine.contains("is_pre_crash=true"));
            assertTrue(oldLine.trim().endsWith(" " + previousTimeNs));
            String newLine = assertSingleRecord(errors, newError);
            assertTrue(newLine.contains("is_pre_crash=false"));
            assertTrue(newLine.trim().endsWith(" " + currentTimeNs));
            if (capturedTimeNs != 0) {
                String actualDump = assertSingleRecord(errors, " " + capturedTimeNs);
                assertTrue("actual crash stack", actualDump.contains(capturedStackField));
                assertTrue("recovered on restart", actualDump.contains("is_pre_crash=true"));
                assertEquals("preallocation padding", -1, actualDump.indexOf('\0'));
            }
            android.util.Log.i("StorageUpgradeProof", run
                    + " received old/new logs and old/new errors once, timestamps and flags matched"
                    + (capturedTimeNs != 0 ? ", real Native dump received once" : ""));
        } finally {
            if (!seed) {
                previousDump.delete();
                newDump.delete();
            }
            LogUtils.registerInnerLogHandler(null);
            FTSdk.shutDown();
            intake.shutdown();
        }
    }

    private static String assertSingleRecord(StringBuilder body, String marker) {
        String matched = null;
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == '\n' && !quoted) {
                // Native stacks contain physical newlines inside the quoted error_stack field.
                String record = body.substring(start, i);
                if (record.contains(marker)) {
                    assertTrue("duplicate delivery: " + marker, matched == null);
                    matched = record;
                }
                start = i + 1;
            }
        }
        assertNotNull("missing delivery: " + marker, matched);
        return matched;
    }

    private static int queued(DataType type, String marker) {
        int count = 0;
        for (SyncData item : FTDataStoreManager.get().queryDataByDataByTypeLimit(0, type)) {
            if (item.getDataString().contains(marker)) count++;
        }
        return count;
    }

    private static void await(Callable<Boolean> condition, String failure) throws Exception {
        long end = SystemClock.elapsedRealtime() + 20000;
        while (!condition.call()) {
            if (SystemClock.elapsedRealtime() >= end) fail(failure);
            SystemClock.sleep(50);
        }
    }
}
