
package com.example.rufusdata.data

import android.content.Context
import com.example.rufusdata.health.HeartRateSample

import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CsvExporter {

    fun export(
        context: Context,
        samples: List<HeartRateSample>
    ): File {

        require(samples.isNotEmpty()) {
            "Não existem amostras de frequência cardíaca."
        }

        // Armazenamento privado do aplicativo.
        val directory = File(
            context.filesDir,
            "sessions"
        )

        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException(
                "Não foi possível criar a pasta de sessões."
            )
        }

        val outputFile = File(
            directory,
            "heartrate_${System.currentTimeMillis()}.csv"
        )

        val zone = ZoneId.systemDefault()

        val dateFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val timeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss")

        outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->

            // Cabeçalho esperado pelo backend.
            writer.appendLine("date,time,heartRate")

            // Organiza as amostras cronologicamente.
            samples.sortedBy { it.time }.forEach { sample ->

                val localTime = sample.time.atZone(zone)

                val date = dateFormatter.format(localTime)
                val time = timeFormatter.format(localTime)

                writer.append(date)
                writer.append(",")
                writer.append(time)
                writer.append(",")
                writer.append(sample.beatsPerMinute.toString())
                writer.append("\n")
            }
        }

        return outputFile
    }
}
