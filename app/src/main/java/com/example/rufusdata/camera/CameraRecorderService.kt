package com.example.rufusdata.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent

import com.example.rufusdata.MainActivity

import android.app.Service

import java.io.File

class CameraRecorderService : LifecycleService() {

    companion object {
        const val KEY_RECORDING_STARTED_AT =
            "recording_started_at"

        const val KEY_RECORDING_ENDED_AT =
            "recording_ended_at"
            
        const val ACTION_START =
            "com.example.seuapp.ACTION_START"

        const val ACTION_STOP =
            "com.example.seuapp.ACTION_STOP"

        const val ACTION_STATUS =
            "com.example.seuapp.ACTION_STATUS"

        const val EXTRA_STATUS = "status"
        const val EXTRA_IS_RECORDING = "isRecording"

        const val PREFS_NAME = "capture_preferences"

        const val KEY_LAST_STATUS = "last_status"
        const val KEY_IS_RECORDING = "is_recording"
        const val KEY_LAST_VIDEO_PATH = "last_video_path"

        private const val CHANNEL_ID = "camera_recording"
        private const val NOTIFICATION_ID = 1

        private const val TAG = "CameraRecorderService"
    }

    private var cameraProvider: ProcessCameraProvider? = null

    private var videoCapture: VideoCapture<Recorder>? = null

    private var recording: Recording? = null

    private var outputFile: File? = null

    private var isStarting = false

    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {

            ACTION_START -> {

                if (!isStarting) {
                    getSharedPreferences(
                        PREFS_NAME,
                        MODE_PRIVATE
                    ).edit()
                        .remove(KEY_LAST_VIDEO_PATH)
                        .remove(KEY_RECORDING_STARTED_AT)
                        .remove(KEY_RECORDING_ENDED_AT)
                        .apply()
    
                    isStarting = true
                    stopRequested = false

                    startForegroundServiceNotification()

                    publishStatus(
                        "Preparando a câmera...",
                        true
                    )

                    initializeCamera()
                }
            }

            ACTION_STOP -> {

                if (isStarting) {

                    stopRequested = true

                    publishStatus(
                        "Finalizando gravação...",
                        true
                    )

                    if (recording != null) {
                        recording?.stop()
                    } else {
                        finishWithoutRecording(
                            "Gravação interrompida."
                        )
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    // Inicializa a câmera frontal.
    private fun initializeCamera() {

        val cameraFuture =
            ProcessCameraProvider.getInstance(this)

        cameraFuture.addListener({

            if (stopRequested) {
                return@addListener
            }

            try {

                cameraProvider = cameraFuture.get()

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(
                                Quality.SD
                            )
                        )
                    )
                    .build()

                val capture = VideoCapture.withOutput(recorder)

                videoCapture = capture

                cameraProvider?.unbindAll()

                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    capture
                )

                startVideoRecording(capture)

            } catch (e: Exception) {

                failRecording(
                    "Erro ao inicializar a câmera: ${e.message}",
                    e
                )
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // Começa a gravar sem habilitar áudio.
    private fun startVideoRecording(
        capture: VideoCapture<Recorder>
    ) {

        val videoDirectory = File(
            filesDir,
            "videos"
        )

        if (!videoDirectory.exists() &&
            !videoDirectory.mkdirs()
        ) {
            throw IllegalStateException(
                "Não foi possível criar a pasta de vídeos."
            )
        }

        val file = File(
            videoDirectory,
            "face_${System.currentTimeMillis()}.mp4"
        )

        outputFile = file

        val outputOptions = FileOutputOptions.Builder(file)
            .build()

        recording = capture.output
            .prepareRecording(this, outputOptions)
            // Não chamamos withAudioEnabled().
            // Portanto, o vídeo será gravado sem áudio.
            .start(
                ContextCompat.getMainExecutor(this)
            ) { event ->

                when (event) {

                    is VideoRecordEvent.Start -> {
                        val startedAt = System.currentTimeMillis()

                        getSharedPreferences(
                            PREFS_NAME,
                            MODE_PRIVATE
                        ).edit()
                            .putLong(
                                KEY_RECORDING_STARTED_AT,
                                startedAt
                            )
                            .apply()

                        publishStatus(
                            "Gravando vídeo...",
                            true
                        )
                    }

                    is VideoRecordEvent.Finalize -> {

                        if (event.hasError()) {

                            file.delete()

                            publishStatus(
                                "Erro durante a gravação: ${event.error}",
                                false
                            )

                        } else {

                            val endedAt = System.currentTimeMillis()

                            getSharedPreferences(
                                PREFS_NAME,
                                MODE_PRIVATE
                            ).edit()
                                .putString(
                                    KEY_LAST_VIDEO_PATH,
                                    file.absolutePath
                                )
                                .putLong(
                                    KEY_RECORDING_ENDED_AT,
                                    endedAt
                                )
                                .apply()

                            publishStatus(
                                "Gravação concluída: ${file.name}",
                                false
                            )
                        }

                        recording = null
                        isStarting = false

                        cameraProvider?.unbindAll()

                        stopForeground(Service.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }

                    else -> Unit
                }
            }
    }

    // Cria a notificação necessária para o Foreground Service.
    private fun startForegroundServiceNotification() {

        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun createNotification(): Notification {

        val stopIntent = Intent(
            this,
            CameraRecorderService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val pendingIntentFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }

        val stopPendingIntent = PendingIntent.getService(
            this,
            100,
            stopIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.presence_video_online
            )
            .setContentTitle("Captura em andamento")
            .setContentText("A câmera frontal está gravando.")
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Finalizar",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gravação de vídeo",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    // Informa à Activity o estado atual.
    private fun publishStatus(
        message: String,
        isRecording: Boolean
    ) {

        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).edit()
            .putString(KEY_LAST_STATUS, message)
            .putBoolean(KEY_IS_RECORDING, isRecording)
            .apply()

        val intent = Intent(ACTION_STATUS).apply {

            setPackage(packageName)

            putExtra(EXTRA_STATUS, message)

            putExtra(
                EXTRA_IS_RECORDING,
                isRecording
            )
        }

        sendBroadcast(intent)
    }

    private fun finishWithoutRecording(message: String) {

        cameraProvider?.unbindAll()

        isStarting = false

        publishStatus(message, false)

        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failRecording(
        message: String,
        exception: Exception
    ) {

        Log.e(TAG, message, exception)

        cameraProvider?.unbindAll()

        isStarting = false

        publishStatus(message, false)

        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {

        recording?.stop()

        cameraProvider?.unbindAll()

        super.onDestroy()
    }
}