package com.buivan.ptalk_child
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    // Nguồn state duy nhất của toàn app
    val state = MutableLiveData<AppState>(AppState.IDLE)

    // Text trạng thái hiển thị cho user
    val statusText = MutableLiveData<String>(ServerConfig.activeMode.statusIdleText)

    fun onStartRecording() {
        state.value = AppState.RECORDING
        statusText.value = "Đang nghe..."
    }

    fun onStopRecording() {
        state.value = AppState.UPLOADING
        statusText.value = "Đang xử lý..."
    }

    fun onStartPlaying() {
        state.value = AppState.PLAYING
        statusText.value = "Đang trả lời..."
    }

    fun onFinishPlaying() {
        state.value = AppState.IDLE
        statusText.value = ServerConfig.activeMode.statusIdleText
    }

    fun onCancelPlayback() {
        state.value = AppState.IDLE
        statusText.value = ServerConfig.activeMode.statusIdleText
    }

    fun onError(message: String = "Có lỗi xảy ra, thử lại nhé!") {
        state.value = AppState.ERROR
        statusText.value = message
    }
}