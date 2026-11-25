package com.ft.sdk.garble.bean;

/**
 * create: by huangDianHua
 * time: 2020/6/9 14:16:13
 * description: Log status level
 */
public enum Status {
    /**
     * Info
     */
    INFO("info"),
    /**
     * Debug
     */
    DEBUG("debug"),

    /**
     * Warning
     */
    WARNING("warning"),
    /**
     * Error
     */
    ERROR("error"),
    /**
     * Critical
     */
    CRITICAL("critical"),
    /** Recovery or healthy status. */
    OK("ok");
    /** Wire value used for log filtering and the reported status field. */
    public String name;

    Status(String name) {
        this.name = name;
    }
}
