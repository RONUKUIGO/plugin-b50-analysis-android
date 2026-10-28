package com.b50.roast.data

import android.util.Log
import org.json.JSONObject

/**
 * 全量记录规整：records[] → B35 / B15 榜 + 自算 rating。
 *
 * 逐行对照 Python 版 fetch.py 的 _enrich_chart / music_version /
 * detect_current_versions / split_by_version / rating_total_of / normalize_records，
 * 保证两边算出来的榜和 rating 完全一致（这是对拍基准）。
 *
 * 两池规则（官方算法）：
 *   旧版本池 = 版本不在「当前版本」里的所有谱，按 ra 降序 → 取前 35 当 B35
 *   当前版本池 = 版本 ∈ 当前版本的谱，按 ra 降序 → 取前 15 当 B15
 *   rating = 两池各自的 ra 之和
 */
object RecordsNormalizer {

    private val DIFF_LABEL = listOf("Basic", "Advanced", "Expert", "Master", "Re:Master")

    /** 兜底当前版本（拿不到公开 B50 且曲库没有 is_new 时用） */
    private val FALLBACK_VERSIONS = listOf("maimai でらっくす PRiSM PLUS")

    data class Chart(
        val musicId: String,
        val title: String,
        val type: String,
        val levelIndex: Int,
        val levelLabel: String,
        val level: String,
        val ds: Double,
        val achievement: Double,
        val ra: Int,
        val rate: String,
        val fcLabel: String,
        val fs: String,
        val dxScore: Int,
        val playCount: Int,
        val version: String,
    )

    data class B50(
        val nickname: String,
        val username: String,
        val rating: Int,
        val ratingSelfCalc: Int,
        val plate: String,
        val recordsTotal: Int,
        val currentVersions: List<String>,
        val versionsSource: String,
        val b35: List<Chart>,
        val b15: List<Chart>,
        val allCharts: List<Chart>,
    )

    // ---------------------------------------------------------------- 版本检测

    /** 三级策略，与 Python detect_current_versions 一致：公开 B50 投票 → 曲库 is_new → 常量 */
    fun detectVersions(db: MusicDb, calibDxIds: List<String>): Pair<Set<String>, String> {
        val counts = HashMap<String, Int>()
        for (id in calibDxIds) {
            val v = db.versionOf(id)
            if (v.isNotBlank()) counts[v] = (counts[v] ?: 0) + 1
        }
        if (counts.isNotEmpty()) {
            val top = counts.values.max()
            // 票数不少于最高票一半的版本都算「当前版本」（改版过渡期会混着上一版）
            return counts.filter { it.value * 2 >= top }.keys to "b50"
        }
        val isNew = db.ids().mapNotNull { id -> if (db.isNewVersion(id)) db.versionOf(id) else null }
            .filter { it.isNotBlank() }
        if (isNew.isNotEmpty()) return isNew.toSet() to "is_new"
        return FALLBACK_VERSIONS.toSet() to "fallback"
    }

    // ---------------------------------------------------------------- 规整

    fun normalize(payload: JSONObject, db: MusicDb, calibDxIds: List<String>): B50 {
        val (versions, source) = detectVersions(db, calibDxIds)

        val arr = payload.optJSONArray("records")
        val charts = ArrayList<Chart>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val raw = arr.optJSONObject(i) ?: continue
                charts.add(enrich(raw, db))
            }
        }

        val current = ArrayList<Chart>()
        val legacy = ArrayList<Chart>()
        for (c in charts) {
            val v = c.version
            if (versions.contains(v)) current.add(c) else legacy.add(c)
        }
        // 同 ra 时按「定数高 → 达成率高」，与官方榜取舍一致
        val order = compareByDescending<Chart> { it.ra }
            .thenByDescending { it.ds }
            .thenByDescending { it.achievement }
        current.sortWith(order)
        legacy.sortWith(order)

        val b35 = legacy.take(35)
        val b15 = current.take(15)
        val selfCalc = b35.sumOf { it.ra } + b15.sumOf { it.ra }

        val apiRating = payload.optInt("rating", 0)
        val result = B50(
            nickname = payload.optString("nickname").ifBlank { payload.optString("username") },
            username = payload.optString("username"),
            rating = if (apiRating != 0) apiRating else selfCalc,
            ratingSelfCalc = selfCalc,
            plate = payload.optString("plate"),
            recordsTotal = charts.size,
            currentVersions = versions.sorted(),
            versionsSource = source,
            b35 = b35,
            b15 = b15,
            allCharts = charts.sortedWith(order),
        )

        Log.w(
            "B50Norm",
            "记录 ${result.recordsTotal} 条 | 旧池 ${legacy.size} / 新池 ${current.size} | " +
                "自算 ${result.ratingSelfCalc}（接口 ${apiRating}）| 版本 ${result.currentVersions}（来源 $source）"
        )
        return result
    }

    /** 与 Python _enrich_chart 一致：接口缺什么就用曲库补 */
    private fun enrich(raw: JSONObject, db: MusicDb): Chart {
        val mid = raw.optString("song_id")
            .ifBlank { raw.optString("music_id") }
            .ifBlank { raw.optString("id") }
        val info = db.song(mid)
        val li = raw.optInt("level_index", 0)

        val title = raw.optString("title").ifBlank {
            info?.optString("title").orEmpty().ifBlank { "Song#$mid" }
        }
        val type = raw.optString("type").ifBlank { info?.optString("type").orEmpty() }
        val ds = if (raw.has("ds")) raw.optDouble("ds", 0.0) else db.dsOf(mid, li)
        val level = raw.optString("level").ifBlank { db.levelOf(mid, li) }
        val label = raw.optString("level_label").ifBlank {
            if (li in DIFF_LABEL.indices) DIFF_LABEL[li] else ""
        }
        // 接口的达成率是百分数（100.1234），字段名历史上有 achievement / achievements 两种
        val ach = when {
            raw.has("achievements") -> raw.optDouble("achievements", 0.0)
            else -> raw.optDouble("achievement", 0.0)
        }
        val fc = raw.optString("fc").lowercase()
        val fcLabel = when (fc) {
            "fc" -> "FC"
            "fcp" -> "FC+"
            "ap" -> "AP"
            "app" -> "AP+"
            else -> ""
        }
        return Chart(
            musicId = mid,
            title = title,
            type = type,
            levelIndex = li,
            levelLabel = label,
            level = level,
            ds = ds,
            achievement = ach,
            ra = raw.optInt("ra", 0),
            rate = raw.optString("rate"),
            fcLabel = fcLabel,
            fs = raw.optString("fs"),
            dxScore = raw.optInt("dxScore", 0),
            playCount = raw.optInt("play_count", raw.optInt("pc", 0)),
            version = db.versionOf(mid),
        )
    }
}
