package com.example.rufusdata.network

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UploadResult(
    val videoResponse: String,
    val heartRateResponse: String
)

class UploadRepository(
    private val baseUrl: String
) {

    suspend fun uploadSession(
        sessionId: String,
        participantId: String,
        videoFile: File,
        csvFile: File
    ): UploadResult = withContext(Dispatchers.IO) {

        require(sessionId.isNotBlank()) {
            "Identificador da sessão não informado."
        }

        require(participantId.isNotBlank()) {
            "Identificador do participante não informado."
        }

        require(videoFile.isFile) {
            "Arquivo de vídeo não encontrado."
        }

        require(csvFile.isFile) {
            "Arquivo CSV não encontrado."
        }

        val session = Uri.encode(sessionId.trim())
        val user = Uri.encode(participantId.trim())

        val videoResponse = uploadMultipart(
            endpoint = "$baseUrl/face/$session/$user",
            file = videoFile,
            mimeType = "video/mp4"
        )

        val heartRateResponse = uploadMultipart(
            endpoint = "$baseUrl/hr/$session/$user",
            file = csvFile,
            mimeType = "text/csv"
        )

        UploadResult(
            videoResponse = videoResponse,
            heartRateResponse = heartRateResponse
        )
    }

    private fun uploadMultipart(
        endpoint: String,
        file: File,
        mimeType: String
    ): String {

        val boundary = "----RufusData${System.nanoTime()}"

        val header = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
                "Content-Type: $mimeType\r\n" +
                "\r\n"
        ).toByteArray(Charsets.UTF_8)

        val footer = (
            "\r\n--$boundary--\r\n"
        ).toByteArray(Charsets.UTF_8)

        val connection = URL(endpoint).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000

            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val contentLength =
                header.size.toLong() + file.length() + footer.size.toLong()

            connection.setFixedLengthStreamingMode(contentLength)

            connection.outputStream.use { output ->
                output.write(header)
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.write(footer)
                output.flush()
            }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = responseStream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode: ${responseBody.take(1000)}")
            }

            return "HTTP $responseCode: $responseBody"
        } finally {
            connection.disconnect()
        }
    }
}