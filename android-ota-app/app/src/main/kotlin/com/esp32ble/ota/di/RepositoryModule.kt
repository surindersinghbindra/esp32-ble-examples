package com.esp32ble.ota.di

import com.esp32ble.ota.data.ble.BleOtaRepositoryImpl
import com.esp32ble.ota.data.ble.FirmwareSourceImpl
import com.esp32ble.ota.domain.repository.BleOtaRepository
import com.esp32ble.ota.domain.repository.FirmwareSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * This is the only file in the whole app that mentions both a domain *interface*
 * (`BleOtaRepository`, `FirmwareSource`) and its concrete Android implementation
 * (`BleOtaRepositoryImpl`, `FirmwareSourceImpl`) in the same place - by design. Everywhere else,
 * code asks for the interface (e.g. a use case's constructor takes a `BleOtaRepository`) and has
 * no idea which concrete class is actually behind it. This module is what tells Hilt "whenever
 * something needs a `BleOtaRepository`, give it a `BleOtaRepositoryImpl`" - the one and only place
 * that decision is made.
 *
 * A few Kotlin/Dagger things worth calling out for anyone new to this pattern:
 * - `abstract class`: this module is never actually instantiated. Dagger's annotation processor
 *   (running via KSP, configured in `app/build.gradle.kts`) reads it at compile time and
 *   generates real, instantiable code from it - none of what's written here runs at app runtime
 *   as-is.
 * - `@Binds` (rather than `@Provides`, which you'll see used for things that need actual
 *   construction logic) is specifically for "interface X should resolve to implementation Y" -
 *   it's just a compile-time instruction, cheaper than `@Provides` because there's no function
 *   body to actually execute.
 * - The function bodies below (`abstract fun ... : BleOtaRepository`) are never called - an
 *   abstract function has no body at all, which is exactly the point: the *signature*
 *   (parameter type -> return type) is the only thing Dagger reads.
 * - `@InstallIn(SingletonComponent::class)` scopes these bindings to the whole app's lifetime
 *   (as opposed to, say, just one Activity) - matching `@Singleton` on `BleOtaRepositoryImpl`
 *   itself.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBleOtaRepository(impl: BleOtaRepositoryImpl): BleOtaRepository

    @Binds
    abstract fun bindFirmwareSource(impl: FirmwareSourceImpl): FirmwareSource
}
