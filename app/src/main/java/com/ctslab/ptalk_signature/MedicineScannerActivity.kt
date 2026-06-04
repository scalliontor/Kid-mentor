package com.ctslab.ptalk_signature

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Màn hình quét thuốc cho ELDER_CARE (port từ MedicineScanner.cs của bản Unity, bỏ giao diện Unity).
 * Luồng: CameraX preview -> chụp -> POST /holobox/medician -> hiện kết quả + đọc to bằng TTS.
 */
class MedicineScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var captureButton: Button

    private var imageCapture: ImageCapture? = null

    private val holoboxApi by lazy { HoloboxApi() }
    private val audioPlayer by lazy { AudioPlayer() }

    private var appMode: AppMode = AppMode.ELDER_CARE
    private var isAnalyzing = false

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                statusText.text = "Cần cấp quyền camera để quét thuốc"
                Toast.makeText(this, "Vui lòng cấp quyền camera để tiếp tục.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medicine_scanner)

        intent.getStringExtra(ModeSelectActivity.EXTRA_APP_MODE)?.let {
            appMode = runCatching { AppMode.valueOf(it) }.getOrDefault(AppMode.ELDER_CARE)
        }

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.tvScannerStatus)
        resultText = findViewById(R.id.tvScannerResult)
        progress = findViewById(R.id.scannerProgress)
        captureButton = findViewById(R.id.btnCapture)

        applyTheme()

        findViewById<ImageView>(R.id.btnCloseScanner).setOnClickListener { finish() }
        captureButton.setOnClickListener { capturePhoto() }
        captureButton.isEnabled = false   // chỉ bật khi camera đã bind xong

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun applyTheme() {
        if (appMode == AppMode.ELDER_CARE) {
            val orange = Color.parseColor("#E67E22")
            captureButton.setBackgroundColor(orange)
            statusText.setTextColor(Color.parseColor("#BF6516"))
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                statusText.text = "Hướng camera vào thuốc rồi bấm chụp"
                captureButton.isEnabled = true
            } catch (e: Exception) {
                Log.e(TAG, "startCamera lỗi: ${e.message}")
                statusText.text = "Không mở được camera"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        if (isAnalyzing) return

        val photoFile = File(cacheDir, "medicine_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        setBusy(true)
        statusText.text = "Đang chụp..."

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    analyze(photoFile)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Chụp lỗi: ${exc.message}")
                    statusText.text = "Chụp ảnh thất bại, thử lại"
                    setBusy(false)
                }
            }
        )
    }

    private fun analyze(photoFile: File) {
        statusText.text = "Đang phân tích thuốc…"
        lifecycleScope.launch {
            try {
                val text = holoboxApi.scanMedicine(photoFile)
                if (text.isBlank()) {
                    statusText.text = "Không nhận diện được, thử chụp rõ hơn"
                } else {
                    resultText.text = text
                    statusText.text = "Kết quả phân tích:"
                    speak(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Phân tích lỗi", e)
                statusText.text = when (e) {
                    is java.net.UnknownHostException -> "Không có Internet (không tìm thấy máy chủ), kiểm tra mạng rồi thử lại"
                    is java.net.SocketTimeoutException -> "Mạng chậm, thử lại nhé"
                    else -> e.message ?: "Lỗi phân tích, thử lại nhé"
                }
            } finally {
                setBusy(false)
                photoFile.delete()
            }
        }
    }

    private fun speak(text: String) {
        lifecycleScope.launch {
            try {
                val wav = holoboxApi.synthesizeToFile(text, cacheDir)
                audioPlayer.play(
                    wav,
                    onComplete = { wav.delete() },
                    onError = { wav.delete(); Log.w(TAG, "TTS play: $it") }
                )
            } catch (e: Exception) {
                Log.w(TAG, "TTS lỗi (bỏ qua, vẫn hiện text): ${e.message}")
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        isAnalyzing = busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        captureButton.isEnabled = !busy
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.stop()
    }

    private companion object {
        private const val TAG = "MedicineScanner"
    }
}
