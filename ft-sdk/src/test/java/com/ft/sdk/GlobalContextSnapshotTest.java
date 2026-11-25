package com.ft.sdk;

import com.ft.sdk.garble.bean.DataType;
import com.ft.sdk.internal.RUMCustomKeys;
import com.ft.sdk.sessionreplay.FTSessionReplayConfig;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class GlobalContextSnapshotTest {
    private final Map<String, Object> serialized = new HashMap<>();

    private void install(DataModifier modifier) {
        FTSDKConfig config = FTSDKConfig.builder("http://127.0.0.1:9")
                .setOnlySupportMainProcess(false).setAutoSync(false)
                .setRemoteConfiguration(false).setEnableDataFilter(false)
                .setDataModifier(modifier).setLineDataModifier((measurement, values) -> {
                    if ("snapshot_test".equals(measurement)) {
                        serialized.clear();
                        serialized.putAll(values);
                    }
                    return null;
                });
        FTSdk.install(config);
        assertTrue(FTSdk.checkInstallState());
        FTSdk.initRUMWithConfig(new FTRUMConfig().setRumAppId("snapshot-test"));
        FTSdk.initSessionReplayConfig(new FTSessionReplayConfig().enableLinkRUMKeys(new String[]{"batch_"}));
        assertTrue(SessionReplayBridge.isReplayEnabled());
    }

    @After public void shutdown() {
        FTSdk.shutDown();
    }

    @Test public void globalCopyConflictRejectsEntireBatch() throws Exception {
        verifyConflict(FTSdk::appendGlobalContext);
    }

    @Test public void rumCopyConflictRejectsEntireBatch() throws Exception {
        verifyConflict(FTSdk::appendRUMGlobalContext);
    }

    private void verifyConflict(Consumer<HashMap<String, Object>> append) throws Exception {
        install(null);
        HashMap<String, Object> stable = new HashMap<>();
        stable.put("batch_old", "accepted");
        append.accept(stable);

        ConflictingMap batch = new ConflictingMap();
        batch.put("batch_old", "rejected");
        batch.put("batch_new", "rejected");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> write = worker.submit(() -> {
                await(batch.iteratorCreated);
                batch.put("batch_concurrent", "rejected");
                batch.writerFinished.countDown();
            });
            append.accept(batch);
            write.get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }

        serialize();
        Map<String, Object> replay = SessionReplayManager.get().getTagLinkMap();
        assertEquals("accepted", serialized.get("batch_old"));
        assertEquals("accepted", replay.get("batch_old"));
        for (String key : new String[]{"batch_new", "batch_concurrent"}) {
            assertFalse(serialized.containsKey(key));
            assertFalse(RUMCustomKeys.parse(serialized.get("custom_keys")).contains(key));
            assertFalse(FTTrackInner.getInstance().getRUMReservedKeys().contains(key));
            assertFalse(replay.containsKey(key));
        }
    }

    @Test public void globalModifierWritesBackAndReplayUsesSnapshot() {
        verifyWriteback(FTSdk::appendGlobalContext);
    }

    @Test public void rumModifierWritesBackAndReplayUsesSnapshot() {
        verifyWriteback(FTSdk::appendRUMGlobalContext);
    }

    private void verifyWriteback(Consumer<HashMap<String, Object>> append) {
        HashMap<String, Object> host = new HashMap<>();
        install((key, value) -> {
            if ("batch_modified".equals(key)) {
                host.put("batch_unchanged", "host update");
                host.put("batch_late", true);
                return "modified";
            }
            return value;
        });
        host.put("batch_modified", "original");
        host.put("batch_unchanged", "snapshot");
        append.accept(host);

        assertEquals("modified", host.get("batch_modified"));
        assertEquals("host update", host.get("batch_unchanged"));
        assertEquals(true, host.get("batch_late"));
        serialize();
        Map<String, Object> replay = SessionReplayManager.get().getTagLinkMap();
        assertEquals("modified", serialized.get("batch_modified"));
        assertEquals("modified", replay.get("batch_modified"));
        assertEquals("snapshot", serialized.get("batch_unchanged"));
        assertEquals("snapshot", replay.get("batch_unchanged"));
        assertFalse(serialized.containsKey("batch_late"));
        assertFalse(replay.containsKey("batch_late"));
    }

    private void serialize() {
        HashMap<String, Object> fields = new HashMap<>();
        fields.put("duration", 1L);
        FTTrackInner.getInstance().getCurrentDataHelper().getBodyContent(
                "snapshot_test", new HashMap<>(), fields, 1L, DataType.RUM_APP, "uuid");
    }

    // Pause a real HashMap iterator until a different thread has structurally modified the map.
    private static class ConflictingMap extends HashMap<String, Object> {
        final CountDownLatch iteratorCreated = new CountDownLatch(1);
        final CountDownLatch writerFinished = new CountDownLatch(1);

        @Override public Set<Map.Entry<String, Object>> entrySet() {
            Set<Map.Entry<String, Object>> entries = super.entrySet();
            return new AbstractSet<Map.Entry<String, Object>>() {
                @Override public int size() {
                    return entries.size();
                }

                @Override public Iterator<Map.Entry<String, Object>> iterator() {
                    Iterator<Map.Entry<String, Object>> iterator = entries.iterator();
                    iteratorCreated.countDown();
                    await(writerFinished);
                    return iterator;
                }
            };
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Timed out coordinating the map copy", latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
