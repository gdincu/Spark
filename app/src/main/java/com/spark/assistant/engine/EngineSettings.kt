package com.spark.assistant.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.engineDataStore by preferencesDataStore(name = "spark_engine")

/**
 * Speech-engine preferences. Whistle transcribes 7 languages; locking to one
 * (default English) skips auto-detect and constrains the decode, which is
 * both faster and more accurate than leaving it on detect.
 */
object EngineSettings {

    /** Empty = auto-detect (pass NULL to the engine). */
    const val AUTO = ""

    /** Whistle language codes + display names. */
    val LANGUAGES = listOf(
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "pl" to "Polski",
        AUTO to "Auto-detect",
    )

    private val LANGUAGE = stringPreferencesKey("speech_language")

    fun observeLanguage(context: Context): Flow<String> =
        context.engineDataStore.data.map { it[LANGUAGE] ?: "en" }

    suspend fun currentLanguage(context: Context): String =
        observeLanguage(context).first()

    suspend fun setLanguage(context: Context, code: String) {
        context.engineDataStore.edit { it[LANGUAGE] = code }
    }
}
