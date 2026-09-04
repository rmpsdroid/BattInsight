package com.rmpsdroid.battinsight.access

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An in-memory preference store.
 *
 * [recreate] returns a store sharing the same backing value, which is how a test checks
 * that a choice survives process death without needing a real DataStore.
 */
class FakeAccessPreferenceStore(
    initial: AccessMode = AccessMode.NOT_CHOSEN,
    private val backing: MutableStateFlow<AccessMode> = MutableStateFlow(initial),
) : AccessPreferenceStore {

    var writes = 0
        private set

    override val accessMode: Flow<AccessMode> = backing.asStateFlow()

    override suspend fun current(): AccessMode = backing.value

    override suspend fun setAccessMode(mode: AccessMode) {
        writes++
        backing.value = mode
    }

    /** A fresh store over the same persisted value, as a restart would produce. */
    fun recreate(): FakeAccessPreferenceStore = FakeAccessPreferenceStore(backing = backing)
}
