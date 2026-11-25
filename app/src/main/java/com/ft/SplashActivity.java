package com.ft;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/** Launcher router matching the observed Splash -> Main path of the reported app. */
public class SplashActivity extends Activity {
    static final String TAG = "LaunchTypeSample";
    static final String EXTRA_FIRST_FRAME_DELAY_MS = "launch_test_first_frame_delay_ms";
    static final String EXTRA_CHANNEL_PAGE = "launch_test_channel_page";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logEvent("router created, sdk=" + com.ft.sdk.FTSdk.AGENT_VERSION);
        // A launcher tap on an existing task must not add another splash over its current page.
        if (!isTaskRoot() && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        Intent home = new Intent().setClassName(this, getString(R.string.launch_test_home_activity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (BuildConfig.DEBUG && getIntent().hasExtra(EXTRA_FIRST_FRAME_DELAY_MS)) {
            home.putExtra(EXTRA_FIRST_FRAME_DELAY_MS,
                    getIntent().getIntExtra(EXTRA_FIRST_FRAME_DELAY_MS, 0));
        }
        if (BuildConfig.DEBUG && getIntent().hasExtra(EXTRA_CHANNEL_PAGE)) {
            home.putExtra(EXTRA_CHANNEL_PAGE, getIntent().getBooleanExtra(EXTRA_CHANNEL_PAGE, false));
        }
        // Do not draw a separate splash Activity. The theme supplies the starting window.
        startActivity(home);
        logEvent("router opened home; finishing in onCreate");
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        logEvent("router started");
    }

    @Override
    protected void onResume() {
        super.onResume();
        logEvent("router resumed");
    }

    @Override
    protected void onDestroy() {
        logEvent("router destroyed");
        super.onDestroy();
    }

    static void logEvent(String event) {
        Log.i(TAG, event + ", elapsedRealtimeMs=" + SystemClock.elapsedRealtime());
    }

}
