
package com.example.rufusdata

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.lifecycleScope

import com.example.rufusdata.camera.CameraRecorderService
import com.example.rufusdata.health.HealthConnectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import androidx.activity.result.contract.ActivityResultContracts

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText

import java.io.File

import com.example.rufusdata.data.CsvExporter
import com.example.rufusdata.health.HeartRateSample
import com.example.rufusdata.network.UploadRepository

class MainActivity : ComponentActivity() {
    companion object {
        private const val API_BASE_URL = "http://10.0.2.2:5127"
    }
    
    // Cores do RufusData
    private val green = Color.rgb(145, 179, 66)
    private val bgColor = Color.rgb(244, 244, 244)
    private val textColor = Color.rgb(35, 35, 35)
    private val secondaryColor = Color.rgb(105, 105, 105)

    // Componentes da interface
    private lateinit var healthStatusText: TextView
    private lateinit var healthResultText: TextView

    private lateinit var cameraStatusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // Identificação do participante
    private lateinit var participantIdInput: EditText

    // Upload
    private lateinit var uploadButton: Button
    private lateinit var uploadStatusText: TextView
    
    private data class HeartRateQueryWindow(
        val start: Instant,
        val end: Instant,
        val isVideoSession: Boolean
    )

    
    // Últimas amostras obtidas do Health Connect
    private var lastHeartRateSamples: List<HeartRateSample> =
        emptyList()

    // Indica se os dados consultados correspondem ao vídeo
    private var lastQueryWasSession = false

    // Evita reenviar arquivos após um upload completo
    private var uploadCompleted = false
    // Health Connect
    private var healthClient: HealthConnectClient? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val cameraGranted =
                permissions[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) {

                startRecording()

            } else {

                updateCameraUI(
                    "Permissão da câmera não concedida.",
                    false
                )
            }
    }
    
    private val heartRatePermissions = setOf(
        HealthPermission.getReadPermission(
            HeartRateRecord::class
        )
    )
    
    private val uploadRepository by lazy {
        UploadRepository(API_BASE_URL)
    }

    // Resultado da solicitação de permissão do Health Connect
    private val healthPermissionLauncher =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { grantedPermissions ->

            if (grantedPermissions.containsAll(heartRatePermissions)) {

                healthStatusText.text =
                    "Acesso à frequência cardíaca autorizado."

                readRecentHeartRate()

            } else {

                healthStatusText.text =
                    "Permissão de frequência cardíaca não concedida."
            }
        } 

    // Recebe atualizações do serviço de câmera
    private val statusReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {

            val message = intent?.getStringExtra(
                CameraRecorderService.EXTRA_STATUS
            ) ?: return

            val isRecording = intent.getBooleanExtra(
                CameraRecorderService.EXTRA_IS_RECORDING,
                false
            )

            updateCameraUI(message, isRecording)
        }
    }
    
    private fun getHeartRateQueryWindow(): HeartRateQueryWindow {

        val preferences = getSharedPreferences(
            CameraRecorderService.PREFS_NAME,
            MODE_PRIVATE
        )

        val videoPath = preferences.getString(
            CameraRecorderService.KEY_LAST_VIDEO_PATH,
            null
        )

        val startedAt = preferences.getLong(
            CameraRecorderService.KEY_RECORDING_STARTED_AT,
            -1L
        )

        val endedAt = preferences.getLong(
            CameraRecorderService.KEY_RECORDING_ENDED_AT,
            -1L
        )

        val videoExists =
            !videoPath.isNullOrBlank() &&
            File(videoPath).isFile

        val validSession =
            videoExists &&
            startedAt > 0 &&
            endedAt > startedAt

        if (validSession) {

            return HeartRateQueryWindow(
                start = Instant.ofEpochMilli(startedAt),
                end = Instant.ofEpochMilli(endedAt),
                isVideoSession = true
            )
        }

        // Sem sessão finalizada: consulta as últimas 24 horas.
        val now = Instant.now()

        return HeartRateQueryWindow(
            start = now.minus(Duration.ofHours(24)),
            end = now,
            isVideoSession = false
        )
    }

    private fun refreshUploadAvailability() {

        if (!::uploadButton.isInitialized) return

        val preferences = getSharedPreferences(
            CameraRecorderService.PREFS_NAME,
            MODE_PRIVATE
        )

        val videoPath = preferences.getString(
            CameraRecorderService.KEY_LAST_VIDEO_PATH,
            null
        )

        val videoExists =
            !videoPath.isNullOrBlank() &&
            File(videoPath).isFile

        val hasHeartRate =
            lastHeartRateSamples.isNotEmpty()

        val canUpload =
            videoExists &&
            hasHeartRate &&
            lastQueryWasSession &&
            !uploadCompleted

        uploadButton.isEnabled = canUpload

        uploadButton.alpha = if (canUpload) {
            1f
        } else {
            0.5f
        }

        if (!canUpload && !uploadCompleted) {

            uploadStatusText.text =
                "Aguardando vídeo finalizado e dados de frequência cardíaca da sessão."
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        createScreen()
        refreshCameraStatus()
        
        refreshUploadAvailability()
    }

    // ----------------------------------------------------
    // INTERFACE
    // ----------------------------------------------------

    private fun createScreen() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
            setBackgroundColor(bgColor)
        }
        
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                dp(22),
                dp(28) + systemBars.top,
                dp(22),
                dp(32) + systemBars.bottom
            )

            insets
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            addView(root)
        }

        // Cabeçalho
        val title = createText(
            "RufusData",
            30f,
            green,
            true
        )

        val subtitle = createText(
            "Coleta de dados para sessões",
            16f,
            textColor
        )

        root.addView(title)
        root.addView(subtitle)

        // ------------------------------------------------
        // CARTÃO: FREQUÊNCIA CARDÍACA
        // ------------------------------------------------

        val healthCard = createCard()

        addText(
            healthCard,
            "Frequência cardíaca",
            20f,
            textColor,
            true
        )

        addText(
            healthCard,
            "Conecte o Health Connect para acessar os dados do Google Fit.",
            14f,
            secondaryColor
        )

        healthStatusText = createText(
            "Health Connect ainda não conectado.",
            14f,
            secondaryColor
        )

        healthCard.addView(healthStatusText)

        healthResultText = createText(
            "Nenhuma consulta realizada.",
            14f,
            textColor
        )

        healthCard.addView(healthResultText)

        addButton(
            healthCard,
            "Conectar Health Connect",
            true
        ) {
            connectHealthConnect()
        }

        addButton(
            healthCard,
            "Consultar últimos dados",
            false
        ) {
            connectHealthConnect()
        }

        addCardToRoot(root, healthCard)

        // ------------------------------------------------
        // CARTÃO: CAPTURA DE VÍDEO
        // ------------------------------------------------

        val cameraCard = createCard()

        addText(
            cameraCard,
            "Captura de vídeo",
            20f,
            textColor,
            true
        )

        addText(
            cameraCard,
            "Grave o rosto do participante durante a sessão.",
            14f,
            secondaryColor
        )

        cameraStatusText = createText(
            "Pronto para iniciar.",
            14f,
            secondaryColor
        )

        cameraCard.addView(cameraStatusText)

        startButton = addButton(
            cameraCard,
            "Iniciar gravação",
            true
        ) {
            requestCameraPermission()
        }

        stopButton = addButton(
            cameraCard,
            "Finalizar gravação",
            false
        ) {
            stopRecording()
        }

        stopButton.isEnabled = false

        addCardToRoot(root, cameraCard)

        // ------------------------------------------------
        // CARTÃO: ENVIO DOS DADOS
        // ------------------------------------------------

        val uploadCard = createCard()

        addText(
            uploadCard,
            "Envio dos dados",
            20f,
            textColor,
            true
        )

        addText(
            uploadCard,
            "Informe o identificador do participante para enviar os arquivos ao servidor.",
            14f,
            secondaryColor
        )

        // Campo de identificação
        participantIdInput = EditText(this).apply {

            hint = "ID do participante"

            textSize = 15f

            inputType = InputType.TYPE_CLASS_TEXT

            isSingleLine = true

            setTextColor(textColor)
            setHintTextColor(secondaryColor)

            setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8)
            )

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), green)
                cornerRadius = dp(12).toFloat()
            }
        }

        uploadCard.addView(
            participantIdInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(8)
            }
        )

        // Status do upload
        uploadStatusText = createText(
            "Finalize uma gravação e consulte a frequência cardíaca para habilitar o envio.",
            14f,
            secondaryColor
        )

        uploadCard.addView(uploadStatusText)

        // Botão de upload
        uploadButton = addButton(
            uploadCard,
            "Enviar MP4 e CSV",
            true
        ) {
            uploadSessionFiles()
        }

        uploadButton.isEnabled = false
        uploadButton.alpha = 0.5f

        addCardToRoot(root, uploadCard)
        
        setContentView(scroll)

        WindowCompat.getInsetsController(
            window,
            window.decorView
        ).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        ViewCompat.requestApplyInsets(root) 
        
    }

    // ----------------------------------------------------
    // COMPONENTES VISUAIS
    // ----------------------------------------------------

    private fun createCard(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(20),
                dp(18),
                dp(20),
                dp(20)
            )

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }

            elevation = dp(3).toFloat()
        }
    }

    private fun createText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {

        return TextView(this).apply {

            text = value
            textSize = size
            setTextColor(color)

            if (bold) {
                typeface = Typeface.DEFAULT_BOLD
            }

            setPadding(
                0,
                dp(6),
                0,
                dp(6)
            )
        }
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) {

        parent.addView(
            createText(value, size, color, bold)
        )
    }

    private fun addButton(
        parent: LinearLayout,
        title: String,
        primary: Boolean,
        action: () -> Unit
    ): Button {

        val button = Button(this).apply {

            text = title
            textSize = 15f
            isAllCaps = false

            setTextColor(
                if (primary) Color.WHITE else green
            )

            background = GradientDrawable().apply {

                setColor(
                    if (primary) green else Color.WHITE
                )

                cornerRadius = dp(28).toFloat()

                if (!primary) {
                    setStroke(dp(1), green)
                }
            }

            setOnClickListener {
                action()
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply {
            topMargin = dp(10)
        }

        parent.addView(button, params)

        return button
    }

    private fun addCardToRoot(
        root: LinearLayout,
        card: LinearLayout
    ) {

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        params.topMargin = dp(22)

        root.addView(card, params)
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    // ----------------------------------------------------
    // HEALTH CONNECT
    // ----------------------------------------------------

    private fun connectHealthConnect() {

        healthStatusText.text =
            "Verificando disponibilidade do Health Connect..."

        val status = HealthConnectClient.getSdkStatus(this)

        when (status) {

            HealthConnectClient.SDK_UNAVAILABLE -> {

                healthStatusText.text =
                    "Health Connect não está disponível neste dispositivo. Verifique a versão do Android e o Google Play Services."
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {

                healthStatusText.text =
                    "Instale ou atualize o Health Connect."

                openHealthConnectStore()
            }

            HealthConnectClient.SDK_AVAILABLE -> {

                try {

                    healthClient =
                        HealthConnectClient.getOrCreate(this)

                    checkHealthPermissions()

                } catch (e: Exception) {

                    healthStatusText.text =
                        "Erro ao inicializar Health Connect: ${e.message}"
                }
            }
        }
    }

    private fun openHealthConnectStore() {

        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "market://details?id=com.google.android.apps.healthdata"
            )
        ).apply {
            setPackage("com.android.vending")
        }

        try {

            startActivity(intent)

        } catch (e: Exception) {

            healthStatusText.text =
                "Não foi possível abrir a Play Store."
        }
    }

    // Confere se o usuário já autorizou a leitura.
    private fun checkHealthPermissions() {

        val client = healthClient ?: return

        lifecycleScope.launch {

            try {

                val granted =
                    client.permissionController.getGrantedPermissions()

                if (
                    granted.containsAll(
                        heartRatePermissions
                    )
                ) {

                    healthStatusText.text =
                        "Permissão concedida. Consultando dados..."

                    readRecentHeartRate()

                } else {

                    healthPermissionLauncher.launch(
                        heartRatePermissions
                    )
                }

            } catch (e: Exception) {

                healthStatusText.text =
                    "Erro ao verificar permissões: ${e.message}"
            }
        }
    }
    
    private fun uploadSessionFiles() {

        val participantId =
            participantIdInput.text.toString().trim()

        if (participantId.isBlank()) {

            AlertDialog.Builder(this)
                .setTitle("Identificação necessária")
                .setMessage(
                    "Informe o identificador do participante antes de enviar os arquivos."
                )
                .setPositiveButton("OK", null)
                .show()

            return
        }

        if (lastHeartRateSamples.isEmpty()) {

            showNoHeartRateDataMessage(
                lastQueryWasSession
            )

            return
        }

        if (!lastQueryWasSession) {

            AlertDialog.Builder(this)
                .setTitle("Consulte novamente")
                .setMessage(
                    "Os dados atuais correspondem às últimas 24 horas, e não ao período do vídeo. Finalize uma gravação e consulte novamente a frequência cardíaca."
                )
                .setPositiveButton("OK", null)
                .show()

            return
        }

        val preferences = getSharedPreferences(
            CameraRecorderService.PREFS_NAME,
            MODE_PRIVATE
        )

        val videoPath = preferences.getString(
            CameraRecorderService.KEY_LAST_VIDEO_PATH,
            null
        )

        if (videoPath.isNullOrBlank()) {

            uploadStatusText.text =
                "Arquivo de vídeo não encontrado."

            return
        }

        val videoFile = File(videoPath)

        if (!videoFile.isFile || videoFile.length() == 0L) {

            uploadStatusText.text =
                "Arquivo de vídeo inválido ou vazio."

            return
        }

        // Preserva as amostras que serão exportadas.
        val samplesToUpload = lastHeartRateSamples.toList()

        uploadButton.isEnabled = false

        uploadStatusText.text =
            "Gerando arquivo CSV..."

        lifecycleScope.launch {

            try {

                // 1. Gerar CSV.
                val csvFile = withContext(Dispatchers.IO) {

                    CsvExporter.export(
                        this@MainActivity,
                        samplesToUpload
                    )
                }

                uploadStatusText.text =
                    "Enviando vídeo e frequência cardíaca..."

                // 2. Enviar os dois arquivos.
                val result = uploadRepository.uploadSession(
                    participantId = participantId,
                    videoFile = videoFile,
                    csvFile = csvFile
                )

                uploadCompleted = true

                uploadStatusText.text =
                    "Arquivos enviados com sucesso!"

                // 3. Confirmar o resultado.
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Upload concluído!")
                    .setMessage(
                        """
                        Os arquivos foram enviados ao servidor.

                        Participante: $participantId

                        Vídeo: enviado
                        Frequência cardíaca: enviada

                        ${result.videoResponse}
                        ${result.heartRateResponse}
                        """.trimIndent()
                    )
                    .setPositiveButton("OK", null)
                    .show()

            } catch (e: Exception) {

                uploadStatusText.text =
                    "Falha no envio: ${e.message}"

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Erro no upload")
                    .setMessage(e.message ?: "Não foi possível enviar os arquivos.")
                    .setPositiveButton("OK", null)
                    .show()

            } finally {

                refreshUploadAvailability()
            }
        }
    }


    private fun showNoHeartRateDataMessage(
        isVideoSession: Boolean
    ) {

        val period = if (isVideoSession) {
            "durante o período da gravação"
        } else {
            "nas últimas 24 horas"
        }

        val message = """
            Não foram encontrados dados de frequência cardíaca $period.

            Verifique:

            • Se o dispositivo ou wearable registrou os batimentos.
            • Se o Google Fit está sincronizado com o Health Connect.
            • Se a sincronização dos dados já foi concluída.
            • Se a permissão de leitura está concedida.

            Caso os dados ainda não estejam disponíveis, aguarde a sincronização e tente novamente.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Frequência cardíaca indisponível")
            .setMessage(message)
            .setPositiveButton("Consultar novamente") { _, _ ->

                readRecentHeartRate()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun readRecentHeartRate() {

        val client = healthClient ?: return

        val window = getHeartRateQueryWindow()

        healthStatusText.text =
            if (window.isVideoSession) {
                "Consultando dados da sessão..."
            } else {
                "Consultando dados das últimas 24 horas..."
            }

        healthResultText.text = ""

        // Descarta resultados anteriores para evitar misturar sessões.
        lastHeartRateSamples = emptyList()
        lastQueryWasSession = false

        refreshUploadAvailability()

        lifecycleScope.launch {

            try {

                // Verifica novamente a autorização.
                val granted =
                    client.permissionController.getGrantedPermissions()

                if (!granted.containsAll(heartRatePermissions)) {

                    healthStatusText.text =
                        "A permissão de frequência cardíaca não está disponível."

                    healthPermissionLauncher.launch(
                        heartRatePermissions
                    )

                    return@launch
                }

                // Consulta o intervalo apropriado.
                val samples = withContext(Dispatchers.IO) {

                    HealthConnectRepository(client).readHeartRate(
                        window.start,
                        window.end
                    )
                }

                lastHeartRateSamples = samples
                lastQueryWasSession = window.isVideoSession

                // Nenhuma amostra encontrada.
                if (samples.isEmpty()) {

                    healthStatusText.text =
                        "Nenhuma amostra encontrada."

                    healthResultText.text =
                        "Não foram encontrados registros de frequência cardíaca."

                    refreshUploadAvailability()

                    showNoHeartRateDataMessage(
                        window.isVideoSession
                    )

                    return@launch
                }

                val latest = samples.last()

                val sources = samples
                    .map { formatSource(it.sourcePackage) }
                    .distinct()

                val formatter = DateTimeFormatter
                    .ofPattern("dd/MM/yyyy HH:mm:ss")
                    .withZone(ZoneId.systemDefault())

                val recentSamples = samples
                    .takeLast(5)
                    .asReversed()
                    .joinToString("\n") { sample ->

                        "${formatter.format(sample.time)} - " +
                        "${sample.beatsPerMinute} BPM - " +
                        formatSource(sample.sourcePackage)
                    }

                healthStatusText.text =
                    "Consulta realizada com sucesso!"

                healthResultText.text =
                    """
                    Amostras encontradas: ${samples.size}

                    Última medição:
                    ${latest.beatsPerMinute} BPM

                    Horário:
                    ${formatter.format(latest.time)}

                    Intervalo consultado:
                    ${formatter.format(window.start)}
                    até
                    ${formatter.format(window.end)}

                    Fontes dos dados:
                    ${sources.joinToString("\n")}

                    Últimas medições:
                    $recentSamples
                    """.trimIndent()

                refreshUploadAvailability()

                // Consulta de 24h serve apenas para diagnóstico.
                // Para upload, precisamos dos dados da sessão.
                if (!window.isVideoSession) {

                    uploadStatusText.text =
                        "Dados recentes encontrados. Finalize uma gravação e consulte novamente para preparar o envio da sessão."
                }

            } catch (e: SecurityException) {

                lastHeartRateSamples = emptyList()
                lastQueryWasSession = false

                healthStatusText.text =
                    "Sem autorização para acessar os dados."

                refreshUploadAvailability()

            } catch (e: Exception) {

                lastHeartRateSamples = emptyList()
                lastQueryWasSession = false

                healthStatusText.text =
                    "Erro ao consultar frequência cardíaca: ${e.message}"

                refreshUploadAvailability()
            }
        }
    }


    // Traduz o pacote de origem quando conhecido.
    private fun formatSource(packageName: String): String {

        return if (
            packageName == "com.google.android.apps.fitness"
        ) {
            "Google Fit ($packageName)"
        } else {
            packageName
        }
    }

    // ----------------------------------------------------
    // CÂMERA
    // ----------------------------------------------------

    private fun requestCameraPermission() { 
        val permissions = mutableListOf(
            Manifest.permission.CAMERA
        )

        if (
            Build.VERSION.SDK_INT >= 33 &&
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

            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {

            cameraPermissionLauncher.launch(
                missingPermissions.toTypedArray()
            )

        } else {

            startRecording()
        }
    } 
    
    private fun startRecording() {
        lastHeartRateSamples = emptyList()

        lastQueryWasSession = false

        uploadCompleted = false

        uploadStatusText.text =
            "Nova sessão iniciada. Consulte a frequência cardíaca após finalizar a gravação."

        healthResultText.text =
            "Aguardando consulta da nova sessão."

        refreshUploadAvailability()        

        //Criação do intent
        val intent = Intent(
            this,
            CameraRecorderService::class.java
        ).apply {

            action = CameraRecorderService.ACTION_START
        }

        ContextCompat.startForegroundService(
            this,
            intent
        )

        updateCameraUI(
            "Preparando a câmera...",
            true
        )
    }

    private fun stopRecording() {

        val intent = Intent(
            this,
            CameraRecorderService::class.java
        ).apply {

            action = CameraRecorderService.ACTION_STOP
        }

        startService(intent)

        updateCameraUI(
            "Finalizando gravação...",
            true
        )
    }

    private fun updateCameraUI(
        message: String,
        isRecording: Boolean
    ) {

        cameraStatusText.text = message

        startButton.isEnabled = !isRecording
        stopButton.isEnabled = isRecording

        startButton.alpha = if (isRecording) 0.5f else 1f
        stopButton.alpha = if (isRecording) 1f else 0.5f
    }

    private fun refreshCameraStatus() {

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

        updateCameraUI(message, isRecording)
    }

    // ----------------------------------------------------
    // BROADCAST DO SERVIÇO DE CÂMERA
    // ----------------------------------------------------

    override fun onStart() {

        super.onStart()

        val filter = IntentFilter(
            CameraRecorderService.ACTION_STATUS
        )

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

        refreshCameraStatus()
    }
}
