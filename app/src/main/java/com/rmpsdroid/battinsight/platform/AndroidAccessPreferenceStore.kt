package com.rmpsdroid.battinsight.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rmpsdroid.battinsight.access.AccessMode
import com.rmpsdroid.battinsight.access.AccessPreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The access choice, on disk.
 *
 * One string. That is the whole of BattInsight's persistent state, and it is deliberate:
 * everything else the application knows is an observation of the device that goes stale the
 * moment anything changes, so storing it would create a second source of truth able to
 * disagree with the first.
 *
 * The file lives in the app's private storage and is excluded from cloud backup along with
 * the rest of the application — `android:allowBackup="false"`, plus explicit backup rules.
 * Nothing here is sensitive, but a preference restored onto a different device would be
 * asserting a setup that device may not have.
 *
 * A corrupted read yields [AccessMode.NOT_CHOSEN], which sends the user back through
 * onboarding. That is the safe direction to fail: it asks a question rather than assuming
 * an answer.
 */
private val Context.accessDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "access_preferences",
)

class AndroidAccessPreferenceStore(context: Context) : AccessPreferenceStore {

    private val appContext = context.applicationContext

    override val accessMode: Flow<AccessMode> = appContext.accessDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { AccessMode.fromStoredValue(it[KEY_ACCESS_MODE]) }

    override suspend fun current(): AccessMode = accessMode.first()

    override suspend fun setAccessMode(mode: AccessMode) {
        appContext.accessDataStore.edit { it[KEY_ACCESS_MODE] = mode.name }
    }

    private companion object {
        val KEY_ACCESS_MODE = stringPreferencesKey("access_mode")
    }
}
