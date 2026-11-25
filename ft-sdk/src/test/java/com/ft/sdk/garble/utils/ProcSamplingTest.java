package com.ft.sdk.garble.utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23, shadows = ProcSamplingTest.FixtureProcFiles.class)
public class ProcSamplingTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    // Only the open operations are redirected. The SDK reads and closes real descriptors.
    @Implements(value = ProcFiles.class, isInAndroidSdk = false)
    public static class FixtureProcFiles {
        static File root;
        static final List<RandomAccessFile> readers = new ArrayList<>();

        @Implementation
        protected static RandomAccessFile openSystemStat() throws FileNotFoundException {
            return open("system-stat");
        }

        @Implementation
        protected static RandomAccessFile openProcessFile(String name) throws FileNotFoundException {
            return open(name);
        }

        private static RandomAccessFile open(String name) throws FileNotFoundException {
            RandomAccessFile file = new RandomAccessFile(new File(root, name), "r");
            readers.add(file);
            return file;
        }
    }

    @Before public void setUp() {
        ReflectionHelpers.setStaticField(CpuUtils.class, "cpuUtils", null);
        FixtureProcFiles.root = folder.getRoot();
    }

    @After public void tearDown() throws IOException {
        for (RandomAccessFile reader : FixtureProcFiles.readers) {
            reader.close();
        }
        FixtureProcFiles.readers.clear();
    }

    @Test public void topSampleClosesOutputWhenPidMatches() throws IOException {
        write("top", "PID %CPU\n" + android.os.Process.myPid() + " 25%\n");
        try (FileInputStream output = new FileInputStream(new File(folder.getRoot(), "top"))) {
            float usage = CpuUtils.get().readTopCpuUsage(output);
            assertEquals(25f / Runtime.getRuntime().availableProcessors(), usage, 0f);
            assertFalse(output.getFD().valid());
        }
    }

    @Test public void topSampleClosesOutputAtEndOfFile() throws IOException {
        write("top", "PID %CPU\n");
        try (FileInputStream output = new FileInputStream(new File(folder.getRoot(), "top"))) {
            assertEquals(0f, CpuUtils.get().readTopCpuUsage(output), 0f);
            assertFalse(output.getFD().valid());
        }
    }

    @Test public void topSampleClosesOutputWhenReadFails() throws IOException {
        File file = folder.newFile("top");
        try (FileInputStream output = new FileInputStream(file) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new IOException("Read failed");
            }
        }) {
            assertThrows(IOException.class, () -> CpuUtils.get().readTopCpuUsage(output));
            assertFalse(output.getFD().valid());
        }
    }

    @Test public void missingProcessStatClosesSystemStatOnEachAttempt() throws IOException {
        write("system-stat", "cpu  100 0 0 0 0 0 0\n");
        assertEquals(0, DeviceUtils.getCpuUsage(), 0);
        FileDescriptor first = FixtureProcFiles.readers.get(0).getFD();
        boolean firstClosedOnReturn = !first.valid();
        assertEquals(0, DeviceUtils.getCpuUsage(), 0);
        writeProcessStat(12, 8);
        assertEquals(20, DeviceUtils.getCpuUsage(), 0);
        assertTrue("first open must be rolled back before retry", firstClosedOnReturn);
        assertAllClosed();
    }

    @Test public void legacyCpuUsagePreservesDeltaAcrossAnIncompleteRead() throws IOException {
        CpuUtils cpu = CpuUtils.get();
        write("system-stat", "cpu  100 0 0 0 0 0 0\n");
        writeProcessStat(12, 8);
        assertEquals(20f, cpu.getCPUUsage(), 0f);
        boolean processStatClosedOnReturn = !FixtureProcFiles.readers.get(1).getFD().valid();
        write("stat", "incomplete\n");
        assertEquals(0f, cpu.getCPUUsage(), 0f);
        write("system-stat", "cpu  140 0 0 0 0 0 0\n");
        writeProcessStat(16, 14);
        assertEquals(25f, cpu.getCPUUsage(), 0f);
        assertTrue("second (process stat) descriptor must close after a CPU sample", processStatClosedOnReturn);
        assertAllClosed();
    }

    @Test public void pageCpuReadsUserTicksAndClosesAfterEachSample() throws IOException {
        CpuUtils cpu = CpuUtils.get();
        assertEquals(-1, cpu.getAppCPUTickCount());
        writeProcessStat(12, 8);
        assertEquals(12, cpu.getAppCPUTickCount());
        boolean closedAfterSuccess = !FixtureProcFiles.readers.get(0).getFD().valid();
        write("stat", "");
        assertEquals(-1, cpu.getAppCPUTickCount());
        writeProcessStat(16, 14);
        assertEquals(16, cpu.getAppCPUTickCount());
        assertTrue("process stat must close after a successful tick sample", closedAfterSuccess);
        assertAllClosed();
    }

    @Test public void pageMemoryReadsRssInBytesAndClosesAfterEachSample() throws IOException {
        DeviceUtils device = DeviceUtils.get();
        assertEquals(0, device.getAppMemoryUseSize());
        write("status", "Name:\ttest\nVmRSS:\t321 kB\nVmSize:\t900 kB\n");
        assertEquals(321000, device.getAppMemoryUseSize());
        boolean closedAfterSuccess = !FixtureProcFiles.readers.get(0).getFD().valid();
        write("status", "Name:\ttest\n");
        assertEquals(0, device.getAppMemoryUseSize());
        write("status", "VmRSS:  123 kB\n");
        assertEquals(123000, device.getAppMemoryUseSize());
        assertTrue("status must close after finding VmRSS", closedAfterSuccess);
        assertAllClosed();
    }

    @Test public void getStartTimeClosesStatAfterSuccessAndParseFailure() throws IOException {
        DeviceUtils device = DeviceUtils.get();
        write("stat", "123 (test) R 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19\n");
        assertEquals(17L, device.getStartTime());
        assertAllClosed();

        write("stat", "incomplete\n");
        assertEquals(-1L, device.getStartTime());
        assertEquals(2, FixtureProcFiles.readers.size());
        assertAllClosed();
    }

    private void assertAllClosed() throws IOException {
        assertFalse("fixture must have opened at least one descriptor", FixtureProcFiles.readers.isEmpty());
        for (RandomAccessFile reader : FixtureProcFiles.readers) {
            assertFalse("proc descriptor retained after sampling", reader.getFD().valid());
        }
    }

    private void writeProcessStat(int user, int system) throws IOException {
        write("stat", "123 (test) R 1 2 3 4 5 6 7 8 9 10 " + user + " " + system + "\n");
    }

    private void write(String name, String value) throws IOException {
        Files.write(new File(folder.getRoot(), name).toPath(), value.getBytes(StandardCharsets.US_ASCII));
    }
}
