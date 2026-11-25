package com.ft.sdk.garble.gesture;

import android.view.Window;

import androidx.annotation.RestrictTo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Track all windows callback
 */
public class WindowCallbackTracker {
    private final Map<Window, WeakReference<WindowCallbackWrapper>> registrations = new WeakHashMap<>();
    private boolean released;

    /**
     *  stat to track window event
     * @param window
     */
    public synchronized void startTrack(Window window) {
        if (released || window == null) return;
        Window.Callback current = window.getCallback();
        WeakReference<WindowCallbackWrapper> reference = registrations.get(window);
        WindowCallbackWrapper previous = reference == null ? null : reference.get();
        if (previous != null) {
            if (previous == current) return;
            previous.stopTracking();
        }
        WindowCallbackWrapper callback = new WindowCallbackWrapper(window, current);
        window.setCallback(callback);
        registrations.put(window, new WeakReference<>(callback));
    }

    /**
     * stop to track window event
     * @param window
     */
    public synchronized void stopTrack(Window window) {
        WeakReference<WindowCallbackWrapper> reference = registrations.remove(window);
        WindowCallbackWrapper callback = reference == null ? null : reference.get();
        if (callback == null) return;
        callback.stopTracking();
        // AppCompat, Replay or the host may have installed another wrapper above ours.
        if (window.getCallback() == callback) {
            Window.Callback original = callback.getWrappedCallback();
            window.setCallback(original == WindowCallbackWrapper.EMPTY_CALLBACK ? null : original);
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public synchronized void release() {
        released = true;
        for (Window window : new ArrayList<>(registrations.keySet())) {
            stopTrack(window);
        }
    }
}
