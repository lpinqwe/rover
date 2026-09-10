package com.rover.gateway

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * Отправляет статусы/ошибки прямо в Telegram-чат (в тот же, где ловишь APK).
 * Никаких зависимостей — голый HttpsURLConnection.
 */
object TgNotify {

    const val KEY_TG_TOKEN = "tg_token"
    const val KEY_TG_CHAT = "tg_chat"
    const val DEFAULT_TOKEN = "8816018038:AAGvNHgaDhNdWeJnJbk_Y_CvGWdd4EyTCQw"
    const val DEFAULT_CHAT = "663450648"

    // Жёсткий глобальный лимит: больше 6 сообщений в минуту в ТГ не уйдёт —
    // ни краши, ни вачдог не смогут зафлудить чат.
    private val lock = Any()
    private var windowStart = 0L
    private var count = 0

    fun report(prefs: SharedPreferences, text: String) {
        val token = prefs.getString(KEY_TG_TOKEN, DEFAULT_TOKEN) ?: DEFAULT_TOKEN
        val chat = prefs.getString(KEY_TG_CHAT, DEFAULT_CHAT) ?: DEFAULT_CHAT
        if (token.isBlank() || chat.isBlank()) return
        thread {
            try {
                val allowed = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    if (now - windowStart > 60_000) {
                        windowStart = now
                        count = 0
                    }
                    if (count >= 6) false else {
                        count++
                        true
                    }
                }
                if (!allowed) return@thread  // молча сбрасываем лишнее
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                val conn = url.openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = JSONObject()
                    .put("chat_id", chat)
                    .put("text", text)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.close()
            } catch (_: Throwable) {
                // репортёр не должен ронять приложение
            }
        }
    }
}