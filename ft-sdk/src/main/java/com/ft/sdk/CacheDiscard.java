package com.ft.sdk;

/** Eviction policy for the combined cache-size limit configured by {@link FTSDKConfig#enableLimitWithCacheSize(long)}. */
public enum CacheDiscard {
    /**
     * Discard new data when the cache limit is reached.
     */
    DISCARD,
    /**
     * Delete oldest cached data when the cache limit is reached.
     */
    DISCARD_OLDEST
}
