package com.ctslab.ptalk_signature

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData

class MainViewModel(app: Application) : AndroidViewModel(app) {

    /** Resolve a string in the app's current (user-selected) locale. */
    private fun s(resId: Int): String = getApplication<Application>().getString(resId)

    // Nguồn state duy nhất của toàn app
    val state = MutableLiveData<AppState>(AppState.IDLE)

    // Text trạng thái hiển thị cho user
    val statusText = MutableLiveData<String>(s(ServerConfig.activeMode.statusIdleTextRes))

    fun onStartRecording() {
        state.value = AppState.RECORDING
        statusText.value = s(R.string.status_listening)
    }

    fun onStopRecording() {
        state.value = AppState.UPLOADING
        statusText.value = s(R.string.status_processing)
    }

    fun onStartPlaying() {
        state.value = AppState.PLAYING
        statusText.value = s(R.string.status_replying)
    }

    fun onFinishPlaying() {
        state.value = AppState.IDLE
        statusText.value = s(ServerConfig.activeMode.statusIdleTextRes)
    }

    fun onCancelPlayback() {
        state.value = AppState.IDLE
        statusText.value = s(ServerConfig.activeMode.statusIdleTextRes)
    }

    fun onError(message: String? = null) {
        state.value = AppState.ERROR
        statusText.value = message ?: s(R.string.error_generic)
    }
}
