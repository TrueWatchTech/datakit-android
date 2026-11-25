package com.ft.sdk.garble.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.ft.sdk.garble.bean.ActiveViewBean;
import com.ft.sdk.garble.bean.CollectType;
import com.ft.sdk.garble.bean.DataType;
import com.ft.sdk.garble.bean.ViewBean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import static com.ft.sdk.garble.db.FTSQL.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProviderSqlRegressionTest {
    private String previousName;
    private FTContentProvider provider;
    private FTDBManager store;
    private Uri actions;
    private Uri views;
    private Uri sync;

    @Before
    public void setUp() {
        previousName = FTDBConfig.DATABASE_NAME;
        FTDBConfig.DATABASE_NAME = "provider-regression-" + System.nanoTime() + ".db";
        FTDBManager.release();
        provider = Robolectric.buildContentProvider(FTContentProvider.class).create().get();
        views = FTContentProvider.getUriViewData();
        actions = FTContentProvider.getUriActionData();
        sync = FTContentProvider.getUriSyncDataFlat();
        ShadowContentResolver.registerProviderInternal(views.getAuthority(), provider);
        store = FTDBManager.get();
    }

    @After
    public void tearDown() {
        FTDBManager.release();
        RuntimeEnvironment.getApplication().deleteDatabase(FTDBConfig.DATABASE_NAME);
        FTDBConfig.DATABASE_NAME = previousName;
    }

    @Test
    public void aggregateCountersAndCloseConditionsUseExactIds() {
        String id = "customer's action";
        insertAggregate(actions, id);
        insertAggregate(actions, "other");
        store.increaseActionError(id);
        store.increaseActionResource(id);
        store.increaseActionLongTask(id);
        store.increaseActionPendingResource(id);
        store.closeAction(id, 12, false);
        assertEquals(0, number(actions, id, RUM_COLUMN_IS_CLOSE));
        store.reduceActionPendingResource(id);
        store.closeAction(id, 12, false);
        assertEquals(1, number(actions, id, RUM_COLUMN_IS_CLOSE));
        assertEquals(12, number(actions, id, RUM_COLUMN_ACTION_DURATION));
        assertEquals(1, number(actions, id, RUM_COLUMN_RESOURCE_COUNT));
        assertEquals(1, number(actions, id, RUM_COLUMN_LONG_TASK_COUNT));
        assertEquals(1, number(actions, id, RUM_COLUMN_ERROR_COUNT));
        assertEquals(0, number(actions, "other", RUM_COLUMN_ERROR_COUNT));
        store.increaseActionPendingResource("other");
        store.closeAction("other", 21, true);
        assertEquals(1, number(actions, "other", RUM_COLUMN_IS_CLOSE));
        store.cleanCloseActionData(new String[]{id, "missing"});
        assertEquals(1, count(actions));

        insertAggregate(views, id);
        store.increaseViewAction(id);
        store.increaseViewError(id);
        store.increaseViewLongTask(id);
        store.increaseViewResource(id);
        store.increaseViewPendingResource(id);
        store.reduceViewPendingResource(id);
        store.updateViewUpdateTime(id, 123);
        store.updateViewUploadTime(id, 456);
        assertEquals(1, number(views, id, RUM_COLUMN_ACTION_COUNT));
        assertEquals(1, number(views, id, RUM_COLUMN_ERROR_COUNT));
        assertEquals(1, number(views, id, RUM_COLUMN_LONG_TASK_COUNT));
        assertEquals(1, number(views, id, RUM_COLUMN_RESOURCE_COUNT));
        assertEquals(0, number(views, id, RUM_COLUMN_PENDING_RESOURCE));
        assertEquals(123, number(views, id, RUM_DATA_UPDATE_TIME));
        assertEquals(456, number(views, id, RUM_DATA_UPLOAD_TIME));
        assertEquals(3, number(views, id, RUM_VIEW_UPDATE_TIME));
        store.cleanCloseViewData();
        assertEquals(1, count(views));
        store.closeAllActionAndView();
        assertEquals(0, number(actions, "other", RUM_COLUMN_PENDING_RESOURCE));
        assertEquals(1, number(views, id, RUM_COLUMN_IS_CLOSE));
        store.cleanCloseViewData();
        assertEquals(0, count(views));
    }

    @Test
    public void cacheQueriesAndCleanupPreserveTypeTimeOrderAndLimit() {
        ContentValues[] rows = {
                record("late", "log", 30), record("early", "rum_app", 10),
                record("middle", "log", 20), record("pending", "historical_pending_rum_app", 5),
                record("sampled", "rum_app_error_sampled", 40)
        };
        assertEquals(rows.length, provider.bulkInsert(sync, rows));
        DataType[] types = {DataType.LOG, DataType.RUM_APP};
        assertEquals(5, store.queryTotalCount(new DataType[0]));
        assertEquals(3, store.queryTotalCount(types));
        assertEquals("late", store.queryDataByDataByTypeLimit(1, DataType.LOG).get(0).getUuid());
        assertEquals("middle", store.queryDataByDataByTypeLimitDesc(1, DataType.LOG).get(0).getUuid());
        store.deleteOldestData(types, 0);
        store.deleteOldestData(new DataType[0], 3);
        assertEquals(5, count(sync));
        store.deleteOldestData(types, 2);
        assertEquals(3, count(sync));
        assertEquals("late", store.queryDataByDataByTypeLimit(1, DataType.LOG).get(0).getUuid());
        assertEquals(0, store.deleteExpireCache(DataType.LOG, 40, 10));
        assertEquals(1, store.deleteExpireCache(DataType.LOG, 41, 10));
        assertEquals(1, store.updateDataType(DataType.RUM_APP_ERROR_SAMPLED, 40));
        assertEquals(1, store.queryTotalCount(DataType.RUM_APP));
        store.deleteOldestData(types, -1);
        assertEquals(1, count(sync));
        assertEquals(0, store.queryTotalCount(types));
    }

    @Test
    public void legacyCacheStillSupportsPagingAndDeletionByRowId() {
        store.getDB(true, db -> db.execSQL("CREATE TABLE sync_data "
                + "(_id INTEGER PRIMARY KEY, tm INTEGER, data TEXT, type TEXT)"));
        ContentValues legacy = record("unused", "log", 10);
        legacy.remove(RECORD_COLUMN_DATA_UUID);
        assertNotNull(provider.insert(FTContentProvider.getUriSyncData(), legacy));
        assertTrue(store.isOldCacheExist());
        assertEquals("body", store.queryDataByDescLimit(1, true).get(0).getDataString());
        assertEquals("", store.queryDataByDescLimit(1, true).get(0).getUuid());
        store.delete(java.util.Collections.singletonList(1L), true);
        assertFalse(store.isOldCacheExist());
    }

    @Test
    public void pendingViewsRetainUploadFilterAndStartTimeOrdering() {
        for (String id : new String[]{"closed", "stale", "open"}) {
            ActiveViewBean active = new ActiveViewBean(id, "parent", 0, "session");
            active.setCollectType(CollectType.COLLECT_BY_SAMPLE);
            ViewBean view = active.convertToViewBean();
            view.setId(id);
            store.initSumView(view);
        }
        ContentValues values = new ContentValues();
        values.put(RUM_COLUMN_START_TIME, 10L);
        values.put(RUM_COLUMN_IS_CLOSE, 1);
        values.put(RUM_DATA_UPLOAD_TIME, 20L);
        values.put(RUM_DATA_UPDATE_TIME, 20L);
        assertEquals(1, provider.update(views, values, "id=?", new String[]{"closed"}));
        values.put(RUM_COLUMN_START_TIME, 15L);
        values.put(RUM_DATA_UPDATE_TIME, 21L);
        assertEquals(1, provider.update(views, values, "id=?", new String[]{"stale"}));
        values.put(RUM_COLUMN_IS_CLOSE, 0);
        values.put(RUM_COLUMN_START_TIME, 30L);
        assertEquals(1, provider.update(views, values, "id=?", new String[]{"open"}));
        assertEquals(2, store.querySumView(0).size());
        assertEquals("stale", store.querySumView(1).get(0).getId());
        assertEquals("closed", store.querySumView(1, true).get(0).getId());
    }

    @Test
    public void unsupportedCommandsAndColumnsCannotChangeRows() {
        insertAggregate(views, "keep");
        ContentValues unknown = new ContentValues();
        unknown.put("unknown_column", 0);
        assertNull(provider.insert(views, unknown));
        assertEquals(0, provider.update(views, unknown, null, null));
        assertEquals(2, provider.bulkInsert(sync, new ContentValues[]{record("a", "log", 1), unknown,
                record("b", "log", 2)}));
        assertEquals(2, count(sync));
        assertNull(provider.query(views, new String[]{"unknown_column"}, null, null, null));
        assertNull(provider.query(views, null, "unsupported filter", null, null));
        assertNull(provider.query(views, null, null, null, "unsupported order"));
        assertEquals(0, provider.delete(views, "id=?", null));
        Bundle legacy = new Bundle();
        legacy.putString("sql", "SELECT 1");
        legacy.putStringArray("sql_array", new String[]{"SELECT 1"});
        assertFalse(provider.call(FTContentProvider.METHOD_EXEC_SQL, null, legacy).getBoolean("success"));
        assertFalse(provider.call(FTContentProvider.METHOD_EXEC_SQL_BATCH, null, legacy).getBoolean("success"));
        assertEquals(1, count(views));
    }

    private void insertAggregate(Uri target, String id) {
        ContentValues values = new ContentValues();
        values.put(RUM_COLUMN_ID, id);
        values.put(RUM_COLUMN_IS_CLOSE, 0);
        values.put(RUM_COLUMN_ERROR_COUNT, 0);
        values.put(RUM_COLUMN_LONG_TASK_COUNT, 0);
        values.put(RUM_COLUMN_RESOURCE_COUNT, 0);
        values.put(RUM_COLUMN_PENDING_RESOURCE, 0);
        if (views.equals(target)) values.put(RUM_COLUMN_ACTION_COUNT, 0);
        assertNotNull(provider.insert(target, values));
    }

    private ContentValues record(String uuid, String type, long time) {
        ContentValues values = new ContentValues();
        values.put(RECORD_COLUMN_DATA_UUID, uuid);
        values.put(RECORD_COLUMN_DATA_TYPE, type);
        values.put(RECORD_COLUMN_TM, time);
        values.put(RECORD_COLUMN_DATA, "body");
        return values;
    }

    private long number(Uri target, String id, String column) {
        try (Cursor cursor = provider.query(target, new String[]{column}, "id=?", new String[]{id}, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private int count(Uri target) {
        try (Cursor cursor = provider.query(target, new String[]{"COUNT(*)"}, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }
}
