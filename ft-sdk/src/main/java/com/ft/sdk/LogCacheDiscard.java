package com.ft.sdk;

import com.ft.sdk.garble.utils.Constants;


/** Controls eviction at the log row-count limit set by {@link FTLoggerConfig#setLogCacheLimitCount(int)}. */
public enum LogCacheDiscard {
    /**
     * Discard incoming records that exceed the limit (default).
     */
    DISCARD,
    /**
     * Evict the oldest cached records to make room for incoming records.
     */
    DISCARD_OLDEST
}
