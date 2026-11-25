package cn.heyread.app.data.local

import cn.heyread.app.data.model.Story
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private val localJson = Json {
  encodeDefaults = false
  explicitNulls = true
  ignoreUnknownKeys = true
}

internal fun encodeHistoryStory(story: Story): String =
  localJson.encodeToString(story.toHistoryDto())

internal fun decodeHistoryStory(raw: String): Story? =
  runCatching {
      val obj = localJson.parseToJsonElement(raw) as? JsonObject ?: return null
      fun required(key: String): String = requiredLegacyString(obj[key]) ?: error("invalid")
      val normalized = buildJsonObject {
        put("id", JsonPrimitive(required("id")))
        put("source", JsonPrimitive(required("source")))
        put("title", JsonPrimitive(required("title")))
        listOf("excerpt", "body", "author", "date", "image", "url", "detailNotice", "channel")
          .forEach { key ->
            val value = compatibleString(obj[key])
            if (value != null) put(key, JsonPrimitive(value))
          }
        listOf("appId", "publishedAt").forEach { key ->
          val token = obj[key] as? JsonPrimitive ?: return@forEach
          val integral: Long? =
            if (token.isString) {
              val exact =
                if (key == "appId") token.content.toIntOrNull()?.toLong()
                else token.content.toLongOrNull()
              exact
                ?: token.content
                  .toDoubleOrNull()
                  ?.takeIf { it.isFinite() }
                  ?.let { if (key == "appId") it.toInt().toLong() else it.toLong() }
            } else {
              val exact =
                if (key == "appId") token.content.toLongOrNull()?.toInt()?.toLong()
                else token.content.toLongOrNull()
              exact
                ?: token.doubleOrNull
                  ?.takeIf { it.isFinite() }
                  ?.let { if (key == "appId") it.toInt().toLong() else it.toLong() }
            }
          integral?.let { put(key, JsonPrimitive(it)) }
        }
        obj["authorProfile"]?.let { element ->
          val profile = element as? JsonObject
          if (
            profile != null &&
              (compatibleString(profile["name"]) != null ||
                compatibleString(profile["userId"]) != null)
          )
            put(
              "authorProfile",
              buildJsonObject {
                val name = compatibleString(profile["name"])
                put("name", JsonPrimitive(name ?: ""))
                listOf("avatarUrl", "level", "location", "userId", "bio").forEach { key ->
                  compatibleString(profile[key])?.let { put(key, JsonPrimitive(it)) }
                }
              },
            )
        }
        listOf("sections", "hashtags").forEach { key ->
          val values = obj[key] as? JsonArray ?: return@forEach
          put(
            key,
            JsonArray(
              values.mapNotNull { item ->
                val label = item as? JsonObject ?: return@mapNotNull null
                val name = compatibleString(label["name"]) ?: return@mapNotNull null
                buildJsonObject {
                  put("id", JsonPrimitive(compatibleString(label["id"]) ?: ""))
                  put("name", JsonPrimitive(name))
                  compatibleString(label["imageUrl"])?.let { put("imageUrl", JsonPrimitive(it)) }
                }
              }
            ),
          )
        }
      }
      localJson.decodeFromJsonElement<HistorySnapshotDto>(normalized).toStory()
    }
    .getOrNull()

private fun compatibleString(value: JsonElement?): String? =
  when (value) {
    null,
    JsonNull -> null
    is JsonPrimitive ->
      if (value.isString || value.doubleOrNull != null)
        value.content.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
      else null
    else -> null
  }

private fun requiredLegacyString(value: JsonElement?): String? =
  when (value) {
    null,
    JsonNull -> null
    is JsonPrimitive -> value.content
    else -> null
  }

internal fun decodeHistoryArray(raw: String?): List<Story> =
  runCatching {
      val array = localJson.parseToJsonElement(raw ?: "[]") as? JsonArray ?: return emptyList()
      array.mapNotNull { item ->
        val encoded =
          when (item) {
            is JsonPrimitive -> if (item.isString) item.content else null
            is JsonObject -> localJson.encodeToString(item)
            else -> null
          }
        encoded?.let(::decodeHistoryStory)
      }
    }
    .getOrDefault(emptyList())

internal fun encodeHistoryArray(stories: List<Story>): String =
  Json.encodeToString(JsonArray(stories.map { JsonPrimitive(encodeHistoryStory(it)) }))
