package com.ft.sdk.garble.bean;

/**
 * One entry for batch submission with {@link com.ft.sdk.FTLogger#logBackground(java.util.List)}.
 */
public class LogData {
    /**
     * Log content, can be plain text or JSONString
     */
    String content;
    /**
     * Log level
     */
    String status;

    /** Creates an entry from plain text or JSON text and a non-null SDK status. */
    public LogData(String content, Status status) {
        this.content = content;
        this.status = status.name;
    }

    /** Creates an entry with a custom status string used by the configured log-level filter. */
    public LogData(String content, String status) {
        this.content = content;
        this.status = status;
    }

    /** Returns the message queued for batch submission. */
    public String getContent() {
        return content;
    }

    /** Replaces the message text for this entry. */
    public void setContent(String content) {
        this.content = content;
    }

    /** Returns the status string used for filtering and reporting. */
    public String getStatus() {
        return status;
    }

    /** Sets the status string used for filtering and reporting. */
    public void setStatus(String status) {
        this.status = status;
    }
}
