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

import android.os.Bundle;

/**
 * Original Smartisan Recorder callback contract.
 *
 * Keep this package and its one-way shape intact: both the original keyguard and recorder
 * exchange this Binder descriptor with independently built APKs.
 */
oneway interface ICallback {
    void done(in @nullable Bundle data);
}
