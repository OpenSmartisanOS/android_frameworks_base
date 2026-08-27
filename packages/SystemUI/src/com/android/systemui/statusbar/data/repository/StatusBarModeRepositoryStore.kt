/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.data.repository

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.statusbar.core.StatusBarInitializer
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

interface StatusBarModeRepositoryStore : PerDisplayStore<StatusBarModePerDisplayRepository>

@SysUISingleton
class MultiDisplayStatusBarModeRepositoryStore
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    private val factory: StatusBarModePerDisplayRepositoryFactory,
    displayRepository: DisplayRepository,
) :
    StatusBarModeRepositoryStore,
    StatusBarPerDisplayStoreImpl<StatusBarModePerDisplayRepository>(
        backgroundApplicationScope,
        displayRepository,
    ) {

    override fun createInstanceForDisplay(displayId: Int): StatusBarModePerDisplayRepository {
        return factory.create(displayId).also { it.start() }
    }

    override suspend fun onDisplayRemovalAction(instance: StatusBarModePerDisplayRepository) {
        instance.stop()
    }

    override val instanceClass = StatusBarModePerDisplayRepository::class.java
}

@Module
object StatusBarModeRepositoryModule {
    @Provides
    @ElementsIntoSet
    fun bindViewInitListener(): Set<StatusBarInitializer.StatusBarViewLifecycleListener> =
        emptySet()

    @Provides
    @SysUISingleton
    @IntoMap
    @ClassKey(StatusBarModeRepositoryStore::class)
    fun storeAsCoreStartable(
        multiDisplayLazy: Lazy<MultiDisplayStatusBarModeRepositoryStore>,
    ): CoreStartable = multiDisplayLazy.get()

    @Provides
    @SysUISingleton
    fun store(
        multiDisplayLazy: Lazy<MultiDisplayStatusBarModeRepositoryStore>,
    ): StatusBarModeRepositoryStore = multiDisplayLazy.get()
}
