package com.example.rufusdata

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.rufusdata.camera.CameraRecorderService

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    // Recebe atualizações enviadas pelo CameraRecorderService.
    private val statusReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            val message = intent?.getStringExtra(
                CameraRecorderService.EXTRA_STATUS
            ) ?: return

            val isRecording = intent.getBooleanExtra(
                CameraRecorderService.EXTRA_IS_RECORDING,
                false
            )

            updateUI(message, isRecording)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createScreen()
        refreshStatus()
    }

    // Cria a interface sem precisar de arquivos XML.
    private fun createScreen() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "Captura de sessão"
            textSize = 24f
        }

        statusText = TextView(this).apply {
            text = "Pronto para iniciar."
            textSize = 16f
        }

        startButton = Button(this).apply {
            text = "Iniciar gravação"

            setOnClickListener {
                requestCameraPermission()
            }
        }

        stopButton = Button(this).apply {
            text = "Finalizar gravação"
            isEnabled = false

            setOnClickListener {
                stopRecording()
            }
        }

        layout.addView(title)
        layout.addView(statusText)

        layout.addView(
            startButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            stopButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(layout)
    }

    // Solicita as permissões necessárias.
    private fun requestCameraPermission() {

        val permissions = mutableListOf(
            Manifest.permission.CAMERA
        )

        // A permissão de notificações é opcional para iniciar
        // o serviço, mas é desejável para mostrar sua notificação.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )

        } else {
            startRecording()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_PERMISSIONS) {

            val cameraGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) {
                startRecording()
            } else {
                updateUI(
                    "Permissão da câmera não concedida.",
                    false
                )
            }
        }
    }

    // Inicia o Foreground Service.
    private fun startRecording() {

        val intent = Intent(
            this,
            CameraRecorderService::class.java
        ).apply {
            action = CameraRecorderService.ACTION_START
        }

        ContextCompat.startForegroundService(this, intent)

        updateUI("Preparando a câmera...", true)
    }

    // Solicita que o serviço finalize a gravação.
    private fun stopRecording() {

        val intent = Intent(
            this,
            CameraRecorderService::class.java
        ).apply {
            action = CameraRecorderService.ACTION_STOP
        }

        startService(intent)

        updateUI("Finalizando gravação...", true)
    }

    private fun updateUI(
        message: String,
        isRecording: Boolean
    ) {
        statusText.text = message

        startButton.isEnabled = !isRecording
        stopButton.isEnabled = isRecording
    }

    // Recupera o estado caso o usuário volte ao aplicativo.
    private fun refreshStatus() {

        val preferences = getSharedPreferences(
            CameraRecorderService.PREFS_NAME,
            MODE_PRIVATE
        )

        val message = preferences.getString(
            CameraRecorderService.KEY_LAST_STATUS,
            "Pronto para iniciar."
        ) ?: "Pronto para iniciar."

        val isRecording = preferences.getBoolean(
            CameraRecorderService.KEY_IS_RECORDING,
            false
        )

        updateUI(message, isRecording)
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(CameraRecorderService.ACTION_STATUS)

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}