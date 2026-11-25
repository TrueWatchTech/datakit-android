package com.ft.sdk;

import com.ft.sdk.garble.bean.AppState;

/** State conversion at an event boundary; never consults the current upload-time state. */
final class EventAppState {
    private EventAppState() { }

    static AppState toLegacy(String state) {
        if (AppStateHistory.FOREGROUND.equals(state)) return AppState.RUN;
        if (AppStateHistory.BACKGROUND.equals(state)) return AppState.BACKGROUND;
        return AppState.UNKNOWN;
    }

    static long end(long start, long duration) {
        return start > 0 && duration >= 0 && start <= Long.MAX_VALUE - duration
                ? start + duration : -1;
    }
}
