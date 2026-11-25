package com.ft.sdk;

import com.ft.sdk.garble.bean.AppState;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventAppStateTest {
    @Test public void requestIntervalAndFailurePointRemainStableAfterForegroundReturn() {
        AppStateHistoryTest.TestClock clock = new AppStateHistoryTest.TestClock();
        AppStateHistory history = new AppStateHistory(clock);
        long start = history.record(AppStateHistory.FOREGROUND);
        clock.advance(100);
        history.record(AppStateHistory.BACKGROUND);
        clock.advance(100);
        long failure = clock.now;
        String resource = history.between(start, failure);
        String error = history.at(failure);
        clock.advance(100);
        history.record(AppStateHistory.FOREGROUND);
        assertEquals(AppStateHistory.MIXED, resource);
        assertEquals(AppState.BACKGROUND, EventAppState.toLegacy(error));
        assertEquals(AppStateHistory.BACKGROUND, history.at(failure));
        assertEquals(AppStateHistory.FOREGROUND, history.at(clock.now));
    }

    @Test public void incompleteStateAndUnusableTimesDoNotInventOccurrenceState() {
        assertEquals(AppState.UNKNOWN, EventAppState.toLegacy("unknown"));
        assertEquals(AppState.UNKNOWN, EventAppState.toLegacy("foreground_and_background"));
        assertEquals(-1, EventAppState.end(Long.MAX_VALUE, 1));
        assertEquals(-1, EventAppState.end(1, -1));
    }
}
