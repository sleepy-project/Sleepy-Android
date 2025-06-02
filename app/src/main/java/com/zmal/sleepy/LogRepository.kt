// LogRepository.kt
package com.zmal.sleepy

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object LogRepository {
    private const val MAX_LOG_ENTRIES = 100
    private val _logs = MutableSharedFlow<String>(replay = MAX_LOG_ENTRIES)
    val logs = _logs.asSharedFlow()

    fun addLog(log: String) {
        _logs.tryEmit(log)
    }
}