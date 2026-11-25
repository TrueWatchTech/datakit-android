package com.ft.sdk;

import com.ft.sdk.garble.bean.ActionBean;
import com.ft.sdk.garble.bean.CollectType;
import com.ft.sdk.garble.bean.DataType;
import com.ft.sdk.garble.bean.LineProtocolBean;
import com.ft.sdk.garble.bean.SyncData;
import com.ft.sdk.garble.bean.ViewBean;
import com.ft.sdk.garble.filter.FTDataFilterManager;
import com.ft.sdk.garble.utils.LogUtils;
import com.ft.sdk.internal.RUMCustomKeys;
import com.ft.sdk.internal.issue.FTIssueFieldEnricher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RUMCustomKeysTest {
    private SyncDataHelper helper;
    private final AtomicReference<Map<String, Object>> output = new AtomicReference<>();

    @Before public void setup() {
        LogUtils.setDebug(false);
        helper = helper(FTSDKConfig.builder().setEnableDataFilter(false), new FTRUMConfig());
    }

    @After public void cleanup() {
        FTDataFilterManager.release();
    }

    private SyncDataHelper helper(FTSDKConfig base, FTRUMConfig rum) {
        base.setLineDataModifier((measurement, values) -> {
            output.set(new HashMap<>(values));
            return null;
        });
        SyncDataHelper instance = new SyncDataHelper();
        instance.initBaseConfig(base);
        instance.initRUMConfig(rum);
        return instance;
    }

    private String emit(DataType type, HashMap<String, Object> tags, HashMap<String, Object> fields) {
        fields.put("duration", 1L);
        return helper.getBodyContent("action", tags, fields, 1, type, "uuid");
    }

    private Set<String> emittedKeys() {
        return RUMCustomKeys.parse(output.get().get("custom_keys"));
    }

    private static HashMap<String, Object> map(Object... entries) {
        HashMap<String, Object> result = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    private static Set<String> keys(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    @Test public void staticAndDynamicGlobalsCombineWithoutEventAccumulation() {
        FTSDKConfig base = FTSDKConfig.builder().addGlobalContext("base", "1");
        base.getGlobalContext().put("before_init", true);
        FTRUMConfig rum = new FTRUMConfig().addGlobalContext("rum", "v");
        helper = helper(base, rum);
        helper.appendGlobalContext("dynamic_base", "v");
        helper.appendGlobalContext(map("base_map", 2));
        helper.appendRUMGlobalContext("dynamic_rum", "v");
        helper.appendRUMGlobalContext(map("rum_map", 3));
        emit(DataType.RUM_APP, new HashMap<>(), RUMCustomKeys.userProperties(map("biz_a", true)));
        assertEquals(keys("base", "before_init", "rum", "dynamic_base", "dynamic_rum", "base_map", "rum_map", "biz_a"), emittedKeys());
        emit(DataType.RUM_APP_ERROR_SAMPLED, new HashMap<>(), RUMCustomKeys.userProperties(map("biz_b", 2.5)));
        assertFalse(emittedKeys().contains("biz_a"));
        assertTrue(emittedKeys().contains("biz_b"));
        assertEquals(2.5, output.get().get("biz_b"));
    }

    @Test public void appendBeforeRumInitializationAndRepeatedInitializationKeepOnlyUserKeys() {
        helper.appendRUMGlobalContext("early", "yes");
        FTSDKConfig base = FTSDKConfig.builder().addGlobalContext("base", "true")
                .setLineDataModifier(helper.lineDataModifier);
        base.captureUserGlobalContextKeys();
        base.getGlobalContext().put("sdk_version", "sdk");
        base.captureUserGlobalContextKeys();
        FTRUMConfig rum = new FTRUMConfig().addGlobalContext("rum", "true");
        rum.captureUserGlobalContextKeys();
        rum.getGlobalContext().put("app_id", "app");
        rum.captureUserGlobalContextKeys();
        helper.initBaseConfig(base);
        helper.initRUMConfig(rum);
        helper.initRUMConfig(rum);
        helper.appendInternalGlobalContext(map("device_uuid", "android-id"));
        emit(DataType.RUM_APP, new HashMap<>(), RUMCustomKeys.newMap());
        assertEquals(keys("early", "base", "rum"), emittedKeys());
        assertEquals("android-id", output.get().get("device_uuid"));
    }

    @Test public void globalInputMapsAreCopiedBeforeLaterMutation() {
        HashMap<String, Object> values = map("original", true);
        helper.appendGlobalContext(values);
        values.clear();
        values.put("later", true);
        emit(DataType.RUM_APP, new HashMap<>(), RUMCustomKeys.newMap());
        assertEquals(keys("original"), emittedKeys());
        assertEquals(true, output.get().get("original"));
        assertFalse(output.get().containsKey("later"));
    }

    @Test public void globalModifierKeepsInputMapContractAndIndependentSerializedSnapshot() {
        FTSDKConfig base = FTSDKConfig.builder().addGlobalContext("base_biz", "original")
                .setDataModifier((key, value) -> key.endsWith("_biz") ? "modified" : value);
        FTRUMConfig rum = new FTRUMConfig().addGlobalContext("rum_biz", "original");
        helper = helper(base, rum);
        FTLoggerConfig log = new FTLoggerConfig().addGlobalContext("log_biz", "original");
        helper.initLogConfig(log);
        assertEquals("modified", base.getGlobalContext().get("base_biz"));
        assertEquals("modified", rum.getGlobalContext().get("rum_biz"));
        assertEquals("modified", log.getGlobalContext().get("log_biz"));
        HashMap<String, Object> baseInput = map("dynamic_base_biz", "original");
        HashMap<String, Object> rumInput = map("dynamic_rum_biz", "original");
        helper.appendGlobalContext(baseInput);
        helper.appendRUMGlobalContext(rumInput);
        assertEquals("modified", baseInput.get("dynamic_base_biz"));
        assertEquals("modified", rumInput.get("dynamic_rum_biz"));
        baseInput.put("dynamic_base_biz", "later");
        rumInput.clear();
        emit(DataType.RUM_APP, new HashMap<>(), RUMCustomKeys.newMap());
        assertEquals("modified", output.get().get("dynamic_base_biz"));
        assertEquals("modified", output.get().get("dynamic_rum_biz"));
        assertEquals(keys("base_biz", "rum_biz", "dynamic_base_biz", "dynamic_rum_biz"), emittedKeys());
    }

    @Test public void emptyTagsAreOmittedButEmptyFieldsAndNumericBooleanValuesRemain() {
        helper.appendGlobalContext(map("tag_null", null, "tag_json_null", JSONObject.NULL,
                "tag_empty", "", "zero_tag", 0, "false_tag", false));
        HashMap<String, Object> fields = RUMCustomKeys.userProperties(map("field_null", null,
                "field_json_null", JSONObject.NULL, "field_empty", "", "zero_field", 0, "false_field", false));
        String line = emit(DataType.RUM_APP, new HashMap<>(), fields);
        assertEquals(keys("zero_tag", "false_tag", "field_null", "field_json_null", "field_empty", "zero_field", "false_field"), emittedKeys());
        assertTrue(line.contains("field_null=\"\""));
        assertTrue(line.contains("zero_field=0"));
        assertEquals(Integer.class, fields.get("zero_field").getClass());
        assertFalse(line.contains("tag_empty="));
    }

    @Test public void tagAndFieldProvenanceCannotSubstituteForEachOther() {
        HashMap<String, Object> tags = RUMCustomKeys.userProperties(map("empty_tag", ""));
        HashMap<String, Object> fields = RUMCustomKeys.newMap();
        fields.put("empty_tag", "sdk-value");
        tags.put("sdk_tag", "sdk");
        RUMCustomKeys.putUserProperties(fields, map("sdk_tag", null));
        emit(DataType.RUM_APP, tags, fields);
        assertEquals(keys("sdk_tag"), emittedKeys());
    }

    @Test public void forgedListsAndExactSystemNamesAreExcludedWithoutPrefixFiltering() {
        helper.appendGlobalContext(map("custom_keys", "[\"forged\"]", "sdk_version", "sdk", "app_id", "app"));
        HashMap<String, Object> fields = RUMCustomKeys.userProperties(map("custom_keys", 123,
                "error_business", 1, "view_business", false, "duration", 99,
                "session_id", "session", "visitor_id", "visitor", "action_position", "heatmap",
                "app_launch_type", "cold", "container", "replay"));
        emit(DataType.RUM_APP, map("custom_keys", "not-json"), fields);
        assertEquals(keys("error_business", "view_business"), emittedKeys());
        assertFalse(fields.containsKey("custom_keys"));
    }

    @Test public void noUserDataProducesNoCustomKeys() {
        emit(DataType.RUM_APP, new HashMap<>(), map("duration", 1));
        assertFalse(output.get().containsKey("custom_keys"));
    }

    @Test public void duplicateKeysAndEscapedUnicodeNamesStayFirstLevel() throws Exception {
        String name = "中文\"key\\value";
        helper.appendGlobalContext(map(name, "global", "same", true));
        HashMap<String, Object> fields = RUMCustomKeys.userProperties(map(name, 1, "same", false,
                "nested", new JSONObject().put("child", 1)));
        emit(DataType.RUM_APP, new HashMap<>(), fields);
        assertEquals(keys(name, "same", "nested"), emittedKeys());
        assertEquals(3, new JSONArray(output.get().get("custom_keys").toString()).length());
        assertEquals(false, fields.get("same"));
        assertTrue(fields.get("nested") instanceof JSONObject);
    }

    @Test public void dataModifierPrecedesListAndListModifierRunsOnceBeforeLineModifier() {
        AtomicInteger listCalls = new AtomicInteger();
        helper = helper(FTSDKConfig.builder().setDataModifier((key, value) -> {
            if (key.equals("hidden")) return "";
            if (key.equals("custom_keys")) {
                listCalls.incrementAndGet();
                assertEquals(keys("biz"), RUMCustomKeys.parse(value));
                return "[\"final_override\"]";
            }
            return value;
        }).addGlobalContext("hidden", "before"), new FTRUMConfig());
        emit(DataType.RUM_APP, new HashMap<>(), RUMCustomKeys.userProperties(map("biz", 1)));
        assertEquals(1, listCalls.get());
        assertEquals(keys("final_override"), emittedKeys());
    }

    @Test public void lineModifierCanOverrideTheGeneratedList() {
        FTSDKConfig base = FTSDKConfig.builder().setLineDataModifier((measurement, values) -> {
            assertEquals(keys("biz"), RUMCustomKeys.parse(values.get("custom_keys")));
            return map("custom_keys", "line-override");
        });
        helper = new SyncDataHelper();
        helper.initBaseConfig(base);
        helper.initRUMConfig(new FTRUMConfig());
        assertTrue(emit(DataType.RUM_APP, new HashMap<>(), RUMCustomKeys.userProperties(map("biz", true)))
                .contains("custom_keys=line-override"));
    }

    @Test public void browserFieldAndTagListsMergeWithNativeSourcesInBothSamplingModes() throws Exception {
        helper = helper(FTSDKConfig.builder().addGlobalContext("app_channel", "native")
                .addGlobalContext("sdk_version", "native-sdk")
                .addGlobalContext("sdk_pkg_info", "{\"agent\":\"native-sdk\"}"),
                new FTRUMConfig().addGlobalContext("member_level", "3"));
        for (DataType type : new DataType[]{DataType.RUM_WEBVIEW, DataType.RUM_WEBVIEW_ERROR_SAMPLED}) {
            HashMap<String, Object> tags = RUMCustomKeys.userProperties(map("ext", "user"));
            tags.putAll(map("custom_keys", "[\"h5_tag\",\"app_channel\"]", "h5_tag", true,
                    "service", "browser", "sdk_version", "web-sdk", "visitor_id", "web-visitor"));
            HashMap<String, Object> fields = map("custom_keys", new JSONArray("[\"h5_biz\",\"session_id\",\"custom_keys\",\"missing\"]"), "h5_biz", 1);
            String line = emit(type, tags, fields);
            assertEquals(line.indexOf("custom_keys="), line.lastIndexOf("custom_keys="));
            assertEquals(keys("h5_biz", "h5_tag", "app_channel", "member_level", "ext"), emittedKeys());
            assertTrue(fields.containsKey("custom_keys"));
            assertEquals("browser", output.get().get("service"));
            assertEquals("web-visitor", output.get().get("visitor_id"));
            assertTrue(output.get().get("sdk_pkg_info").toString().contains("web-sdk"));
            assertEquals(1, fields.get("h5_biz"));
        }
    }

    @Test public void browserTagOnlyKeepsTagLocationAndNeverLeaksPreviousEventKeys() {
        HashMap<String, Object> fields = map("h5_one", 1);
        emit(DataType.RUM_WEBVIEW, map("custom_keys", "[\"h5_one\"]"), fields);
        assertEquals(keys("h5_one"), emittedKeys());
        assertFalse(fields.containsKey("custom_keys"));
        fields = map("h5_two", false);
        emit(DataType.RUM_WEBVIEW, map("custom_keys", "[\"h5_two\"]"), fields);
        assertEquals(keys("h5_two"), emittedKeys());
        assertFalse(fields.containsKey("custom_keys"));
    }

    @Test public void missingAndMalformedBrowserListsStillProduceNativeKeys() {
        helper.appendRUMGlobalContext("native", "yes");
        for (Object list : new Object[]{null, 12, "{bad-json", new JSONObject()}) {
            HashMap<String, Object> fields = map("browser_system", 1);
            if (list != null) fields.put("custom_keys", list);
            emit(DataType.RUM_WEBVIEW, new HashMap<>(), fields);
            assertEquals(keys("native"), emittedKeys());
            assertEquals(list != null, fields.containsKey("custom_keys"));
        }
    }

    @Test public void acceptedProviderFieldsOnlyAreMarkedAndForgedListIsRejected() {
        FTIssueFieldEnricher enricher = new FTIssueFieldEnricher(null);
        HashMap<String, Object> fields = RUMCustomKeys.newMap();
        fields.put("sdk_field", 1);
        HashMap<String, Object> tags = map("sdk_tag", true);
        enricher.mergeAdditionalFields(map("accepted", 2.5, "sdk_field", 3, "sdk_tag", false,
                "reserved", true, "custom_keys", "[\"forged\"]"), tags, fields, keys("reserved"));
        emit(DataType.RUM_APP, tags, fields);
        assertEquals(keys("accepted"), emittedKeys());
        assertFalse(fields.containsKey("reserved"));
        assertFalse(fields.containsKey("custom_keys"));
        assertEquals(1, fields.get("sdk_field"));
    }

    @Test public void actionAndViewAttributesPreserveSourcesAndOldCachesDoNotInferThem() throws Exception {
        ActionBean action = new ActionBean();
        action.setCollectType(CollectType.COLLECT_BY_SAMPLE);
        RUMCustomKeys.putUserProperties(action.getProperty(), map("action_biz", 1));
        action.getProperty().put("internal", true);
        action.setTags(RUMCustomKeys.userProperties(map("user_ext", "a")));
        String attr = action.getAttrJsonString();
        assertNotNull(new JSONObject(attr).optJSONObject(RUMCustomKeys.CACHE_KEY));
        ActionBean restored = new ActionBean();
        restored.setFromAttrJsonString(attr);
        String line = emit(DataType.RUM_APP, restored.getTags(), RUMCustomKeys.copy(restored.getProperty()));
        assertEquals(keys("action_biz", "user_ext"), emittedKeys());
        assertFalse(line.contains(RUMCustomKeys.CACHE_KEY));
        assertTrue(line.contains("internal=true"));
        JSONObject old = new JSONObject(attr);
        old.remove(RUMCustomKeys.CACHE_KEY);
        restored = new ActionBean();
        restored.setFromAttrJsonString(old.toString());
        emit(DataType.RUM_APP, restored.getTags(), RUMCustomKeys.copy(restored.getProperty()));
        assertTrue(emittedKeys().isEmpty());
        assertEquals(1, restored.getProperty().get("action_biz"));

        ViewBean view = new ViewBean();
        RUMCustomKeys.putUserProperties(view.getProperty(), map("view_start", true));
        RUMCustomKeys.putUserProperties(view.getProperty(), map("view_stop", false));
        view.setTags(RUMCustomKeys.userProperties(map("user_ext", "a")));
        ViewBean restoredView = new ViewBean();
        restoredView.setFromAttrJsonString(view.getAttrJsonString());
        emit(DataType.RUM_APP, restoredView.getTags(), RUMCustomKeys.copy(restoredView.getProperty()));
        assertEquals(keys("view_start", "view_stop", "user_ext"), emittedKeys());
    }

    @Test public void serializedRowsAreStableAfterGlobalChanges() {
        helper.appendGlobalContext("global", "first");
        SyncData data = SyncData.getSyncData(helper, DataType.RUM_APP,
                new LineProtocolBean("action", RUMCustomKeys.newMap(),
                        RUMCustomKeys.userProperties(map("biz", true)), 1), 0);
        String serialized = data.getDataString();
        helper.appendGlobalContext("later", "second");
        assertEquals(serialized, data.getDataString());
        assertFalse(serialized.contains("later"));
    }

    @Test public void linkedLogsRetainRumGlobalListWithoutAddingEventProperties() {
        helper = helper(FTSDKConfig.builder().addGlobalContext("base", "1"),
                new FTRUMConfig().addGlobalContext("rum", "2"));
        helper.initLogConfig(new FTLoggerConfig().setEnableLinkRumData(true));
        helper.appendRUMGlobalContext("dynamic_rum", "yes");
        emit(DataType.LOG, new HashMap<>(), RUMCustomKeys.userProperties(map("log_only", 1)));
        assertEquals(keys("rum", "dynamic_rum"), emittedKeys());
    }

    @Test public void linkedLogGlobalListStillReceivesItsDataModifier() {
        AtomicInteger calls = new AtomicInteger();
        helper = helper(FTSDKConfig.builder().setDataModifier((key, value) -> {
            if (key.equals("custom_keys")) {
                calls.incrementAndGet();
                return "log-override";
            }
            return value;
        }), new FTRUMConfig().addGlobalContext("rum", "value"));
        helper.initLogConfig(new FTLoggerConfig().setEnableLinkRumData(true));
        emit(DataType.LOG, new HashMap<>(), map("message", "kept"));
        assertEquals("log-override", output.get().get("custom_keys"));
        assertEquals(1, calls.get());
    }

    @Test public void automaticActionMapKeepsOnlyHandlerSourcesBeforeSystemMerge() throws Exception {
        java.lang.reflect.Method merge = FTAutoTrack.class.getDeclaredMethod("mergeActionProperties",
                HashMap.class, HashMap.class);
        merge.setAccessible(true);
        HashMap<String, Object> user = map("handler_biz", false, "action_position", "user-collision");
        @SuppressWarnings("unchecked") HashMap<String, Object> mixed = (HashMap<String, Object>) merge.invoke(null,
                map("internal_metric", 1, "action_position", "heatmap"), user);
        emit(DataType.RUM_APP, new HashMap<>(), mixed);
        assertEquals(keys("handler_biz"), emittedKeys());
        assertEquals(false, output.get().get("handler_biz"));
        assertEquals("user-collision", output.get().get("action_position"));
        assertFalse(user.containsKey("internal_metric"));
    }

    @Test public void filtersReceiveGeneratedListAfterLineModifierWithoutExtraCallbacks() {
        AtomicInteger calls = new AtomicInteger();
        HashMap<String, String[]> filters = new HashMap<>();
        filters.put("rum", new String[]{"{ source in ['action'] and custom_keys in ['filtered-list'] }"});
        FTSDKConfig base = FTSDKConfig.builder().setEnableDataFilter(true).setDataFilters(filters)
                .setLineDataModifier((measurement, values) -> {
                    calls.incrementAndGet();
                    assertEquals(keys("biz"), RUMCustomKeys.parse(values.get("custom_keys")));
                    return map("custom_keys", "filtered-list");
                });
        FTDataFilterManager.get().init(base);
        helper = new SyncDataHelper();
        helper.initBaseConfig(base);
        helper.initRUMConfig(new FTRUMConfig());
        assertEquals("", emit(DataType.RUM_APP, new HashMap<>(), RUMCustomKeys.userProperties(map("biz", 1))));
        assertEquals(1, calls.get());
    }

    @Test public void concurrentAppendPublishesValuesAndKeysTogether() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) helper.appendGlobalContext(map("key_" + i, i));
            } catch (Throwable error) { failure.set(error); }
        });
        writer.start();
        try {
            for (int i = 0; i < 200; i++) {
                emit(DataType.RUM_APP, new HashMap<>(), RUMCustomKeys.newMap());
                Map<String, Object> snapshot = output.get();
                Set<String> actual = new HashSet<>(snapshot.keySet());
                actual.removeAll(keys("sdk_data_id", "duration", "session_sample_rate", "session_on_error_sample_rate", "custom_keys"));
                assertEquals(actual, emittedKeys());
            }
        } finally { writer.join(); }
        assertNull(failure.get());
    }

    @Test public void interruptedPropertyCopyDropsSnapshotWithoutThrowing() {
        assertTrue(RUMCustomKeys.copy(changingInput()).isEmpty());
    }

    @Test public void interruptedPropertyMergePreservesPreviouslyAcceptedFields() {
        HashMap<String, Object> fields = RUMCustomKeys.userProperties(map("existing", 1));
        RUMCustomKeys.putUserProperties(fields, changingInput());
        assertEquals(map("existing", 1), fields);
        assertEquals(keys("existing"), RUMCustomKeys.parse(
                new RUMCustomKeys(null, fields).serialize(null, fields, null, null)));
        assertTrue(RUMCustomKeys.userKeys(changingInput()).isEmpty());
    }

    @Test public void interruptedSourceSnapshotPreservesEventValuesWithoutThrowing() throws Exception {
        HashMap<String, Object> fields = RUMCustomKeys.userProperties(map("biz", 1));
        interruptSourceIteration(fields);
        assertNull(new RUMCustomKeys(null, fields).serialize(null, fields, null, null));
        HashMap<String, Object> copied = RUMCustomKeys.copy(fields);
        assertEquals(1, copied.get("biz"));
        assertNull(new RUMCustomKeys(null, copied).serialize(null, copied, null, null));
    }

    @Test public void interruptedSourceSnapshotDoesNotEscapeViewOrActionCacheEncoding() throws Exception {
        ViewBean view = new ViewBean();
        RUMCustomKeys.putUserProperties(view.getProperty(), map("biz", 1));
        interruptSourceIteration(view.getProperty());
        assertEquals(1, new JSONObject(view.getAttrJsonString()).getJSONObject("property").getInt("biz"));

        ActionBean action = new ActionBean();
        RUMCustomKeys.putUserProperties(action.getProperty(), map("biz", 2));
        interruptSourceIteration(action.getProperty());
        assertEquals(2, new JSONObject(action.getAttrJsonString()).getJSONObject("property").getInt("biz"));
    }

    // Deterministic fault injection for fail-fast collection reads; no scheduling-dependent test.
    private static HashMap<String, Object> changingInput() {
        HashMap<String, Object> values = new HashMap<String, Object>() {
            @Override public Set<Map.Entry<String, Object>> entrySet() {
                throw new ConcurrentModificationException("input changed during traversal");
            }
            @Override public Set<String> keySet() {
                throw new ConcurrentModificationException("input changed during traversal");
            }
        };
        values.put("biz", 1);
        return values;
    }

    private static void interruptSourceIteration(HashMap<String, Object> values) throws Exception {
        Set<String> changing = new LinkedHashSet<String>() {
            @Override public Iterator<String> iterator() {
                throw new ConcurrentModificationException("source changed during traversal");
            }
        };
        changing.add("biz");
        java.lang.reflect.Field sources = values.getClass().getDeclaredField("userKeys");
        sources.setAccessible(true);
        sources.set(values, changing);
    }
}
