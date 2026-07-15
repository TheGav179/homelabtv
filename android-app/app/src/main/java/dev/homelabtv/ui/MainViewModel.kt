package dev.homelabtv.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.homelabtv.data.ChannelGuide
import dev.homelabtv.data.ChannelRepository
import dev.homelabtv.data.GuideRepository
import dev.homelabtv.data.GuideState
import dev.homelabtv.data.PhysicalChannel
import dev.homelabtv.data.channelMajor
import dev.homelabtv.data.channelMinor
import dev.homelabtv.data.normalizeChannelNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    fun zap(delta: Int) {
        val size = physicalChannels.size
        if (size == 0) return
        currentChannelIndex = ((currentChannelIndex + delta) % size + size) % size
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
        currentChannelIndex = match.index
        return true
    }

    private fun majorOf(channel: PhysicalChannel): Int = channelMajor(channel.displayNumber)

    private fun minorOf(channel: PhysicalChannel): Int = channelMinor(channel.displayNumber)

    /** Tune to the physical channel a guide row is mapped to. False if no tuner match. */
    fun tuneTo(guide: ChannelGuide): Boolean {
        val target = normalizeChannelNumber(guide.physical_channel)
        val index = physicalChannels.indexOfFirst { normalizeChannelNumber(it.displayNumber) == target }
        if (index >= 0) {
            currentChannelIndex = index
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_SERVER_URL = ""
        private const val REFRESH_INTERVAL_MS = 15L * 60 * 1000
    }
}
