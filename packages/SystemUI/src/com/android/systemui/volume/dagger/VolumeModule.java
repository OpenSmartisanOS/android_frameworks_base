/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.volume.dagger;

import android.content.BroadcastReceiver;
import android.content.Context;
import com.android.systemui.CoreStartable;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.volume.CsdWarningDialog;
import com.android.systemui.volume.VolumeDialogBrightnessController;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.volume.VolumeDialogComponent;
import com.android.systemui.volume.VolumeDialogImpl;
import com.android.systemui.volume.VolumePanelDialogReceiver;
import com.android.systemui.volume.VolumeUI;
import com.android.systemui.volume.panel.dagger.VolumePanelComponent;
import com.android.systemui.volume.panel.dagger.factory.VolumePanelComponentFactory;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;

/** Dagger Module for code in the volume package. */
@Module(
        includes = {
                AudioModule.class,
                AudioSharingModule.class,
                AncModule.class,
                CaptioningModule.class,
                MediaDevicesModule.class,
                SpatializerModule.class,
        },
        subcomponents = {
                VolumePanelComponent.class,
        }
)
public interface VolumeModule {

    /**
     * Binds [VolumePanelDialogReceiver]
     */
    @Binds
    @IntoMap
    @ClassKey(VolumePanelDialogReceiver.class)
    BroadcastReceiver bindVolumePanelDialogReceiver(VolumePanelDialogReceiver receiver);

    /** Starts VolumeUI. */
    @Binds
    @IntoMap
    @ClassKey(VolumeUI.class)
    CoreStartable bindVolumeUIStartable(VolumeUI impl);

    /** Listen to config changes for VolumeUI. */
    @Binds
    @IntoSet
    ConfigurationController.ConfigurationListener bindVolumeUIConfigChanges(VolumeUI impl);

    /**  */
    @Binds
    VolumeComponent provideVolumeComponent(VolumeDialogComponent volumeDialogComponent);

    /**  */
    @Binds
    VolumePanelComponentFactory bindVolumePanelComponentFactory(VolumePanelComponent.Factory impl);

    /** Provides the single volume-dialog presentation used on every display. */
    @Provides
    static VolumeDialog provideVolumeDialog(
            Context context,
            VolumeDialogController volumeDialogController,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            CsdWarningDialog.Factory csdFactory,
            VolumeDialogBrightnessController brightnessController,
            UserTracker userTracker) {
        return new VolumeDialogImpl(
                context,
                volumeDialogController,
                accessibilityManagerWrapper,
                csdFactory,
                brightnessController,
                userTracker);
    }
}
