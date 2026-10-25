package com.spark.assistant.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * User mapping: keyword/phrase -> an app already installed on the device,
 * optionally with a query template for media search.
 *
 * Example: keyword "tune", package "com.example.music",
 * queryTemplate "play {query}" — "play jazz on tune" opens the mapped app
 * with a play search for "jazz".
 */
data class ActionMapping(
    val keyword: String,
    val packageName: String,
    val label: String = "",
    val queryTemplate: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("keyword", keyword)
        .put("package", packageName)
        .put("label", label)
        .put("template", queryTemplate ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): ActionMapping = ActionMapping(
            keyword = o.optString("keyword", ""),
            packageName = o.optString("package", ""),
            label = o.optString("label", ""),
            queryTemplate = o.optString("template", "").ifEmpty { null },
        )
    }
}

private val Context.actionDataStore by preferencesDataStore(name = "spark_actions")

object ActionMappingStore {
    private val KEY = stringPreferencesKey("mappings_json")

    fun observe(context: Context): Flow<List<ActionMapping>> =
        context.actionDataStore.data.map { prefs ->
            decode(prefs[KEY])
        }

    suspend fun current(context: Context): List<ActionMapping> =
        observe(context).first()

    suspend fun add(context: Context, mapping: ActionMapping) {
        val cleaned = mapping.copy(keyword = mapping.keyword.trim().lowercase())
        if (cleaned.keyword.isEmpty() || cleaned.packageName.isEmpty()) return
        context.actionDataStore.edit { prefs ->
            val list = decode(prefs[KEY]).filterNot {
                it.keyword.equals(cleaned.keyword, ignoreCase = true)
            } + cleaned
            prefs[KEY] = encode(list)
        }
    }

    suspend fun remove(context: Context, keyword: String) {
        context.actionDataStore.edit { prefs ->
            prefs[KEY] = encode(decode(prefs[KEY]).filterNot {
                it.keyword.equals(keyword.trim(), ignoreCase = true)
            })
        }
    }

    private fun encode(list: List<ActionMapping>): String =
        JSONArray(list.map { it.toJson() }).toString()

    private fun decode(raw: String?): List<ActionMapping> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> ActionMapping.fromJson(arr.getJSONObject(i)) }
                .filter { it.keyword.isNotBlank() && it.packageName.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
