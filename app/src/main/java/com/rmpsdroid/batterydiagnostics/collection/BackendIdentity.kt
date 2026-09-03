package com.rmpsdroid.batterydiagnostics.collection

/**
 * The identity a [PrivilegeBackend] actually executes as.
 *
 * Measured, never inferred. Phase 1B read these values from the running process rather
 * than deducing them from how a backend was started, and the distinction matters:
 * `/sys/class/wakeup` on Android 16 is mode 0755 root:root -- world-readable by classic
 * permissions -- yet unreadable from `u:r:shell:s0`. The SELinux domain, not the UID,
 * was the deciding factor. A backend that reports only a UID cannot explain that.
 */
data class BackendIdentity(
    /** POSIX uid the backend's commands run as. */
    val uid: Int,
    /** Full SELinux context, e.g. `u:r:shell:s0` or `u:r:untrusted_app:s0:c241,...`. */
    val selinuxContext: String,
    /** How this identity was obtained. */
    val kind: Kind,
) {
    enum class Kind {
        /** Our own application process. Measured `u:r:untrusted_app:s0`. */
        APP_UID,

        /** A shell-identity session. Measured uid 2000, `u:r:shell:s0`. */
        SHELL,

        /** Root. No root environment has been measured in any phase to date. */
        ROOT,
    }

    /**
     * Whether this identity shares the shell domain.
     *
     * Phase 1B measured an ADB-started Shizuku session at `u:r:shell:s0` -- the same domain
     * as ADB shell -- which is why its batterystats output matched ADB's exactly, including
     * the UID-to-package-name mappings an app UID could not resolve.
     */
    val isShellDomain: Boolean get() = kind == Kind.SHELL

    companion object {
        /** Sentinel for a backend that has not yet reported its identity. */
        val UNKNOWN = BackendIdentity(uid = -1, selinuxContext = "", kind = Kind.APP_UID)
    }
}
