package com.basira.app.di

import com.basira.app.data.glasses.SimulatedGlassesController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Release builds never simulate glasses and do not contain MockDeviceKit. */
@Module
@InstallIn(SingletonComponent::class)
object SimulationModule {
    /** No-op controller. */
    @Provides
    fun provide(): SimulatedGlassesController = object : SimulatedGlassesController {
        override val isActive: Boolean = false
        override fun prepare() = Unit
    }
}
