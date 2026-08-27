/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.app.admin.DevicePolicyResources.Strings.SystemUi.STATUS_BAR_WORK_ICON_ACCESSIBILITY;

import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.telecom.TelecomManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.Flags;
import com.android.systemui.Prefs;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.common.shared.model.Icon;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyType;
import com.android.systemui.privacy.logging.PrivacyLogger;
import com.android.systemui.qs.tiles.RotationLockTile;
import com.android.systemui.res.R;
import com.android.systemui.screenrecord.ScreenRecordUxController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DataSaverController.Listener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;
import com.android.systemui.statusbar.policy.SensorPrivacyController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor;
import com.android.systemui.statusbar.policy.domain.model.ZenModeInfo;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.DateFormatUtil;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * This class contains all of the policy about which icons are installed in the status bar at boot
 * time. It goes through the normal API for icons, even though it probably strictly doesn't need to.
 */
public class PhoneStatusBarPolicy
        implements BluetoothController.Callback,
                CommandQueue.Callbacks,
                RotationLockControllerCallback,
                Listener,
                DeviceProvisionedListener,
                KeyguardStateController.Callback,
                PrivacyItemController.Callback,
                LocationController.LocationChangeCallback,
                ScreenRecordUxController.StateChangeCallback {
    private static final String TAG = "PhoneStatusBarPolicy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final int LOCATION_STATUS_ICON_ID = PrivacyType.TYPE_LOCATION.getIconId();

    private final String mSlotHotspot;
    private final String mSlotBluetooth;
    private final String mSlotTty;
    private final String mSlotZen;
    private final String mSlotVolume;
    private final String mSlotAlarmClock;
    private final String mSlotManagedProfile;
    private final String mSlotRotate;
    private final String mSlotDataSaver;
    private final String mSlotLocation;
    private final String mSlotMicrophone;
    private final String mSlotCamera;
    private final String mSlotSensorsOff;
    private final String mSlotScreenRecord;
    private final String mSlotCast;
    private final String mSlotBluetoothHeadset;
    private final String mSlotNormalHeadset;
    private final String mSlotVolte;
    private final String mSlotSyncActive;
    private final String mSlotSyncFailing;
    private final String mSlotCdmaEri;
    private final String mSlotFirewall;
    private final int mDisplayId;
    private final SharedPreferences mSharedPreferences;
    private final DateFormatUtil mDateFormatUtil;
    private final JavaAdapter mJavaAdapter;
    private final ConnectedDisplayInteractor mConnectedDisplayInteractor;
    private final TelecomManager mTelecomManager;

    private final Context mContext;
    private final Handler mHandler;
    private final HotspotController mHotspot;
    private final NextAlarmController mNextAlarmController;
    private final AlarmManager mAlarmManager;
    private final UserInfoController mUserInfoController;
    private final UserManager mUserManager;
    private final UserTracker mUserTracker;
    private final DevicePolicyManager mDevicePolicyManager;
    private final StatusBarIconController mIconController;
    private final CommandQueue mCommandQueue;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Resources mResources;
    private final RotationLockController mRotationLockController;
    private final DataSaverController mDataSaver;
    private final ZenModeController mZenController;
    private final DeviceProvisionedController mProvisionedController;
    private final KeyguardStateController mKeyguardStateController;
    private final LocationController mLocationController;
    private final PrivacyItemController mPrivacyItemController;
    private final Executor mMainExecutor;
    private final Executor mUiBgExecutor;
    private final SensorPrivacyController mSensorPrivacyController;
    private final RingerModeTracker mRingerModeTracker;
    private final PrivacyLogger mPrivacyLogger;
    private final ZenModeInteractor mZenModeInteractor;
    private final ConnectivityManager mConnectivityManager;
    private final AudioManager mAudioManager;
    private final ScreenRecordUxController mScreenRecordController;
    private final VolteStateController mVolteStateController;
    private final VolteStateController.Callback mVolteCallback;
    private final Runnable mRemoveCastIconRunnable;

    private boolean mZenIconVisible;
    private boolean mVolumeVisible;
    private boolean mCurrentUserSetup;

    private boolean mProfileIconVisible = false;
    private boolean mFirewallVisible = false;

    private int mLastResumedActivityUid = -1;
    private boolean mWiredHeadsetConnected;

    private BluetoothController mBluetooth;
    private AlarmClockInfo mNextAlarm;

    private final AudioDeviceCallback mHeadsetDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            updateHeadsetDeviceState();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            updateHeadsetDeviceState();
        }
    };

    @Inject
    public PhoneStatusBarPolicy(Context context, StatusBarIconController iconController,
            CommandQueue commandQueue, BroadcastDispatcher broadcastDispatcher,
            @Main Executor mainExecutor, @UiBackground Executor uiBgExecutor, @Main Looper looper,
            @Main Resources resources,
            HotspotController hotspotController, BluetoothController bluetoothController,
            NextAlarmController nextAlarmController, UserInfoController userInfoController,
            RotationLockController rotationLockController, DataSaverController dataSaverController,
            ZenModeController zenModeController,
            DeviceProvisionedController deviceProvisionedController,
            KeyguardStateController keyguardStateController,
            LocationController locationController,
            SensorPrivacyController sensorPrivacyController, AlarmManager alarmManager,
            UserManager userManager, UserTracker userTracker,
            DevicePolicyManager devicePolicyManager,
            @Nullable TelecomManager telecomManager, @DisplayId int displayId,
            @Main SharedPreferences sharedPreferences, DateFormatUtil dateFormatUtil,
            RingerModeTracker ringerModeTracker,
            PrivacyItemController privacyItemController,
            PrivacyLogger privacyLogger,
            ConnectedDisplayInteractor connectedDisplayInteractor,
            VolteStateController volteStateController,
            ScreenRecordUxController screenRecordController,
            ZenModeInteractor zenModeInteractor,
            JavaAdapter javaAdapter
    ) {
        mContext = context;
        mIconController = iconController;
        mCommandQueue = commandQueue;
        mConnectedDisplayInteractor = connectedDisplayInteractor;
        mBroadcastDispatcher = broadcastDispatcher;
        mHandler = new Handler(looper);
        mResources = resources;
        mHotspot = hotspotController;
        mBluetooth = bluetoothController;
        mNextAlarmController = nextAlarmController;
        mAlarmManager = alarmManager;
        mUserInfoController = userInfoController;
        mUserManager = userManager;
        mUserTracker = userTracker;
        mDevicePolicyManager = devicePolicyManager;
        mRotationLockController = rotationLockController;
        mDataSaver = dataSaverController;
        mZenController = zenModeController;
        mProvisionedController = deviceProvisionedController;
        mKeyguardStateController = keyguardStateController;
        mLocationController = locationController;
        mPrivacyItemController = privacyItemController;
        mSensorPrivacyController = sensorPrivacyController;
        mMainExecutor = mainExecutor;
        mUiBgExecutor = uiBgExecutor;
        mTelecomManager = telecomManager;
        mRingerModeTracker = ringerModeTracker;
        mPrivacyLogger = privacyLogger;
        mScreenRecordController = screenRecordController;
        mZenModeInteractor = zenModeInteractor;
        mJavaAdapter = javaAdapter;
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mAudioManager = context.getSystemService(AudioManager.class);

        mDisplayId = displayId;
        mSlotHotspot = resources.getString(com.android.internal.R.string.status_bar_hotspot);
        mSlotBluetooth = resources.getString(
                com.android.internal.R.string.status_bar_bluetooth);
        mSlotTty = resources.getString(com.android.internal.R.string.status_bar_tty);
        mSlotZen = resources.getString(com.android.internal.R.string.status_bar_zen);
        mSlotVolume = resources.getString(com.android.internal.R.string.status_bar_volume);
        mSlotAlarmClock = resources.getString(com.android.internal.R.string.status_bar_alarm_clock);
        mSlotManagedProfile = resources.getString(
                com.android.internal.R.string.status_bar_managed_profile);
        mSlotRotate = resources.getString(com.android.internal.R.string.status_bar_rotate);
        mSlotBluetoothHeadset = DynamicIconPolicy.SLOT_BLUETOOTH_HEADSET;
        mSlotNormalHeadset = DynamicIconPolicy.SLOT_NORMAL_HEADSET;
        mSlotCast = resources.getString(com.android.internal.R.string.status_bar_cast);
        mSlotVolte = DynamicIconPolicy.SLOT_VOLTE;
        mSlotSyncActive = resources.getString(
                com.android.internal.R.string.status_bar_sync_active);
        mSlotSyncFailing = resources.getString(
                com.android.internal.R.string.status_bar_sync_failing);
        mSlotCdmaEri = resources.getString(com.android.internal.R.string.status_bar_cdma_eri);
        mSlotDataSaver = resources.getString(com.android.internal.R.string.status_bar_data_saver);
        mSlotLocation = resources.getString(com.android.internal.R.string.status_bar_location);
        mSlotMicrophone = resources.getString(com.android.internal.R.string.status_bar_microphone);
        mSlotCamera = resources.getString(com.android.internal.R.string.status_bar_camera);
        mSlotSensorsOff = resources.getString(com.android.internal.R.string.status_bar_sensors_off);
        mSlotScreenRecord = resources.getString(
                com.android.internal.R.string.status_bar_screen_record);
        mSlotFirewall = resources.getString(R.string.status_bar_firewall_slot);
        mVolteStateController = volteStateController;
        mVolteCallback =
                state -> {
                    final int icon;
                    switch (state) {
                        case CALLING:
                            icon = R.drawable.stat_sys_volte_mode_calling;
                            break;
                        case READY:
                            icon = R.drawable.stat_sys_volte_mode_ready;
                            break;
                        case DISABLED:
                            icon = R.drawable.stat_sys_volte_mode_disabled;
                            break;
                        case HIDDEN:
                        default:
                            mIconController.setIconVisibility(mSlotVolte, false);
                            return;
                    }
                    mIconController.setIcon(mSlotVolte, icon, null);
                    mIconController.setIconVisibility(mSlotVolte, true);
                };
        mRemoveCastIconRunnable =
                () -> mIconController.setIconVisibility(mSlotCast, false);

        mSharedPreferences = sharedPreferences;
        mDateFormatUtil = dateFormatUtil;
    }

    /** Initialize the object after construction. */
    public void init() {
        // listen for broadcasts
        IntentFilter filter = new IntentFilter();

        filter.addAction(AudioManager.ACTION_HEADSET_PLUG);
        filter.addAction(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        filter.addAction(Intent.ACTION_PROFILE_REMOVED);
        filter.addAction(Intent.ACTION_PROFILE_ACCESSIBLE);
        filter.addAction(Intent.ACTION_PROFILE_INACCESSIBLE);
        mBroadcastDispatcher.registerReceiverWithHandler(mIntentReceiver, filter, mHandler);
        if (mAudioManager != null) {
            mAudioManager.registerAudioDeviceCallback(mHeadsetDeviceCallback, mHandler);
        }
        Observer<Integer> observer = ringer -> mHandler.post(this::updateVolumeZen);

        mRingerModeTracker.getRingerMode().observeForever(observer);
        mRingerModeTracker.getRingerModeInternal().observeForever(observer);

        // listen for user / profile change.
        mUserTracker.addCallback(mUserSwitchListener, mMainExecutor);

        // TTY status
        updateTTY();
        updateHeadsetDeviceState();

        // bluetooth status
        updateBluetooth();

        // Alarm clock
        mIconController.setIcon(mSlotAlarmClock, R.drawable.stat_sys_alarm, null);
        mIconController.setIconVisibility(mSlotAlarmClock, false);

        mIconController.setIcon(mSlotVolume, R.drawable.stat_sys_ringer_vibrate,
                mResources.getString(R.string.accessibility_ringer_vibrate));
        mIconController.setIconVisibility(mSlotVolume, false);
        mIconController.setIcon(mSlotZen, R.drawable.stat_sys_dnd, null);
        mIconController.setIconVisibility(mSlotZen, false);
        updateVolumeZen();

        mIconController.setIcon(mSlotCast, R.drawable.stat_sys_cast,
                mResources.getString(R.string.connected_display_icon_desc));
        mIconController.setIconVisibility(mSlotCast, false);

        // VoLTE is sourced from Android's public IMS registration/capability callbacks.
        mIconController.setIcon(mSlotVolte, R.drawable.stat_sys_volte_mode_ready, null);
        mIconController.setIconVisibility(mSlotVolte, false);
        mVolteStateController.addCallback(mVolteCallback);

        // Keep the factory legacy slots present in the logical queue. Android 16 no longer
        // has the old global sync broadcaster, and CDMA ERI is supplied only when telephony
        // publishes that standard state.
        mIconController.setIcon(mSlotSyncActive, R.drawable.stat_sys_sync, null);
        mIconController.setIconVisibility(mSlotSyncActive, false);
        mIconController.setIcon(mSlotSyncFailing, R.drawable.stat_sys_sync_error, null);
        mIconController.setIconVisibility(mSlotSyncFailing, false);
        mIconController.setIcon(mSlotCdmaEri, R.drawable.stat_sys_roaming_cdma_0, null);
        mIconController.setIconVisibility(mSlotCdmaEri, false);

        // hotspot
        mIconController.setIcon(mSlotHotspot, R.drawable.stat_sys_hotspot,
                mResources.getString(R.string.accessibility_status_bar_hotspot));
        mIconController.setIconVisibility(mSlotHotspot, mHotspot.isHotspotEnabled());

        // profile
        updateProfileIcon();

        // data saver
        mIconController.setIcon(mSlotDataSaver, R.drawable.stat_sys_data_saver,
                mResources.getString(R.string.accessibility_data_saver_on));
        mIconController.setIconVisibility(mSlotDataSaver, false);


        // privacy items
        String microphoneString = mResources.getString(PrivacyType.TYPE_MICROPHONE.getNameId());
        String microphoneDesc = mResources.getString(
                R.string.ongoing_privacy_chip_content_multiple_apps, microphoneString);
        mIconController.setIcon(mSlotMicrophone, PrivacyType.TYPE_MICROPHONE.getIconId(),
                microphoneDesc);
        mIconController.setIconVisibility(mSlotMicrophone, false);

        String cameraString = mResources.getString(PrivacyType.TYPE_CAMERA.getNameId());
        String cameraDesc = mResources.getString(
                R.string.ongoing_privacy_chip_content_multiple_apps, cameraString);
        mIconController.setIcon(mSlotCamera, PrivacyType.TYPE_CAMERA.getIconId(),
                cameraDesc);
        mIconController.setIconVisibility(mSlotCamera, false);

        mIconController.setIcon(mSlotLocation, LOCATION_STATUS_ICON_ID,
                mResources.getString(R.string.accessibility_location_active));
        mIconController.setIconVisibility(mSlotLocation, false);

        // sensors off
        mIconController.setIcon(mSlotSensorsOff, R.drawable.stat_sys_sensors_off,
                mResources.getString(R.string.accessibility_sensors_off_active));
        mIconController.setIconVisibility(mSlotSensorsOff,
                mSensorPrivacyController.isSensorPrivacyEnabled());

        // screen record
        mIconController.setIcon(mSlotScreenRecord, R.drawable.stat_sys_screen_record, null);
        final boolean screenRecordCallbacksSupported = !Flags.screenReactions();
        mIconController.setIconVisibility(
                mSlotScreenRecord,
                screenRecordCallbacksSupported && mScreenRecordController.isRecording());
        if (screenRecordCallbacksSupported) {
            mScreenRecordController.addCallback(this);
        }

        // firewall
        mIconController.setIcon(mSlotFirewall, R.drawable.stat_sys_firewall, null);
        mIconController.setIconVisibility(mSlotFirewall, mFirewallVisible);

        mRotationLockController.addCallback(this);
        mBluetooth.addCallback(this);
        mProvisionedController.addCallback(this);
        mCurrentUserSetup = mProvisionedController.isCurrentUserSetup();
        // Note that we're not fully replacing ZenModeController with ZenModeInteractor yet, so
        // we listen for the extra event here but still add the ZMC callback.
        mJavaAdapter.alwaysCollectFlow(mZenModeInteractor.getMainActiveMode(),
                this::onMainActiveModeChanged);
        mZenController.addCallback(mZenControllerCallback);
        mHotspot.addCallback(mHotspotCallback);
        mNextAlarmController.addCallback(mNextAlarmCallback);
        mDataSaver.addCallback(this);
        mKeyguardStateController.addCallback(this);
        mPrivacyItemController.addCallback(this);
        mSensorPrivacyController.addCallback(mSensorPrivacyListener);
        mLocationController.addCallback(this);
        mJavaAdapter.alwaysCollectFlow(mConnectedDisplayInteractor.getConnectedDisplayState(),
                this::onConnectedDisplayAvailabilityChanged);

        mCommandQueue.addCallback(this);
    }

    private String getManagedProfileAccessibilityString() {
        return mDevicePolicyManager.getResources().getString(
                STATUS_BAR_WORK_ICON_ACCESSIBILITY,
                () -> mResources.getString(R.string.accessibility_managed_profile));
    }

    private void onMainActiveModeChanged(@Nullable ZenModeInfo mainActiveMode) {
        updateZenPresentation(mZenController.getZen());
    }

    // TODO: b/308591859 - Should be removed and use the ZenModeInteractor only.
    private final ZenModeController.Callback mZenControllerCallback =
            new ZenModeController.Callback() {
                @Override
                public void onZenChanged(int zen) {
                    updateVolumeZen();
                }

                @Override
                public void onConsolidatedPolicyChanged(NotificationManager.Policy policy) {
                    updateVolumeZen();
                }
            };

    private void updateAlarm() {
        final AlarmClockInfo alarm = mAlarmManager.getNextAlarmClock(mUserTracker.getUserId());
        final boolean hasAlarm = alarm != null && alarm.getTriggerTime() > 0;
        int alarmIcon = R.drawable.stat_sys_alarm;
        if (mZenController.getZen() == Settings.Global.ZEN_MODE_NO_INTERRUPTIONS) {
            alarmIcon = R.drawable.stat_sys_alarm_dim;
        }
        mIconController.setIcon(mSlotAlarmClock, alarmIcon,
                buildAlarmContentDescription());
        mIconController.setIconVisibility(mSlotAlarmClock, mCurrentUserSetup && hasAlarm);
    }

    private String buildAlarmContentDescription() {
        if (mNextAlarm == null) {
            return mResources.getString(R.string.status_bar_alarm);
        }

        String skeleton = mDateFormatUtil.is24HourFormat() ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        String dateString = DateFormat.format(pattern, mNextAlarm.getTriggerTime()).toString();

        return mResources.getString(R.string.accessibility_quick_settings_alarm, dateString);
    }

    private void updateVolumeZen() {
        int zen = mZenController.getZen();
        updateZenPresentation(zen);
        updateRingerAndAlarmIcons(zen);
    }

    private void updateZenPresentation(int zen) {
        final boolean dndTileVisible =
                Prefs.getBoolean(mContext, Prefs.Key.DND_TILE_VISIBLE, false);
        final boolean combinedIcon =
                Prefs.getBoolean(mContext, Prefs.Key.DND_TILE_COMBINED_ICON, false);
        final boolean visible;
        final int icon;
        if (dndTileVisible || combinedIcon) {
            visible = zen != Settings.Global.ZEN_MODE_OFF;
            icon = zen == Settings.Global.ZEN_MODE_NO_INTERRUPTIONS
                    ? R.drawable.stat_sys_dnd_total_silence
                    : R.drawable.stat_sys_dnd;
        } else if (zen == Settings.Global.ZEN_MODE_NO_INTERRUPTIONS) {
            visible = true;
            icon = R.drawable.stat_sys_zen_none;
        } else if (zen == Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            visible = true;
            icon = R.drawable.stat_sys_zen_important;
        } else {
            visible = false;
            icon = R.drawable.stat_sys_dnd;
        }
        if (visible) {
            mIconController.setIcon(
                    mSlotZen, icon, mResources.getString(R.string.quick_settings_dnd_label));
        }
        if (visible != mZenIconVisible) {
            mIconController.setIconVisibility(mSlotZen, visible);
            mZenIconVisible = visible;
        }
    }

    private void updateRingerAndAlarmIcons(int zen) {
        int icon = 0;
        String description = null;
        boolean volumeVisible = false;

        NotificationManager.Policy consolidatedPolicy = mZenController.getConsolidatedPolicy();
        if (!ZenModeConfig.isZenOverridingRinger(zen, consolidatedPolicy)) {
            final Integer ringerModeInternal =
                    mRingerModeTracker.getRingerModeInternal().getValue();
            if (ringerModeInternal != null) {
                if (ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE) {
                    volumeVisible = true;
                    icon = R.drawable.stat_sys_ringer_vibrate;
                    description = mResources.getString(R.string.accessibility_ringer_vibrate);
                } else if (ringerModeInternal == AudioManager.RINGER_MODE_SILENT) {
                    volumeVisible = true;
                    icon = R.drawable.stat_sys_ringer_silent;
                    description = mResources.getString(R.string.accessibility_ringer_silent);
                }
            }
        }

        if (volumeVisible) {
            mIconController.setIcon(mSlotVolume, icon, description);
        }
        if (volumeVisible != mVolumeVisible) {
            mIconController.setIconVisibility(mSlotVolume, volumeVisible);
            mVolumeVisible = volumeVisible;
        }

        updateAlarm();
    }

    @Override
    public void onBluetoothDevicesChanged() {
        updateBluetooth();
    }

    @Override
    public void onBluetoothStateChange(boolean enabled) {
        updateBluetooth();
    }

    private final void updateBluetooth() {
        if (mBluetooth == null) return;
        final boolean enabled = mBluetooth.isBluetoothEnabled();
        boolean audioConnected = false;
        boolean hidConnected = false;
        for (CachedBluetoothDevice device : mBluetooth.getConnectedDevices()) {
            audioConnected |= device.isConnectedProfile(BluetoothProfile.A2DP)
                    || device.isConnectedProfile(BluetoothProfile.HEADSET)
                    || device.isConnectedProfile(BluetoothProfile.HEARING_AID)
                    || device.isConnectedProfile(BluetoothProfile.LE_AUDIO);
            hidConnected |= device.isConnectedProfile(BluetoothProfile.HID_HOST);
        }

        final boolean anyConnected = mBluetooth.isBluetoothConnected();
        final int bluetoothIcon = (hidConnected || (anyConnected && !audioConnected))
                ? R.drawable.stat_sys_data_bluetooth_connected
                : R.drawable.stat_sys_data_bluetooth;
        mIconController.setIcon(mSlotBluetooth, bluetoothIcon,
                mResources.getString(anyConnected
                        ? R.string.accessibility_bluetooth_connected
                        : R.string.accessibility_quick_settings_bluetooth_on));
        mIconController.setIconVisibility(mSlotBluetooth,
                enabled && (!audioConnected || hidConnected));

        mIconController.setIcon(mSlotBluetoothHeadset,
                getBluetoothEarphoneIcon(mBluetooth.getBatteryLevel()),
                mResources.getString(R.string.accessibility_bluetooth_connected));
        mIconController.setIconVisibility(mSlotBluetoothHeadset,
                enabled && audioConnected);
    }

    private int getBluetoothEarphoneIcon(int batteryLevel) {
        if (batteryLevel < 0) {
            return R.drawable.stat_sys_data_bluetooth_earphone;
        }
        final int[] batteryIcons = {
                R.drawable.stat_sys_data_bluetooth_earphonebattery_0,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_1,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_2,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_3,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_4,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_5,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_6,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_7,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_8,
                R.drawable.stat_sys_data_bluetooth_earphonebattery_9,
        };
        return batteryIcons[Math.min(9, batteryLevel / 10)];
    }

    private final void updateTTY() {
        if (mTelecomManager == null) {
            updateTTY(TelecomManager.TTY_MODE_OFF);
        } else {
            updateTTY(mTelecomManager.getCurrentTtyMode());
        }
    }

    private final void updateTTY(int currentTtyMode) {
        boolean enabled = currentTtyMode != TelecomManager.TTY_MODE_OFF;

        if (DEBUG) Log.v(TAG, "updateTTY: enabled: " + enabled);

        if (enabled) {
            // TTY is on
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY on");
            mIconController.setIcon(mSlotTty, R.drawable.stat_sys_tty_mode,
                    mResources.getString(R.string.accessibility_tty_enabled));
            mIconController.setIconVisibility(mSlotTty, true);
        } else {
            // TTY is off
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY off");
            mIconController.setIconVisibility(mSlotTty, false);
        }
    }

    private void updateProfileIcon() {
        // getLastResumedActivityUserId needs to acquire the AM lock, which may be contended in
        // some cases. Since it doesn't really matter here whether it's updated in this frame
        // or in the next one, we call this method from our UI offload thread.
        mUiBgExecutor.execute(() -> {
            try {
                final int userId = ActivityTaskManager.getService().getLastResumedActivityUserId();
                final int iconResId = mUserManager.isProfile(userId) ?
                        mUserManager.getUserStatusBarIconResId(userId) : Resources.ID_NULL;
                mMainExecutor.execute(() -> {
                    final boolean showIcon;
                    if (iconResId != Resources.ID_NULL && (!mKeyguardStateController.isShowing()
                            || mKeyguardStateController.isOccluded())) {
                        String accessibilityString = "";
                        if (android.os.Flags.allowPrivateProfile()
                                && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
                            try {
                                accessibilityString =
                                        mUserManager.getProfileAccessibilityString(userId);
                            } catch (Resources.NotFoundException nfe) {
                                Log.e(TAG, "Accessibility string not found for userId:"
                                        + userId);
                            }
                        } else {
                            accessibilityString = getManagedProfileAccessibilityString();
                        }
                        showIcon = true;
                        mIconController.setIcon(
                                mSlotManagedProfile, iconResId, accessibilityString);
                    } else {
                        showIcon = false;
                    }
                    if (mProfileIconVisible != showIcon) {
                        mIconController.setIconVisibility(mSlotManagedProfile, showIcon);
                        mProfileIconVisible = showIcon;
                    }
                });
            } catch (RemoteException e) {
                Log.w(TAG, "updateProfileIcon: ", e);
            }
        });
    }

    private void registerBlockedStatusChangedCallbackForLastResumedActivityUid() {
        mUiBgExecutor.execute(() -> {
            try {
                final int uid = ActivityTaskManager.getService().getLastResumedActivityUid();
                if (mLastResumedActivityUid != uid) {
                    mLastResumedActivityUid = uid;
                    try {
                        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
                    } catch (IllegalArgumentException e) {
                        // Ignore
                    }
                    mConnectivityManager.registerDefaultNetworkCallbackForUid(uid, mNetworkCallback,
                            mHandler);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "registerBlockedStatusChangedCallbackForLastResumedActivityUid", e);
            }
        });
    }

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {

                @Override
                public void onBlockedStatusChanged(@NonNull Network network, int blocked) {
                    mHandler.post(() -> {
                        registerBlockedStatusChangedCallbackForLastResumedActivityUid();
                        mUiBgExecutor.execute(() -> {
                            try {
                                final int uid = ActivityTaskManager.getService()
                                        .getLastResumedActivityUid();
                                if (uid != Process.INVALID_UID) {
                                    mMainExecutor.execute(() -> {
                                        boolean isLauncher = false;
                                        List<ResolveInfo> homeActivities = mContext
                                                .getPackageManager().queryIntentActivitiesAsUser(
                                                        new Intent(Intent.ACTION_MAIN)
                                                                .addCategory(Intent.CATEGORY_HOME)
                                                                .addCategory(
                                                                        Intent.CATEGORY_DEFAULT),
                                                        PackageManager.ResolveInfoFlags.of(0),
                                                        UserHandle.getUserId(uid));
                                        for (ResolveInfo homeActivity : homeActivities) {
                                            int homeUid =
                                                    homeActivity.activityInfo.applicationInfo.uid;
                                            if (uid == homeUid) {
                                                isLauncher = true;
                                                break;
                                            }
                                        }
                                        final boolean finalIsLauncher = isLauncher;
                                        final boolean showIcon;
                                        if (!finalIsLauncher
                                                && blocked
                                                != ConnectivityManager.BLOCKED_REASON_NONE
                                                && (!mKeyguardStateController.isShowing()
                                                || mKeyguardStateController.isOccluded())) {
                                            showIcon = true;
                                            mIconController.setIcon(
                                                    mSlotFirewall,
                                                    R.drawable.stat_sys_firewall,
                                                    null);
                                        } else {
                                            showIcon = false;
                                        }
                                        if (mFirewallVisible != showIcon) {
                                            mIconController.setIconVisibility(mSlotFirewall,
                                                    showIcon);
                                            mFirewallVisible = showIcon;
                                        }
                                    });
                                }
                            } catch (RemoteException e) {
                                Log.w(TAG, "onBlockedStatusChanged", e);
                            }
                        });
                    });
                }
            };

    private final UserTracker.Callback mUserSwitchListener =
            new UserTracker.Callback() {
                @Override
                public void onUserChanging(int newUser, Context userContext) {
                    mHandler.post(() -> mUserInfoController.reloadUserInfo());
                }

                @Override
                public void onUserChanged(int newUser, Context userContext) {
                    mHandler.post(() -> {
                        updateAlarm();
                        updateProfileIcon();
                        onUserSetupChanged();
                    });
                }
            };

    private final HotspotController.Callback mHotspotCallback = new HotspotController.Callback() {
        @Override
        public void onHotspotChanged(boolean enabled, int numDevices) {
            mIconController.setIconVisibility(mSlotHotspot, enabled);
        }
    };

    private final NextAlarmController.NextAlarmChangeCallback mNextAlarmCallback =
            new NextAlarmController.NextAlarmChangeCallback() {
                @Override
                public void onNextAlarmChanged(AlarmClockInfo nextAlarm) {
                    mNextAlarm = nextAlarm;
                    updateAlarm();
                }
            };

    private final SensorPrivacyController.OnSensorPrivacyChangedListener mSensorPrivacyListener =
            new SensorPrivacyController.OnSensorPrivacyChangedListener() {
                @Override
                public void onSensorPrivacyChanged(boolean enabled) {
                    mHandler.post(() -> {
                        mIconController.setIconVisibility(mSlotSensorsOff, enabled);
                    });
                }
            };

    @Override
    public void appTransitionStarting(int displayId, long startTime, long duration,
            boolean forced) {
        if (mDisplayId == displayId) {
            updateProfileIcon();
            registerBlockedStatusChangedCallbackForLastResumedActivityUid();
        }
    }

    @Override
    public void appTransitionFinished(int displayId) {
        if (mDisplayId == displayId) {
            updateProfileIcon();
            registerBlockedStatusChangedCallbackForLastResumedActivityUid();
        }
    }

    @Override
    public void onKeyguardShowingChanged() {
        updateProfileIcon();
        registerBlockedStatusChangedCallbackForLastResumedActivityUid();
    }

    @Override
    public void onUserSetupChanged() {
        boolean userSetup = mProvisionedController.isCurrentUserSetup();
        if (mCurrentUserSetup == userSetup) return;
        mCurrentUserSetup = userSetup;
        updateAlarm();
    }

    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        boolean portrait = RotationLockTile.isCurrentOrientationLockPortrait(
                mRotationLockController, mResources);
        if (rotationLocked) {
            if (portrait) {
                mIconController.setIcon(mSlotRotate, R.drawable.stat_sys_rotate_portrait,
                        mResources.getString(R.string.accessibility_rotation_lock_on_portrait));
            } else {
                mIconController.setIcon(mSlotRotate, R.drawable.stat_sys_rotate_landscape,
                        mResources.getString(R.string.accessibility_rotation_lock_on_landscape));
            }
            mIconController.setIconVisibility(mSlotRotate, true);
        } else {
            mIconController.setIconVisibility(mSlotRotate, false);
        }
    }

    private void updateHeadsetPlug(Intent intent) {
        boolean connected = intent.getIntExtra("state", 0) != 0;
        mWiredHeadsetConnected = connected || hasWiredHeadsetDevice();
        updateHeadsetPresentation();
    }

    private void updateHeadsetDeviceState() {
        mWiredHeadsetConnected = hasWiredHeadsetDevice();
        updateHeadsetPresentation();
    }

    private void updateHeadsetPresentation() {
        if (mWiredHeadsetConnected) {
            mIconController.setIcon(mSlotNormalHeadset, R.drawable.stat_sys_normal_earphone,
                    mResources.getString(R.string.accessibility_status_bar_headphones));
        }
        mIconController.setIconVisibility(mSlotNormalHeadset, mWiredHeadsetConnected);
    }

    private boolean hasWiredHeadsetDevice() {
        if (mAudioManager == null) {
            return false;
        }
        for (AudioDeviceInfo device : mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    @Override
    public void onDataSaverChanged(boolean isDataSaving) {
        mIconController.setIconVisibility(mSlotDataSaver, isDataSaving);
    }

    @Override  // PrivacyItemController.Callback
    public void onPrivacyItemsChanged(List<PrivacyItem> privacyItems) {
        updatePrivacyItems(privacyItems);
    }

    private void updatePrivacyItems(List<PrivacyItem> items) {
        boolean showCamera = false;
        boolean showMicrophone = false;
        boolean showLocation = false;
        for (PrivacyItem item : items) {
            if (item == null /* b/124234367 */) {
                Log.e(TAG, "updatePrivacyItems - null item found");
                StringWriter out = new StringWriter();
                mPrivacyItemController.dump(new PrintWriter(out), null);
                // Throw so we can look into this
                throw new NullPointerException(out.toString());
            }
            switch (item.getPrivacyType()) {
                case TYPE_CAMERA:
                    showCamera = true;
                    break;
                case TYPE_LOCATION:
                    showLocation = true;
                    break;
                case TYPE_MICROPHONE:
                    showMicrophone = true;
                    break;
            }
        }

        // Disabling for now, but keeping the log
        /*
        mIconController.setIconVisibility(mSlotCamera, showCamera);
        mIconController.setIconVisibility(mSlotMicrophone, showMicrophone);
        if (mPrivacyItemController.getLocationAvailable()) {
            mIconController.setIconVisibility(mSlotLocation, showLocation);
        }
         */
        mPrivacyLogger.logStatusBarIconsVisible(showCamera, showMicrophone,  showLocation);
    }

    @Override
    public void onLocationActiveChanged(boolean active) {
        if (!mPrivacyItemController.getLocationAvailable()) {
            updateLocationFromController();
        }
    }

    // Updates the status view based on the current state of location requests.
    private void updateLocationFromController() {
        if (mLocationController.isLocationActive()) {
            mIconController.setIconVisibility(mSlotLocation, true);
        } else {
            mIconController.setIconVisibility(mSlotLocation, false);
        }
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case Intent.ACTION_SIM_STATE_CHANGED:
                    // Avoid rebroadcast because SysUI is direct boot aware.
                    if (intent.getBooleanExtra(Intent.EXTRA_REBROADCAST_ON_UNLOCK, false)) {
                        break;
                    }
                    break;
                case TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED:
                    updateTTY(intent.getIntExtra(TelecomManager.EXTRA_CURRENT_TTY_MODE,
                            TelecomManager.TTY_MODE_OFF));
                    break;
                case Intent.ACTION_MANAGED_PROFILE_AVAILABLE:
                case Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE:
                case Intent.ACTION_PROFILE_REMOVED:
                case Intent.ACTION_PROFILE_ACCESSIBLE:
                case Intent.ACTION_PROFILE_INACCESSIBLE:
                    updateProfileIcon();
                    break;
                case AudioManager.ACTION_HEADSET_PLUG:
                    updateHeadsetPlug(intent);
                    break;
            }
        }
    };

    private void onConnectedDisplayAvailabilityChanged(ConnectedDisplayInteractor.State state) {
        boolean visible = state != ConnectedDisplayInteractor.State.DISCONNECTED;

        if (DEBUG) {
            Log.d(TAG, "connected_display: " + (visible ? "showing" : "hiding") + " icon");
        }

        // Keep the last cast frame around briefly after disconnect, matching the factory R2
        // policy and avoiding a one-frame hole while the display route is being torn down.
        mHandler.removeCallbacks(mRemoveCastIconRunnable);
        if (visible) {
            mIconController.setIconVisibility(mSlotCast, true);
        } else {
            mHandler.postDelayed(mRemoveCastIconRunnable, 3000L);
        }
    }

    @Override
    public void onRecordingStart() {
        mIconController.setIconVisibility(mSlotScreenRecord, true);
    }

    @Override
    public void onRecordingEnd() {
        mIconController.setIconVisibility(mSlotScreenRecord, false);
    }
}
