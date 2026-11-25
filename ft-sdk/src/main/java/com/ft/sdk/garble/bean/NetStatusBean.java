package com.ft.sdk.garble.bean;


import java.util.HashMap;

/**
 * Network measurements for a manually reported RUM resource. Use a common monotonic
 * nanosecond clock for all phase boundaries (for example {@link System#nanoTime()}).
 * {@link #callStartEpoch} uses Unix epoch nanoseconds instead. Set {@link #phaseTimingKnown}
 * to false when only the total request duration is available. Phase offset getters subtract
 * the raw timestamps, so only use them when both boundaries are known.
 */
public class NetStatusBean {

    /**
     * Request start time.
     * @deprecated Use {@link #callStartTime} instead.
     */
    @Deprecated
    public long fetchStartTime = -1;
    /**
     * Legacy header start time.
     * @deprecated Use {@link #headerStartTime} instead.
     */
    @Deprecated
    public long requestStartTime = -1;
    /**
     * Legacy response start time.
     * @deprecated Use {@link #bodyStartTime} instead.
     */
    @Deprecated
    public long responseStartTime = -1;
    /**
     * Response end time.
     * @deprecated Use {@link #bodyEndTime} instead.
     */
    @Deprecated
    public long responseEndTime = -1;

    /**
     * Request start time from call Start
     */
    public long callStartTime = -1;
    /** Wall-clock start of the same call in Unix epoch nanoseconds, or -1 when unavailable. */
    public long callStartEpoch = -1;

    /**
     * Request response header start time
     */
    public long headerStartTime = -1;

    /**
     * Request response header end time
     */
    public long headerEndTime = -1;

    /**
     * tcp connection start time
     */
    public long tcpStartTime = -1;
    /**
     * tcp end time
     */
    public long tcpEndTime = -1;
    /**
     * dns resolution start time
     */
    public long dnsStartTime = -1;
    /**
     * dns resolution end time
     */
    public long dnsEndTime = -1;

    /**
     * response body start time
     */
    public long bodyStartTime = -1;

    /**
     * response body end time
     */
    public long bodyEndTime = -1;


    /**
     * ssl connection start time
     */
    public long sslStartTime = -1;

    /**
     * ssl connection end time
     */
    public long sslEndTime = -1;


    /**
     * host IP address
     */
    public String resourceHostIP = "";

    /**
     * Additional properties
     */
    public HashMap<String, Object> property;

    /**
     * Request host address
     */
    public String requestHost;

    /**
     * Request body size in bytes.
     */
    public long requestBodySize = 0;

    /**
     * Response body size in bytes.
     */
    public long responseBodySize = 0;

    /**
     * Connection reuse
     */
    public boolean connectionReuse = false;

    /** False when the transport cannot observe reuse; omitted from Resource fields. */
    public boolean connectionReuseKnown = true;

    /** False when only total duration is observable. */
    public boolean phaseTimingKnown = true;

    /** @return elapsed TCP connection time in nanoseconds */
    public long getTcpTime() {
        if (tcpEndTime > tcpStartTime) {
            return tcpEndTime - tcpStartTime;
        }
        return 0;
    }

    /** @return offset from call start to TCP connection start in nanoseconds */
    public long getConnectStartTime() {
        return tcpStartTime - callStartTime;
    }

    /** @return elapsed DNS lookup time in nanoseconds */
    public long getDNSTime() {
        if (dnsEndTime > dnsStartTime) {
            return dnsEndTime - dnsStartTime;
        }
        return 0;
    }

    /** @return offset from call start to DNS lookup start in nanoseconds */
    public long getDNSStartTime() {
        return dnsStartTime - callStartTime;
    }


    /** @return elapsed response body time in nanoseconds */
    public long getDownloadTime() {
        if (bodyEndTime > bodyStartTime) {
            return bodyEndTime - bodyStartTime;
        }
        return 0;
    }

    /** @return offset from call start to response body start in nanoseconds */
    public long getDownloadTimeStart() {
        return bodyStartTime - callStartTime;
    }

    /** @return elapsed time from response headers start to response body end in nanoseconds */
    public long getResponseTime() {
        if (bodyEndTime > headerStartTime) {
            return bodyEndTime - headerStartTime;
        }
        return 0;
    }


    /** @return elapsed time between response headers start and end in nanoseconds */
    public long getTTFB() {
        if (headerEndTime > headerStartTime) {
            return headerEndTime - headerStartTime;
        }
        return 0;
    }

    /** @return same duration as {@link #getTTFB()} in nanoseconds */
    public long getFirstByteTime() {
        return getTTFB();
    }


    /** @return offset from call start to response headers start in nanoseconds */
    public long getFirstByteStartTime() {
        return headerStartTime - callStartTime;
    }

    /** @return elapsed time from call start to response body end in nanoseconds */
    public long getHoleRequestTime() {
        if (bodyEndTime > callStartTime) {
            return bodyEndTime - callStartTime;
        }
        return 0;
    }

    /** @return elapsed TLS handshake time in nanoseconds */
    public long getSSLTime() {
        if (sslEndTime > sslStartTime) {
            return sslEndTime - sslStartTime;
        }
        return 0;
    }

    /** @return offset from call start to TLS handshake start in nanoseconds */
    public long getSslStartTime() {
        return sslStartTime - callStartTime;
    }
}
