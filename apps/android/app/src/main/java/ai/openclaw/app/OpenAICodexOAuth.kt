package ai.openclaw.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OpenAICodexAuthorizationFlow(
  val state: String,
  val verifier: String,
  val authorizationUrl: String,
  val redirectUri: String,
)

data class OpenAICodexAuthorizationInput(
  val code: String?,
  val state: String?,
)

data class OpenAICodexTokens(
  val access: String,
  val refresh: String,
  val expiresAtMs: Long,
  val accountId: String,
)

object OpenAICodexOAuth {
  const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
  const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
  const val TOKEN_URL = "https://auth.openai.com/oauth/token"
  const val REDIRECT_URI = "http://localhost:1455/auth/callback"
  const val SCOPE = "openid profile email offline_access"
  const val JWT_CLAIM_PATH = "https://api.openai.com/auth"

  const val SUCCESS_HTML =
    "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\" />" +
      "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />" +
      "<title>Authentication successful</title></head><body>" +
      "<p>Authentication successful. Return to your app to continue.</p></body></html>"

  private val json = Json { ignoreUnknownKeys = true }
  private val secureRandom = SecureRandom()

  fun createAuthorizationFlow(
    originator: String = "pi",
    redirectUri: String = REDIRECT_URI,
  ): OpenAICodexAuthorizationFlow {
    val verifier = randomBase64Url(32)
    val challenge = sha256Base64Url(verifier)
    val state = randomHex(16)
    val url =
      buildString {
        append(AUTHORIZE_URL)
        append("?response_type=code")
        append("&client_id=${urlEncode(CLIENT_ID)}")
        append("&redirect_uri=${urlEncode(redirectUri)}")
        append("&scope=${urlEncode(SCOPE)}")
        append("&code_challenge=${urlEncode(challenge)}")
        append("&code_challenge_method=S256")
        append("&state=${urlEncode(state)}")
        append("&id_token_add_organizations=true")
        append("&codex_cli_simplified_flow=true")
        append("&originator=${urlEncode(originator)}")
      }
    return OpenAICodexAuthorizationFlow(
      state = state,
      verifier = verifier,
      authorizationUrl = url,
      redirectUri = redirectUri,
    )
  }

  fun parseAuthorizationInput(input: String): OpenAICodexAuthorizationInput {
    val value = input.trim()
    if (value.isBlank()) return OpenAICodexAuthorizationInput(code = null, state = null)

    runCatching {
      val url = URL(value)
      return OpenAICodexAuthorizationInput(
        code = url.query?.let { queryField(it, "code") },
        state = url.query?.let { queryField(it, "state") },
      )
    }

    if (value.contains("#")) {
      val parts = value.split("#", limit = 2)
      return OpenAICodexAuthorizationInput(
        code = parts.getOrNull(0)?.trim().takeUnless { it.isNullOrBlank() },
        state = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() },
      )
    }

    if (value.contains("code=")) {
      return OpenAICodexAuthorizationInput(
        code = queryField(value, "code"),
        state = queryField(value, "state"),
      )
    }

    return OpenAICodexAuthorizationInput(code = value, state = null)
  }

  fun extractAccountIdFromJwt(accessToken: String): String? {
    return try {
      val parts = accessToken.split(".")
      val payloadPart = parts.getOrNull(1) ?: return null
      val normalized =
        payloadPart
          .replace('-', '+')
          .replace('_', '/')
          .let { value ->
            val padding = (4 - value.length % 4) % 4
            value + "=".repeat(padding)
          }
      val decoded = String(Base64.getDecoder().decode(normalized))
      val payload = json.parseToJsonElement(decoded) as? JsonObject ?: return null
      val auth = payload[JWT_CLAIM_PATH] as? JsonObject ?: return null
      auth["chatgpt_account_id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
      null
    }
  }

  fun exchangeAuthorizationCode(
    code: String,
    verifier: String,
    redirectUri: String = REDIRECT_URI,
    tokenUrl: String = TOKEN_URL,
  ): Result<OpenAICodexTokens> {
    val body =
      formBody(
        "grant_type" to "authorization_code",
        "client_id" to CLIENT_ID,
        "code" to code,
        "code_verifier" to verifier,
        "redirect_uri" to redirectUri,
      )
    return performTokenRequest(tokenUrl = tokenUrl, body = body)
  }

  fun refreshAccessToken(
    refreshToken: String,
    tokenUrl: String = TOKEN_URL,
  ): Result<OpenAICodexTokens> {
    val body =
      formBody(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to CLIENT_ID,
      )
    return performTokenRequest(tokenUrl = tokenUrl, body = body)
  }

  private fun performTokenRequest(tokenUrl: String, body: String): Result<OpenAICodexTokens> {
    return runCatching {
      val conn =
        (URL(tokenUrl).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 10_000
          readTimeout = 15_000
          doOutput = true
          setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
      conn.outputStream.use { it.write(body.toByteArray()) }
      val code = conn.responseCode
      val text =
        (if (code in 200..299) conn.inputStream else conn.errorStream)
          ?.bufferedReader()
          ?.use { it.readText() }
          .orEmpty()
      if (code !in 200..299) {
        error("Token exchange failed ($code): ${text.take(240)}")
      }
      val obj = json.parseToJsonElement(text) as? JsonObject ?: error("Invalid token response")
      val access = obj.string("access_token").ifBlank { error("Missing access_token") }
      val refresh = obj.string("refresh_token").ifBlank { error("Missing refresh_token") }
      val expiresIn = obj.string("expires_in").toLongOrNull() ?: error("Missing expires_in")
      val accountId = extractAccountIdFromJwt(access) ?: error("Missing chatgpt_account_id")
      OpenAICodexTokens(
        access = access,
        refresh = refresh,
        expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
        accountId = accountId,
      )
    }
  }

  private fun randomBase64Url(numBytes: Int): String {
    val bytes = ByteArray(numBytes)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun randomHex(numBytes: Int): String {
    val bytes = ByteArray(numBytes)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString(separator = "") { "%02x".format(it) }
  }

  private fun sha256Base64Url(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }

  private fun formBody(vararg fields: Pair<String, String>): String =
    fields.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

  private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

  private fun queryField(query: String, key: String): String? {
    val encodedKey = "${key}="
    return query
      .split("&")
      .firstOrNull { it.startsWith(encodedKey) }
      ?.substringAfter('=')
      ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
  }

  private fun JsonObject.string(key: String): String =
    get(key)?.jsonPrimitive?.content?.trim().orEmpty()
}
