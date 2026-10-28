package com.b50.roast.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 曲库索引：把 assets 里的 music_data.json（1326 首）建成 id → 元数据 的查表。
 *
 * 对应 Python 的 fetch.build_lookup / _pick_song_info：
 * DX 谱（id ≥ 10000）在曲库里找不到时，退回同名的 SD 曲（id - 10000）。
 */
class MusicDb(private val res: Res) {

    private val lookup: Map<String, JSONObject> by lazy { load() }

    private fun load(): Map<String, JSONObject> {
        val f = res.data("music_data.json")
        if (!f.exists()) return emptyMap()
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return emptyMap()
        val m = HashMap<String, JSONObject>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) m[id] = o
        }
        return m
    }

    fun size(): Int = lookup.size

    fun ids(): Set<String> = lookup.keys

    /** 与 Python _pick_song_info 一致：找不到就 SD/DX 互换再找一次 */
    fun song(id: String): JSONObject? {
        if (id.isBlank()) return null
        lookup[id]?.let { return it }
        val num = id.toIntOrNull() ?: return null
        return if (num >= 10000) lookup[(num - 10000).toString()]
        else lookup[(num + 10000).toString()]
    }

    /** basic_info.from —— 谱面所属版本 */
    fun versionOf(id: String): String {
        val s = song(id) ?: return ""
        return s.optJSONObject("basic_info")?.optString("from").orEmpty()
            .ifBlank { s.optString("from") }
    }

    fun isNewVersion(id: String): Boolean {
        val s = song(id) ?: return false
        return s.optJSONObject("basic_info")?.optBoolean("is_new") ?: false
    }

    fun titleOf(id: String): String = song(id)?.optString("title").orEmpty()

    fun typeOf(id: String): String = song(id)?.optString("type").orEmpty()

    fun dsOf(id: String, levelIndex: Int): Double {
        val arr = song(id)?.optJSONArray("ds") ?: return 0.0
        if (levelIndex < 0 || levelIndex >= arr.length()) return 0.0
        return arr.optDouble(levelIndex, 0.0)
    }

    fun levelOf(id: String, levelIndex: Int): String {
        val arr = song(id)?.optJSONArray("level") ?: return ""
        if (levelIndex < 0 || levelIndex >= arr.length()) return ""
        return arr.optString(levelIndex)
    }
}
