package com.basira.app.data.glasses

/**
 * Prepares simulated glasses for development builds.
 *
 * The debug implementation enables DAT MockDeviceKit and pairs a simulated Ray-Ban Meta device;
 * release builds use a no-op implementation and never contain MockDeviceKit.
 */
interface SimulatedGlassesController {
    /** `true` when this build simulates the glasses through MockDeviceKit. */
    val isActive: Boolean

    /**
     * Enables the simulation if [isActive]. Must run before the first DAT session is created.
     * Idempotent.
     */
    fun prepare()
}
