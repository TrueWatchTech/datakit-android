package com.ft.sdk.garble.bean;

import com.ft.sdk.internal.RUMCustomKeys;

import java.util.HashMap;

/**
 * create by huangDianHua
 * time 2020/3/12 10:59:15
 */
public class LineProtocolBean {
    /**
     * Data index
     */
    private final String measurement;
    /**
     * Tag data
     */
    final HashMap<String, Object> tags;
    /**
     * Index data
     */
    final HashMap<String, Object> fields;
    /**
     * Generation time, unit nanosecond
     */
    final long timeNano;
    /** Immutable provenance, outside the serialized payload. */
    private final RUMCustomKeys customKeys;

    public LineProtocolBean(String measurement, HashMap<String, Object> tags, HashMap<String, Object> fields, long timeNano) {
        this(measurement, tags, fields, timeNano, new RUMCustomKeys(tags, fields));
    }

    public LineProtocolBean(String measurement, HashMap<String, Object> tags, HashMap<String, Object> fields,
                            long timeNano, RUMCustomKeys customKeys) {
        this.customKeys = customKeys;
        this.measurement = measurement;
        this.tags = tags;
        this.fields = fields;
        this.timeNano = timeNano;
    }

    public RUMCustomKeys getCustomKeys() {
        return customKeys;
    }

    public String getMeasurement() {
        return measurement;
    }

    public HashMap<String, Object> getTags() {
        return tags;
    }

    public HashMap<String, Object> getFields() {
        return fields;
    }

    public long getTimeNano() {
        return timeNano;
    }
}
