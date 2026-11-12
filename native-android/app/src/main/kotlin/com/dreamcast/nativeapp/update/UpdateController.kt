package com.dreamcast.nativeapp.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the update dialog shows; null hides it. */
sealed interface UpdateState {
    data object Checking : UpdateState

    /** A manual check found nothing newer. */
    data object UpToDate : UpdateState

    data class Available(val release: AppRelease) : UpdateState

    /** [progress] is 0..1, or negative when the size is unknown. */
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState

    /** Downloaded and verified; Android must allow this app to install packages first. */
    data class NeedsPermission(val release: AppRelease, val apk: File) : UpdateState

    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * Update flow behind the dialog: check, then download, verify and install only after the user
 * presses "Install". The user can postpone, skip the version or turn automatic checks off.
 */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller = UpdateInstaller(context),
) {
    var state by mutableStateOf<UpdateState?>(null)
        private set

    var autoCheck by mutableStateOf(checker.autoCheckEnabled())
        private set

    private var job: Job? = null

    /** Quiet check on start: shows the dialog only when there is a version to offer. */
    fun checkAutomatically() {
        if (job?.isActive == true || state != null) return
        job = scope.launch {
            installer.clean()
            checker.automatic()?.let { if (state == null) state = UpdateState.Available(it) }
        }
    }

    /** Check from settings: always reports the result. */
    fun checkManually() {
        job?.cancel()
        state = UpdateState.Checking
        job = scope.launch {
            state =
                try {
                    checker.manual()?.let(UpdateState::Available) ?: UpdateState.UpToDate
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    UpdateState.Failed(error.message ?: "Не удалось проверить обновления")
                }
        }
    }

    fun install(release: AppRelease) {
        job?.cancel()
        state = UpdateState.Downloading(release, 0f)
        job = scope.launch {
            try {
                var shown = -1
                val apk =
                    installer.download(release) { progress ->
                        // Limit recompositions to whole percents.
                        val percent = (progress * 100).toInt()
                        if (percent != shown) {
                            shown = percent
                            state = UpdateState.Downloading(release, progress)
                        }
                    }
                launchInstaller(release, apk)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                state = UpdateState.Failed(error.message ?: "Не удалось скачать обновление", release)
            }
        }
    }

    /** Opens the system screen where the user allows installing from this app. */
    fun openInstallPermission() {
        val intent = installer.installPermissionIntent() ?: return
        runCatching { context.startActivity(intent) }
    }

    /** Continues an install the user had to allow in system settings. */
    fun onResume() {
        val waiting = state as? UpdateState.NeedsPermission ?: return
        if (installer.canInstall()) launchInstaller(waiting.release, waiting.apk)
    }

    private fun launchInstaller(release: AppRelease, apk: File) {
        if (!installer.canInstall()) {
            state = UpdateState.NeedsPermission(release, apk)
            return
        }
        state =
            runCatching { context.startActivity(installer.installIntent(apk)) }
                .fold(
                    onSuccess = { null },
                    onFailure = { UpdateState.Failed("Не удалось открыть установщик", release) },
                )
    }

    /** "Later": hide the dialog; the next automatic check may offer it again. */
    fun dismiss() {
        job?.cancel()
        state = null
    }

    /** "Skip this version": automatic checks will not offer [release] again. */
    fun skip(release: AppRelease) {
        checker.skip(release.version)
        dismiss()
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        checker.setAutoCheck(enabled)
        autoCheck = enabled
    }
}
