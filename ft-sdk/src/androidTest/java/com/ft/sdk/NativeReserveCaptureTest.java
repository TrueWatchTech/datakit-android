package com.ft.sdk;

import android.content.Context;
import android.os.SystemClock;
import android.system.Os;

import androidx.test.platform.app.InstrumentationRegistry;

import com.ft.sdk.nativelib.ExceptionHandler;
import com.ft.sdk.nativelib.NativeEngineInit;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;

import static org.junit.Assert.*;

/** Explicit device probe. The runner expects this instrumented process to terminate after reporting its pool. */
public class NativeReserveCaptureTest {
    @Test
    public void unavailableCacheFallsBackToDurableDirectory() throws Exception {
        Assume.assumeTrue("blocked".equals(InstrumentationRegistry.getArguments().getString("captureReserve")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String suffix = "reserve-fallback-" + System.nanoTime();
        File reports = new File(context.getFilesDir(), suffix);
        File occupied = new File(context.getCacheDir(), suffix);
        assertTrue(occupied.createNewFile());
        try {
            NativeEngineInit.init(context, reports.getPath(), true, false, null, null, null, occupied.getPath());
            long deadline = SystemClock.elapsedRealtime() + 10000;
            File[] reserves;
            do {
                SystemClock.sleep(50);
                reserves = reports.listFiles((dir, name) -> name.endsWith(".clean.log"));
            } while ((reserves == null || reserves.length != 3) && SystemClock.elapsedRealtime() < deadline);
            assertNotNull(reserves);
            assertEquals(3, reserves.length);
            assertTrue(occupied.isFile());
        } finally {
            File[] created = reports.listFiles();
            if (created != null) for (File file : created) file.delete();
            reports.delete();
            occupied.delete();
        }
    }

    @Test
    public void leaveRealNativeDumpForNextProcess() throws Exception {
        String mode = InstrumentationRegistry.getArguments().getString("captureReserve");
        Assume.assumeTrue("reuse".equals(mode) || "evict".equals(mode));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File reports = new File(context.getFilesDir(), FTSdk.NATIVE_DUMP_PATH);
        File pool = new File(context.getCacheDir(), FTSdk.NATIVE_DUMP_PATH);
        assertTrue(reports.isDirectory() || reports.mkdirs());
        File legacy = new File(reports, String.format(Locale.US, "placeholder_%020d.clean.log", System.nanoTime()));
        assertTrue(legacy.createNewFile());
        try (RandomAccessFile file = new RandomAccessFile(legacy, "rw")) {
            file.setLength(512 * 1024);
        }
        // Omit the live consumer to exercise startup recovery after process death.
        NativeEngineInit.init(context, reports.getPath(), true, false, null, null, null, pool.getPath());
        long deadline = SystemClock.elapsedRealtime() + 10000;
        File[] reserves;
        do {
            SystemClock.sleep(50);
            reserves = pool.listFiles((dir, name) -> name.endsWith(".clean.log"));
        } while ((legacy.exists() || reserves == null || reserves.length != 3)
                && SystemClock.elapsedRealtime() < deadline);
        assertFalse(legacy.exists());
        assertNotNull(reserves);
        assertEquals(3, reserves.length);
        StringBuilder inodes = new StringBuilder();
        for (File reserve : reserves) {
            inodes.append(Os.stat(reserve.getPath()).st_ino).append(' ');
            if ("evict".equals(mode)) assertTrue(reserve.delete());
        }
        if ("evict".equals(mode)) assertTrue(pool.delete());
        android.util.Log.i("NativeReserveProbe", mode + " inodes " + inodes);
        ExceptionHandler.get().crashAndGetExceptionMessage();
        fail("Native capture did not terminate the test process");
    }
}
