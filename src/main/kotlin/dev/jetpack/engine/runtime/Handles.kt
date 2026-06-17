package dev.jetpack.engine.runtime

interface IntervalHandle {
    fun destroy(): Boolean
    fun activate(): Boolean
    fun deactivate(): Boolean
    fun trigger(): Boolean
    fun isActive(): Boolean
}

interface ScheduleHandle {
    fun destroy(): Boolean
    fun activate(): Boolean
    fun deactivate(): Boolean
    fun trigger(): Boolean
    fun isActive(): Boolean
    fun cron(): String
    fun nextRun(): JetValue
}

interface ListenerHandle {
    fun activate(): Boolean
    fun deactivate(): Boolean
    fun destroy(): Boolean
    fun trigger(sender: JetValue): Boolean
    fun isActive(): Boolean
}

interface CommandHandle {
    val hasSender: Boolean
    fun activate(): Boolean
    fun deactivate(): Boolean
    fun destroy(): Boolean
    fun trigger(sender: JetValue, args: List<JetValue>): Boolean
    fun triggerPath(path: List<String>, sender: JetValue, args: List<JetValue>): Boolean
    fun isActive(): Boolean
}
