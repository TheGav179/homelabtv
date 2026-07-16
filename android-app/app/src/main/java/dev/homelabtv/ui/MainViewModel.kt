package dev.homelabtv.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.homelabtv.data.ChannelGuide
import dev.homelabtv.data.ChannelRepository
import dev.homelabtv.data.GuideRepository
import dev.homelabtv.data.GuideState
import dev.homelabtv.data.PhysicalChannel
import dev.homelabtv.data.Program
import dev.homelabtv.data.XmltvTime
import dev.homelabtv.data.channelMajor
import dev.homelabtv.data.channelMinor
import dev.homelabtv.data.normalizeChannelNumber
import dev.homelabtv.data.numberPrefixComplete
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Reminder(val channel: String, val title: String, val startMillis: Long)

enum class NumberEntryMode {
    /** Learn from the scanned lineup: complete a prefix as soon as it's unambiguous. */
    AUTO,
    /** Majors are always two digits: 6.1 is typed "06". */
    LEADING_ZERO,
    /** A first digit above the configurable threshold completes the major by itself. */
    QUICK,
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("homelab_prefs", Context.MODE_PRIVATE)
    private val guideRepository = GuideRepository(app)
    private val channelRepository = ChannelRepository(app)

    val guideState: StateFlow<GuideState> = guideRepository.state

    var physicalChannels by mutableStateOf(listOf<PhysicalChannel>())
        private set

    var currentChannelIndex by mutableIntStateOf(0)
        private set

    var serverUrl by mutableStateOf(prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
        private set

    var numberEntryMode by mutableStateOf(loadNumberEntryMode())
        private set

    var quickThreshold by mutableIntStateOf(prefs.getInt("quick_threshold", 5).coerceIn(1, 8))
        private set

    fun cycleNumberEntryMode() {
        numberEntryMode =
            when (numberEntryMode) {
                NumberEntryMode.AUTO -> NumberEntryMode.LEADING_ZERO
                NumberEntryMode.LEADING_ZERO -> NumberEntryMode.QUICK
                NumberEntryMode.QUICK -> NumberEntryMode.AUTO
            }
        prefs.edit().putString("number_entry_mode", numberEntryMode.name).apply()
    }

    fun cycleQuickThreshold() {
        quickThreshold = if (quickThreshold >= 8) 1 else quickThreshold + 1
        prefs.edit().putInt("quick_threshold", quickThreshold).apply()
    }

    private fun loadNumberEntryMode(): NumberEntryMode {
        prefs.getString("number_entry_mode", null)?.let { stored ->
            try {
                return NumberEntryMode.valueOf(stored)
            } catch (_: IllegalArgumentException) {}
        }
        // Migrate the old boolean setting; new installs default to AUTO
        return if (prefs.getBoolean("number_entry_quick", false)) NumberEntryMode.QUICK
        else NumberEntryMode.AUTO
    }

    private fun lineupMajors(): Set<String> =
        physicalChannels
            .map { normalizeChannelNumber(it.displayNumber).substringBefore('.') }
            .map { it.trimStart('0').ifEmpty { "0" } }
            .toSet()

    private fun lineupMinors(major: Int): Set<String> =
        physicalChannels
            .filter { channelMajor(it.displayNumber) == major }
            .map { normalizeChannelNumber(it.displayNumber).substringAfter('.', "") }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Should the typed major prefix auto-complete (jump to the decimal part)? */
    fun isMajorEntryComplete(prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        return when (numberEntryMode) {
            NumberEntryMode.LEADING_ZERO -> prefix.length >= 2
            NumberEntryMode.QUICK ->
                prefix.length >= 2 || prefix.first() - '0' > quickThreshold
            NumberEntryMode.AUTO -> {
                val majors = lineupMajors()
                when {
                    majors.isEmpty() -> prefix.length >= 2 // no lineup to learn from
                    prefix == "0" -> false // explicit leading-zero entry still works
                    prefix.startsWith("0") -> true // "06" -> major 6
                    else -> numberPrefixComplete(prefix, majors) || prefix.length >= 3
                }
            }
        }
    }

    /** Should a typed "major.minor" entry commit immediately (no 2s wait)? */
    fun isMinorEntryComplete(entry: String): Boolean {
        val minorPart = entry.substringAfter('.', "")
        if (minorPart.length >= 2) return true
        if (minorPart.isEmpty() || numberEntryMode != NumberEntryMode.AUTO) return false
        val major = entry.substringBefore('.').toIntOrNull() ?: return false
        val minors = lineupMinors(major)
        return minors.isNotEmpty() && numberPrefixComplete(minorPart, minors)
    }

    val fallbackInputId: String? by lazy { channelRepository.defaultTunerInputId() }

    val currentChannel: PhysicalChannel?
        get() = physicalChannels.getOrNull(currentChannelIndex)

    init {
        physicalChannels = channelRepository.getPhysicalChannels()
        viewModelScope.launch {
            guideRepository.loadCache()
            while (true) {
                guideRepository.refresh(serverUrl)
                delay(REFRESH_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            // Delay first: Main.immediate would otherwise run checkReminders()
            // synchronously inside the constructor, before the reminder
            // properties below are initialized
            while (true) {
                delay(15_000)
                checkReminders()
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { guideRepository.refresh(serverUrl) }
    }

    /** Re-query TvContract, e.g. after READ_TV_LISTINGS is granted or a channel rescan. */
    fun reloadChannels() {
        physicalChannels = channelRepository.getPhysicalChannels()
        currentChannelIndex = currentChannelIndex.coerceIn(0, (physicalChannels.size - 1).coerceAtLeast(0))
    }

    fun updateServerUrl(url: String) {
        serverUrl = url
        prefs.edit().putString("server_url", url).apply()
    }

    // ----- channel selection (tracks the previous channel for jump-back) -----

    private var previousChannelIndex = -1

    private fun selectChannel(index: Int) {
        if (index == currentChannelIndex || index !in physicalChannels.indices) return
        previousChannelIndex = currentChannelIndex
        currentChannelIndex = index
    }

    /** Swap back to the channel watched before this one ("last channel" key). */
    fun jumpBack(): Boolean {
        if (previousChannelIndex !in physicalChannels.indices) return false
        selectChannel(previousChannelIndex)
        return true
    }

    fun zap(delta: Int) {
        val size = physicalChannels.size
        if (size == 0) return
        selectChannel(((currentChannelIndex + delta) % size + size) % size)
    }

    /**
     * Tune directly to a typed channel number like "7.1" or "20.3". A major-only
     * entry ("13", "7") goes to that station's .1 subchannel, falling back to the
     * lowest subchannel that exists. False if no match.
     */
    fun tuneToNumber(entry: String): Boolean {
        val cleaned = entry.trim().trimEnd('.')
        if (cleaned.isEmpty()) return false
        val major = cleaned.substringBefore('.').toIntOrNull() ?: return false
        val minor = cleaned.substringAfter('.', "").toIntOrNull()

        val majorMatches = physicalChannels.withIndex().filter { majorOf(it.value) == major }
        val match =
            if (minor != null) {
                majorMatches.firstOrNull { minorOf(it.value) == minor }
            } else {
                majorMatches.firstOrNull { minorOf(it.value) == 1 }
                    ?: majorMatches.minByOrNull { minorOf(it.value) }
            }
        if (match == null) return false
        selectChannel(match.index)
        return true
    }

    private fun majorOf(channel: PhysicalChannel): Int = channelMajor(channel.displayNumber)

    private fun minorOf(channel: PhysicalChannel): Int = channelMinor(channel.displayNumber)

    /** Tune to the physical channel a guide row is mapped to. False if no tuner match. */
    fun tuneTo(guide: ChannelGuide): Boolean {
        val target = normalizeChannelNumber(guide.physical_channel)
        val index = physicalChannels.indexOfFirst { normalizeChannelNumber(it.displayNumber) == target }
        if (index >= 0) {
            selectChannel(index)
            return true
        }
        return false
    }

    // ----- program reminders -----

    private val gson = Gson()

    var reminders by mutableStateOf(loadReminders())
        private set

    /** The reminder whose show is starting right now, if any — drives the banner. */
    var dueReminder by mutableStateOf<Reminder?>(null)
        private set

    private fun loadReminders(): List<Reminder> =
        try {
            val json = prefs.getString("reminders", null)
            if (json == null) emptyList()
            else gson.fromJson(json, object : TypeToken<List<Reminder>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }

    private fun saveReminders() {
        prefs.edit().putString("reminders", gson.toJson(reminders)).apply()
    }

    /** @return true if a reminder was added, false if an existing one was removed. */
    fun toggleReminder(channel: ChannelGuide, program: Program): Boolean {
        val start = XmltvTime.parse(program.start) ?: return false
        val number = normalizeChannelNumber(channel.physical_channel)
        val existing = reminders.firstOrNull { it.channel == number && it.startMillis == start }
        reminders =
            if (existing != null) reminders - existing
            else reminders + Reminder(number, program.title ?: "Program", start)
        saveReminders()
        return existing == null
    }

    private fun checkReminders() {
        val now = System.currentTimeMillis()
        // Drop reminders whose show is long underway
        val stale = reminders.filter { it.startMillis + STALE_REMINDER_MS < now }
        if (stale.isNotEmpty()) {
            reminders = reminders - stale.toSet()
            saveReminders()
        }
        if (dueReminder == null) {
            val due = reminders.firstOrNull { it.startMillis - 30_000 <= now }
            if (due != null) {
                dueReminder = due
                reminders = reminders - due
                saveReminders()
            }
        }
    }

    fun watchReminder() {
        dueReminder?.let { tuneToNumber(it.channel) }
        dueReminder = null
    }

    fun dismissReminder() {
        dueReminder = null
    }

    companion object {
        const val DEFAULT_SERVER_URL = ""
        private const val REFRESH_INTERVAL_MS = 15L * 60 * 1000
        private const val STALE_REMINDER_MS = 5L * 60 * 1000
    }
}
