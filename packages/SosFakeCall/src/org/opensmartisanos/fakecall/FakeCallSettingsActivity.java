/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensmartisanos.fakecall;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** User-facing configuration for delay, caller identity and ringtone. */
public final class FakeCallSettingsActivity extends Activity {
    private static final int REQUEST_RINGTONE = 4305;
    private static final long[] DELAYS = {5_000L, 10_000L, 30_000L, 60_000L};

    private SharedPreferences mPreferences;
    private EditText mCallerName;
    private EditText mCallerNumber;
    private Spinner mDelay;
    private Button mRingtone;
    private Uri mRingtoneUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPreferences = getSharedPreferences(FakeCallService.PREFS, MODE_PRIVATE);
        setContentView(createContent());
        loadValues();
    }

    private LinearLayout createContent() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(42), dp(24), dp(24));
        root.setBackgroundColor(0xfff5f5f5);

        final TextView title = label(getString(R.string.settings_title), 26);
        title.setTextColor(0xff202124);
        root.addView(title);

        root.addView(label(getString(R.string.caller_name), 14), topMargin(28));
        mCallerName = new EditText(this);
        mCallerName.setSingleLine(true);
        root.addView(mCallerName, matchWidth());

        root.addView(label(getString(R.string.caller_number), 14), topMargin(18));
        mCallerNumber = new EditText(this);
        mCallerNumber.setSingleLine(true);
        mCallerNumber.setInputType(InputType.TYPE_CLASS_PHONE);
        root.addView(mCallerNumber, matchWidth());

        root.addView(label(getString(R.string.delay), 14), topMargin(18));
        mDelay = new Spinner(this);
        mDelay.setAdapter(
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        getResources().getStringArray(R.array.fake_call_delay_entries)));
        root.addView(mDelay, matchWidth());

        root.addView(label(getString(R.string.ringtone), 14), topMargin(18));
        mRingtone = new Button(this);
        mRingtone.setAllCaps(false);
        mRingtone.setOnClickListener(view -> chooseRingtone());
        root.addView(mRingtone, matchWidth());

        final Button save = new Button(this);
        save.setText(R.string.save);
        save.setTextColor(Color.WHITE);
        save.setTextSize(16);
        save.setAllCaps(false);
        save.setBackgroundColor(0xff397bea);
        save.setOnClickListener(view -> save());
        final LinearLayout.LayoutParams saveParams = matchWidth();
        saveParams.topMargin = dp(32);
        saveParams.height = dp(52);
        root.addView(save, saveParams);
        return root;
    }

    private void loadValues() {
        mCallerName.setText(
                mPreferences.getString(
                        FakeCallService.PREF_CALLER_NAME,
                        getString(R.string.default_caller_name)));
        mCallerNumber.setText(
                mPreferences.getString(
                        FakeCallService.PREF_CALLER_NUMBER,
                        getString(R.string.default_caller_number)));
        final long selectedDelay =
                mPreferences.getLong(
                        FakeCallService.PREF_DELAY_MILLIS,
                        FakeCallService.DEFAULT_DELAY_MILLIS);
        for (int i = 0; i < DELAYS.length; i++) {
            if (DELAYS[i] == selectedDelay) {
                mDelay.setSelection(i);
                break;
            }
        }
        final String ringtone = mPreferences.getString(FakeCallService.PREF_RINGTONE, null);
        mRingtoneUri = ringtone != null ? Uri.parse(ringtone) : null;
        updateRingtoneLabel();
    }

    private void chooseRingtone() {
        final Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        mRingtoneUri != null
                                ? mRingtoneUri
                                : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
        startActivityForResult(intent, REQUEST_RINGTONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_RINGTONE && resultCode == RESULT_OK && data != null) {
            mRingtoneUri = data.getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri.class);
            updateRingtoneLabel();
        }
    }

    private void updateRingtoneLabel() {
        if (mRingtoneUri == null) {
            mRingtone.setText(R.string.default_ringtone);
            return;
        }
        final android.media.Ringtone ringtone =
                RingtoneManager.getRingtone(this, mRingtoneUri);
        mRingtone.setText(
                ringtone != null ? ringtone.getTitle(this) : getString(R.string.default_ringtone));
    }

    private void save() {
        mPreferences.edit()
                .putString(
                        FakeCallService.PREF_CALLER_NAME,
                        mCallerName.getText().toString().trim())
                .putString(
                        FakeCallService.PREF_CALLER_NUMBER,
                        mCallerNumber.getText().toString().trim())
                .putLong(
                        FakeCallService.PREF_DELAY_MILLIS,
                        DELAYS[mDelay.getSelectedItemPosition()])
                .putString(
                        FakeCallService.PREF_RINGTONE,
                        mRingtoneUri != null ? mRingtoneUri.toString() : null)
                .apply();
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private TextView label(String text, float sizeSp) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(0xff5f6368);
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginDp);
        return params;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
