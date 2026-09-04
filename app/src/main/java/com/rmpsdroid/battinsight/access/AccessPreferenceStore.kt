package com.rmpsdroid.battinsight.access

import kotlinx.coroutines.flow.Flow

/**
 * Persists the user's access choice, and nothing else.
 *
 * ## What is deliberately not stored
 *
 * No batterystats payloads, no package lists, no Shizuku binder identity, no permission
 * denial text, no capability reports. Those are either the user's data or observations that
 * go stale the moment the device changes, and keeping them would create a second, quieter
 * source of truth that could disagree with reality.
 *
 * ## Why there is no `onboardingCompleted` flag
 *
 * A completion flag would outlive the thing it claims. Shizuku stops on reboot; permissions
 * can be revoked from Settings or ADB; a user can uninstall Shizuku entirely. An
 * application that remembered "setup done" would then keep asserting readiness it no longer
 * has. Readiness is therefore always re-derived from a current capability report, and the
 * only thing worth remembering is the *choice* — which is a statement of intent, not of
 * capability, and stays true until the user changes it.
 */
interface AccessPreferenceStore {

    /** The current choice, emitting again whenever it changes. */
    val accessMode: Flow<AccessMode>

    /** Reads once, for callers that cannot collect. */
    suspend fun current(): AccessMode

    /** Records a new choice. */
    suspend fun setAccessMode(mode: AccessMode)
}
