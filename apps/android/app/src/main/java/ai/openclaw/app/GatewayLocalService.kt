package ai.openclaw.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android-local gateway runtime:
 * - Runs as foreground service
 * - Binds local TCP 18789
 * - Exposes basic HTTP endpoints for health/status/token
 *
 * This build targets direct on-device usage on Android.
 */
class GatewayLocalService : Service() {
  private var serverThread: Thread? = null
  private var serverSocket: ServerSocket? = null
  private var oauthCallbackThread: Thread? = null
  private var oauthCallbackSocket: ServerSocket? = null
  private var telegramPollThread: Thread? = null
  private var watchdogThread: Thread? = null
  @Volatile private var telegramPolling: Boolean = false
  private var telegramPollingRequested: Boolean = false
  private var telegramLastUpdateId: Long = 0L
  private var telegramHandledCount: Long = 0L
  private val prefs by lazy { applicationContext.getSharedPreferences("openclaw.gateway.local", Context.MODE_PRIVATE) }
  private val securePrefs by lazy { SecurePrefs(applicationContext) }
  private val json = Json { ignoreUnknownKeys = true }
  private var localToken: String = ""
  private var telegramBotToken: String = ""
  private var telegramChatId: String = ""
  private var gatewayNetworkMode: String = NETWORK_MODE_LOCAL
  private var oauthDeviceCode: String = ""
  private var oauthUserCode: String = ""
  private var oauthVerificationUri: String = ""
  private var oauthVerificationUriComplete: String = ""
  private var oauthAccessToken: String = ""
  private var oauthRefreshToken: String = ""
  private var oauthAccessExpiresAtMs: Long = 0L
  private var oauthState: String = ""
  private var oauthCodeVerifier: String = ""
  private var oauthAccountId: String = ""
  private var oauthAccountLabel: String = ""
  private var oauthPending: Boolean = false
  private var oauthLastError: String = ""
  private var oauthStartedAtMs: Long = 0L
  private var oauthCompletedAtMs: Long = 0L
  private var defaultModel: String = ""
  private var lastInboundText: String = ""
  private var lastOutboundText: String = ""
  private var lastTelegramError: String = ""
  private var telegramLastTestOk: Boolean = false
  private var telegramLastTestAtMs: Long = 0L
  private var telegramLastTestMessage: String = ""
  private var telegramPairingCode: String = ""
  private var telegramPairingChatId: String = ""
  private var telegramPairingRequestedAtMs: Long = 0L
  private var telegramPairingApprovedAtMs: Long = 0L
  private val oauthLock = Any()
  private val serverLock = Any()

  override fun onCreate() {
    super.onCreate()
    localToken = securePrefs.getString("gateway.local.token")?.takeIf { it.isNotBlank() }
      ?: UUID.randomUUID().toString().replace("-", "")
    telegramBotToken = securePrefs.getString("gateway.local.telegram.botToken") ?: ""
    telegramChatId = prefs.getString("telegramChatId", "") ?: ""
    gatewayNetworkMode = sanitizeNetworkMode(prefs.getString("gateway.local.networkMode", NETWORK_MODE_LOCAL))
    oauthDeviceCode = prefs.getString("gateway.local.oauth.deviceCode", "") ?: ""
    oauthUserCode = prefs.getString("gateway.local.oauth.userCode", "") ?: ""
    oauthVerificationUri = prefs.getString("gateway.local.oauth.verificationUri", "") ?: ""
    oauthVerificationUriComplete = prefs.getString("gateway.local.oauth.verificationUriComplete", "") ?: ""
    oauthAccessToken = securePrefs.getString("gateway.local.oauth.accessToken") ?: ""
    oauthRefreshToken = securePrefs.getString("gateway.local.oauth.refreshToken") ?: ""
    oauthAccessExpiresAtMs = prefs.getLong("gateway.local.oauth.accessExpiresAtMs", 0L)
    oauthState = prefs.getString("gateway.local.oauth.state", "") ?: ""
    oauthCodeVerifier = securePrefs.getString("gateway.local.oauth.codeVerifier") ?: ""
    oauthAccountId = prefs.getString("gateway.local.oauth.accountId", "") ?: ""
    oauthAccountLabel = prefs.getString("gateway.local.oauth.accountLabel", "") ?: ""
    oauthPending = prefs.getBoolean("gateway.local.oauth.pending", false)
    oauthLastError = prefs.getString("gateway.local.oauth.lastError", "") ?: ""
    oauthStartedAtMs = prefs.getLong("gateway.local.oauth.startedAtMs", 0L)
    oauthCompletedAtMs = prefs.getLong("gateway.local.oauth.completedAtMs", 0L)
    defaultModel = prefs.getString("gateway.local.model.default", "") ?: ""
    telegramLastUpdateId = prefs.getLong("telegramLastUpdateId", 0L)
    telegramHandledCount = prefs.getLong("telegramHandledCount", 0L)
    telegramPollingRequested = prefs.getBoolean("telegramPollingRequested", false)
    lastInboundText = prefs.getString("gateway.local.telegram.lastInbound", "") ?: ""
    lastOutboundText = prefs.getString("gateway.local.telegram.lastOutbound", "") ?: ""
    lastTelegramError = prefs.getString("gateway.local.telegram.lastError", "") ?: ""
    telegramLastTestOk = prefs.getBoolean("gateway.local.telegram.lastTestOk", false)
    telegramLastTestAtMs = prefs.getLong("gateway.local.telegram.lastTestAtMs", 0L)
    telegramLastTestMessage = prefs.getString("gateway.local.telegram.lastTestMessage", "") ?: ""
    telegramPairingCode = prefs.getString("gateway.local.telegram.pairing.code", "") ?: ""
    telegramPairingChatId = prefs.getString("gateway.local.telegram.pairing.chatId", "") ?: ""
    telegramPairingRequestedAtMs = prefs.getLong("gateway.local.telegram.pairing.requestedAtMs", 0L)
    telegramPairingApprovedAtMs = prefs.getLong("gateway.local.telegram.pairing.approvedAtMs", 0L)

    securePrefs.putString("gateway.local.token", localToken)
    tokenRef.set(localToken)
    if (oauthVerificationUri.startsWith("openclaw://")) {
      oauthVerificationUri = ""
    }
    if (oauthVerificationUriComplete.startsWith("openclaw://")) {
      oauthVerificationUriComplete = ""
    }
    if (oauthPending && (oauthState.isBlank() || oauthCodeVerifier.isBlank())) {
      oauthPending = false
      oauthDeviceCode = ""
      oauthUserCode = ""
      oauthVerificationUri = ""
      oauthVerificationUriComplete = ""
      oauthLastError = "ChatGPT login expired after restart. Start the QR login again."
      persistOauthState()
    }
    cleanupExpiredTelegramPairing()

    ensureChannel()
    startForeground(NOTIFICATION_ID, buildNotification("Starting local gateway…"))
    startServer()
    if (oauthPending && !startOAuthCallbackListener()) {
      oauthPending = false
      oauthLastError =
        oauthLastError.ifBlank {
          "Could not resume the local OAuth callback listener on localhost:${OAUTH_CALLBACK_PORT}."
        }
      persistOauthState()
    }
    if (oauthRefreshToken.isNotBlank()) {
      thread(start = true, name = "openclaw-oauth-refresh") {
        maybeRefreshOAuthSessionIfNeeded()
      }
    }
    if (telegramPollingRequested && telegramBotToken.isNotBlank()) {
      startTelegramPolling()
    }
    startWatchdog()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopSelf()
        return START_NOT_STICKY
      }
    }
    intent?.getStringExtra(EXTRA_NETWORK_MODE)?.let { requestedMode ->
      applyGatewayNetworkMode(sanitizeNetworkMode(requestedMode))
    }
    return START_STICKY
  }

  override fun onDestroy() {
    stopWatchdog()
    stopTelegramPolling()
    stopOAuthCallbackListener()
    stopServer()
    isRunning.set(false)
    tokenRef.set("")
    super.onDestroy()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= 10) {
      lastInboundText = capText(lastInboundText, 120)
      lastOutboundText = capText(lastOutboundText, 120)
      if (lastTelegramError.length > 240) {
        lastTelegramError = lastTelegramError.takeLast(240)
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startServer() {
    if (isRunning.get()) return

    serverThread =
      thread(start = true, name = "openclaw-local-gateway") {
        var ss: ServerSocket? = null
        try {
          val bindHost = gatewayBindHost()
          ss = ServerSocket(PORT, 50, InetAddress.getByName(bindHost))
          serverSocket = ss
          isRunning.set(true)
          updateNotification("Local gateway listening on ${gatewayBindLabel()}:$PORT")

          while (!Thread.currentThread().isInterrupted) {
            val socket = ss.accept()
            handleClient(socket)
          }
        } catch (_: SocketException) {
          // expected when socket closes during stop
        } catch (t: Throwable) {
          updateNotification("Gateway crashed: ${t.javaClass.simpleName}")
        } finally {
          synchronized(serverLock) {
            if (serverSocket === ss) {
              serverSocket = null
              isRunning.set(false)
            }
          }
        }
      }
  }

  private fun stopServer() {
    val socketToClose =
      synchronized(serverLock) {
        val current = serverSocket
        serverSocket = null
        isRunning.set(false)
        current
      }
    try {
      socketToClose?.close()
    } catch (_: Throwable) {
    }
    serverThread?.interrupt()
    serverThread = null
  }

  private fun restartServer() {
    stopServer()
    startServer()
  }

  private fun applyGatewayNetworkMode(mode: String) {
    if (mode == gatewayNetworkMode) return
    gatewayNetworkMode = mode
    prefs.edit { putString("gateway.local.networkMode", gatewayNetworkMode) }
    if (serverThread?.isAlive == true || isRunning.get()) {
      restartServer()
    }
  }

  private fun startWatchdog() {
    if (watchdogThread?.isAlive == true) return
    watchdogThread =
      thread(start = true, name = "openclaw-gateway-watchdog") {
        while (!Thread.currentThread().isInterrupted) {
          try {
            val serverAlive = serverThread?.isAlive == true
            if (!serverAlive) {
              startServer()
            }

            if (telegramPollingRequested && telegramBotToken.isNotBlank()) {
              val pollAlive = telegramPollThread?.isAlive == true
              if (!pollAlive) {
                startTelegramPolling()
              }
            }

            updateNotification(
              "Gateway :$PORT | poll=${if (telegramPolling) "on" else "off"} | handled=$telegramHandledCount",
            )
          } catch (t: Throwable) {
            lastTelegramError = t.message ?: t.javaClass.simpleName
          }

          try {
            Thread.sleep(5000)
          } catch (_: InterruptedException) {
            break
          }
        }
      }
  }

  private fun stopWatchdog() {
    watchdogThread?.interrupt()
    watchdogThread = null
  }

  private fun handleClient(socket: java.net.Socket) {
    socket.use { s ->
      val reader = BufferedReader(InputStreamReader(s.getInputStream()))
      val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))

      val requestLine = reader.readLine() ?: return
      val parts = requestLine.split(" ")
      val method = parts.getOrNull(0) ?: "GET"
      val target = parts.getOrNull(1) ?: "/"
      val path = target.substringBefore('?')
      val query = target.substringAfter('?', "")

      val headers = mutableMapOf<String, String>()
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
          val key = line.substring(0, idx).trim().lowercase()
          val value = line.substring(idx + 1).trim()
          headers[key] = value
        }
      }

      val body = readBody(reader, headers)

      when {
        path == "/" -> sendHtml(out, 200, buildGatewayProofHtml())
        path == "/proof" -> sendHtml(out, 200, buildGatewayProofHtml())
        path == "/chat" -> {
          val sessionKey = queryField(query, "session").ifBlank { "main" }
          sendHtml(out, 200, buildGatewayProofHtml(sessionKey = sessionKey, desktopChatRoute = true))
        }
        path == "/health" -> sendJson(out, 200, "{\"ok\":true,\"service\":\"gateway-local\"}")
        path == "/status" -> sendJson(out, 200, baseStatusJson())
        path == "/token" && method == "GET" -> {
          val remote = s.inetAddress
          if (remote != null && !remote.isLoopbackAddress) {
            sendJson(out, 401, "{\"error\":\"token_endpoint_local_only\"}")
          } else {
            sendJson(out, 200, "{\"token\":\"$localToken\"}")
          }
        }
        path == "/v1/wizard/status" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            maybeRefreshOAuthSessionIfNeeded()
            sendJson(out, 200, wizardStatusJson())
          }
        }
        path == "/v1/oauth/device/start" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val clientId = jsonField(body, "clientId").ifBlank { "openclaw-android-local" }
            val flow = OpenAICodexOAuth.createAuthorizationFlow()
            stopOAuthCallbackListener()
            oauthDeviceCode = flow.state
            oauthUserCode = flow.state.take(6).uppercase()
            oauthVerificationUri = OpenAICodexOAuth.AUTHORIZE_URL
            oauthVerificationUriComplete = flow.authorizationUrl
            oauthAccessToken = ""
            oauthRefreshToken = ""
            oauthAccessExpiresAtMs = 0L
            oauthState = flow.state
            oauthCodeVerifier = flow.verifier
            oauthAccountId = ""
            oauthAccountLabel = ""
            oauthPending = true
            oauthLastError = ""
            oauthStartedAtMs = System.currentTimeMillis()
            oauthCompletedAtMs = 0L
            val callbackReady = startOAuthCallbackListener()
            persistOauthState()
            if (!callbackReady) {
              oauthPending = false
              oauthLastError = "Could not start the local OAuth callback listener on localhost:${OAUTH_CALLBACK_PORT}."
              persistOauthState()
              sendJson(
                out,
                500,
                "{\"ok\":false,\"error\":\"oauth_callback_unavailable\",\"message\":\"${escapeJson(oauthLastError)}\"}",
              )
            } else {
              val payload =
                "{\"ok\":true,\"clientId\":\"${escapeJson(clientId)}\",\"deviceCode\":\"$oauthDeviceCode\",\"userCode\":\"$oauthUserCode\",\"verificationUri\":\"${escapeJson(oauthVerificationUri)}\",\"verificationUriComplete\":\"${escapeJson(oauthVerificationUriComplete)}\"}"
              sendJson(out, 200, payload)
            }
          }
        }
        path == "/v1/oauth/device/complete" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            if (oauthSessionReady()) {
              sendJson(
                out,
                200,
                "{\"ok\":true,\"message\":\"OAuth session already linked\",\"accountLabel\":\"${escapeJson(oauthAccountLabel)}\"}",
              )
            } else {
              val callbackUrl = jsonField(body, "callbackUrl")
              val manualCode = jsonField(body, "authorizationCode").ifBlank { jsonField(body, "code") }
              val manualState = jsonField(body, "state").ifBlank { jsonField(body, "deviceCode") }
              val parsed =
                callbackUrl
                  .takeIf { it.isNotBlank() }
                  ?.let { OpenAICodexOAuth.parseAuthorizationInput(it) }
              val result =
                completePendingOAuth(
                  code = parsed?.code ?: manualCode,
                  state = parsed?.state ?: manualState,
                )

              result.fold(
                onSuccess = {
                  sendJson(
                    out,
                    200,
                    "{\"ok\":true,\"message\":\"OAuth session saved\",\"accountLabel\":\"${escapeJson(oauthAccountLabel)}\"}",
                  )
                  stopOAuthCallbackListener()
                },
                onFailure = { error ->
                  sendJson(
                    out,
                    400,
                    "{\"ok\":false,\"error\":\"oauth_complete_failed\",\"message\":\"${escapeJson(error.message ?: "OAuth completion failed")}\"}",
                  )
                  stopOAuthCallbackListener()
                },
              )
            }
          }
        }
        path == "/v1/oauth/status" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            maybeRefreshOAuthSessionIfNeeded()
            sendJson(out, 200, oauthStatusJson())
          }
        }
        path == "/v1/oauth/reset" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            clearOauthState()
            sendJson(out, 200, "{\"ok\":true,\"message\":\"OAuth state cleared\"}")
          }
        }
        path == "/v1/model/default" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 200, modelStatusJson())
          }
        }
        path == "/v1/model/default" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val requestedModel = jsonField(body, "model").trim()
            if (requestedModel.isBlank()) {
              sendJson(out, 400, "{\"error\":\"invalid_payload\",\"need\":[\"model\"]}")
            } else {
              defaultModel = capText(requestedModel, 120)
              persistModelState()
              sendJson(
                out,
                200,
                "{\"ok\":true,\"selected\":\"${escapeJson(defaultModel)}\",\"message\":\"Default model saved\"}",
              )
            }
          }
        }
        path == "/v1/gateway/start" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 200, "{\"ok\":true,\"message\":\"gateway already running in app service\"}")
          }
        }
        path == "/v1/gateway/status" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 200, gatewayStatusJson())
          }
        }
        path == "/v1/gateway/network-mode" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 200, gatewayStatusJson())
          }
        }
        path == "/v1/gateway/network-mode" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val requestedMode = sanitizeNetworkMode(jsonField(body, "networkMode"))
            applyGatewayNetworkMode(requestedMode)
            sendJson(
              out,
              200,
              gatewayStatusJson(message = "Gateway network mode updated to $requestedMode"),
            )
          }
        }
        path == "/v1/telegram/token" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val bot = jsonField(body, "botToken").trim()
            val startPolling = jsonBooleanField(body, "startPolling")
            if (bot.isBlank()) {
              sendJson(out, 400, "{\"error\":\"invalid_payload\",\"need\":[\"botToken\"]}")
            } else {
              setTelegramBotToken(bot)
              if (startPolling) {
                telegramPollingRequested = true
                prefs.edit { putBoolean("telegramPollingRequested", true) }
                startTelegramPolling()
              }
              sendJson(
                out,
                200,
                "{\"ok\":true,\"botTokenReady\":true,\"polling\":${telegramPolling || telegramPollingRequested},\"message\":\"Telegram bot saved. Send /start to the bot, then approve the pairing code in the app.\"}",
              )
            }
          }
        }
        path == "/v1/telegram/pairing/status" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 200, telegramPairingStatusJson())
          }
        }
        path == "/v1/telegram/pairing/approve" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            cleanupExpiredTelegramPairing()
            val code = jsonField(body, "code").trim()
            when {
              code.isBlank() -> sendJson(out, 400, "{\"error\":\"invalid_payload\",\"need\":[\"code\"]}")
              telegramPairingCode.isBlank() || telegramPairingChatId.isBlank() ->
                sendJson(out, 404, "{\"error\":\"pairing_not_pending\",\"message\":\"No pending Telegram pairing request\"}")
              !telegramPairingCode.equals(code, ignoreCase = true) ->
                sendJson(out, 409, "{\"error\":\"pairing_code_mismatch\",\"message\":\"Pairing code does not match\"}")
              else -> {
                applyApprovedTelegramChat(telegramPairingChatId)
                sendJson(
                  out,
                  200,
                  "{\"ok\":true,\"chatId\":\"${escapeJson(telegramChatId)}\",\"message\":\"Telegram pairing approved\"}",
                )
              }
            }
          }
        }
        path == "/v1/config/telegram" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val bot = jsonField(body, "botToken")
            val chat = jsonField(body, "chatId")
            val startPolling = jsonBooleanField(body, "startPolling")
            if (bot.isBlank() || chat.isBlank()) {
              sendJson(out, 400, "{\"error\":\"invalid_payload\",\"need\":[\"botToken\",\"chatId\"]}")
            } else {
              setTelegramBotToken(bot)
              applyApprovedTelegramChat(chat)
              if (startPolling) {
                telegramPollingRequested = true
                prefs.edit { putBoolean("telegramPollingRequested", true) }
                startTelegramPolling()
              }
              sendJson(
                out,
                200,
                "{\"ok\":true,\"configured\":true,\"polling\":${telegramPolling || telegramPollingRequested},\"message\":\"Telegram config saved\"}",
              )
            }
          }
        }
        path == "/v1/config/telegram" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val masked = maskToken(telegramBotToken)
            sendJson(
              out,
              200,
              "{\"ok\":true,\"configured\":${telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()},\"botToken\":\"$masked\",\"chatId\":\"${escapeJson(telegramChatId)}\",\"lastError\":\"${escapeJson(lastTelegramError)}\",\"polling\":$telegramPolling,\"lastTestOk\":$telegramLastTestOk,\"lastTestAtMs\":$telegramLastTestAtMs}",
            )
          }
        }
        path == "/v1/setup/quickstart" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val bot = jsonField(body, "botToken")
            val chat = jsonField(body, "chatId")
            if (bot.isBlank() || chat.isBlank()) {
              sendJson(out, 400, "{\"error\":\"invalid_payload\",\"need\":[\"botToken\",\"chatId\"]}")
            } else {
              setTelegramBotToken(bot)
              applyApprovedTelegramChat(chat)
              telegramPollingRequested = true
              prefs.edit { putBoolean("telegramPollingRequested", true) }
              startTelegramPolling()
              sendJson(out, 200, "{\"ok\":true,\"configured\":true,\"polling\":true,\"message\":\"Telegram quickstart complete\"}")
            }
          }
        }
        path == "/v1/telegram/poll/start" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else if (telegramBotToken.isBlank()) {
            sendJson(out, 400, "{\"error\":\"telegram_not_configured\"}")
          } else {
            telegramPollingRequested = true
            prefs.edit { putBoolean("telegramPollingRequested", true) }
            startTelegramPolling()
            sendJson(out, 200, "{\"ok\":true,\"polling\":true}")
          }
        }
        path == "/v1/telegram/poll/stop" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            telegramPollingRequested = false
            prefs.edit { putBoolean("telegramPollingRequested", false) }
            stopTelegramPolling()
            sendJson(out, 200, "{\"ok\":true,\"polling\":false}")
          }
        }
        path == "/v1/telegram/poll/status" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(
              out,
              200,
              "{\"ok\":true,\"polling\":$telegramPolling,\"requested\":$telegramPollingRequested,\"configured\":${telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()},\"lastUpdateId\":$telegramLastUpdateId,\"handled\":$telegramHandledCount,\"lastError\":\"${escapeJson(lastTelegramError)}\"}",
            )
          }
        }
        path == "/v1/telegram/reset" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            clearTelegramState()
            sendJson(out, 200, "{\"ok\":true,\"message\":\"Telegram config cleared\"}")
          }
        }
        path == "/v1/session" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 200, "{\"ok\":true,\"session\":{\"id\":\"android-local-main\"}}")
          }
        }
        path == "/v1/telegram/update" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val text = jsonField(body, "text")
            val chat = jsonField(body, "chatId").ifBlank { telegramChatId }
            if (text.isBlank()) {
              sendJson(out, 400, "{\"error\":\"invalid_payload\",\"need\":[\"text\"]}")
            } else {
              lastInboundText = capText(text)
              persistTelegramDiagnostics()
              // Minimal pipeline: map inbound Telegram text into a local response.
              lastOutboundText = capText("[android-local] received: $text")
              val sent = if (telegramBotToken.isNotBlank() && chat.isNotBlank()) sendTelegramMessage(chat, lastOutboundText) else false
              val payload =
                "{\"ok\":true,\"received\":\"${escapeJson(text)}\",\"chatId\":\"${escapeJson(chat)}\",\"reply\":\"${escapeJson(lastOutboundText)}\",\"telegramSent\":$sent}"
              sendJson(out, 200, payload)
            }
          }
        }
        path == "/v1/telegram/send" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val text = jsonField(body, "text")
            val chat = jsonField(body, "chatId").ifBlank { telegramChatId }
            if (telegramBotToken.isBlank() || chat.isBlank() || text.isBlank()) {
              sendJson(out, 400, "{\"error\":\"invalid_payload\",\"need\":[\"botToken\",\"chatId\",\"text\"]}")
            } else {
              val sent = sendTelegramMessage(chat, text)
              if (sent) {
                lastOutboundText = capText(text)
                telegramLastTestOk = true
                telegramLastTestAtMs = System.currentTimeMillis()
                telegramLastTestMessage = "Test message sent"
                persistTelegramDiagnostics()
                sendJson(out, 200, "{\"ok\":true,\"sent\":true,\"message\":\"Telegram test sent\"}")
              } else {
                telegramLastTestOk = false
                telegramLastTestAtMs = System.currentTimeMillis()
                telegramLastTestMessage = lastTelegramError.ifBlank { "Telegram send failed" }
                persistTelegramDiagnostics()
                sendJson(
                  out,
                  500,
                  "{\"ok\":false,\"sent\":false,\"message\":\"${escapeJson(telegramLastTestMessage)}\"}",
                )
              }
            }
          }
        }
        path == "/v1/telegram/outbox" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val payload =
              "{\"ok\":true,\"chatId\":\"${escapeJson(telegramChatId)}\",\"lastInbound\":\"${escapeJson(lastInboundText)}\",\"lastOutbound\":\"${escapeJson(lastOutboundText)}\"}"
            sendJson(out, 200, payload)
          }
        }
        path == "/v1/messages" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val escaped = escapeJson(body)
            lastOutboundText = body.take(400)
            sendJson(
              out,
              200,
              "{\"ok\":true,\"echo\":\"$escaped\",\"message\":\"gateway-local accepted request\"}",
            )
          }
        }
        path.startsWith("/v1/") -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 501, "{\"error\":\"not_implemented\",\"message\":\"Gateway protocol adapter pending\"}")
          }
        }
        else -> sendJson(out, 404, "{\"error\":\"not_found\"}")
      }
    }
  }

  private fun readBody(reader: BufferedReader, headers: Map<String, String>): String {
    val len = headers["content-length"]?.toIntOrNull() ?: return ""
    if (len <= 0) return ""
    val chars = CharArray(len)
    var read = 0
    while (read < len) {
      val n = reader.read(chars, read, len - read)
      if (n <= 0) break
      read += n
    }
    return String(chars, 0, read)
  }

  private fun authorized(headers: Map<String, String>): Boolean {
    val auth = headers["authorization"].orEmpty()
    return auth == "Bearer $localToken"
  }

  private fun baseStatusJson(): String =
    "{\"ok\":true," +
      "\"port\":$PORT," +
      "\"running\":${isRunning.get()}," +
      "\"mode\":\"local-gateway\"," +
      "\"kind\":\"android-local-gateway\"," +
      "\"installState\":\"bundled-apk\"," +
      "\"tokenReady\":${localToken.isNotBlank()}," +
      "\"telegramConfigured\":${telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()}," +
      "\"telegramPolling\":$telegramPolling," +
      "\"oauthReady\":${oauthSessionReady()}," +
      "\"oauthPending\":$oauthPending," +
      "\"networkMode\":\"${escapeJson(gatewayNetworkMode)}\"," +
      "\"bindHost\":\"${escapeJson(gatewayBindLabel())}\"," +
      "\"localUrl\":\"${escapeJson(localGatewayUrl())}\"," +
      "\"lanUrl\":\"${escapeJson(lanGatewayUrl())}\"," +
      "\"proofUrl\":\"${escapeJson(localGatewayProofUrl())}\"," +
      "\"chatUrl\":\"${escapeJson(localGatewayChatUrl())}\"," +
      "\"controlUiReady\":false," +
      "\"chatPathReady\":true," +
      "\"chatPathMessage\":\"${escapeJson(chatPathMessage())}\"" +
      "}"

  private fun gatewayStatusJson(message: String = ""): String =
    "{\"ok\":true," +
      "\"running\":${isRunning.get()}," +
      "\"port\":$PORT," +
      "\"kind\":\"android-local-gateway\"," +
      "\"installState\":\"bundled-apk\"," +
      "\"networkMode\":\"${escapeJson(gatewayNetworkMode)}\"," +
      "\"bindHost\":\"${escapeJson(gatewayBindLabel())}\"," +
      "\"localUrl\":\"${escapeJson(localGatewayUrl())}\"," +
      "\"lanUrl\":\"${escapeJson(lanGatewayUrl())}\"," +
      "\"proofUrl\":\"${escapeJson(localGatewayProofUrl())}\"," +
      "\"chatUrl\":\"${escapeJson(localGatewayChatUrl())}\"," +
      "\"controlUiReady\":false," +
      "\"chatPathReady\":true," +
      "\"chatPathMessage\":\"${escapeJson(chatPathMessage())}\"," +
      "\"message\":\"${escapeJson(message)}\"" +
      "}"

  private fun wizardStatusJson(): String {
    cleanupExpiredTelegramPairing()
    return "{\"ok\":true," +
      "\"gateway\":{" +
      "\"running\":${isRunning.get()}," +
      "\"port\":$PORT," +
      "\"tokenReady\":${localToken.isNotBlank()}," +
      "\"kind\":\"android-local-gateway\"," +
      "\"installState\":\"bundled-apk\"," +
      "\"networkMode\":\"${escapeJson(gatewayNetworkMode)}\"," +
      "\"bindHost\":\"${escapeJson(gatewayBindLabel())}\"," +
      "\"localUrl\":\"${escapeJson(localGatewayUrl())}\"," +
      "\"lanUrl\":\"${escapeJson(lanGatewayUrl())}\"," +
      "\"proofUrl\":\"${escapeJson(localGatewayProofUrl())}\"," +
      "\"chatUrl\":\"${escapeJson(localGatewayChatUrl())}\"," +
      "\"controlUiReady\":false," +
      "\"chatPathReady\":true," +
      "\"chatPathMessage\":\"${escapeJson(chatPathMessage())}\"" +
      "}," +
      "\"oauth\":{" +
      "\"ready\":${oauthSessionReady()}," +
      "\"pending\":$oauthPending," +
      "\"deviceCode\":\"${escapeJson(oauthDeviceCode)}\"," +
      "\"userCode\":\"${escapeJson(oauthUserCode)}\"," +
      "\"verificationUri\":\"${escapeJson(oauthVerificationUri)}\"," +
      "\"verificationUriComplete\":\"${escapeJson(oauthVerificationUriComplete)}\"," +
      "\"accountId\":\"${escapeJson(oauthAccountId)}\"," +
      "\"accountLabel\":\"${escapeJson(oauthAccountLabel)}\"," +
      "\"hasRefreshToken\":${oauthRefreshToken.isNotBlank()}," +
      "\"accessExpiresAtMs\":$oauthAccessExpiresAtMs," +
      "\"callbackListening\":${oauthCallbackThread?.isAlive == true}," +
      "\"lastError\":\"${escapeJson(oauthLastError)}\"," +
      "\"startedAtMs\":$oauthStartedAtMs," +
      "\"completedAtMs\":$oauthCompletedAtMs" +
      "}," +
      "\"model\":{" +
      "\"selected\":\"${escapeJson(defaultModel)}\"," +
      "\"ready\":${defaultModel.isNotBlank()}," +
      "\"message\":\"${escapeJson(modelSelectionMessage())}\"," +
      "\"suggested\":" + jsonArrayJson(DEFAULT_MODEL_CHOICES) +
      "}," +
      "\"telegram\":{" +
      "\"configured\":${telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()}," +
      "\"botTokenReady\":${telegramBotToken.isNotBlank()}," +
      "\"polling\":$telegramPolling," +
      "\"requested\":$telegramPollingRequested," +
      "\"chatId\":\"${escapeJson(telegramChatId)}\"," +
      "\"botTokenMasked\":\"${escapeJson(maskToken(telegramBotToken))}\"," +
      "\"handled\":$telegramHandledCount," +
      "\"lastError\":\"${escapeJson(lastTelegramError)}\"," +
      "\"lastInbound\":\"${escapeJson(lastInboundText)}\"," +
      "\"lastOutbound\":\"${escapeJson(lastOutboundText)}\"," +
      "\"lastTestOk\":$telegramLastTestOk," +
      "\"lastTestAtMs\":$telegramLastTestAtMs," +
      "\"lastTestMessage\":\"${escapeJson(telegramLastTestMessage)}\"," +
      "\"pairingApproved\":${telegramChatId.isNotBlank()}," +
      "\"pairingPending\":${telegramPairingCode.isNotBlank()}," +
      "\"pairingCode\":\"${escapeJson(telegramPairingCode)}\"," +
      "\"pairingChatId\":\"${escapeJson(telegramPairingChatId)}\"," +
      "\"pairingRequestedAtMs\":$telegramPairingRequestedAtMs," +
      "\"pairingApprovedAtMs\":$telegramPairingApprovedAtMs," +
      "\"pairingMessage\":\"${escapeJson(telegramPairingMessage())}\"" +
      "}" +
      "}"
  }

  private fun oauthStatusJson(): String =
    "{\"ok\":true," +
      "\"ready\":${oauthSessionReady()}," +
      "\"pending\":$oauthPending," +
      "\"deviceCode\":\"${escapeJson(oauthDeviceCode)}\"," +
      "\"userCode\":\"${escapeJson(oauthUserCode)}\"," +
      "\"verificationUri\":\"${escapeJson(oauthVerificationUri)}\"," +
      "\"verificationUriComplete\":\"${escapeJson(oauthVerificationUriComplete)}\"," +
      "\"accountId\":\"${escapeJson(oauthAccountId)}\"," +
      "\"accountLabel\":\"${escapeJson(oauthAccountLabel)}\"," +
      "\"hasRefreshToken\":${oauthRefreshToken.isNotBlank()}," +
      "\"accessExpiresAtMs\":$oauthAccessExpiresAtMs," +
      "\"callbackListening\":${oauthCallbackThread?.isAlive == true}," +
      "\"lastError\":\"${escapeJson(oauthLastError)}\"," +
      "\"startedAtMs\":$oauthStartedAtMs," +
      "\"completedAtMs\":$oauthCompletedAtMs" +
      "}"

  private fun modelStatusJson(): String =
    "{\"ok\":true," +
      "\"selected\":\"${escapeJson(defaultModel)}\"," +
      "\"ready\":${defaultModel.isNotBlank()}," +
      "\"message\":\"${escapeJson(modelSelectionMessage())}\"," +
      "\"suggested\":" + jsonArrayJson(DEFAULT_MODEL_CHOICES) +
      "}"

  private fun telegramPairingStatusJson(): String {
    cleanupExpiredTelegramPairing()
    return "{\"ok\":true," +
      "\"botTokenReady\":${telegramBotToken.isNotBlank()}," +
      "\"approved\":${telegramChatId.isNotBlank()}," +
      "\"chatId\":\"${escapeJson(telegramChatId)}\"," +
      "\"pending\":${telegramPairingCode.isNotBlank()}," +
      "\"code\":\"${escapeJson(telegramPairingCode)}\"," +
      "\"pendingChatId\":\"${escapeJson(telegramPairingChatId)}\"," +
      "\"requestedAtMs\":$telegramPairingRequestedAtMs," +
      "\"approvedAtMs\":$telegramPairingApprovedAtMs," +
      "\"message\":\"${escapeJson(telegramPairingMessage())}\"" +
      "}"
  }

  private fun persistModelState() {
    prefs.edit { putString("gateway.local.model.default", defaultModel) }
  }

  private fun persistOauthState() {
    securePrefs.putString("gateway.local.oauth.accessToken", oauthAccessToken)
    securePrefs.putString("gateway.local.oauth.refreshToken", oauthRefreshToken)
    securePrefs.putString("gateway.local.oauth.codeVerifier", oauthCodeVerifier)
    prefs.edit {
      putString("gateway.local.oauth.deviceCode", oauthDeviceCode)
      putString("gateway.local.oauth.userCode", oauthUserCode)
      putString("gateway.local.oauth.verificationUri", oauthVerificationUri)
      putString("gateway.local.oauth.verificationUriComplete", oauthVerificationUriComplete)
      putString("gateway.local.oauth.state", oauthState)
      putString("gateway.local.oauth.accountId", oauthAccountId)
      putString("gateway.local.oauth.accountLabel", oauthAccountLabel)
      putBoolean("gateway.local.oauth.pending", oauthPending)
      putString("gateway.local.oauth.lastError", oauthLastError)
      putLong("gateway.local.oauth.accessExpiresAtMs", oauthAccessExpiresAtMs)
      putLong("gateway.local.oauth.startedAtMs", oauthStartedAtMs)
      putLong("gateway.local.oauth.completedAtMs", oauthCompletedAtMs)
    }
  }

  private fun clearOauthState() {
    stopOAuthCallbackListener()
    oauthDeviceCode = ""
    oauthUserCode = ""
    oauthVerificationUri = ""
    oauthVerificationUriComplete = ""
    oauthAccessToken = ""
    oauthRefreshToken = ""
    oauthAccessExpiresAtMs = 0L
    oauthState = ""
    oauthCodeVerifier = ""
    oauthAccountId = ""
    oauthAccountLabel = ""
    oauthPending = false
    oauthLastError = ""
    oauthStartedAtMs = 0L
    oauthCompletedAtMs = 0L
    securePrefs.remove("gateway.local.oauth.accessToken")
    securePrefs.remove("gateway.local.oauth.refreshToken")
    securePrefs.remove("gateway.local.oauth.codeVerifier")
    persistOauthState()
  }

  private fun oauthSessionReady(): Boolean {
    return oauthAccessToken.isNotBlank() &&
      oauthAccessExpiresAtMs > System.currentTimeMillis() + OAUTH_EXPIRY_SKEW_MS
  }

  private fun maybeRefreshOAuthSessionIfNeeded(force: Boolean = false): Boolean {
    synchronized(oauthLock) {
      if (oauthPending || oauthRefreshToken.isBlank()) {
        return oauthSessionReady()
      }
      if (!force && oauthSessionReady()) {
        return true
      }
      return OpenAICodexOAuth.refreshAccessToken(oauthRefreshToken).fold(
        onSuccess = { tokens ->
          applyOAuthTokens(tokens)
          true
        },
        onFailure = { error ->
          oauthAccessToken = ""
          oauthAccessExpiresAtMs = 0L
          oauthLastError = error.message ?: "OAuth refresh failed"
          persistOauthState()
          false
        },
      )
    }
  }

  private fun applyOAuthTokens(tokens: OpenAICodexTokens) {
    oauthAccessToken = tokens.access
    oauthRefreshToken = tokens.refresh
    oauthAccessExpiresAtMs = tokens.expiresAtMs
    oauthAccountId = tokens.accountId
    oauthAccountLabel = tokens.accountId
    oauthPending = false
    oauthLastError = ""
    oauthCompletedAtMs = System.currentTimeMillis()
    oauthState = ""
    oauthCodeVerifier = ""
    oauthDeviceCode = ""
    oauthUserCode = ""
    oauthVerificationUri = ""
    oauthVerificationUriComplete = ""
    persistOauthState()
  }

  private fun completePendingOAuth(code: String?, state: String?): Result<Unit> {
    return runCatching {
      synchronized(oauthLock) {
        val authorizationCode = code?.trim().orEmpty()
        val returnedState = state?.trim().orEmpty()
        if (authorizationCode.isBlank()) {
          error("Missing authorization code from OAuth callback.")
        }
        if (!oauthPending || oauthState.isBlank() || oauthCodeVerifier.isBlank()) {
          error("No active ChatGPT login is waiting for completion.")
        }
        if (returnedState.isBlank() || returnedState != oauthState) {
          error("OAuth state mismatch. Create a fresh QR and try again.")
        }

        val tokens =
          OpenAICodexOAuth.exchangeAuthorizationCode(
            code = authorizationCode,
            verifier = oauthCodeVerifier,
          ).getOrElse { throw it }
        applyOAuthTokens(tokens)
      }
    }.onFailure { error ->
      oauthPending = false
      oauthAccessToken = ""
      oauthAccessExpiresAtMs = 0L
      oauthState = ""
      oauthCodeVerifier = ""
      oauthDeviceCode = ""
      oauthUserCode = ""
      oauthVerificationUri = ""
      oauthVerificationUriComplete = ""
      oauthLastError = error.message ?: "OAuth completion failed"
      persistOauthState()
    }
  }

  private fun startOAuthCallbackListener(): Boolean {
    synchronized(oauthLock) {
      if (oauthCallbackThread?.isAlive == true) {
        return true
      }
      return try {
        val server = ServerSocket(OAUTH_CALLBACK_PORT).apply {
          reuseAddress = true
        }
        oauthCallbackSocket = server
        oauthCallbackThread =
          thread(start = true, name = "openclaw-oauth-callback") {
            try {
              while (!Thread.currentThread().isInterrupted) {
                val socket = server.accept()
                handleOAuthCallback(socket)
              }
            } catch (_: SocketException) {
              // expected while shutting down the callback listener
            } catch (t: Throwable) {
              oauthLastError = t.message ?: "OAuth callback listener crashed"
              persistOauthState()
            } finally {
              try {
                server.close()
              } catch (_: Throwable) {
              }
            }
          }
        true
      } catch (t: Throwable) {
        oauthCallbackSocket = null
        oauthCallbackThread = null
        oauthLastError = t.message ?: "OAuth callback listener failed"
        false
      }
    }
  }

  private fun stopOAuthCallbackListener() {
    synchronized(oauthLock) {
      try {
        oauthCallbackSocket?.close()
      } catch (_: Throwable) {
      }
      oauthCallbackSocket = null
      oauthCallbackThread?.interrupt()
      oauthCallbackThread = null
    }
  }

  private fun handleOAuthCallback(socket: java.net.Socket) {
    socket.use { s ->
      val remote = s.inetAddress
      val reader = BufferedReader(InputStreamReader(s.getInputStream()))
      val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
      val requestLine = reader.readLine() ?: return
      val parts = requestLine.split(" ")
      val method = parts.getOrNull(0) ?: "GET"
      val target = parts.getOrNull(1) ?: "/"

      while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) break
      }

      if (remote != null && !remote.isLoopbackAddress) {
        sendHtml(out, 401, buildErrorHtml("OAuth callback is only accepted from localhost."))
        return
      }

      val callbackPath = target.substringBefore('?')
      val query = target.substringAfter('?', "")
      if (method != "GET") {
        sendHtml(out, 405, buildErrorHtml("Unsupported OAuth callback method."))
        return
      }
      if (callbackPath != OAUTH_CALLBACK_PATH) {
        sendHtml(out, 404, buildErrorHtml("OAuth callback path was not found."))
        return
      }

      val providerError = queryField(query, "error")
      val providerErrorDescription = queryField(query, "error_description")
      if (providerError.isNotBlank()) {
        val message =
          providerErrorDescription.ifBlank { providerError }.ifBlank { "OAuth login was cancelled." }
        oauthPending = false
        oauthState = ""
        oauthCodeVerifier = ""
        oauthDeviceCode = ""
        oauthUserCode = ""
        oauthVerificationUri = ""
        oauthVerificationUriComplete = ""
        oauthLastError = message
        persistOauthState()
        sendHtml(out, 400, buildErrorHtml(message))
        stopOAuthCallbackListener()
        return
      }

      val result =
        completePendingOAuth(
          code = queryField(query, "code"),
          state = queryField(query, "state"),
        )

      result.fold(
        onSuccess = {
          sendHtml(out, 200, OpenAICodexOAuth.SUCCESS_HTML)
          stopOAuthCallbackListener()
        },
        onFailure = { error ->
          sendHtml(
            out,
            400,
            buildErrorHtml(error.message ?: "OAuth callback failed. Return to the app and create a fresh QR."),
          )
          stopOAuthCallbackListener()
        },
      )
    }
  }

  private fun persistTelegramConfig() {
    securePrefs.putString("gateway.local.telegram.botToken", telegramBotToken)
    prefs.edit {
      putString("telegramChatId", telegramChatId)
      putLong("gateway.local.telegram.pairing.approvedAtMs", telegramPairingApprovedAtMs)
    }
    persistTelegramPairingState()
    persistTelegramDiagnostics()
  }

  private fun persistTelegramPairingState() {
    prefs.edit {
      putString("gateway.local.telegram.pairing.code", telegramPairingCode)
      putString("gateway.local.telegram.pairing.chatId", telegramPairingChatId)
      putLong("gateway.local.telegram.pairing.requestedAtMs", telegramPairingRequestedAtMs)
      putLong("gateway.local.telegram.pairing.approvedAtMs", telegramPairingApprovedAtMs)
    }
  }

  private fun persistTelegramDiagnostics() {
    prefs.edit {
      putString("gateway.local.telegram.lastInbound", lastInboundText)
      putString("gateway.local.telegram.lastOutbound", lastOutboundText)
      putString("gateway.local.telegram.lastError", lastTelegramError)
      putBoolean("gateway.local.telegram.lastTestOk", telegramLastTestOk)
      putLong("gateway.local.telegram.lastTestAtMs", telegramLastTestAtMs)
      putString("gateway.local.telegram.lastTestMessage", telegramLastTestMessage)
    }
  }

  private fun clearTelegramState() {
    stopTelegramPolling()
    telegramPollingRequested = false
    telegramBotToken = ""
    telegramChatId = ""
    telegramPairingCode = ""
    telegramPairingChatId = ""
    telegramPairingRequestedAtMs = 0L
    telegramPairingApprovedAtMs = 0L
    telegramLastUpdateId = 0L
    telegramHandledCount = 0L
    lastInboundText = ""
    lastOutboundText = ""
    lastTelegramError = ""
    telegramLastTestOk = false
    telegramLastTestAtMs = 0L
    telegramLastTestMessage = ""
    securePrefs.remove("gateway.local.telegram.botToken")
    prefs.edit {
      putString("telegramChatId", "")
      putBoolean("telegramPollingRequested", false)
      putLong("telegramLastUpdateId", 0L)
      putLong("telegramHandledCount", 0L)
      putString("gateway.local.telegram.lastInbound", "")
      putString("gateway.local.telegram.lastOutbound", "")
      putString("gateway.local.telegram.lastError", "")
      putBoolean("gateway.local.telegram.lastTestOk", false)
      putLong("gateway.local.telegram.lastTestAtMs", 0L)
      putString("gateway.local.telegram.lastTestMessage", "")
      putString("gateway.local.telegram.pairing.code", "")
      putString("gateway.local.telegram.pairing.chatId", "")
      putLong("gateway.local.telegram.pairing.requestedAtMs", 0L)
      putLong("gateway.local.telegram.pairing.approvedAtMs", 0L)
    }
  }

  private fun sendText(out: BufferedWriter, status: Int, body: String) {
    val bytes = body.toByteArray()
    out.write("HTTP/1.1 $status ${statusText(status)}\r\n")
    out.write("Content-Type: text/plain; charset=utf-8\r\n")
    out.write("Content-Length: ${bytes.size}\r\n")
    out.write("Connection: close\r\n\r\n")
    out.write(body)
    out.flush()
  }

  private fun sendJson(out: BufferedWriter, status: Int, body: String) {
    val bytes = body.toByteArray()
    out.write("HTTP/1.1 $status ${statusText(status)}\r\n")
    out.write("Content-Type: application/json; charset=utf-8\r\n")
    out.write("Content-Length: ${bytes.size}\r\n")
    out.write("Connection: close\r\n\r\n")
    out.write(body)
    out.flush()
  }

  private fun sendHtml(out: BufferedWriter, status: Int, body: String) {
    val bytes = body.toByteArray()
    out.write("HTTP/1.1 $status ${statusText(status)}\r\n")
    out.write("Content-Type: text/html; charset=utf-8\r\n")
    out.write("Content-Length: ${bytes.size}\r\n")
    out.write("Connection: close\r\n\r\n")
    out.write(body)
    out.flush()
  }

  private fun statusText(code: Int): String =
    when (code) {
      200 -> "OK"
      400 -> "Bad Request"
      401 -> "Unauthorized"
      405 -> "Method Not Allowed"
      409 -> "Conflict"
      404 -> "Not Found"
      500 -> "Internal Server Error"
      501 -> "Not Implemented"
      503 -> "Service Unavailable"
      else -> "OK"
    }

  private fun jsonField(body: String, key: String): String {
    val re = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
    return re.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
  }

  private fun jsonBooleanField(body: String, key: String): Boolean {
    val re = Regex("\"$key\"\\s*:\\s*(true|false)")
    return re.find(body)?.groupValues?.getOrNull(1)?.equals("true", ignoreCase = true) == true
  }

  private fun escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

  private fun escapeHtml(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")

  private fun buildErrorHtml(message: String): String =
    "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\" />" +
      "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />" +
      "<title>Authentication failed</title></head><body>" +
      "<p>${escapeHtml(message)}</p></body></html>"

  private fun buildGatewayProofHtml(sessionKey: String = "main", desktopChatRoute: Boolean = false): String {
    cleanupExpiredTelegramPairing()
    val sessionLabel = sessionKey.trim().ifEmpty { "main" }
    val gatewayUrl = localGatewayUrl()
    val proofUrl = localGatewayProofUrl()
    val chatUrl = localGatewayChatUrl(sessionLabel)
    val title =
      if (desktopChatRoute) {
        "OpenClaw Android local chat route"
      } else {
        "OpenClaw Android local gateway"
      }
    val routeNote =
      if (desktopChatRoute) {
        "You opened the local chat route for session <code>${escapeHtml(sessionLabel)}</code>. In this Android build it serves a lightweight local status page."
      } else {
        "This page proves the Android-local gateway is running on <code>$gatewayUrl</code>."
      }
    val oauthState =
      when {
        oauthSessionReady() -> "ready"
        oauthPending -> "pending"
        else -> "not linked"
      }
    val telegramState =
      when {
        telegramChatId.isNotBlank() -> "approved chat $telegramChatId"
        telegramPairingCode.isNotBlank() -> "pending code $telegramPairingCode"
        telegramBotToken.isNotBlank() -> "token saved, waiting for /start"
        else -> "not configured"
      }
    return """
      <!doctype html>
      <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>$title</title>
        <style>
          :root { color-scheme: light; }
          body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #f5f6f7; color: #101418; }
          main { max-width: 760px; margin: 0 auto; padding: 24px 18px 40px; }
          .card { background: #ffffff; border: 1px solid #d7dce1; border-radius: 16px; padding: 18px; margin-top: 14px; }
          h1 { margin: 0 0 10px; font-size: 28px; line-height: 1.15; }
          h2 { margin: 0 0 10px; font-size: 18px; }
          p, li { line-height: 1.5; }
          code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; background: #eef2f5; padding: 2px 6px; border-radius: 6px; }
          ul { padding-left: 18px; margin: 10px 0 0; }
          .warn { background: #fff3e6; border-color: #f2c48d; }
        </style>
      </head>
      <body>
        <main>
          <div class="card">
            <h1>$title</h1>
            <p>$routeNote</p>
            <p>${escapeHtml(chatPathMessage())}</p>
          </div>
          <div class="card warn">
            <h2>Android local runtime</h2>
            <p>This APK runs the gateway directly on the phone. Use the in-app wizard to finish OAuth, choose a model, and pair Telegram.</p>
          </div>
          <div class="card">
            <h2>Runtime status</h2>
            <ul>
              <li>Install: <code>bundled-apk</code></li>
              <li>Running: <code>${isRunning.get()}</code></li>
              <li>Bind: <code>${escapeHtml(gatewayBindLabel())}:$PORT</code></li>
              <li>OAuth: <code>${escapeHtml(oauthState)}</code></li>
              <li>Default model: <code>${escapeHtml(defaultModel.ifBlank { "not selected" })}</code></li>
              <li>Telegram: <code>${escapeHtml(telegramState)}</code></li>
            </ul>
          </div>
          <div class="card">
            <h2>Useful local routes</h2>
            <ul>
              <li><a href="$proofUrl">$proofUrl</a></li>
              <li><a href="$chatUrl">$chatUrl</a></li>
              <li><a href="/health">/health</a></li>
              <li><a href="/status">/status</a></li>
            </ul>
          </div>
        </main>
      </body>
      </html>
    """.trimIndent()
  }

  private fun queryField(query: String, key: String): String {
    if (query.isBlank()) return ""
    val prefix = "$key="
    return query
      .split("&")
      .firstOrNull { it.startsWith(prefix) }
      ?.substringAfter('=')
      ?.let { URLDecoder.decode(it, "UTF-8") }
      ?.trim()
      .orEmpty()
  }

  private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

  private fun gatewayBindHost(): String =
    when (gatewayNetworkMode) {
      NETWORK_MODE_LAN -> "0.0.0.0"
      else -> "127.0.0.1"
    }

  private fun gatewayBindLabel(): String =
    when (gatewayNetworkMode) {
      NETWORK_MODE_LAN -> "0.0.0.0"
      else -> "127.0.0.1"
    }

  private fun localGatewayUrl(): String = "http://127.0.0.1:$PORT"

  private fun localGatewayProofUrl(): String = "${localGatewayUrl()}/proof"

  private fun localGatewayChatUrl(sessionKey: String = "main"): String =
    "${localGatewayUrl()}/chat?session=${urlEncode(sessionKey)}"

  private fun lanGatewayUrl(): String {
    val host = discoverLanIpv4Address().ifBlank { return "" }
    return "http://$host:$PORT"
  }

  private fun discoverLanIpv4Address(): String {
    return try {
      val interfaces =
        NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()
      val siteLocal =
        interfaces
          .asSequence()
          .filter { it.isUp && !it.isLoopback && !it.isVirtual }
          .flatMap { iface -> Collections.list(iface.inetAddresses).asSequence() }
          .filterIsInstance<Inet4Address>()
          .filter { !it.isLoopbackAddress && !it.hostAddress.orEmpty().startsWith("169.254.") }
          .firstOrNull { it.isSiteLocalAddress }
      val fallback =
        interfaces
          .asSequence()
          .filter { it.isUp && !it.isLoopback && !it.isVirtual }
          .flatMap { iface -> Collections.list(iface.inetAddresses).asSequence() }
          .filterIsInstance<Inet4Address>()
          .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.orEmpty().startsWith("169.254.") }
      (siteLocal ?: fallback)?.hostAddress.orEmpty()
    } catch (_: Throwable) {
      ""
    }
  }

  private fun sanitizeNetworkMode(value: String?): String {
    return when (value?.trim()?.lowercase()) {
      NETWORK_MODE_LAN -> NETWORK_MODE_LAN
      else -> NETWORK_MODE_LOCAL
    }
  }

  private fun maskToken(token: String): String {
    if (token.length <= 8) return token
    return token.take(4) + "****" + token.takeLast(4)
  }

  private fun capText(value: String, maxLen: Int = 500): String {
    val trimmed = value.trim()
    return if (trimmed.length <= maxLen) trimmed else trimmed.take(maxLen)
  }

  private fun chatPathMessage(): String =
    "The Android build exposes /chat?session=... as a local proof and status page so you can confirm the gateway is alive on this phone."

  private fun modelSelectionMessage(): String =
    if (defaultModel.isBlank()) {
      "Choose the default model after OAuth so this Android gateway can answer requests."
    } else {
      "Default model stored locally as $defaultModel."
    }

  private fun telegramPairingMessage(): String =
    when {
      telegramChatId.isNotBlank() ->
        "Pairing approved for chat $telegramChatId. This Android local gateway will only answer that Telegram DM."
      telegramPairingCode.isNotBlank() ->
        "Pending pairing code ${telegramPairingCode}. Send /start to the bot, then approve this code in the app."
      telegramBotToken.isBlank() ->
        "Save the Telegram bot token first."
      else ->
        "Bot token saved. Send /start to the bot so the app can generate a pairing code."
    }

  private fun jsonArrayJson(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { "\"${escapeJson(it)}\"" }

  private fun setTelegramBotToken(botToken: String) {
    val next = botToken.trim()
    val changed = telegramBotToken != next
    telegramBotToken = next
    if (changed) {
      telegramChatId = ""
      telegramPairingApprovedAtMs = 0L
      clearPendingTelegramPairing()
      telegramLastUpdateId = 0L
      telegramLastTestOk = false
      telegramLastTestAtMs = 0L
      telegramLastTestMessage = ""
    }
    persistTelegramConfig()
  }

  private fun clearPendingTelegramPairing() {
    telegramPairingCode = ""
    telegramPairingChatId = ""
    telegramPairingRequestedAtMs = 0L
    persistTelegramPairingState()
  }

  private fun applyApprovedTelegramChat(chatId: String) {
    telegramChatId = chatId.trim()
    telegramPairingApprovedAtMs = System.currentTimeMillis()
    val pendingChat = telegramPairingChatId
    clearPendingTelegramPairing()
    persistTelegramConfig()
    if (telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()) {
      val notifyText =
        if (pendingChat == telegramChatId) {
          "✅ Telegram pairing approved from the Android app. You can now DM this bot."
        } else {
          "✅ Telegram chat approved from the Android app."
        }
      sendTelegramMessage(telegramChatId, notifyText)
    }
  }

  private fun cleanupExpiredTelegramPairing() {
    if (telegramPairingCode.isBlank()) return
    val ageMs = System.currentTimeMillis() - telegramPairingRequestedAtMs
    if (telegramPairingRequestedAtMs <= 0L || ageMs >= TELEGRAM_PAIRING_EXPIRY_MS) {
      clearPendingTelegramPairing()
    }
  }

  private fun generateTelegramPairingCode(length: Int = 8): String =
    buildString {
      repeat(length) {
        val index = (Math.random() * TELEGRAM_PAIRING_ALPHABET.length).toInt()
        append(TELEGRAM_PAIRING_ALPHABET[index])
      }
    }

  private fun ensureTelegramPairingRequest(chatId: String): String {
    cleanupExpiredTelegramPairing()
    val now = System.currentTimeMillis()
    if (
      telegramPairingCode.isNotBlank() &&
      telegramPairingChatId.isNotBlank() &&
      telegramPairingChatId != chatId
    ) {
      return "⏳ Another Telegram pairing request is already waiting in the app. Ask the owner to approve it or retry later."
    }
    if (telegramPairingCode.isBlank() || telegramPairingChatId != chatId) {
      telegramPairingCode = generateTelegramPairingCode()
      telegramPairingChatId = chatId
      telegramPairingRequestedAtMs = now
      persistTelegramPairingState()
    }
    return "🔐 Pairing required. Approval code: $telegramPairingCode. Open the Android app and tap Approve Pairing."
  }

  private fun sendTelegramMessage(chatId: String, text: String): Boolean {
    return try {
      val endpoint = "https://api.telegram.org/bot$telegramBotToken/sendMessage"
      val payload =
        "chat_id=" + URLEncoder.encode(chatId, "UTF-8") +
          "&text=" + URLEncoder.encode(text, "UTF-8")
      val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5000
        readTimeout = 5000
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      }
      conn.outputStream.use { it.write(payload.toByteArray()) }
      val code = conn.responseCode
      val body =
        (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
          .orEmpty()
      val ok = code in 200..299 && body.contains("\"ok\":true")
      if (!ok) {
        lastTelegramError = body.ifBlank { "sendMessage failed (code=$code)" }
      } else {
        lastTelegramError = ""
      }
      persistTelegramDiagnostics()
      ok
    } catch (t: Throwable) {
      lastTelegramError = t.message ?: t.javaClass.simpleName
      persistTelegramDiagnostics()
      false
    }
  }

  private fun startTelegramPolling() {
    if (telegramPolling) return
    telegramPolling = true
    telegramPollThread =
      thread(start = true, name = "openclaw-telegram-poll") {
        var backoffMs = 1500L
        while (telegramPolling) {
          try {
            val updates = fetchTelegramUpdates()
            if (updates.isNotEmpty()) {
              backoffMs = 800L
            }
            for (u in updates) {
              if (u.updateId > telegramLastUpdateId) {
                telegramLastUpdateId = u.updateId
                prefs.edit { putLong("telegramLastUpdateId", telegramLastUpdateId) }
              }
              if (u.text.isNotBlank()) {
                handleIncomingTelegramMessage(u.chatId, u.text)
                telegramHandledCount += 1
                prefs.edit { putLong("telegramHandledCount", telegramHandledCount) }
              }
            }
          } catch (t: Throwable) {
            lastTelegramError = t.message ?: t.javaClass.simpleName
            backoffMs = (backoffMs * 2).coerceAtMost(15000L)
          }
          try {
            Thread.sleep(backoffMs)
          } catch (_: InterruptedException) {
            break
          }
        }
      }
  }

  private fun stopTelegramPolling() {
    telegramPolling = false
    telegramPollThread?.interrupt()
    telegramPollThread = null
  }

  private fun handleIncomingTelegramMessage(chatId: String, text: String) {
    lastInboundText = capText(text)
    val trimmed = text.trim()
    if (telegramChatId.isBlank() || telegramChatId != chatId) {
      lastOutboundText = capText(ensureTelegramPairingRequest(chatId))
      persistTelegramDiagnostics()
      sendTelegramMessage(chatId, lastOutboundText)
      return
    }
    lastOutboundText =
      capText(
        when {
          trimmed.equals("/start", ignoreCase = true) ->
            "✅ OpenClaw Android local gateway is online on this phone. OAuth, model selection, and Telegram pairing are managed in the app."
          trimmed.equals("/status", ignoreCase = true) ->
            "📡 Gateway running=${isRunning.get()} | oauth=${oauthSessionReady()} | model=${defaultModel.ifBlank { "not selected" }} | poll=$telegramPolling"
          trimmed.equals("/help", ignoreCase = true) ->
            "Commands: /start /status /help. Use the Android app to manage OAuth, model selection, and pairing."
          else ->
            "[android-local gateway] received: $trimmed\nBasic Telegram routing is active on this phone."
        },
      )
    persistTelegramDiagnostics()
    sendTelegramMessage(chatId, lastOutboundText)
  }

  private data class TelegramUpdate(val updateId: Long, val chatId: String, val text: String)

  private fun fetchTelegramUpdates(): List<TelegramUpdate> {
    if (telegramBotToken.isBlank()) return emptyList()
    val endpoint =
      "https://api.telegram.org/bot$telegramBotToken/getUpdates?timeout=10&offset=${telegramLastUpdateId + 1}"
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 5000
      readTimeout = 15000
    }
    val code = conn.responseCode
    val body =
      (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        .orEmpty()
    if (code !in 200..299 || !body.contains("\"ok\":true")) {
      if (body.isNotBlank()) lastTelegramError = body
      persistTelegramDiagnostics()
      return emptyList()
    }

    val root = json.parseToJsonElement(body).jsonObject
    val result = root["result"]?.jsonArray ?: return emptyList()
    val out = mutableListOf<TelegramUpdate>()
    result.forEach { item ->
      val obj = item.jsonObject
      val updateId = obj["update_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
      val msg = obj["message"]?.jsonObject ?: return@forEach
      val text = msg["text"]?.jsonPrimitive?.content.orEmpty()
      val chatId = msg["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.content.orEmpty()
      if (chatId.isNotBlank()) {
        out += TelegramUpdate(updateId = updateId, chatId = chatId, text = text)
      }
    }
    return out
  }

  private fun ensureChannel() {
    val mgr = getSystemService(NotificationManager::class.java)
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Local Gateway",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "OpenClaw local gateway status"
        setShowBadge(false)
      }
    mgr.createNotificationChannel(channel)
  }

  private fun buildNotification(text: String): Notification {
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val launchPending =
      PendingIntent.getActivity(
        this,
        30,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent = Intent(this, GatewayLocalService::class.java).setAction(ACTION_STOP)
    val stopPending =
      PendingIntent.getService(
        this,
        31,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("OpenClaw Gateway (Local)")
      .setContentText(text)
      .setContentIntent(launchPending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, "Stop", stopPending)
      .build()
  }

  private fun updateNotification(text: String) {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.notify(NOTIFICATION_ID, buildNotification(text))
  }

  companion object {
    private const val CHANNEL_ID = "gateway-local"
    private const val NOTIFICATION_ID = 41
    private const val ACTION_STOP = "ai.openclaw.app.action.GATEWAY_LOCAL_STOP"
    private const val EXTRA_NETWORK_MODE = "ai.openclaw.app.extra.GATEWAY_NETWORK_MODE"
    private const val OAUTH_CALLBACK_PORT = 1455
    private const val OAUTH_CALLBACK_PATH = "/auth/callback"
    private const val OAUTH_EXPIRY_SKEW_MS = 60_000L
    private const val TELEGRAM_PAIRING_EXPIRY_MS = 60 * 60 * 1000L
    private const val TELEGRAM_PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val NETWORK_MODE_LOCAL = "local"
    const val NETWORK_MODE_LAN = "lan"
    const val PORT = 18789
    val DEFAULT_MODEL_CHOICES =
      listOf(
        "openai-codex/gpt-5.4",
        "openai-codex/gpt-5.3-codex",
        "openai-codex/gpt-5.2-codex",
      )

    private val isRunning = AtomicBoolean(false)
    private val tokenRef = AtomicReference("")

    fun running(): Boolean = isRunning.get()

    fun currentToken(): String = tokenRef.get()

    fun start(context: Context, networkMode: String? = null) {
      val intent =
        Intent(context, GatewayLocalService::class.java).apply {
          networkMode?.trim()?.takeIf { it.isNotEmpty() }?.let {
            putExtra(EXTRA_NETWORK_MODE, it)
          }
        }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, GatewayLocalService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }
  }
}
