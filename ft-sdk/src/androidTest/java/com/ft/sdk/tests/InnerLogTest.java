package com.ft.sdk.tests;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.ft.sdk.FTSDKConfig;
import com.ft.sdk.FTSdk;
import com.ft.sdk.garble.manager.LogFileHelper;
import com.ft.sdk.garble.utils.LogUtils;
import com.ft.sdk.garble.utils.Utils;
import com.ft.test.base.FTBaseTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Internal log management test case, test appending logs and file cache management
 * {@link com.ft.sdk.garble.manager.LogFileHelper}
 */
public class InnerLogTest extends FTBaseTest {

    private static final String TAG = "InnerLogTest";
    public static final String INNER_LOG_LOG_FILE = "InnerLog.log";
    public static final String INSERT_LOG_CONTENT = "appendLogTest";
    /**
     * sample：2024-03-25 08:54:22:386 E InnerLogTest 111111111
     * Exactly {@link LogFileHelper#TEST_SPLIT_FILE_SIZE}
     */
    public static final String TEST_DATA = "111111111";

    private File logFile;

    private File logBackFile;

    /**
     * {@link LogUtils#registerInnerLogCacheToFile(File, boolean)}
     *
     * @throws Exception
     */
    @Before
    public void setUp() throws Exception {
        Context application = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        logFile = new File(application.getFilesDir(), INNER_LOG_LOG_FILE);
        logBackFile = new File(application.getFilesDir(), LogFileHelper.LOG_BACKUP_CACHE_PATH);
        Whitebox.invokeMethod(LogUtils.class, logFile, true);

        FTSdk.install(FTSDKConfig.builder(TEST_FAKE_URL).setDebug(true));
    }


    /**
     * Check if the file content is written successfully, and check if the file is generated
     *
     * @throws IOException
     */
    @Test
    public void appendLogTest() throws IOException {
        LogUtils.e(TAG, INSERT_LOG_CONTENT);
        Assert.assertTrue(logFile.exists());

        String content = Utils.readFile(logFile);
        Assert.assertTrue(content.contains(INSERT_LOG_CONTENT));
    }

    @Test
    public void defaultLogAndArchiveRecoverTogetherAfterCacheEviction() throws IOException {
        File cacheDir = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File root = new File(cacheDir, "log-test-" + System.nanoTime());
        LogFileHelper helper = LogFileHelper.forDefaultLog(root, true);
        File logs = new File(root, "ft_sdk/logs");
        File active = new File(logs, "LogInner.log");
        File archives = new File(logs, "LogBackup");
        try {
            helper.appendLog(new String(new char[55]).replace('\0', 'x'));
            helper.appendLog("rotated");
            File[] rotated = archives.listFiles();
            Assert.assertNotNull(rotated);
            Assert.assertEquals(1, rotated.length);
            Assert.assertEquals(55, rotated[0].length());
            Assert.assertTrue(rotated[0].delete());
            Assert.assertTrue(archives.delete());
            Assert.assertTrue(active.delete());
            Assert.assertTrue(logs.delete());
            helper.appendLog("after cache eviction");
            Assert.assertTrue(Utils.readFile(active).contains("after cache eviction"));
            Assert.assertTrue(archives.isDirectory());
        } finally {
            active.delete();
            Utils.deleteFile(archives.getPath());
            logs.delete();
            new File(root, "ft_sdk").delete();
            root.delete();
        }
    }

    /**
     * Log separation verification
     * {@link com.ft.sdk.garble.manager.LogFileHelper#TEST_SPLIT_FILE_SIZE}
     */
    @Test
    public void splitLogFile() {
        LogUtils.e(TAG, TEST_DATA);
        LogUtils.e(TAG, TEST_DATA);
        Assert.assertTrue(logBackFile.exists());
        Assert.assertTrue(Objects.requireNonNull(logBackFile.listFiles()).length > 0);
    }

    /**
     * Reach total cache size
     * {@link com.ft.sdk.garble.manager.LogFileHelper#TEST_CACHE_MAX_TOTAL_SIZE}
     */
    @Test
    public void reachLogLimit() {
        LogUtils.e(TAG, TEST_DATA);
        LogUtils.e(TAG, TEST_DATA);
        LogUtils.e(TAG, TEST_DATA);


        File[] logFiles = logBackFile.listFiles();
        long totalSize = 0;
        if (logFiles != null) {
            for (File file : logFiles) {
                totalSize += file.length();
            }
        }

        System.out.println("reachLogLimit get totalSize:" + totalSize);
        Assert.assertTrue(totalSize <= LogFileHelper.TEST_CACHE_MAX_TOTAL_SIZE);
    }

    @Override
    public void tearDown() {
        super.tearDown();
        //Delete log file
        Utils.deleteFile(logFile.getAbsolutePath());
        Utils.deleteFile(logBackFile.getAbsolutePath());
    }
}
