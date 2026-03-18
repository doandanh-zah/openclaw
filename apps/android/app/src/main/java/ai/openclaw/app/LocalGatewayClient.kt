package ai.openclaw.app

import java.net.HttpURLConnection
import java.net.URL

object LocalGatewayClient {
  private const val BASE = "http://127.0.0.1:18789"

  private fun request(
    method: String,
    path: String,
    body: String? = null,
    bearer: String? = null,
    timeoutMs: Int = 5000,
  ): Pair<Int, String> {
    val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = timeoutMs
      readTimeout = timeoutMs
      if (!bearer.isNullOrBlank()) {
        setRequestProperty("Authorization", "Bearer $bearer")
      }
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
    }

    if (body != null) {
      conn.outputStream.use { it.write(body.toByteArray()) }
    }

    val code = conn.responseCode
    val text =
      (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()
    return code to text
  }

  private fun field(json: String, key: String): String {
    val re = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
    return re.find(json)?.groupValues?.getOrNull(1).orEmpty()
  }

  fun getLocalToken(): String {
    val (_, body) = request("GET", "/token")
    return field(body, "token")
  }

  fun quickstartTelegram(botToken: String, chatId: String): Pair<Boolean, String> {
    val token = getLocalToken()
    if (token.isBlank()) return false to "Local gateway token missing"

    val payload = "{\"botToken\":\"${escape(botToken)}\",\"chatId\":\"${escape(chatId)}\"}"
    val (code, body) = request("POST", "/v1/setup/quickstart", payload, token)
    return (code in 200..299) to body
  }

  fun sendTelegramTest(text: String): Pair<Boolean, String> {
    val token = getLocalToken()
    if (token.isBlank()) return false to "Local gateway token missing"

    val payload = "{\"text\":\"${escape(text)}\"}"
    val (code, body) = request("POST", "/v1/telegram/send", payload, token)
    return (code in 200..299) to body
  }

  private fun escape(v: String): String =
    v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
