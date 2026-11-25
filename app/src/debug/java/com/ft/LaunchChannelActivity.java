package com.ft;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** A real sibling-process UI for exercising the last-Stop cross-process boundary. */
public class LaunchChannelActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText(R.string.launch_test_channel_instructions);
        text.setTextSize(22);
        text.setGravity(android.view.Gravity.CENTER);
        text.setPadding(32, 32, 32, 32);
        setContentView(text);
        SplashActivity.logEvent("channel created");
    }

    @Override protected void onResume() {
        super.onResume();
        SplashActivity.logEvent("channel resumed");
    }

    @Override protected void onStop() {
        super.onStop();
        SplashActivity.logEvent("channel stopped; closing this test page");
        // Close only this test page after the user backgrounds it. Main stays in the background.
        // Returning via the launcher will show Main, whose first frame is still pending.
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onDestroy() {
        SplashActivity.logEvent("channel destroyed");
        super.onDestroy();
    }
}
