package com.ft.sdk;

import com.ft.sdk.garble.bean.DataType;
import com.ft.sdk.garble.bean.SyncData;
import com.ft.sdk.garble.db.FTContentProvider;
import com.ft.sdk.garble.db.FTDBConfig;
import com.ft.sdk.garble.db.FTDBManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.ft.sdk.garble.bean.DataType.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SqliteUploadQueueTest {
    private String previousName;
    private FTDBManager store;

    @Before
    public void setUp() {
        previousName = FTDBConfig.DATABASE_NAME;
        FTDBConfig.DATABASE_NAME = "upload-queue-" + System.nanoTime() + ".db";
        FTDBManager.release();
        FTContentProvider provider = Robolectric.buildContentProvider(FTContentProvider.class).create().get();
        ShadowContentResolver.registerProviderInternal(FTContentProvider.getUriSyncDataFlat().getAuthority(), provider);
        store = FTDBManager.get();
    }

    @After
    public void tearDown() {
        FTDBManager.release();
        RuntimeEnvironment.getApplication().deleteDatabase(FTDBConfig.DATABASE_NAME);
        FTDBConfig.DATABASE_NAME = previousName;
    }

    @Test
    public void uploadPagesPreserveRowsAndPayloadAcrossWritesAndAcknowledgements() {
        SyncData first = record(LOG, "shop'01", 90);
        SyncData view = record(RUM_APP, "view01", 20);
        SyncData second = record(LOG, "log02", 10);
        SyncData third = record(LOG, "log03", 40);
        assertTrue(store.insertFtOperation(first, false));
        assertTrue(store.insertFtOptList(Arrays.asList(view, second, third), false));
        assertTrue(store.insertFtOptList(Collections.emptyList(), false));

        second.setTime(31);
        second.setDataString("custom_log,sdk_data_id=log02 message=\"买家 O'Brien 已支付\" 31\n");
        assertTrue(store.updateOrInsertSyncData(second));
        assertFalse(store.updateOrInsertSyncData(record(LOG, "absent", 12)));
        assertEquals(4, store.queryTotalCount(new DataType[]{LOG, RUM_APP}));
        assertEquals(3, store.queryTotalCount(LOG));

        List<SyncData> page = store.queryDataByDataByTypeLimit(2, LOG);
        assertEquals(2, page.size());
        assertRow(first, page.get(0), 1);
        assertRow(second, page.get(1), 3);
        List<SyncData> newest = store.queryDataByDataByTypeLimitDesc(2, LOG);
        assertEquals(2, newest.size());
        assertRow(third, newest.get(0), 4);
        assertRow(second, newest.get(1), 3);

        String expectedBody = first.getDataString().replace("sdk_data_id=", "sdk_data_id=pkg.")
                + second.getDataString().replace("sdk_data_id=", "sdk_data_id=pkg.");
        assertEquals(expectedBody, SyncTaskManager.buildRequestBody(page, "pkg"));
        assertRow(first, store.queryDataByDataByTypeLimit(1, LOG).get(0), 1);
        store.delete(Arrays.asList(page.get(0).getId(), page.get(1).getId()), false);
        List<SyncData> next = store.queryDataByDataByTypeLimit(2, LOG);
        assertEquals(1, next.size());
        assertRow(third, next.get(0), 4);
        assertRow(view, store.queryDataByDataByTypeLimit(1, RUM_APP).get(0), 2);
    }

    @Test
    public void retryRekeysOnlyTheLineProtocolId() {
        SyncData original = record(LOG, "shop'01", 12);
        assertTrue(store.insertFtOperation(original, false));
        List<SyncData> page = store.queryDataByDataByTypeLimit(1, LOG);
        SyncTaskManager.buildRequestBody(page, "pkg");
        store.delete(Collections.singletonList(page.get(0).getId()), false);
        assertTrue(store.insertFtOptList(page, true));
        assertEquals(1, store.queryTotalCount(LOG));
        SyncData retried = store.queryDataByDataByTypeLimit(1, LOG).get(0);
        assertNotEquals(original.getUuid(), retried.getUuid());
        assertFalse(retried.getUuid().isEmpty());
        assertEquals(12, retried.getTime());
        assertEquals(2, retried.getId());
        assertEquals(original.getDataString().replace("sdk_data_id=shop'01",
                "sdk_data_id=pkg." + retried.getUuid()), retried.getDataString());
    }

    @Test
    public void batchInsertAndDeleteSpanTheTransactionThreshold() {
        List<SyncData> batch = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            batch.add(record(LOG, "buyer'" + i, 100 - i));
        }
        SyncData retained = record(RUM_APP, "checkout", 1);
        assertTrue(store.insertFtOptList(batch, false));
        assertTrue(store.insertFtOperation(retained, false));
        List<SyncData> page = store.queryDataByDataByTypeLimit(11, LOG);
        assertEquals(11, page.size());
        List<Long> acknowledged = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            assertRow(batch.get(i), page.get(i), i + 1);
            acknowledged.add(page.get(i).getId());
        }
        store.delete(acknowledged, false);
        assertEquals(2, store.queryTotalCount(new DataType[0]));
        assertRow(batch.get(11), store.queryDataByDataByTypeLimit(1, LOG).get(0), 12);
        assertRow(retained, store.queryDataByDataByTypeLimit(1, RUM_APP).get(0), 13);
    }

    @Test
    public void typePromotionExpiryAndOldestCleanupPreserveOtherTypesAndCutoffs() {
        SyncData atCutoff = record(RUM_APP_ERROR_SAMPLED, "at", 20);
        SyncData afterCutoff = record(RUM_APP_ERROR_SAMPLED, "after", 21);
        SyncData oldLog = record(LOG, "old", 10);
        SyncData boundaryLog = record(LOG, "equal", 20);
        SyncData web = record(RUM_WEBVIEW, "web", 1);
        assertTrue(store.insertFtOptList(Arrays.asList(afterCutoff, atCutoff, boundaryLog, web, oldLog), false));
        assertEquals(1, store.updateDataType(RUM_APP_ERROR_SAMPLED, 20));
        List<SyncData> promoted = store.queryDataByDataByTypeLimit(0, RUM_APP);
        assertEquals(1, promoted.size());
        assertEquals("at", promoted.get(0).getUuid());
        assertEquals(atCutoff.getDataString(), promoted.get(0).getDataString());
        assertRow(afterCutoff, store.queryDataByDataByTypeLimit(0, RUM_APP_ERROR_SAMPLED).get(0), 1);
        assertEquals(1, store.deleteExpireCache(LOG, 30, 10));
        assertRow(boundaryLog, store.queryDataByDataByTypeLimit(0, LOG).get(0), 3);

        // The earliest eligible timestamp belongs to the fourth inserted row.
        store.deleteOldestData(new DataType[]{RUM_WEBVIEW, LOG}, 1);
        assertEquals(0, store.queryTotalCount(RUM_WEBVIEW));
        assertEquals(3, store.queryTotalCount(new DataType[0]));
        assertRow(boundaryLog, store.queryDataByDataByTypeLimit(0, LOG).get(0), 3);
    }

    private static SyncData record(DataType type, String uuid, long time) {
        SyncData row = new SyncData(type);
        row.setUuid(uuid);
        row.setTime(time);
        String measurement = type == LOG ? "custom_log" : "view";
        row.setDataString(measurement + ",sdk_data_id=" + uuid + " message=\"O'Brien 的订单\" " + time + "\n");
        return row;
    }

    private static void assertRow(SyncData expected, SyncData actual, long rowId) {
        assertEquals(rowId, actual.getId());
        assertEquals(expected.getTime(), actual.getTime());
        assertEquals(expected.getDataType(), actual.getDataType());
        assertEquals(expected.getUuid(), actual.getUuid());
        assertEquals(expected.getDataString(), actual.getDataString());
    }
}
