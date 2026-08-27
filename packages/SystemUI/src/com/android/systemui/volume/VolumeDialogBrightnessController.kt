package com.android.systemui.volume

import com.android.systemui.brightness.domain.interactor.BrightnessPolicyEnforcementInteractor
import com.android.systemui.brightness.domain.interactor.ScreenBrightnessInteractor
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.utils.PolicyRestriction
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Small Java-facing bridge from the R2 panel to Android 16's brightness domain. */
@SysUISingleton
class VolumeDialogBrightnessController
@Inject
constructor(
    private val interactor: ScreenBrightnessInteractor,
    private val policyInteractor: BrightnessPolicyEnforcementInteractor,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainExecutor: Executor,
) {
    interface Listener {
        fun onBrightnessChanged(
            gamma: Int,
            automatic: Boolean,
            overriddenByWindow: Boolean,
            restrictedByPolicy: Boolean,
        )
    }

    private var collection: Job? = null
    private val interactionGeneration = AtomicInteger()
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private sealed interface Command {
        val generation: Int

        data class Temporary(val gamma: Int, override val generation: Int) : Command
        data class Permanent(val gamma: Int, override val generation: Int) : Command
        data class Cancel(val gamma: Int, override val generation: Int) : Command
    }

    init {
        applicationScope.launch {
            for (command in commands) {
                if (command.generation != interactionGeneration.get()) continue
                when (command) {
                    is Command.Temporary ->
                        interactor.setTemporaryBrightness(GammaBrightness(command.gamma))
                    is Command.Permanent -> {
                        interactor.setBrightness(GammaBrightness(command.gamma))
                        interactionGeneration.compareAndSet(command.generation, command.generation + 1)
                    }
                    is Command.Cancel -> {
                        interactor.setTemporaryBrightness(GammaBrightness(command.gamma))
                        interactionGeneration.compareAndSet(command.generation, command.generation + 1)
                    }
                }
            }
        }
    }

    fun start(listener: Listener) {
        collection?.cancel()
        collection =
            applicationScope.launch {
                combine(
                        interactor.gammaBrightness,
                        interactor.isAutoBrightnessEnabledFlow,
                        interactor.brightnessOverriddenByWindow,
                        policyInteractor.brightnessPolicyRestriction,
                    ) { gamma, automatic, overridden, restriction ->
                        BrightnessSnapshot(
                            gamma.value,
                            automatic,
                            overridden,
                            restriction is PolicyRestriction.Restricted,
                        )
                    }
                    .collect { value ->
                        mainExecutor.execute {
                            listener.onBrightnessChanged(
                                value.gamma,
                                value.automatic,
                                value.overriddenByWindow,
                                value.restrictedByPolicy,
                            )
                        }
                    }
            }
    }

    fun stop() {
        collection?.cancel()
        collection = null
        // VolumeDialogImpl ends any active gesture before stopping collection. Do not invalidate
        // that queued terminal Cancel command here, or a temporary brightness override could
        // survive the dialog lifecycle transition.
    }

    fun minGamma(): Int = interactor.minGammaBrightness.value

    fun maxGamma(): Int = interactor.maxGammaBrightness.value

    fun beginInteraction(): Int = interactionGeneration.incrementAndGet()

    fun setTemporary(gamma: Int, generation: Int) {
        commands.trySend(Command.Temporary(gamma, generation))
    }

    fun setPermanent(gamma: Int, generation: Int) {
        commands.trySend(Command.Permanent(gamma, generation))
    }

    fun cancelInteraction(gamma: Int, generation: Int) {
        commands.trySend(Command.Cancel(gamma, generation))
    }

    fun invalidateInteractions() {
        interactionGeneration.incrementAndGet()
    }

    private data class BrightnessSnapshot(
        val gamma: Int,
        val automatic: Boolean,
        val overriddenByWindow: Boolean,
        val restrictedByPolicy: Boolean,
    )
}
