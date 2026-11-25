package com.ft.sdk.garble.manager;

import android.content.Context;

import com.ft.sdk.garble.utils.Constants;
import com.ft.sdk.garble.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * SDK log management class
 */
public class LogFileHelper {

    private static final String TAG = Constants.LOG_TAG_PREFIX + "LogFileHelper";
    /**
     * Log split size limit
     */
    private final static int SPLIT_FILE_SIZE = 33554432; //32MB

    /**
     * Log total size limit
     */
    private final static int CACHE_MAX_TOTAL_SIZE = 1073741824; //1G

    /**
     * Android Test log split size limit
     */
    public final static int TEST_CACHE_MAX_TOTAL_SIZE = 100;

    /**
     * Android Test log total size limit
     */
    private final static int TEST_SPLIT_FILE_SIZE = 50;

    public final static String LOG_BACKUP_CACHE_PATH = "LogBackup";

    private final File cacheFile;

    private final File backupLogDir;

    private final int cacheMaxTotalSize;
    private final int splitFileSize;

    public LogFileHelper(Context context, File cache, boolean isAndroidTest) {
        this(cache, new File(context.getFilesDir(), LOG_BACKUP_CACHE_PATH), isAndroidTest);
    }

    /** SDK-owned default output. Explicit log paths keep their original archive directory. */
    public static LogFileHelper forDefaultLog(File cacheDir, boolean isAndroidTest) {
        File dir = new File(cacheDir, "ft_sdk/logs");
        return new LogFileHelper(new File(dir, "LogInner.log"), new File(dir, LOG_BACKUP_CACHE_PATH), isAndroidTest);
    }

    private LogFileHelper(File cache, File backupLogDir, boolean isAndroidTest) {
        this.backupLogDir = backupLogDir;
        if (!backupLogDir.exists()) {
            backupLogDir.mkdirs();
        }
        this.cacheFile = cache;
        this.splitFileSize = isAndroidTest ? TEST_SPLIT_FILE_SIZE : SPLIT_FILE_SIZE;
        this.cacheMaxTotalSize = isAndroidTest ? TEST_CACHE_MAX_TOTAL_SIZE : CACHE_MAX_TOTAL_SIZE;
    }

    /**
     * Append log
     * Note: Avoid using the SDK logger to print logs,
     * as it may cause stack overflow
     *
     * @param logMessage Log data
     */
    public synchronized void appendLog(String logMessage) {
        if (!backupLogDir.isDirectory()) {
            // Android can reclaim the entire SDK cache directory between two appends.
            backupLogDir.mkdirs();
        }
        if (cacheFile.length() >= splitFileSize) {
            splitLog();
        }
        try {
            Utils.writeToFile(cacheFile, logMessage);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Split log to {@link #LOG_BACKUP_CACHE_PATH}
     *
     */
    private void splitLog() {
        String prefix = Utils.getNameWithoutExtension(cacheFile.getName()) + "_";
        long timestamp = System.currentTimeMillis();
        try {
            File archive;
            do {
                archive = new File(backupLogDir, prefix + timestamp++ + ".log");
            } while (!archive.createNewFile());
            if (cacheFile.renameTo(archive)) {
                deleteOldLogFilesIfNecessary(backupLogDir);
            } else {
                archive.delete();
            }
        } catch (IOException e) {
            android.util.Log.w(TAG, "Unable to rotate SDK log", e);
        }
    }

    /**
     * If it exceeds {@link #CACHE_MAX_TOTAL_SIZE}, 
     * the oldest file will be deleted
     *
     * @param logDir Log path
     */
    private void deleteOldLogFilesIfNecessary(File logDir) {
        File[] logFiles = logDir.listFiles();
        if (logFiles == null || logFiles.length == 0) {
            return;
        }

        Arrays.sort(logFiles, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return Long.compare(f1.lastModified(), f2.lastModified());
            }
        });

        long totalSize = 0;
        for (File file : logFiles) {
            totalSize += file.length();
        }

        if (totalSize > cacheMaxTotalSize) {
            long deletedSize = 0;
            for (File file : logFiles) {
                long fileSize = file.length();
                if (file.delete()) {
                    deletedSize += fileSize;
                    if (totalSize - deletedSize <= cacheMaxTotalSize * 0.8) {
                        break;
                    }
                } else {
                    android.util.Log.w(TAG, "Failed to delete log file: " + file.getAbsolutePath());
                }
            }
        }
    }


}
