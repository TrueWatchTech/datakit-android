package com.ft.sdk;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;

/** Real lifecycle/window host for upload retry contention regression tests. */
public class SyncPollResumeTestActivity extends Activity {
    static volatile CountDownLatch stopped = new CountDownLatch(1);
    static volatile CountDownLatch resumed = new CountDownLatch(1);
    static volatile CountDownLatch focused = new CountDownLatch(1);
    static volatile long resumeDurationMs;
    private long resumeStartedMs;

    static void resetProbe() {
        stopped = new CountDownLatch(1);
        resumed = new CountDownLatch(1);
        focused = new CountDownLatch(1);
        resumeDurationMs = -1;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Upload retry / Activity resume verification");
        setContentView(text);
    }

    @Override
    protected void onResume() {
        resumeStartedMs = SystemClock.elapsedRealtime();
        super.onResume();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        resumeDurationMs = SystemClock.elapsedRealtime() - resumeStartedMs;
        resumed.countDown();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped.countDown();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) focused.countDown();
    }
}
