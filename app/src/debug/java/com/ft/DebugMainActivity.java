package com.ft;

import android.content.Intent;
import android.app.ActivityManager;
import android.os.Process;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;

/**
 * For testing purposes
 *
 * @author Brandon
 */
public class DebugMainActivity extends MainActivity {
    static final String CHANNEL_PAGE_PREF = "launch_channel_page";
    private DebugLaunchFrameGate launchFrameGate;
    private static boolean channelOpened;
    private boolean channelScenario;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SplashActivity.logEvent("home created");
        launchFrameGate = new DebugLaunchFrameGate(this);
        channelScenario = getIntent().getBooleanExtra(SplashActivity.EXTRA_CHANNEL_PAGE,
                getPreferences(MODE_PRIVATE).getBoolean(CHANNEL_PAGE_PREF, false));
        ScrollView scrollView = findViewById(R.id.scrollView);
        ViewGroup content = (ViewGroup) scrollView.getChildAt(0);
        Button acceptanceButton = new Button(this);
        acceptanceButton.setText(R.string.historical_anr_acceptance_entry);
        acceptanceButton.setOnClickListener(view -> startActivity(
                new Intent(this, HistoricalAnrAcceptanceActivity.class)));
        content.addView(acceptanceButton, 0);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuItem launchTests = menu.add(R.string.launch_test_entry);
        launchTests.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        launchTests.setOnMenuItemClickListener(item -> {
            new LaunchTestSettingsDialog().show(getSupportFragmentManager(), "launch_tests");
            return true;
        });
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        SplashActivity.logEvent("home resumed");
        if (channelScenario && !channelOpened) {
            channelOpened = true;
            startActivity(new Intent(this, LaunchChannelActivity.class));
        }
    }

    @Override protected void onPause() {
        SplashActivity.logEvent("home paused");
        super.onPause();
    }

    @Override protected void onStop() {
        SplashActivity.logEvent("home stopped");
        super.onStop();
        if (channelScenario) {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.uid == Process.myUid()) {
                        SplashActivity.logEvent("home stop snapshot: process=" + process.processName
                                + ",pid=" + process.pid + ",importance=" + process.importance);
                    }
                }
            }
        }
    }

    @Override protected void onDestroy() {
        if (launchFrameGate != null) launchFrameGate.close();
        SplashActivity.logEvent("home destroyed");
        super.onDestroy();
    }

}
