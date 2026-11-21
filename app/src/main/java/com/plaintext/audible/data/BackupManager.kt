package com.plaintext.audible.data

import org.json.JSONArray
import org.json.JSONObject

object BackupManager {
    suspend fun exportJson(repo: ReleaseRepository): String {
        val root = JSONObject()
        val prefs = repo.getPrefs()

        root.put("version", 9)
        root.put("prefs", JSONObject().apply {
            put("englishOnly", prefs.englishOnly)
            put("hideAbridged", prefs.hideAbridged)
            put("hideDramatized", prefs.hideDramatized)
            put("alwaysShowCardActions", prefs.alwaysShowCardActions)
            put("checkAbbDaily", prefs.checkAbbDaily)
            put("hideCoverArt", prefs.hideCoverArt)
            put("themeModeCode", prefs.themeModeCode)
            put("notifyHour", prefs.notifyHour)
            put("notifyMinute", prefs.notifyMinute)
            put("marketplaceCode", prefs.marketplaceCode)
        })

        val trackedArr = JSONArray()
        for (t in repo.dao.getAllTrackedItems()) {
            trackedArr.put(JSONObject().apply {
                put("type", t.type.name)
                put("queryValue", t.queryValue)
                put("externalId", t.externalId ?: "")
                put("lastReadSequence", t.lastReadSequence)
                put("thumbnailUrl", t.thumbnailUrl)
            })
        }
        root.put("trackedItems", trackedArr)

        val dndArr = JSONArray()
        for (d in repo.dao.getAllDndRules()) {
            dndArr.put(JSONObject().apply {
                put("targetKey", d.targetKey)
                put("targetType", d.targetType.name)
                put("displayTitle", d.displayTitle)
            })
        }
        root.put("dndRules", dndArr)

        val readArr = JSONArray()
        for (r in repo.dao.getAllReadBooks()) {
            readArr.put(JSONObject().apply {
                put("asin", r.asin)
                put("groupKey", r.groupKey)
                put("title", r.title)
                put("authors", r.authors)
                put("narrators", r.narrators)
                put("seriesTitle", r.seriesTitle)
                put("seriesAsin", r.seriesAsin)
                if (r.seriesSequence != null) put("seriesSequence", r.seriesSequence)
                put("runtimeMinutes", r.runtimeMinutes)
                put("coverUrl", r.coverUrl)
                put("releaseDateIso", r.releaseDateIso)
                put("readAtEpochMs", r.readAtEpochMs)
            })
        }
        root.put("readBooks", readArr)

        val pinnedArr = JSONArray()
        for (pin in repo.dao.getAllPinnedReleases()) {
            pinnedArr.put(JSONObject().apply {
                put("asin", pin.asin)
                put("groupKey", pin.groupKey)
                put("title", pin.title)
                put("createdAt", pin.createdAt)
            })
        }
        root.put("pinnedReleases", pinnedArr)

        return root.toString(2)
    }

    suspend fun importJson(repo: ReleaseRepository, rawJson: String) {
        val root = JSONObject(rawJson)
        root.optJSONObject("prefs")?.let { p ->
            val themeCode = p.optString("themeModeCode", "").ifBlank {
                if (p.has("isDarkTheme")) {
                    if (p.optBoolean("isDarkTheme", true)) "DARK" else "LIGHT"
                } else {
                    "SYSTEM"
                }
            }
            repo.savePrefs(
                FilterPrefs(
                    englishOnly = p.optBoolean("englishOnly", true),
                    hideAbridged = p.optBoolean("hideAbridged", true),
                    hideDramatized = p.optBoolean("hideDramatized", false),
                    alwaysShowCardActions = p.optBoolean("alwaysShowCardActions", false),
                    checkAbbDaily = p.optBoolean("checkAbbDaily", true),
                    hideCoverArt = p.optBoolean("hideCoverArt", false),
                    themeModeCode = themeCode,
                    notifyHour = p.optInt("notifyHour", 9).coerceIn(0, 23),
                    notifyMinute = p.optInt("notifyMinute", 0).coerceIn(0, 59),
                    marketplaceCode = p.optString("marketplaceCode", "US")
                )
            )
        }

        root.optJSONArray("trackedItems")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = runCatching { TrackType.valueOf(o.optString("type")) }.getOrNull() ?: continue
                val query = o.optString("queryValue", "").trim()
                if (query.isBlank()) continue
                repo.addTrackedItemUnique(
                    type = type,
                    rawQuery = query,
                    lastReadSequence = o.optInt("lastReadSequence", 0).coerceAtLeast(0),
                    initialThumbnailUrl = o.optString("thumbnailUrl", ""),
                    externalId = o.optString("externalId", "").ifBlank { null }
                )
            }
        }

        root.optJSONArray("dndRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val targetType = runCatching { DndTargetType.valueOf(o.optString("targetType")) }.getOrNull() ?: continue
                val targetKey = o.optString("targetKey", "").trim()
                if (targetKey.isBlank()) continue
                repo.dao.insertDndRule(
                    DndRuleEntity(
                        targetKey = targetKey,
                        targetType = targetType,
                        displayTitle = o.optString("displayTitle", targetKey)
                    )
                )
            }
        }

        root.optJSONArray("readBooks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val asin = o.optString("asin", "").trim()
                val title = o.optString("title", "").trim()
                if (asin.isBlank() || title.isBlank()) continue
                val authors = o.optString("authors", "")
                val groupKey = o.optString("groupKey", "").ifBlank {
                    ReleaseRepository.computeGroupKey(title, authors)
                }
                val seq = if (o.has("seriesSequence") && !o.isNull("seriesSequence")) {
                    o.optInt("seriesSequence")
                } else null
                repo.dao.upsertReadBook(
                    ReadBookEntity(
                        asin = asin,
                        groupKey = groupKey,
                        title = title,
                        authors = authors,
                        narrators = o.optString("narrators", ""),
                        seriesTitle = o.optString("seriesTitle", ""),
                        seriesAsin = o.optString("seriesAsin", ""),
                        seriesSequence = seq,
                        runtimeMinutes = o.optInt("runtimeMinutes", 0).coerceAtLeast(0),
                        coverUrl = o.optString("coverUrl", ""),
                        releaseDateIso = o.optString("releaseDateIso", ""),
                        readAtEpochMs = o.optLong("readAtEpochMs", System.currentTimeMillis())
                    )
                )
            }
        }

        root.optJSONArray("pinnedReleases")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val asin = o.optString("asin", "").trim()
                val title = o.optString("title", "").trim()
                if (asin.isBlank() || title.isBlank()) continue
                repo.dao.upsertPinnedRelease(
                    PinnedReleaseEntity(
                        asin = asin,
                        groupKey = o.optString("groupKey", ""),
                        title = title,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
    }
}
