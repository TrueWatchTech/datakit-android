package com.ft.launchfixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class LaunchActivity extends Activity {
    private static WeakReference<LaunchActivity> current = new WeakReference<>(null);
    private boolean firstDrawObserved;
    private final ViewTreeObserver.OnPreDrawListener drawGate = () -> false;
    private final ViewTreeObserver.OnDrawListener firstDraw = () -> {
        if (!firstDrawObserved) {
            firstDrawObserved = true;
            LaunchApplication.event("first_draw_observed");
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        TextView content = new TextView(this);
        content.setText("Launch type acceptance\nFirst frame can be held across background/foreground.");
        setContentView(content);
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        if (getIntent().getBooleanExtra("gate_first_frame", false)) {
            observer.addOnPreDrawListener(drawGate);
        }
        observer.addOnDrawListener(firstDraw);
    }

    @Override
    protected void onDestroy() {
        // Android may preserve the window across recreation, so detach the old instance's gate.
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(drawGate);
            observer.removeOnDrawListener(firstDraw);
        }
        super.onDestroy();
    }

    public static void recreateCurrent() {
        LaunchActivity activity = current.get();
        if (activity == null) throw new IllegalStateException("No current Activity");
        activity.recreate();
    }

    public static void releaseFirstDraw() {
        LaunchActivity activity = current.get();
        if (activity == null) throw new IllegalStateException("No pending Activity");
        LaunchApplication.event("release_first_draw");
        View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().removeOnPreDrawListener(activity.drawGate);
        decor.requestLayout();
        decor.postInvalidateOnAnimation();
    }

    public static void finishCurrent() {
        LaunchActivity activity = current.get();
        if (activity == null) throw new IllegalStateException("No current Activity");
        activity.finish();
    }
}
