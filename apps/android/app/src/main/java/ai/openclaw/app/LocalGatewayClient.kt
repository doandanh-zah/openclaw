package ai.openclaw.app

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

object LocalGatewayClient {
  private const val BASE = "http://127.0.0.1:18789"

  data class ApiResult<T>(
    val ok: Boolean,
    val value: T? = null,
    val message: String = "",
    val statusCode: Int = 0,
  )

  data class GatewaySummary(
    val running: Boolean,
    val port: Int,
    val tokenReady: Boolean,
    val networkMode: String,
    val bindHost: String,
    val localUrl: String,
    val lanUrl: String,
  )

  data class OAuthSummary(
    val ready: Boolean,
    val pending: Boolean,
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val accountLabel: String,
    val hasRefreshToken: Boolean,
    val lastError: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
  )

  data class TelegramSummary(
    val configured: Boolean,
    val polling: Boolean,
    val requested: Boolean,
    val chatId: String,
    val botTokenMasked: String,
    val handled: Long,
    val lastError: String,
    val lastInbound: String,
    val lastOutbound: String,
    val lastTestOk: Boolean,
    val lastTestAtMs: Long,
    val lastTestMessage: String,
  )

  data class WizardSnapshot(
    val gateway: GatewaySummary,
    val oauth: OAuthSummary,
    val telegram: TelegramSummary,
  )

  data class OAuthDeviceFlow(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
  )

  private data class HttpResult(
    val code: Int,
    val body: String,
  )

  private val json = Json { ignoreUnknownKeys = true }

  private fun request(
    method: String,
    path: String,
    body: String? = null,
    bearer: String? = null,
    timeoutMs: Int = 5_000,
  ): HttpResult {
    val conn =
      (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
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
    return HttpResult(code = code, body = text)
  }

  private fun requestAuthorized(
    method: String,
    path: String,
    body: String? = null,
    timeoutMs: Int = 5_000,
  ): HttpResult {
    val token = getLocalToken()
    if (token.isBlank()) {
      return HttpResult(code = 503, body = """{"error":"local_gateway_token_missing"}""")
    }
    return request(method = method, path = path, body = body, bearer = token, timeoutMs = timeoutMs)
  }

  private fun parseObject(text: String): JsonObject? =
    try {
      json.parseToJsonElement(text).jsonObject
    } catch (_: Throwable) {
      null
    }

  private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

  private fun JsonObject.boolean(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull == true

  private fun JsonObject.int(key: String): Int =
    string(key).toIntOrNull() ?: ((get(key) as? JsonPrimitive)?.longOrNull?.toInt() ?: 0)

  private fun JsonObject.long(key: String): Long =
    string(key).toLongOrNull() ?: ((get(key) as? JsonPrimitive)?.longOrNull ?: 0L)

  fun getLocalToken(): String {
    return try {
      val body = request("GET", "/token", timeoutMs = 2_000).body
      parseObject(body)?.string("token").orEmpty()
    } catch (_: Throwable) {
      ""
    }
  }

  fun waitForGatewayReady(timeoutMs: Int = 8_000, intervalMs: Long = 350L): ApiResult<WizardSnapshot> {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastFailure = "Local gateway did not respond"
    while (System.currentTimeMillis() < deadline) {
      val snapshot = fetchWizardSnapshot()
      if (snapshot.ok && snapshot.value != null) {
        return snapshot
      }
      if (snapshot.message.isNotBlank()) {
        lastFailure = snapshot.message
      }
      Thread.sleep(intervalMs)
    }
    return ApiResult(ok = false, message = lastFailure, statusCode = 504)
  }

  fun fetchWizardSnapshot(): ApiResult<WizardSnapshot> {
    return try {
      val authed = requestAuthorized("GET", "/v1/wizard/status", timeoutMs = 3_500)
      if (authed.code in 200..299) {
        val obj = parseObject(authed.body)
        if (obj != null) {
          return ApiResult(
            ok = true,
            value = parseWizardSnapshot(obj),
            message = "ok",
            statusCode = authed.code,
          )
        }
      }

      val fallback = request("GET", "/status", timeoutMs = 3_500)
      if (fallback.code !in 200..299) {
        return ApiResult(
          ok = false,
          message = parseErrorMessage(fallback.body).ifBlank { "Gateway is offline" },
          statusCode = fallback.code,
        )
      }

      val obj = parseObject(fallback.body)
        ?: return ApiResult(ok = false, message = "Gateway returned invalid status", statusCode = fallback.code)
      ApiResult(ok = true, value = parseFallbackSnapshot(obj), message = "ok", statusCode = fallback.code)
    } catch (t: Throwable) {
      ApiResult(ok = false, message = t.message ?: t.javaClass.simpleName)
    }
  }

  fun startOAuthDeviceFlow(): ApiResult<OAuthDeviceFlow> {
    val body = """{"clientId":"openclaw-android-local"}"""
    val result = requestAuthorized("POST", "/v1/oauth/device/start", body)
    if (result.code !in 200..299) {
      return ApiResult(ok = false, message = parseErrorMessage(result.body), statusCode = result.code)
    }
    val obj = parseObject(result.body)
      ?: return ApiResult(ok = false, message = "Invalid OAuth response", statusCode = result.code)
    return ApiResult(
      ok = true,
      value =
        OAuthDeviceFlow(
          deviceCode = obj.string("deviceCode"),
          userCode = obj.string("userCode"),
          verificationUri = obj.string("verificationUri"),
          verificationUriComplete = obj.string("verificationUriComplete"),
        ),
      message = "OAuth login prepared",
      statusCode = result.code,
    )
  }

  fun completeOAuthDeviceFlow(deviceCode: String, accountLabel: String = "ChatGPT linked"): ApiResult<String> {
    val payload =
      """{"deviceCode":"${escape(deviceCode)}","accountLabel":"${escape(accountLabel)}"}"""
    val result = requestAuthorized("POST", "/v1/oauth/device/complete", payload)
    return simpleMessageResult(result, successMessage = "OAuth completed")
  }

  fun resetOAuth(): ApiResult<String> {
    val result = requestAuthorized("POST", "/v1/oauth/reset", body = "{}")
    return simpleMessageResult(result, successMessage = "OAuth reset")
  }

  fun setGatewayNetworkMode(networkMode: String): ApiResult<GatewaySummary> {
    val payload = """{"networkMode":"${escape(networkMode)}"}"""
    val result = requestAuthorized("POST", "/v1/gateway/network-mode", body = payload)
    if (result.code !in 200..299) {
      return ApiResult(ok = false, message = parseErrorMessage(result.body), statusCode = result.code)
    }
    val obj = parseObject(result.body)
      ?: return ApiResult(ok = false, message = "Invalid gateway network response", statusCode = result.code)
    val summary =
      GatewaySummary(
        running = obj.boolean("running"),
        port = obj.int("port"),
        tokenReady = getLocalToken().isNotBlank(),
        networkMode = obj.string("networkMode").ifBlank { GatewayLocalService.NETWORK_MODE_LOCAL },
        bindHost = obj.string("bindHost"),
        localUrl = obj.string("localUrl"),
        lanUrl = obj.string("lanUrl"),
      )
    return ApiResult(
      ok = true,
      value = summary,
      message = obj.string("message").ifBlank { "Gateway network mode updated" },
      statusCode = result.code,
    )
  }

  fun configureTelegram(botToken: String, chatId: String, startPolling: Boolean = true): ApiResult<String> {
    val payload =
      """{"botToken":"${escape(botToken)}","chatId":"${escape(chatId)}","startPolling":$startPolling}"""
    val result = requestAuthorized("POST", "/v1/config/telegram", payload)
    return simpleMessageResult(result, successMessage = "Telegram configured")
  }

  fun quickstartTelegram(botToken: String, chatId: String): ApiResult<String> {
    val payload = """{"botToken":"${escape(botToken)}","chatId":"${escape(chatId)}"}"""
    val result = requestAuthorized("POST", "/v1/setup/quickstart", payload)
    return simpleMessageResult(result, successMessage = "Telegram quick setup complete")
  }

  fun startTelegramPolling(): ApiResult<String> {
    val result = requestAuthorized("POST", "/v1/telegram/poll/start", body = "{}")
    return simpleMessageResult(result, successMessage = "Telegram polling started")
  }

  fun stopTelegramPolling(): ApiResult<String> {
    val result = requestAuthorized("POST", "/v1/telegram/poll/stop", body = "{}")
    return simpleMessageResult(result, successMessage = "Telegram polling stopped")
  }

  fun resetTelegram(): ApiResult<String> {
    val result = requestAuthorized("POST", "/v1/telegram/reset", body = "{}")
    return simpleMessageResult(result, successMessage = "Telegram config cleared")
  }

  fun sendTelegramTest(text: String, chatId: String? = null): ApiResult<String> {
    val extraChat = chatId?.trim().orEmpty()
    val payload =
      buildString {
        append("{\"text\":\"${escape(text)}\"")
        if (extraChat.isNotBlank()) {
          append(",\"chatId\":\"${escape(extraChat)}\"")
        }
        append("}")
      }
    val result = requestAuthorized("POST", "/v1/telegram/send", payload)
    return simpleMessageResult(result, successMessage = "Test message sent")
  }

  private fun simpleMessageResult(result: HttpResult, successMessage: String): ApiResult<String> {
    return if (result.code in 200..299) {
      val message = parseObject(result.body)?.string("message").orEmpty().ifBlank { successMessage }
      ApiResult(ok = true, value = message, message = message, statusCode = result.code)
    } else {
      ApiResult(ok = false, message = parseErrorMessage(result.body), statusCode = result.code)
    }
  }

  private fun parseWizardSnapshot(root: JsonObject): WizardSnapshot {
    val gatewayObj = root["gateway"]?.jsonObject ?: JsonObject(emptyMap())
    val oauthObj = root["oauth"]?.jsonObject ?: JsonObject(emptyMap())
    val telegramObj = root["telegram"]?.jsonObject ?: JsonObject(emptyMap())
    return WizardSnapshot(
      gateway =
        GatewaySummary(
          running = gatewayObj.boolean("running"),
          port = gatewayObj.int("port"),
          tokenReady = gatewayObj.boolean("tokenReady"),
          networkMode = gatewayObj.string("networkMode").ifBlank { GatewayLocalService.NETWORK_MODE_LOCAL },
          bindHost = gatewayObj.string("bindHost"),
          localUrl = gatewayObj.string("localUrl"),
          lanUrl = gatewayObj.string("lanUrl"),
        ),
      oauth =
        OAuthSummary(
          ready = oauthObj.boolean("ready"),
          pending = oauthObj.boolean("pending"),
          deviceCode = oauthObj.string("deviceCode"),
          userCode = oauthObj.string("userCode"),
          verificationUri = oauthObj.string("verificationUri"),
          verificationUriComplete = oauthObj.string("verificationUriComplete"),
          accountLabel = oauthObj.string("accountLabel"),
          hasRefreshToken = oauthObj.boolean("hasRefreshToken"),
          lastError = oauthObj.string("lastError"),
          startedAtMs = oauthObj.long("startedAtMs"),
          completedAtMs = oauthObj.long("completedAtMs"),
        ),
      telegram =
        TelegramSummary(
          configured = telegramObj.boolean("configured"),
          polling = telegramObj.boolean("polling"),
          requested = telegramObj.boolean("requested"),
          chatId = telegramObj.string("chatId"),
          botTokenMasked = telegramObj.string("botTokenMasked"),
          handled = telegramObj.long("handled"),
          lastError = telegramObj.string("lastError"),
          lastInbound = telegramObj.string("lastInbound"),
          lastOutbound = telegramObj.string("lastOutbound"),
          lastTestOk = telegramObj.boolean("lastTestOk"),
          lastTestAtMs = telegramObj.long("lastTestAtMs"),
          lastTestMessage = telegramObj.string("lastTestMessage"),
        ),
    )
  }

  private fun parseFallbackSnapshot(root: JsonObject): WizardSnapshot {
    val running = root.boolean("running")
    val port = root.int("port")
    val tokenReady = root.boolean("tokenReady")
    return WizardSnapshot(
      gateway =
        GatewaySummary(
          running = running,
          port = port,
          tokenReady = tokenReady,
          networkMode = root.string("networkMode").ifBlank { GatewayLocalService.NETWORK_MODE_LOCAL },
          bindHost = root.string("bindHost"),
          localUrl = root.string("localUrl"),
          lanUrl = root.string("lanUrl"),
        ),
      oauth =
        OAuthSummary(
          ready = root.boolean("oauthReady"),
          pending = false,
          deviceCode = "",
          userCode = "",
          verificationUri = "",
          verificationUriComplete = "",
          accountLabel = "",
          hasRefreshToken = false,
          lastError = "",
          startedAtMs = 0L,
          completedAtMs = 0L,
        ),
      telegram =
        TelegramSummary(
          configured = root.boolean("telegramConfigured"),
          polling = false,
          requested = false,
          chatId = "",
          botTokenMasked = "",
          handled = 0L,
          lastError = "",
          lastInbound = "",
          lastOutbound = "",
          lastTestOk = false,
          lastTestAtMs = 0L,
          lastTestMessage = "",
        ),
    )
  }

  private fun parseErrorMessage(body: String): String {
    val obj = parseObject(body) ?: return body.ifBlank { "Request failed" }
    val directMessage = obj.string("message")
    if (directMessage.isNotBlank()) return directMessage
    val error = obj["error"]
    if (error is JsonPrimitive) {
      return error.contentOrNull.orEmpty().ifBlank { body.ifBlank { "Request failed" } }
    }
    val errorObj = error as? JsonObject
    if (errorObj != null) {
      return errorObj.string("message").ifBlank { errorObj.string("code") }.ifBlank { body.ifBlank { "Request failed" } }
    }
    return body.ifBlank { "Request failed" }
  }

  private fun escape(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
}
