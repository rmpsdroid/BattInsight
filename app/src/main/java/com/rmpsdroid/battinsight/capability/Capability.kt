package com.rmpsdroid.battinsight.capability

/**
 * The distinct things the application may be able to observe.
 *
 * Each is probed independently. Phase 1A measured a device where alarms and CPU worked
 * while everything else was empty, so a single global "is it working" flag would be wrong.
 *
 * Deliberately not present: any capability we have not measured a route to.
 */
enum class Capability {
    /** Aggregate battery statistics -- the primary data set. */
    BATTERY_STATS_AGGREGATE,

    /** Battery statistics history events. */
    BATTERY_STATS_HISTORY,

    /** Kernel wakelocks. Measured reachable via batterystats, not via sysfs or debugfs. */
    KERNEL_WAKELOCKS,

    /** Per-application partial wakelocks. */
    PARTIAL_WAKELOCKS,

    /** Alarm attribution. */
    ALARMS,

    /** Sensor usage. */
    SENSORS,

    /** CPU and process attribution. */
    CPU_AND_PROCESSES,

    /** Per-UID network usage. */
    NETWORK,

    /** Application usage statistics. */
    USAGE_STATS,

    /** Resolving a UID to a human-readable application name and icon. */
    UID_NAME_RESOLUTION,

    /** Public battery level, status, temperature, voltage and charge counters. */
    BATTERY_PROPERTIES,
}
