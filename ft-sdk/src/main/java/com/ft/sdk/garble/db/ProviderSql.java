package com.ft.sdk.garble.db;

import android.content.ContentValues;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.ft.sdk.garble.db.FTSQL.*;

/** SQL shapes used across the SDK's private ContentProvider boundary. */
final class ProviderSql {
    static final String BY_ID = RUM_COLUMN_ID + "=?";
    static final String CLOSEABLE_ACTION = BY_ID + " AND " + RUM_COLUMN_PENDING_RESOURCE + "<=0";
    static final String CLOSED_VIEW = RUM_COLUMN_IS_CLOSE + "=1 AND " + RUM_COLUMN_PENDING_RESOURCE + "<=0";
    static final String PENDING_VIEWS = "(" + RUM_COLUMN_IS_CLOSE + "=1 AND (" + RUM_DATA_UPLOAD_TIME
            + "<" + RUM_DATA_UPDATE_TIME + " OR " + RUM_DATA_UPLOAD_TIME + "=0)) OR " + RUM_COLUMN_IS_CLOSE + "=0";
    static final String EXPIRED = RECORD_COLUMN_DATA_TYPE + "=? AND " + RECORD_COLUMN_TM + "<?";
    static final String ERROR_SAMPLED = RECORD_COLUMN_DATA_TYPE + "=? AND " + RECORD_COLUMN_TM + "<=?";
    static final String BY_TYPE = RECORD_COLUMN_DATA_TYPE + "=?";
    static final String COUNT = "COUNT(*)";

    private static final Set<String> LEGACY_COLUMNS = columns(RECORD_COLUMN_ID, RECORD_COLUMN_TM,
            RECORD_COLUMN_DATA, RECORD_COLUMN_DATA_TYPE);
    private static final Set<String> SYNC_COLUMNS = columns(RECORD_COLUMN_ID, RECORD_COLUMN_TM,
            RECORD_COLUMN_DATA, RECORD_COLUMN_DATA_TYPE, RECORD_COLUMN_DATA_UUID);
    private static final Set<String> ACTION_COLUMNS = columns(RUM_COLUMN_ID, RUM_COLUMN_START_TIME,
            RUM_COLUMN_IS_CLOSE, RUM_COLUMN_ERROR_COUNT, RUM_COLUMN_LONG_TASK_COUNT, RUM_COLUMN_RESOURCE_COUNT,
            RUM_COLUMN_PENDING_RESOURCE, RUM_COLUMN_SESSION_ID, RUM_COLUMN_VIEW_NAME, RUM_COLUMN_VIEW_ID,
            RUM_COLUMN_VIEW_REFERRER, RUM_COLUMN_ACTION_NAME, RUM_COLUMN_ACTION_TYPE, RUM_COLUMN_ACTION_DURATION,
            RUM_COLUMN_EXTRA_ATTR);
    private static final Set<String> VIEW_COLUMNS = columns(RUM_COLUMN_ID, RUM_COLUMN_START_TIME,
            RUM_COLUMN_IS_CLOSE, RUM_COLUMN_ERROR_COUNT, RUM_COLUMN_LONG_TASK_COUNT, RUM_COLUMN_RESOURCE_COUNT,
            RUM_COLUMN_PENDING_RESOURCE, RUM_COLUMN_SESSION_ID, RUM_COLUMN_VIEW_NAME, RUM_COLUMN_VIEW_REFERRER,
            RUM_COLUMN_ACTION_COUNT, RUM_COLUMN_VIEW_LOAD_TIME, RUM_COLUMN_VIEW_TIME_SPENT, RUM_DATA_UPDATE_TIME,
            RUM_DATA_UPLOAD_TIME, RUM_VIEW_UPDATE_TIME, RUM_COLUMN_EXTRA_ATTR);

    private ProviderSql() {}

    private static Set<String> columns(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static Set<String> columnsFor(String table) {
        switch (table) {
            case FT_SYNC_OLD_CACHE_TABLE_NAME: return LEGACY_COLUMNS;
            case FT_SYNC_DATA_FLAT_TABLE_NAME: return SYNC_COLUMNS;
            case FT_TABLE_ACTION: return ACTION_COLUMNS;
            case FT_TABLE_VIEW: return VIEW_COLUMNS;
            default: throw new IllegalArgumentException("Unknown SDK table");
        }
    }

    static boolean validValues(String table, ContentValues values) {
        return values != null && columnsFor(table).containsAll(values.keySet());
    }

    static boolean validProjection(String table, String[] projection) {
        if (projection == null) return true;
        Set<String> allowed = columnsFor(table);
        for (String column : projection) {
            if (!allowed.contains(column) && !COUNT.equals(column)) return false;
        }
        return true;
    }

    static String byTypes(int count) {
        return count == 1 ? BY_TYPE : in(RECORD_COLUMN_DATA_TYPE, count);
    }

    static String in(String column, int count) {
        if (count <= 0) throw new IllegalArgumentException("Empty database filter");
        StringBuilder clause = new StringBuilder(column).append(" IN (?");
        for (int i = 1; i < count; i++) {
            clause.append(",?");
        }
        return clause.append(')').toString();
    }

    static String selection(String clause, String[] args) {
        int count = args == null ? 0 : args.length;
        if (clause == null && count == 0) return null;
        if (clause == null) throw new IllegalArgumentException("Filter arguments without a filter");
        switch (clause) {
            case BY_ID: return checked(BY_ID, 1, count);
            case CLOSEABLE_ACTION: return checked(CLOSEABLE_ACTION, 1, count);
            case BY_TYPE: return checked(BY_TYPE, 1, count);
            case RECORD_COLUMN_ID + "=?": return checked(RECORD_COLUMN_ID + "=?", 1, count);
            case RECORD_COLUMN_DATA_UUID + "=?": return checked(RECORD_COLUMN_DATA_UUID + "=?", 1, count);
            case RUM_COLUMN_IS_CLOSE + "=?": return checked(RUM_COLUMN_IS_CLOSE + "=?", 1, count);
            case EXPIRED: return checked(EXPIRED, 2, count);
            case ERROR_SAMPLED: return checked(ERROR_SAMPLED, 2, count);
            case CLOSED_VIEW: return checked(CLOSED_VIEW, 0, count);
            case PENDING_VIEWS: return checked(PENDING_VIEWS, 0, count);
            default:
                if (count > 0) {
                    String column = clause.startsWith(RUM_COLUMN_ID + " IN (") ? RUM_COLUMN_ID : RECORD_COLUMN_DATA_TYPE;
                    String bound = in(column, count);
                    if (bound.equals(clause)) return bound;
                }
                throw new IllegalArgumentException("Unsupported SDK database filter");
        }
    }

    private static String checked(String clause, int expected, int actual) {
        if (expected != actual) throw new IllegalArgumentException("Incorrect number of filter arguments");
        return clause;
    }

    static String order(String order) {
        if (order == null) return null;
        int offset = order.indexOf(" LIMIT ");
        String base = offset < 0 ? order : order.substring(0, offset);
        switch (base) {
            case RECORD_COLUMN_ID + " asc": base = RECORD_COLUMN_ID + " asc"; break;
            case RECORD_COLUMN_ID + " desc": base = RECORD_COLUMN_ID + " desc"; break;
            case RUM_COLUMN_START_TIME + " ASC": base = RUM_COLUMN_START_TIME + " ASC"; break;
            default: throw new IllegalArgumentException("Unsupported SDK database order");
        }
        return offset < 0 ? base : base + " LIMIT " + Integer.parseInt(order.substring(offset + 7));
    }

    enum AggregateUpdate {
        ACTION_ERRORS(FT_TABLE_ACTION, RUM_COLUMN_ERROR_COUNT, 1),
        ACTION_RESOURCES(FT_TABLE_ACTION, RUM_COLUMN_RESOURCE_COUNT, 1),
        ACTION_LONG_TASKS(FT_TABLE_ACTION, RUM_COLUMN_LONG_TASK_COUNT, 1),
        ACTION_PENDING_ADD(FT_TABLE_ACTION, RUM_COLUMN_PENDING_RESOURCE, 1),
        ACTION_PENDING_REMOVE(FT_TABLE_ACTION, RUM_COLUMN_PENDING_RESOURCE, -1),
        VIEW_ACTIONS(FT_TABLE_VIEW, RUM_COLUMN_ACTION_COUNT, 1),
        VIEW_ERRORS(FT_TABLE_VIEW, RUM_COLUMN_ERROR_COUNT, 1),
        VIEW_RESOURCES(FT_TABLE_VIEW, RUM_COLUMN_RESOURCE_COUNT, 1),
        VIEW_LONG_TASKS(FT_TABLE_VIEW, RUM_COLUMN_LONG_TASK_COUNT, 1),
        VIEW_PENDING_ADD(FT_TABLE_VIEW, RUM_COLUMN_PENDING_RESOURCE, 1),
        VIEW_PENDING_REMOVE(FT_TABLE_VIEW, RUM_COLUMN_PENDING_RESOURCE, -1),
        VIEW_UPLOAD_TIME(RUM_DATA_UPLOAD_TIME),
        VIEW_UPDATE_TIME(RUM_DATA_UPDATE_TIME);

        final String sql;
        final boolean usesTimestamp;

        AggregateUpdate(String table, String column, int delta) {
            sql = "UPDATE " + table + " SET " + column + "=" + column + (delta > 0 ? "+1" : "-1")
                    + " WHERE " + BY_ID;
            usesTimestamp = false;
        }

        AggregateUpdate(String timeColumn) {
            sql = "UPDATE " + FT_TABLE_VIEW + " SET " + timeColumn + "=?," + RUM_VIEW_UPDATE_TIME
                    + "=" + RUM_VIEW_UPDATE_TIME + "+1 WHERE " + BY_ID;
            usesTimestamp = true;
        }
    }
}
