package com.basira.app.data.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.basira.core.logging.AppLogger
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.registration.RegistrationRequest
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import javax.inject.Inject

/** A registration request initiated from the Meta AI app, waiting for the user's decision. */
interface PendingRegistrationRequest {
    /**
     * Continues the registration in Meta AI.
     *
     * @param activity foreground activity.
     */
    fun accept(activity: Activity)

    /** Declines the request. */
    fun decline()
}

/**
 * Activity-bound glasses setup flows that cannot live in the domain layer.
 *
 * All methods must be called from the main thread with a resumed activity.
 */
interface GlassesSetupLauncher {

    /** Contract that opens the Meta AI camera-permission screen and returns whether it was granted. */
    val cameraPermissionContract: ActivityResultContract<Unit, Boolean>

    /**
     * Starts the Meta AI registration flow.
     *
     * @param activity foreground activity that receives the callback deep link.
     */
    fun startRegistration(activity: Activity)

    /**
     * Opens the glasses firmware update screen in Meta AI.
     *
     * @param activity foreground activity.
     * @return `false` when it could not be opened.
     */
    fun openFirmwareUpdate(activity: Activity): Boolean

    /**
     * Opens the update screen for the DAT app that runs on the glasses.
     *
     * @param activity foreground activity.
     * @return `false` when it could not be opened.
     */
    fun openGlassesAppUpdate(activity: Activity): Boolean

    /**
     * Lets the SDK handle a deep link from Meta AI.
     *
     * @param intent incoming intent.
     * @param onRegistrationRequest invoked when Meta AI asks the user to connect this app.
     * @return `true` when the SDK consumed the intent.
     */
    fun handleIntent(intent: Intent, onRegistrationRequest: (PendingRegistrationRequest) -> Unit): Boolean
}

/** [GlassesSetupLauncher] using the documented DAT APIs. */
class DatGlassesSetupLauncher @Inject constructor(
    private val sdk: DatSdkInitializer,
    private val logger: AppLogger,
) : GlassesSetupLauncher {

    /** Wraps `Wearables.RequestPermissionContract` so callers receive a plain boolean. */
    private class CameraPermissionContract : ActivityResultContract<Unit, Boolean>() {
        private val delegate = Wearables.RequestPermissionContract()

        override fun createIntent(context: Context, input: Unit): Intent =
            delegate.createIntent(context, Permission.CAMERA)

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            delegate.parseResult(resultCode, intent).getOrNull() == PermissionStatus.Granted

        override fun getSynchronousResult(context: Context, input: Unit): SynchronousResult<Boolean>? =
            delegate.getSynchronousResult(context, Permission.CAMERA)?.let { result ->
                SynchronousResult(result.value.getOrNull() == PermissionStatus.Granted)
            }
    }

    override val cameraPermissionContract: ActivityResultContract<Unit, Boolean> = CameraPermissionContract()

    override fun startRegistration(activity: Activity) {
        if (sdk.initializeIfPermitted() != com.basira.domain.model.SdkState.READY) return
        Wearables.startRegistration(activity)
    }

    override fun openFirmwareUpdate(activity: Activity): Boolean =
        Wearables.openFirmwareUpdate(activity).fold(
            { true },
            { error, _ ->
                logger.warn(TAG, "openFirmwareUpdate: ${error.description}")
                false
            },
        )

    override fun openGlassesAppUpdate(activity: Activity): Boolean =
        Wearables.openDATGlassesAppUpdate(activity).fold(
            { true },
            { error, _ ->
                logger.warn(TAG, "openDATGlassesAppUpdate: ${error.description}")
                false
            },
        )

    override fun handleIntent(intent: Intent, onRegistrationRequest: (PendingRegistrationRequest) -> Unit): Boolean {
        if (sdk.state.value != com.basira.domain.model.SdkState.READY) return false
        return Wearables.handleIntent(intent) { request -> onRegistrationRequest(DatPendingRequest(request, logger)) }
            .fold(
                { handled -> handled },
                { error, _ ->
                    logger.warn(TAG, "handleIntent: ${error.description}")
                    false
                },
            )
    }

    private class DatPendingRequest(
        private val request: RegistrationRequest,
        private val logger: AppLogger,
    ) : PendingRegistrationRequest {
        override fun accept(activity: Activity) {
            request.continueRegistration(activity).onFailure { error, _ ->
                logger.warn(TAG, "continueRegistration: ${error.description}")
            }
        }

        override fun decline() {
            request.cancelRegistration().onFailure { error, _ ->
                logger.warn(TAG, "cancelRegistration: ${error.description}")
            }
        }
    }

    private companion object {
        const val TAG = "DatSetup"
    }
}

/**
 * [GlassesSetupLauncher] for the bundled-sample simulation: every flow succeeds immediately so the
 * complete app can be exercised without Meta AI.
 *
 * @property repository simulated repository updated by the flows.
 */
class FakeGlassesSetupLauncher(private val repository: FakeGlassesRepository) : GlassesSetupLauncher {

    override val cameraPermissionContract: ActivityResultContract<Unit, Boolean> =
        object : ActivityResultContract<Unit, Boolean>() {
            override fun createIntent(context: Context, input: Unit): Intent = Intent()
            override fun parseResult(resultCode: Int, intent: Intent?): Boolean = true
            override fun getSynchronousResult(context: Context, input: Unit): SynchronousResult<Boolean> =
                SynchronousResult(true)
        }

    override fun startRegistration(activity: Activity) = repository.simulateRegistration(true)

    override fun openFirmwareUpdate(activity: Activity): Boolean = true

    override fun openGlassesAppUpdate(activity: Activity): Boolean = true

    override fun handleIntent(intent: Intent, onRegistrationRequest: (PendingRegistrationRequest) -> Unit): Boolean = false
}
