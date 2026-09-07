package com.itantra.utils

import android.content.Context
import com.itantra.models.AppSettings
import com.itantra.models.InputMode
import com.itantra.models.Languages
import com.itantra.models.TransportType

/** SharedPreferences-backed settings + onboarding flags (Schema §10). */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("itantra", Context.MODE_PRIVATE)

    var firstLaunch: Boolean
        get() = prefs.getBoolean("first_launch", true)
        set(v) = prefs.edit().putBoolean("first_launch", v).apply()

    fun load(): AppSettings = AppSettings(
        sourceLanguage = prefs.getString("src", Languages.DEFAULT_SOURCE)!!,
        targetLanguage = prefs.getString("tgt", Languages.DEFAULT_TARGET)!!,
        inputMode = InputMode.valueOf(
            prefs.getString("input_mode", InputMode.PUSH_TO_TALK.name)!!
        ),
        transportType = TransportType.valueOf(
            prefs.getString("transport", TransportType.LOOPBACK.name)!!
        ),
        vadThreshold = prefs.getFloat("vad_thr", 0.5f),
        emergencyMaxVolume = prefs.getBoolean("emg_vol", true),
        emergencyVibrationEnabled = prefs.getBoolean("emg_vib", true)
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("src", s.sourceLanguage)
            .putString("tgt", s.targetLanguage)
            .putString("input_mode", s.inputMode.name)
            .putString("transport", s.transportType.name)
            .putFloat("vad_thr", s.vadThreshold)
            .putBoolean("emg_vol", s.emergencyMaxVolume)
            .putBoolean("emg_vib", s.emergencyVibrationEnabled)
            .apply()
    }
}
