// LogRepository.kt
package com.zmal.sleepy

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object LogRepository {
    private val _logs = MutableSharedFlow<String>(replay = 100)
    val logs = _logs.asSharedFlow()

    fun addLog(log: String) {
        _logs.tryEmit(log)
    }
}