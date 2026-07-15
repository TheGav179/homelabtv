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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Reminder(val channel: String, val title: String, val startMillis: Long)

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

    /**
     * Number-entry style. false (default): leading-zero — majors are two digits,
     * so 6.1 is typed "06" and 61.1 is "61". true: quick mode — a first digit
     * above 5 completes the major by itself (handy where no majors start with 6-9).
     */
    var numberEntryQuickMode by mutableStateOf(prefs.getBoolean("number_entry_quick", false))
        private set

    fun toggleNumberEntryMode() {
        numberEntryQuickMode = !numberEntryQuickMode
        prefs.edit().putBoolean("number_entry_quick", numberEntryQuickMode).apply()
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
