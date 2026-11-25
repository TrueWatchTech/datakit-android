package com.ft.sdk;

/**
 * Minimum severity for SDK debug output configured with {@link FTSDKConfig#setSdkLogLevel(SDKLogLevel)}.
 */
public enum SDKLogLevel {
    /** Verbose severity. */
    V,
    /** Debug severity. */
    D,
    /** Informational severity. */
    I,
    /** Warning severity. */
    W,
    /** Error severity. */
    E,
}
