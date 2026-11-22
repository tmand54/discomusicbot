package app.snapstack.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScanSpeed(val label: String, val votes: Int, val description: String) {
    TURBO("Turbo", 1, "Commit on the first clean read. Fastest; best with good light."),
    BALANCED("Balanced", 2, "Two matching frames — or one when the read matches a card on your phone and its printed name. Recommended."),
    CAREFUL("Careful", 3, "Three matching frames (two for a verified read). For glare, sleeves or toploaders."),
}

enum class ScanMode(val label: String) { RAPID("Rapid"), SINGLE("Single"), BINDER("Binder") }

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = Pref(key, prefs.getBoolean(key, def)) { e, v -> e.putBoolean(key, v) }
    private fun string(key: String, def: String) = Pref(key, prefs.getString(key, def) ?: def) { e, v -> e.putString(key, v) }

    inner class Pref<T>(private val key: String, initial: T, private val writer: (SharedPreferences.Editor, T) -> Unit) {
        private val state = MutableStateFlow(initial)
        val flow: StateFlow<T> = state.asStateFlow()
        var value: T
            get() = state.value
            set(v) {
                state.value = v
                prefs.edit().also { writer(it, v) }.apply()
            }
    }

    val scanSpeed = string("scan_speed", ScanSpeed.BALANCED.name)
    val scanMode = string("scan_mode", ScanMode.RAPID.name)
    val scanGame = string("scan_game", "auto")
    val binderGrid = string("binder_grid", "3x3")
    val beep = bool("beep", true)
    val haptics = bool("haptics", true)
    val autoPrefetch = bool("auto_prefetch", true)
    val conditionAdjusted = bool("condition_adjusted", false)
    val backgroundRefresh = bool("background_refresh", true)
    val wifiOnly = bool("wifi_only", false)
    val showOcrStats = bool("show_ocr_stats", true)

    fun getLong(key: String): Long = prefs.getLong(key, 0L)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

    var lastPriceRefresh: Long
        get() = prefs.getLong("last_price_refresh", 0L)
        set(v) = prefs.edit().putLong("last_price_refresh", v).apply()

    fun speed(): ScanSpeed = runCatching { ScanSpeed.valueOf(scanSpeed.value) }.getOrDefault(ScanSpeed.BALANCED)
    fun mode(): ScanMode = runCatching { ScanMode.valueOf(scanMode.value) }.getOrDefault(ScanMode.RAPID)
}
