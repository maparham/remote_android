package com.example.lanremote.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

enum class KeyAction { BACK, HOME, RECENTS }

sealed interface ControlEvent {
    data class Tap(val x: Float, val y: Float) : ControlEvent
    data class LongPress(val x: Float, val y: Float, val durationMs: Long) : ControlEvent
    data class Swipe(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val durationMs: Long
    ) : ControlEvent
    data class Key(val action: KeyAction) : ControlEvent
    data class Text(val value: String) : ControlEvent
    /** Stream-settings change from the viewer; null fields are left unchanged. */
    data class Quality(val scale: Int?, val fps: Int?, val jpegQuality: Int?) : ControlEvent
}

object ControlEventParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun Float.inUnit() = this in 0f..1f

    fun parse(input: String): ControlEvent? = try {
        val obj = json.parseToJsonElement(input).jsonObject
        fun f(k: String) = obj[k]!!.jsonPrimitive.float
        fun l(k: String) = obj[k]?.jsonPrimitive?.long ?: 300L
        when (obj["type"]?.jsonPrimitive?.content) {
            "tap" -> {
                val x = f("x"); val y = f("y")
                if (x.inUnit() && y.inUnit()) ControlEvent.Tap(x, y) else null
            }
            "longpress" -> {
                val x = f("x"); val y = f("y")
                if (x.inUnit() && y.inUnit()) ControlEvent.LongPress(x, y, l("durationMs")) else null
            }
            "swipe" -> {
                val x1 = f("x1"); val y1 = f("y1"); val x2 = f("x2"); val y2 = f("y2")
                if (x1.inUnit() && y1.inUnit() && x2.inUnit() && y2.inUnit())
                    ControlEvent.Swipe(x1, y1, x2, y2, l("durationMs")) else null
            }
            "key" -> when (obj["action"]?.jsonPrimitive?.content) {
                "back" -> ControlEvent.Key(KeyAction.BACK)
                "home" -> ControlEvent.Key(KeyAction.HOME)
                "recents" -> ControlEvent.Key(KeyAction.RECENTS)
                else -> null
            }
            "text" -> obj["value"]?.jsonPrimitive?.content?.let { ControlEvent.Text(it) }
            "quality" -> ControlEvent.Quality(
                scale = obj["scale"]?.jsonPrimitive?.intOrNull,
                fps = obj["fps"]?.jsonPrimitive?.intOrNull,
                jpegQuality = obj["jpegQuality"]?.jsonPrimitive?.intOrNull
            )
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}
