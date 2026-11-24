
package com.example.rufusdata.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter

import java.time.Instant

data class HeartRateSample(
    val time: Instant,
    val beatsPerMinute: Long,
    val sourcePackage: String
)

class HealthConnectRepository(
    private val client: HealthConnectClient
) {

    suspend fun readHeartRate(
        startTime: Instant,
        endTime: Instant
    ): List<HeartRateSample> {

        val samples = mutableListOf<HeartRateSample>()

        var pageToken: String? = null

        do {

            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        startTime,
                        endTime
                    ),
                    pageToken = pageToken
                )
            )

            response.records.forEach { record ->

                record.samples.forEach { sample ->

                    samples.add(
                        HeartRateSample(
                            time = sample.time,
                            beatsPerMinute = sample.beatsPerMinute,
                            sourcePackage = record.metadata.dataOrigin.packageName
                        )
                    )
                }
            }

            pageToken = response.pageToken

        } while (!pageToken.isNullOrEmpty())

        return samples.sortedBy { it.time }
    }
}
