package com.ft.sdk;

/** Controls eviction at the RUM row-count limit set by {@link FTRUMConfig#setRumCacheLimitCount(int)}. */
public enum RUMCacheDiscard {
    /**
     * Discard incoming records that exceed the limit (default).
     */
    DISCARD,
    /**
     * Evict the oldest cached records to make room for incoming records.
     */
    DISCARD_OLDEST
}