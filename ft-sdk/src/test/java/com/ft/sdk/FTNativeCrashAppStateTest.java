package com.ft.sdk;

import com.ft.sdk.garble.bean.AppState;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 31)
public class FTNativeCrashAppStateTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void startupScanOnlyConsumesFinishedCrashAndAnrReports() throws Exception {
        assertTrue(FTExceptionHandler.isFinishedNativeDump(temporaryFolder.newFile("tombstone_old.native.xcrash")));
        assertTrue(FTExceptionHandler.isFinishedNativeDump(temporaryFolder.newFile("tombstone_old_native.dump")));
        assertTrue(FTExceptionHandler.isFinishedNativeDump(temporaryFolder.newFile("tombstone_legacy_native.dmp")));
        assertTrue(FTExceptionHandler.isFinishedNativeDump(temporaryFolder.newFile("tombstone_new.anr.log")));
        assertFalse(FTExceptionHandler.isFinishedNativeDump(temporaryFolder.newFile("tombstone_nativeproc.trace.log")));
        assertFalse(FTExceptionHandler.isFinishedNativeDump(temporaryFolder.newFile("placeholder_000.clean.log")));
        assertFalse(FTExceptionHandler.isFinishedNativeDump(temporaryFolder.newFolder("tombstone_folder.native.log")));
    }

    @Test
    public void readsNativeSectionsWithoutMistakingStackTextForState() throws Exception {
        File dump = temporaryFolder.newFile("tombstone_native.dump");
        try (FileWriter writer = new FileWriter(dump)) {
            writer.write("at example.appState(foo.java:10)\nunsupported\n"
                    + "at example.foreground(foo.java:20)\nyes\n"
                    + "\nforeground:\nno\n\nappState:\nrunning\n\n");
        }
        assertEquals(AppState.BACKGROUND, FTExceptionHandler.readNativeAppState(dump));
    }

    @Test
    public void stopsBeforeLogcatWhenStateSectionsAlreadyAppearedAndIgnoresNoise() throws Exception {
        File dump = temporaryFolder.newFile("tombstone_header_then_logcat.dump");
        try (FileWriter writer = new FileWriter(dump)) {
            writer.write("foreground:\nno\n\nappState:\nbackground\n\n"
                    + "logcat:\nforeground:\nyes\nappState:\nrunning\n");
        }
        assertEquals(AppState.BACKGROUND, FTExceptionHandler.readNativeAppState(dump));
    }

    @Test
    public void decodesNativeRecorderNamesAndForegroundFlag() {
        assertEquals(AppState.STARTUP, FTExceptionHandler.resolveNativeAppState("launch", "no"));
        assertEquals(AppState.STARTUP, FTExceptionHandler.resolveNativeAppState("start", "no"));
        assertEquals(AppState.RUN, FTExceptionHandler.resolveNativeAppState("running", "yes"));
        assertEquals(AppState.BACKGROUND, FTExceptionHandler.resolveNativeAppState("running", "no"));
        assertEquals(AppState.BACKGROUND, FTExceptionHandler.resolveNativeAppState(" running\n", "no\n"));
    }

    @Test
    public void preservesCanonicalStatesAndDoesNotGuessFromMissingData() {
        assertEquals(AppState.STARTUP, FTExceptionHandler.resolveNativeAppState("startup", null));
        assertEquals(AppState.RUN, FTExceptionHandler.resolveNativeAppState("run", null));
        assertEquals(AppState.BACKGROUND, FTExceptionHandler.resolveNativeAppState("background", null));
        assertEquals(AppState.UNKNOWN, FTExceptionHandler.resolveNativeAppState(null, null));
        assertEquals(AppState.UNKNOWN, FTExceptionHandler.resolveNativeAppState("unsupported", ""));
        assertEquals(AppState.BACKGROUND, FTExceptionHandler.resolveNativeAppState(null, "no"));
        assertEquals(AppState.RUN, FTExceptionHandler.resolveNativeAppState(null, "yes"));
    }

    @Test
    @Config(sdk = 28)
    public void legacyNativeStartupFlagCannotOverrideVisibleUiOrInventStartup() {
        assertEquals(AppState.RUN, FTExceptionHandler.resolveNativeAppState("launch", "yes"));
        assertEquals(AppState.RUN, FTExceptionHandler.resolveNativeAppState("start", "yes"));
        assertEquals(AppState.UNKNOWN, FTExceptionHandler.resolveNativeAppState("launch", "no"));
        assertEquals(AppState.UNKNOWN, FTExceptionHandler.resolveNativeAppState("start", "no"));
        assertEquals(AppState.BACKGROUND, FTExceptionHandler.resolveNativeAppState("running", "no"));
    }
}
