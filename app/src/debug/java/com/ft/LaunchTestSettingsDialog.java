package com.ft;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.DialogFragment;

public class LaunchTestSettingsDialog extends DialogFragment {
    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Activity home = requireActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(home);
        View settings = View.inflate(builder.getContext(), R.layout.dialog_launch_test_settings, null);
        SwitchCompat delay = settings.findViewById(R.id.launch_test_delay);
        delay.setChecked(DebugLaunchFrameGate.isEnabled(home));
        delay.setOnCheckedChangeListener((button, checked) ->
                DebugLaunchFrameGate.setEnabled(home, checked));

        SharedPreferences preferences = home.getPreferences(Context.MODE_PRIVATE);
        SwitchCompat channel = settings.findViewById(R.id.launch_test_channel);
        channel.setChecked(preferences.getBoolean(DebugMainActivity.CHANNEL_PAGE_PREF, false));
        channel.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(DebugMainActivity.CHANNEL_PAGE_PREF, checked).apply());

        return builder.setTitle(R.string.launch_test_title)
                .setView(settings)
                .setPositiveButton(R.string.launch_test_done, null)
                .create();
    }
}
