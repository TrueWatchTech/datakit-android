package com.ft.sdk.internal;

import androidx.annotation.RestrictTo;

import com.ft.sdk.garble.utils.Constants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Event-local provenance. Metadata is never an entry in a tag or field map. */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class RUMCustomKeys {
    public static final String CACHE_KEY = "_ft_user_custom_keys";

    // Exact protocol names, not prefixes: error_business and view_business remain valid.
    private static final Set<String> SYSTEM_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            Constants.KEY_BATTERY_USE,
            Constants.KEY_MEMORY_TOTAL,
            Constants.KEY_MEMORY_USE,
            Constants.KEY_CPU_USE,
            Constants.KEY_CPU_TICK_COUNT,
            Constants.KEY_CPU_TICK_COUNT_PER_SECOND,
            Constants.KEY_MEMORY_AVG,
            Constants.KEY_MEMORY_MAX,
            Constants.KEY_FPS_MINI,
            Constants.KEY_FPS_AVG,
            Constants.KEY_BATTERY_CURRENT_AVG,
            Constants.KEY_BATTERY_CURRENT_MAX,
            Constants.KEY_HAS_REPLAY,
            Constants.KEY_SERVICE,
            Constants.KEY_ENV,
            Constants.KEY_APPLICATION_UUID,
            Constants.KEY_APP_VERSION_NAME,
            Constants.KEY_SDK_NAME,
            Constants.KEY_DEVICE_UUID,
            Constants.KEY_DEVICE_OS,
            Constants.KEY_DEVICE_OS_VERSION,
            Constants.KEY_DEVICE_OS_VERSION_MAJOR,
            Constants.KEY_DEVICE_DEVICE_BAND,
            Constants.KEY_DEVICE_DEVICE_MODEL,
            Constants.KEY_DEVICE_DEVICE_ARCH,
            Constants.KEY_DEVICE_DISPLAY,
            Constants.KEY_DEVICE_CARRIER,
            Constants.KEY_DEVICE_LOCALE,
            Constants.KEY_RUM_IS_SIGN_IN,
            Constants.KEY_RUM_USER_ID,
            Constants.KEY_RUM_USER_NAME,
            Constants.KEY_RUM_USER_EMAIL,
            Constants.KEY_RUM_APP_ID,
            Constants.KEY_RUM_CUSTOM_KEYS,
            Constants.KEY_RUM_RESOURCE_URL,
            Constants.KEY_RUM_RESOURCE_ID,
            Constants.KEY_RUM_RESOURCE_URL_HOST,
            Constants.KEY_RUM_RESOURCE_TYPE,
            Constants.KEY_RUM_RESOURCE_WEBSOCKET_COLLECTION_LEVEL,
            Constants.KEY_RUM_RESOURCE_WEBSOCKET_HANDSHAKE_STATE,
            Constants.KEY_RUM_RESPONSE_CONNECTION,
            Constants.KEY_RUM_RESPONSE_CONTENT_TYPE,
            Constants.KEY_RUM_RESPONSE_CONTENT_ENCODING,
            Constants.KEY_RUM_RESOURCE_METHOD,
            Constants.KEY_RUM_RESPONSE_HEADER,
            Constants.KEY_RUM_REQUEST_HEADER,
            Constants.KEY_RUM_RESOURCE_HTTP_PROTOCOL,
            Constants.KEY_RUM_RESOURCE_REQUEST_SIZE,
            Constants.KEY_RUM_RESOURCE_CONNECTION_REUSE,
            Constants.KEY_SDK_PACKAGE_INFO,
            Constants.KEY_SDK_DATA_FLAG,
            Constants.KEY_RUM_SDK_VIEW_UPDATE_TIME,
            Constants.KEY_SDK_VERSION,
            Constants.KEY_RUM_RESOURCE_STATUS,
            Constants.KEY_RUM_RESOURCE_STATUS_GROUP,
            Constants.KEY_RUM_RESOURCE_SIZE,
            Constants.KEY_RUM_RESOURCE_DURATION,
            Constants.KEY_RUM_RESOURCE_DNS,
            Constants.KEY_RUM_RESOURCE_DNS_TIME,
            Constants.KEY_RUM_RESOURCE_TCP,
            Constants.KEY_RUM_RESOURCE_CONNECT_TIME,
            Constants.KEY_RUM_RESOURCE_SSL,
            Constants.KEY_RUM_RESOURCE_SSL_TIME,
            Constants.KEY_RUM_RESOURCE_TTFB,
            Constants.KEY_RUM_RESOURCE_FIRST_BYTE,
            Constants.KEY_RUM_RESOURCE_FIRST_BYTE_TIME,
            Constants.KEY_RUM_DOWNLOAD_TIME,
            Constants.KEY_RUM_RESOURCE_SPAN_ID,
            Constants.KEY_RUM_RESOURCE_TRACE_ID,
            Constants.KEY_RUM_RESOURCE_TRANS,
            Constants.KEY_RUM_RESOURCE_URL_PATH,
            Constants.KEY_RUM_RESOURCE_URL_PATH_GROUP,
            Constants.KEY_RUM_RESOURCE_HOST_IP,
            Constants.KEY_RUM_ERROR_MESSAGE,
            Constants.KEY_RUM_ERROR_STACK,
            Constants.KEY_RUM_ERROR_SOURCE,
            Constants.KEY_RUM_ERROR_TYPE,
            Constants.KEY_RUM_ERROR_SITUATION,
            Constants.KEY_RUM_FOREGROUND_CRASH_FREE_DURATION,
            Constants.KEY_RUM_BACKGROUND_CRASH_FREE_DURATION,
            Constants.KEY_RUM_APP_STATE,
            Constants.KEY_RUM_SESSION_START_REASON,
            Constants.KEY_RUM_LONG_TASK_DURATION,
            Constants.KEY_RUM_LONG_TASK_STACK,
            Constants.KEY_RUM_NETWORK_TYPE,
            Constants.KEY_RUM_NETWORK_AVAILABLE,
            Constants.KEY_RUM_NETWORK_VALIDATED,
            Constants.KEY_RUM_NETWORK_DOWNLINK_KBPS,
            Constants.KEY_RUM_NETWORK_UPLINK_KBPS,
            Constants.KEY_RUM_NETWORK_SIGNAL_STRENGTH,
            Constants.KEY_RUM_SESSION_ID,
            Constants.KEY_RUM_SAMPLED_FOR_ERROR_SESSION,
            Constants.KEY_SAMPLED_FOR_ERROR_REPLAY,
            Constants.KEY_RUM_SESSION_ERROR_TIMESTAMP,
            Constants.KEY_RUM_SESSION_SAMPLE_RATE,
            Constants.KEY_RUM_SESSION_ON_ERROR_SAMPLE_RATE,
            Constants.KEY_RUM_SESSION_REPLAY_SAMPLE_RATE,
            Constants.KEY_RUM_SESSION_REPLAY_ON_ERROR_SAMPLE_RATE,
            Constants.KEY_RUM_SESSION_TYPE,
            Constants.KEY_RUM_VIEW_ID,
            Constants.KEY_RUM_VIEW_REFERRER,
            Constants.KEY_RUM_VIEW_NAME,
            Constants.KEY_RUM_VIEW_NETWORK_SETTLED_TIME,
            Constants.KEY_RUM_VIEW_INTERACTION_TO_NEXT_VIEW_TIME,
            Constants.KEY_RUM_VIEW_LOAD,
            Constants.KEY_RUM_VIEW_LONG_TASK_COUNT,
            Constants.KEY_RUM_VIEW_LONG_TASK_RATE,
            Constants.KEY_RUM_VIEW_RESOURCE_COUNT,
            Constants.KEY_RUM_VIEW_ERROR_COUNT,
            Constants.KEY_RUM_VIEW_ACTION_COUNT,
            Constants.KEY_RUM_VIEW_TIME_SPENT,
            Constants.KEY_RUM_VIEW_IS_ACTIVE,
            Constants.KEY_RUM_VIEW_IS_WEB_VIEW,
            Constants.KEY_RUM_ACTION_ID,
            Constants.KEY_RUM_ACTION_NAME,
            Constants.KEY_RUM_ACTION_TYPE,
            Constants.KEY_RUM_ACTION_POSITION,
            Constants.KEY_RUM_ACTION_TARGET,
            Constants.KEY_RUM_DISPLAY,
            Constants.KEY_RUM_APP_PRE_APPLICATION_INIT_TIME,
            Constants.KEY_RUM_APP_APPLICATION_INIT_TIME,
            Constants.KEY_RUM_APP_FIRST_FRAME_INIT_TIME,
            Constants.KEY_RUM_APP_LAUNCH_TYPE,
            Constants.KEY_RUM_ACTION_LONG_TASK_COUNT,
            Constants.KEY_RUM_ACTION_RESOURCE_COUNT,
            Constants.KEY_RUM_ACTION_ERROR_COUNT,
            Constants.KEY_RUM_ACTION_DURATION,
            "user_id", "visitor_id", "process_name", "historical", "is_pre_crash",
            "container", "resource_collection_source", "resource_completion", "resource_body_complete",
            "resource_response_bytes_observed", "resource_request_bytes_observed"
    )));

    private final Set<String> tagKeys;
    private final Set<String> fieldKeys;

    public RUMCustomKeys(Map<String, Object> tags, Map<String, Object> fields) {
        tagKeys = Collections.unmodifiableSet(sourceKeys(tags));
        fieldKeys = Collections.unmodifiableSet(sourceKeys(fields));
    }

    /** Map entries retain their ordinary meaning; only explicit user merges add provenance. */
    private static final class SourceMap extends HashMap<String, Object> {
        final Set<String> userKeys = new LinkedHashSet<>();
    }

    public static HashMap<String, Object> newMap() {
        return new SourceMap();
    }

    public static HashMap<String, Object> copy(Map<String, Object> values) {
        SourceMap copy = new SourceMap();
        if (values != null) {
            try {
                copy.putAll(values);
            } catch (ConcurrentModificationException ignored) {
                // A shared event may change while being copied. Drop this snapshot rather
                // than propagate a telemetry failure to the caller; do not retry on its thread.
                return new SourceMap();
            }
            copy.userKeys.addAll(sourceKeys(values));
        }
        return copy;
    }

    public static HashMap<String, Object> userProperties(Map<String, Object> values) {
        HashMap<String, Object> copy = newMap();
        putUserProperties(copy, values);
        return copy;
    }

    public static void putUserProperties(Map<String, Object> target, Map<String, ?> values) {
        if (values == null) return;
        Set<String> suppliedSources = values instanceof SourceMap ? sourceKeys(values) : null;
        try {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                String key = entry.getKey();
                if (!Constants.KEY_RUM_CUSTOM_KEYS.equals(key)) {
                    target.put(key, entry.getValue());
                    if (target instanceof SourceMap && isUserKey(key)
                            && (suppliedSources == null || suppliedSources.contains(key))) {
                        ((SourceMap) target).userKeys.add(key);
                    }
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // Keep entries already accepted; a concurrently modified input is best effort.
        }
    }

    public static void acceptedField(Map<String, Object> fields, String key) {
        if (fields instanceof SourceMap && isUserKey(key)) {
            ((SourceMap) fields).userKeys.add(key);
        }
    }

    public static Set<String> userKeys(Map<String, ?> values) {
        Set<String> keys = new LinkedHashSet<>();
        if (values != null) {
            try {
                for (String key : values.keySet()) if (isUserKey(key)) keys.add(key);
            } catch (ConcurrentModificationException ignored) {
                return Collections.emptySet();
            }
        }
        return keys;
    }

    private static Set<String> sourceKeys(Map<String, ?> values) {
        try {
            return values instanceof SourceMap
                    ? new LinkedHashSet<>(((SourceMap) values).userKeys) : new LinkedHashSet<>();
        } catch (ConcurrentModificationException ignored) {
            // Provenance is optional. Preserve the event when its source list races an update.
            return Collections.emptySet();
        }
    }

    public static boolean isUserKey(String key) {
        return key != null && !key.isEmpty() && !SYSTEM_KEYS.contains(key) && !CACHE_KEY.equals(key);
    }

    /** Optional attr metadata, ignored by older readers; old payloads get no inferred sources. */
    public static HashMap<String, Object> cache(Map<String, Object> tags, Map<String, Object> fields) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("tags", new java.util.ArrayList<>(sourceKeys(tags)));
        metadata.put("fields", new java.util.ArrayList<>(sourceKeys(fields)));
        return metadata;
    }

    public static void restore(JSONObject metadata, Map<String, Object> tags, Map<String, Object> fields) {
        restoreKeys(tags, metadata == null ? null : metadata.opt("tags"));
        restoreKeys(fields, metadata == null ? null : metadata.opt("fields"));
    }

    private static void restoreKeys(Map<String, Object> values, Object encoded) {
        if (values instanceof SourceMap) {
            Set<String> keys = ((SourceMap) values).userKeys;
            keys.clear();
            keys.addAll(parse(encoded));
        }
    }

    /** Browser SDK lists may be arrays or encoded arrays; malformed inputs are isolated. */
    public static Set<String> parse(Object encoded) {
        Set<String> keys = new LinkedHashSet<>();
        try {
            JSONArray array = encoded instanceof JSONArray ? (JSONArray) encoded
                    : encoded instanceof String ? new JSONArray((String) encoded) : null;
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    Object key = array.opt(i);
                    if (key instanceof String && isUserKey((String) key)) keys.add((String) key);
                }
            }
        } catch (Exception ignored) {
            // A broken Browser list must not discard the event or native context.
        }
        return keys;
    }

    public String serialize(Map<String, Object> tags, Map<String, Object> fields,
                            Set<String> globalTagKeys, Set<String> browserKeys) {
        Set<String> result = new LinkedHashSet<>();
        addSurviving(result, tagKeys, tags, true);
        addSurviving(result, globalTagKeys, tags, true);
        addSurviving(result, fieldKeys, fields, false);
        addSurviving(result, browserKeys, tags, true);
        addSurviving(result, browserKeys, fields, false);
        return result.isEmpty() ? null : new JSONArray(result).toString();
    }

    private static void addSurviving(Set<String> result, Set<String> candidates,
                                     Map<String, Object> values, boolean tag) {
        if (candidates == null || values == null) return;
        for (String key : candidates) {
            if (!isUserKey(key) || !values.containsKey(key)) continue;
            Object value = values.get(key);
            if (!tag || (value != null && !JSONObject.NULL.equals(value)
                    && !String.valueOf(value).isEmpty())) result.add(key);
        }
    }
}
