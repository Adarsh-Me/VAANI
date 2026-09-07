package com.itantra.utils

import com.itantra.data.MetricsDao
import com.itantra.data.MetricsEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** Stage-latency tracker feeding the PRD §1.3 latency budget + metrics table. */
class PerformanceMonitor(private val metrics: MetricsDao? = null) {

    private val lastReport = MutableStateFlow<Map<String, Long>>(emptyMap())
    val report: StateFlow<Map<String, Long>> = lastReport

    private val stageMs = ConcurrentHashMap<String, Long>()

    suspend fun <T> timed(stage: String, block: suspend () -> T): T {
        val t0 = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val ms = System.currentTimeMillis() - t0
            stageMs[stage] = ms
            lastReport.value = HashMap(stageMs)
            runCatching {
                metrics?.insert(MetricsEntity(metricType = "${stage}_latency", value = ms.toFloat()))
            }
        }
    }

    fun totalLatency(): Long = stageMs.values.sum()

    /** PRD budget: p50 < 2000 ms single hop (target 850 ms, max 2150 ms). */
    fun withinBudget(): Boolean = totalLatency() <= 2000

    fun reset() {
        stageMs.clear()
        lastReport.value = emptyMap()
    }
}
