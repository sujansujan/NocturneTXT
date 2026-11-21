package com.plaintext.audible.data

import org.json.JSONArray
import org.json.JSONObject

object BackupManager {
    suspend fun exportJson(repo: ReleaseRepository): String {
        val root = JSONObject()
        val prefs = repo.getPrefs()

        root.put("version", 8)
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
    }
}
