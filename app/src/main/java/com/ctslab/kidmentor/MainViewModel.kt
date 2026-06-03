package com.ctslab.kidmentor
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    // Nguồn state duy nhất của toàn app
    val state = MutableLiveData<AppState>(AppState.IDLE)

    // Text trạng thái hiển thị cho user
    val statusText = MutableLiveData<String>("")

    fun onStartRecording() {
        state.value = AppState.RECORDING
    }

    fun onStopRecording() {
        state.value = AppState.UPLOADING
    }

    fun onStartPlaying() {
        state.value = AppState.PLAYING
    }

    fun onFinishPlaying() {
        state.value = AppState.IDLE
    }

    fun onCancelPlayback() {
        state.value = AppState.IDLE
    }

    fun onError(message: String) {
        state.value = AppState.ERROR
        statusText.value = message
    }
}