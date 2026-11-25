package com.ft.sdk;

/**
 * Line limits for Android Logcat buffers attached to crash or ANR reports.
 */
public class ExtraLogCatSetting {

    private static final int MINI_LINES = 0;
    private static final int MAX_LINES = 500;

    private int logcatMainLines = 200;
    private int logcatSystemLines = 50;
    private int logcatEventsLines = 50;

    /**
     * logcatMainLines default 200
     * logcatSystemLines default 50
     * logcatEventsLines default 50
     * Custom, please use {@link #ExtraLogCatSetting(int, int, int)}
     */

    public ExtraLogCatSetting() {

    }

    /**
     * Sets the line limit separately for each buffer. Values are clamped to 0–500.
     *
     * @param logcatMainLines   This is the main log buffer, containing most of the application's log output, [0,500], default 200
     * @param logcatSystemLines System log buffer, containing system-level log information, [0,500], default 50
     * @param logcatEventsLines Event log buffer, mainly records specific event information, [0,500], default 50
     */
    public ExtraLogCatSetting(int logcatMainLines, int logcatSystemLines, int logcatEventsLines) {
        this.logcatMainLines = Math.min(Math.max(logcatMainLines, MINI_LINES), MAX_LINES);
        this.logcatSystemLines = Math.min(Math.max(logcatSystemLines, MINI_LINES), MAX_LINES);
        this.logcatEventsLines = Math.min(Math.max(logcatEventsLines, MINI_LINES), MAX_LINES);
    }

    /** Returns the maximum number of lines to read from the main buffer. */
    public int getLogcatMainLines() {
        return logcatMainLines;
    }

    /** Returns the maximum number of lines to read from the system buffer. */
    public int getLogcatSystemLines() {
        return logcatSystemLines;
    }

    /** Returns the maximum number of lines to read from the events buffer. */
    public int getLogcatEventsLines() {
        return logcatEventsLines;
    }
}