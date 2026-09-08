package com.rmpsdroid.battinsight.harness;

/**
 * A test-only remote endpoint that speaks both privileged transports.
 *
 * Lives in the androidTest source set and is declared with android:process, so it runs in a
 * genuinely separate process of the test APK. That makes every call below a real Binder
 * transaction with real parcel marshalling and real ParcelFileDescriptor transfer -- none of
 * which a JVM test can simulate, and the parcel size limit is exactly the thing that failed.
 *
 * It ships in no production APK and is exported to nobody.
 */
interface ITransportHarness {

    /**
     * The transport as it is now: a reply carrying descriptors, and bytes through pipes.
     * Same Bundle keys, same completion frame, same producer as the real service.
     */
    Bundle openStream(int stdoutBytes, int stderrBytes, int exitCode) = 1;

    /**
     * The transport as it was before Phase 10A.3: stdout in the reply, marshalled by value.
     *
     * Present so the regression can be demonstrated rather than asserted. At the size that
     * failed on hardware this call fails, and the streaming call above does not.
     */
    Bundle returnByValue(int stdoutBytes) = 2;
}
