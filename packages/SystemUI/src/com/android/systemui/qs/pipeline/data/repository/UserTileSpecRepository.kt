package com.android.systemui.qs.pipeline.data.repository

import android.annotation.UserIdInt
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.Flags.hsuQsChanges
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.TilesUpgradePath
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.res.R
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode
import com.android.systemui.util.settings.SecureSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Single user version of [TileSpecRepository]. It provides a similar interface as
 * [TileSpecRepository], but focusing solely on the user it was created for.
 *
 * This is the source of truth for that user's tiles, after the user has been started. Persisting
 * all the changes to [Settings]. Changes in [Settings] that disagree with this repository will be
 * reverted
 *
 * All operations against [Settings] will be performed in a background thread.
 */
class UserTileSpecRepository
@AssistedInject
constructor(
    @Assisted private val userId: Int,
    private val defaultTilesRepository: DefaultTilesRepository,
    private val secureSettings: SecureSettings,
    private val hsum: HeadlessSystemUserMode,
    private val logger: QSPipelineLogger,
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val _tilesUpgradePath = Channel<TilesUpgradePath>(capacity = 3)
    val tilesUpgradePath: ReceiveChannel<TilesUpgradePath> = _tilesUpgradePath

    private val defaultTiles: List<TileSpec>
        get() = defaultTilesRepository.getDefaultTiles(isHeadlessSystemUser)

    private val changeEvents =
        MutableSharedFlow<ChangeAction>(extraBufferCapacity = CHANGES_BUFFER_SIZE)

    private var isHeadlessSystemUser = false

    private lateinit var _tiles: StateFlow<List<TileSpec>>

    suspend fun tiles(): Flow<List<TileSpec>> {
        if (!::_tiles.isInitialized) {
            withContext(backgroundDispatcher) {
              isHeadlessSystemUser = hsuQsChanges() && hsum.isHeadlessSystemUser(userId)
            }
            _tiles =
                changeEvents
                    .scan(loadTilesFromSettingsAndParse(userId)) { current, change ->
                        change
                            .apply(current)
                            .also { afterRestore ->
                                if (current != afterRestore) {
                                    if (change is RestoreTiles) {
                                        logger.logTilesRestoredAndReconciled(
                                            current,
                                            afterRestore,
                                            userId,
                                        )
                                    } else {
                                        logger.logProcessTileChange(change, afterRestore, userId)
                                    }
                                }
                                if (change is RestoreTiles) {
                                    _tilesUpgradePath.send(
                                        TilesUpgradePath.RestoreFromBackup(afterRestore.toSet())
                                    )
                                }
                            }
                            // Distinct preserves the order of the elements removing later
                            // duplicates,
                            // all tiles should be different
                            .distinct()
                    }
                    .flowOn(backgroundDispatcher)
                    .stateIn(applicationScope)
                    .also { startFlowCollections(it) }
        }
        return _tiles
    }

    private fun startFlowCollections(tiles: StateFlow<List<TileSpec>>) {
        applicationScope.launch(context = backgroundDispatcher) {
            launch { tiles.collect { storeTiles(userId, it) } }
            launch {
                // As Settings is not the source of truth, once we started tracking tiles for a
                // user, we don't want anyone to change the underlying setting. Therefore, if there
                // are any changes that don't match with the source of truth (this class), we
                // overwrite them with the current value.
                ConflatedCallbackFlow.conflatedCallbackFlow {
                        val observer =
                            object : ContentObserver(null) {
                                override fun onChange(selfChange: Boolean) {
                                    trySend(Unit)
                                }
                            }
                        secureSettings.registerContentObserverForUserSync(SETTING, observer, userId)
                        awaitClose { secureSettings.unregisterContentObserverSync(observer) }
                    }
                    .map { loadTilesFromSettings(userId) }
                    .flowOn(backgroundDispatcher)
                    .collect { setting ->
                        val current = tiles.value
                        if (setting != current) {
                            storeTiles(userId, current)
                        }
                    }
            }
        }
    }

    private suspend fun storeTiles(@UserIdInt forUser: Int, tiles: List<TileSpec>) {
        val toStore =
            tiles
                .filter { it !is TileSpec.Invalid }
                .joinToString(DELIMITER, transform = TileSpec::spec)
        withContext(backgroundDispatcher) {
            secureSettings.putStringForUser(SETTING, toStore, null, false, forUser, true)
        }
    }

    suspend fun addTile(tile: TileSpec, position: Int = TileSpecRepository.POSITION_AT_END) {
        if (tile is TileSpec.Invalid) {
            return
        }
        changeEvents.emit(AddTile(tile, position))
    }

    suspend fun removeTiles(tiles: Collection<TileSpec>) {
        changeEvents.emit(RemoveTiles(tiles))
    }

    suspend fun setTiles(tiles: List<TileSpec>) {
        changeEvents.emit(ChangeTiles(tiles))
    }

    private fun parseTileSpecs(fromSettings: List<TileSpec>, user: Int): List<TileSpec> {
        return if (fromSettings.isNotEmpty()) {
            fromSettings.also { logger.logParsedTiles(it, false, user) }
        } else {
            defaultTiles.also { logger.logParsedTiles(it, true, user) }
        }
    }

    private suspend fun loadTilesFromSettingsAndParse(userId: Int): List<TileSpec> {
        val loadedTiles = loadTilesFromSettings(userId)
        val layoutVersion =
            secureSettings.getIntForUser(SOS_LAYOUT_VERSION_SETTING, 0, userId)
        if (layoutVersion == 0) {
            val legacyTiles = loadLegacySosTiles(userId)
            val migratedCurrentTiles = migrateSosTiles(loadedTiles)
            val initialTiles =
                when {
                    legacyTiles.isNotEmpty() -> legacyTiles
                    migratedCurrentTiles.isNotEmpty() -> migratedCurrentTiles
                    else -> SOS_DEFAULT_TILES
                }
            if (initialTiles != loadedTiles) {
                storeTiles(userId, initialTiles)
            }
            secureSettings.putIntForUser(
                SOS_LAYOUT_VERSION_SETTING,
                SOS_LAYOUT_VERSION,
                userId,
            )
            _tilesUpgradePath.send(
                if (legacyTiles.isNotEmpty() || loadedTiles.isNotEmpty()) {
                    TilesUpgradePath.ReadFromSettings(initialTiles.toSet())
                } else {
                    TilesUpgradePath.DefaultSet
                }
            )
            return initialTiles
        }
        val migratedTiles =
            if (layoutVersion < SOS_LAYOUT_VERSION) {
                migrateSosTiles(loadedTiles).also {
                    if (it != loadedTiles) {
                        storeTiles(userId, it)
                    }
                    secureSettings.putIntForUser(
                        SOS_LAYOUT_VERSION_SETTING,
                        SOS_LAYOUT_VERSION,
                        userId,
                    )
                }
            } else {
                loadedTiles
            }
        return finishLoadingTiles(migratedTiles, userId)
    }

    private suspend fun finishLoadingTiles(
        loadedTiles: List<TileSpec>,
        userId: Int,
    ): List<TileSpec> {
        if (loadedTiles.isNotEmpty()) {
            _tilesUpgradePath.send(TilesUpgradePath.ReadFromSettings(loadedTiles.toSet()))
        } else {
            _tilesUpgradePath.send(TilesUpgradePath.DefaultSet)
        }
        return parseTileSpecs(loadedTiles, userId)
    }

    private fun migrateSosTilesV2(tiles: List<TileSpec>): List<TileSpec> =
        tiles
            .mapNotNull { tile ->
                when {
                    tile.spec in SOS_V2_DROPPED_SPECS -> null
                    tile.spec in SOS_V2_SPEC_ALIASES ->
                        TileSpec.create(SOS_V2_SPEC_ALIASES.getValue(tile.spec))
                    else -> tile
                }
            }
            .distinct()

    private fun migrateSosTiles(tiles: List<TileSpec>): List<TileSpec> {
        val migrated = migrateSosTilesV2(tiles)
        return if (migrated.map(TileSpec::spec) in SOS_INCOMPLETE_GENERATED_LAYOUTS) {
            // Early development builds registered only fourteen generated entries. Replace those
            // exact known layouts with the complete original 4x5 order without disturbing a
            // genuinely user-customized list.
            SOS_DEFAULT_TILES
        } else {
            migrated
        }
    }

    private fun loadLegacySosTiles(userId: Int): List<TileSpec> {
        val resolver = context.contentResolver
        val primary =
            Settings.System.getStringForUser(
                resolver,
                SOS_LEGACY_TILE_SETTING,
                userId,
            )
        val additional =
            Settings.System.getStringForUser(
                resolver,
                SOS_LEGACY_ADDITIONAL_TILE_SETTING,
                userId,
            )
        return sequenceOf(primary, additional)
            .filterNotNull()
            .flatMap { it.split('|').asSequence() }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(TileSpec::create)
            .toList()
            .let(::migrateSosTiles)
    }

    private suspend fun loadTilesFromSettings(userId: Int): List<TileSpec> {
        return withContext(backgroundDispatcher) {
                secureSettings.getStringForUser(SETTING, userId) ?: ""
            }
            .toTilesList()
    }

    suspend fun reconcileRestore(restoreData: RestoreData, currentAutoAdded: Set<TileSpec>) {
        changeEvents.emit(RestoreTiles(restoreData, currentAutoAdded))
    }

    suspend fun prependDefault() {
        changeEvents.emit(PrependDefault(defaultTiles))
    }

    suspend fun resetToDefault(): List<TileSpec> {
        changeEvents.emit(ResetToDefault(defaultTiles))
        return defaultTiles
    }

    sealed interface ChangeAction {
        fun apply(currentTiles: List<TileSpec>): List<TileSpec>
    }

    private data class AddTile(
        val tileSpec: TileSpec,
        val position: Int = TileSpecRepository.POSITION_AT_END,
    ) : ChangeAction {
        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            val tilesList = currentTiles.toMutableList()
            if (tileSpec !in tilesList) {
                if (position < 0 || position >= tilesList.size) {
                    tilesList.add(tileSpec)
                } else {
                    tilesList.add(position, tileSpec)
                }
            }
            return tilesList
        }
    }

    private data class RemoveTiles(val tileSpecs: Collection<TileSpec>) : ChangeAction {
        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            return currentTiles.toMutableList().apply { removeAll(tileSpecs) }
        }
    }

    private data class ChangeTiles(val newTiles: List<TileSpec>) : ChangeAction {
        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            val new = newTiles.filter { it !is TileSpec.Invalid }
            return if (new.isNotEmpty()) new else currentTiles
        }
    }

    private data class PrependDefault(val defaultTiles: List<TileSpec>) : ChangeAction {
        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            return defaultTiles + currentTiles
        }
    }

    private data class ResetToDefault(val defaultTiles: List<TileSpec>) : ChangeAction {
        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            return defaultTiles
        }
    }

    private data class RestoreTiles(
        val restoreData: RestoreData,
        val currentAutoAdded: Set<TileSpec>,
    ) : ChangeAction {

        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            return reconcileTiles(currentTiles, currentAutoAdded, restoreData)
        }
    }

    companion object {
        private const val SETTING = Settings.Secure.QS_TILES
        private const val SOS_LAYOUT_VERSION_SETTING = "sos_qs_layout_version"
        private const val SOS_LAYOUT_VERSION = 4
        private const val SOS_LEGACY_TILE_SETTING = "expanded_widget_buttons"
        private const val SOS_LEGACY_ADDITIONAL_TILE_SETTING =
            "expanded_widget_buttons_additional"
        private const val DELIMITER = TilesSettingConverter.DELIMITER
        // We want a small buffer in case multiple changes come in at the same time (sometimes
        // happens in first start. This should be enough to not lose changes.
        private const val CHANGES_BUFFER_SIZE = 10

        private val SOS_DEFAULT_TILES =
            listOf(
                    "airplane",
                    "wifi",
                    "cell",
                    "vpn",
                    "hotspot",
                    "bt",
                    "sos_disable_buttons",
                    "location",
                    "flashlight",
                    "rotation",
                    "screenrecord",
                    "battery",
                    "sos_screenshot",
                    "sos_vibrate",
                    "sos_mute",
                    "nfc",
                    "caffeine",
                    "sos_lock_screen",
                    "sos_protect_eyes",
                    "sos_fake_call",
                )
                .map(TileSpec::create)

        private val SOS_INCOMPLETE_GENERATED_LAYOUTS =
            setOf(
                listOf(
                    "airplane",
                    "wifi",
                    "cell",
                    "vpn",
                    "hotspot",
                    "bt",
                    "location",
                    "flashlight",
                    "rotation",
                    "screenrecord",
                    "battery",
                    "nfc",
                    "caffeine",
                    "sos_fake_call",
                ),
                listOf(
                    "airplane",
                    "wifi",
                    "cell",
                    "vpn",
                    "hotspot",
                    "bt",
                    "location",
                    "flashlight",
                    "rotation",
                    "screenrecord",
                    "battery",
                    "nfc",
                    "caffeine",
                    "sos_protect_eyes",
                ),
            )

        private val SOS_V2_SPEC_ALIASES =
            mapOf(
                "toggleAirplane" to "airplane",
                "toggleAutoRotate" to "rotation",
                "toggleBluetooth" to "bt",
                "toggleDisableButtons" to "sos_disable_buttons",
                "toggleFakeCall" to "sos_fake_call",
                "toggleFlashlight" to "flashlight",
                "toggleGPS" to "location",
                "toggleKeepScreenOn" to "caffeine",
                "toggleLockScreen" to "sos_lock_screen",
                "toggleMobileData" to "cell",
                "toggleMute" to "sos_mute",
                "toggleNFC" to "nfc",
                "togglepowersave" to "battery",
                "toggleProtectEyes" to "sos_protect_eyes",
                "toggleReadingMode" to "reading_mode",
                "toggleRealtimeSubtitle" to "hearing_devices",
                "togglerrecordscreen" to "screenrecord",
                "toggleScreenShot" to "sos_screenshot",
                "toggleVibrate" to "sos_vibrate",
                "toggleVpn" to "vpn",
                "toggleWifi" to "wifi",
                "toggleWifiAp" to "hotspot",
                "toggleWirelessTNT" to "cast",
                "disable_buttons" to "sos_disable_buttons",
                "screenshot" to "sos_screenshot",
                "vibration" to "sos_vibrate",
                "silent" to "sos_mute",
                "lock" to "sos_lock_screen",
                "eyes" to "sos_protect_eyes",
                "fake_call" to "sos_fake_call",
                "night" to "sos_protect_eyes",
            )

        private val SOS_V2_DROPPED_SPECS = setOf("toggleAutoBrightness")

        private fun String.toTilesList() = TilesSettingConverter.toTilesList(this)

        fun reconcileTiles(
            currentTiles: List<TileSpec>,
            currentAutoAdded: Set<TileSpec>,
            restoreData: RestoreData,
        ): List<TileSpec> {
            val toRestore = restoreData.restoredTiles.toMutableList()
            val freshlyAutoAdded =
                currentAutoAdded.filterNot { it in restoreData.restoredAutoAddedTiles }
            freshlyAutoAdded
                .filter { it in currentTiles && it !in restoreData.restoredTiles }
                .map { it to currentTiles.indexOf(it) }
                .sortedBy { it.second }
                .forEachIndexed { iteration, (tile, position) ->
                    val insertAt = position + iteration
                    if (insertAt > toRestore.size) {
                        toRestore.add(tile)
                    } else {
                        toRestore.add(insertAt, tile)
                    }
                }

            return toRestore
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(userId: Int): UserTileSpecRepository
    }
}
