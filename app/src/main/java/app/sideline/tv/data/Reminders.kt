package app.sideline.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class Reminder(val channelKey: String, val channelName: String, val title: String, val start: Long) {
    val id: String get() = "$channelKey@$start"
}

/**
 * "Remind me" for programmes in the TV guide. About a minute before the start, [due] fires and the app
 * shows a pop-up with "Watch now". Works while the app is open (on any screen, including while watching).
 */
class Reminders(context: Context) {
    private val sp = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Reminder.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Reminder>> = _list
    private val _due = MutableSharedFlow<Reminder>(extraBufferCapacity = 8)
    val due: SharedFlow<Reminder> = _due

    private fun load(): List<Reminder> = try {
        sp.getString("list", null)?.let { json.decodeFromString(ser, it) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun save(l: List<Reminder>) {
        _list.value = l
        sp.edit().putString("list", json.encodeToString(ser, l)).apply()
    }

    fun has(channelKey: String, start: Long) = _list.value.any { it.channelKey == channelKey && it.start == start }
    fun add(r: Reminder) = save((_list.value.filter { it.id != r.id } + r).sortedBy { it.start })
    fun remove(r: Reminder) = save(_list.value.filter { it.id != r.id })

    fun start() {
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis() / 1000
                val fire = _list.value.filter { it.start - 60 <= now }
                if (fire.isNotEmpty()) {
                    // Drop ones that were missed by more than 15 minutes (e.g. the app wasn't open).
                    fire.filter { now - it.start < 15 * 60 }.forEach { _due.tryEmit(it) }
                    save(_list.value - fire.toSet())
                }
                delay(15_000)
            }
        }
    }
}
