/*
 * Copyright (C) 2026 OpenSmartisanOS
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

package com.smartisanos.recorder.service;

import com.smartisanos.recorder.service.ICallback;

/**
 * Original Smartisan Recorder creator-service contract.
 *
 * Method order deliberately matches the original APK's transaction IDs.  The service is shared
 * across independently built SystemUI and Recorder APKs, so changing either order or one-way
 * semantics would silently make Binder calls target the wrong operation.
 */
interface IRecorderCreatorService {
    oneway void startRecording(
            in @nullable String sourceDirectory,
            in @nullable String sourceName,
            int format,
            int sampleRate,
            int channel,
            int bitRate,
            ICallback callback);
    oneway void pauseRecording(ICallback callback);
    oneway void resumeRecording(ICallback callback);
    oneway void stopRecording(in @nullable String sourceName, ICallback callback);
    oneway void getRecordingCurrentState(ICallback callback);
    oneway void registerRecordingCallback(ICallback callback);
    oneway void unregisterRecordingCallback(ICallback callback);
    oneway void mark(ICallback callback);
    int getRecordingState();
    oneway void stopRecordingWithNotification(ICallback callback);
    oneway void stopRecordingTemporarily(ICallback callback);
}
