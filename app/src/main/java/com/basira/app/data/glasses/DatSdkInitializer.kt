package com.basira.app.data.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.domain.model.SdkState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.WearablesError
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Initializes the Meta Wearables Device Access Toolkit exactly once.
 *
 * DAT documentation requires Android Bluetooth permissions before `Wearables.initialize`, so the
 * Application calls [initializeIfPermitted] at start-up and the Activity calls it again after the
 * user grants `BLUETOOTH_CONNECT`. Initialization failures are reported through [state] and never
 * retried in a loop.
 */
@Singleton
class DatSdkInitializer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val errorReporter: ErrorReporter,
) {
    private val _state = MutableStateFlow(SdkState.NOT_INITIALIZED)

    /** Current initialization state. */
    val state: StateFlow<SdkState> = _state.asStateFlow()

    /**
     * Initializes DAT when the Bluetooth permission is granted.
     *
     * @return the resulting state.
     */
    @Synchronized
    fun initializeIfPermitted(): SdkState {
        if (_state.value == SdkState.READY || _state.value == SdkState.FAILED) return _state.value
        if (!hasBluetoothPermission()) {
            _state.value = SdkState.PERMISSION_REQUIRED
            return _state.value
        }
        _state.value = Wearables.initialize(context).fold(
            { SdkState.READY },
            { error, _ ->
                if (error == WearablesError.ALREADY_INITIALIZED) {
                    SdkState.READY
                } else {
                    errorReporter.report(
                        ErrorReport(
                            domain = ErrorDomain.GLASSES,
                            operation = "dat.initialize",
                            severity = ErrorSeverity.CRITICAL,
                            outcome = "glasses_unavailable",
                            message = error.description,
                            reason = DatErrorMapper.reason(error),
                        ),
                    )
                    SdkState.FAILED
                }
            },
        )
        return _state.value
    }

    /** Returns `true` when the Bluetooth permission required by DAT is granted. */
    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
