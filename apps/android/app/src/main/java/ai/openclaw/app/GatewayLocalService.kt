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
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Android-local gateway scaffold (phase-2):
 * - Runs as foreground service
 * - Binds local TCP 18789
 * - Exposes basic HTTP endpoints for health/status/token
 *
 * NOTE: still not full OpenClaw gateway protocol yet.
 */
class GatewayLocalService : Service() {
  private var serverThread: Thread? = null
  private var serverSocket: ServerSocket? = null
  private val prefs by lazy { applicationContext.getSharedPreferences("openclaw.gateway.local", Context.MODE_PRIVATE) }
  private var localToken: String = ""
  private var telegramBotToken: String = ""
  private var telegramChatId: String = ""
  private var oauthDeviceCode: String = ""
  private var oauthUserCode: String = ""
  private var oauthAccessToken: String = ""
  private var lastInboundText: String = ""
  private var lastOutboundText: String = ""
  private var lastTelegramError: String = ""

  override fun onCreate() {
    super.onCreate()
    localToken = prefs.getString("localToken", null)?.takeIf { it.isNotBlank() }
      ?: UUID.randomUUID().toString().replace("-", "")
    telegramBotToken = prefs.getString("telegramBotToken", "") ?: ""
    telegramChatId = prefs.getString("telegramChatId", "") ?: ""
    oauthAccessToken = prefs.getString("oauthAccessToken", "") ?: ""

    prefs.edit { putString("localToken", localToken) }
    tokenRef.set(localToken)
    ensureChannel()
    startForeground(NOTIFICATION_ID, buildNotification("Starting local gateway…"))
    startServer()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopSelf()
        return START_NOT_STICKY
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    stopServer()
    isRunning.set(false)
    tokenRef.set("")
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startServer() {
    if (isRunning.get()) return

    serverThread =
      thread(start = true, name = "openclaw-local-gateway") {
        try {
          val ss = ServerSocket(PORT)
          serverSocket = ss
          isRunning.set(true)
          updateNotification("Local gateway listening on :$PORT")

          while (!Thread.currentThread().isInterrupted) {
            val socket = ss.accept()
            handleClient(socket)
          }
        } catch (_: SocketException) {
          // expected when socket closes during stop
        } catch (t: Throwable) {
          updateNotification("Gateway crashed: ${t.javaClass.simpleName}")
          isRunning.set(false)
        }
      }
  }

  private fun stopServer() {
    try {
      serverSocket?.close()
    } catch (_: Throwable) {
    }
    serverSocket = null
    serverThread?.interrupt()
    serverThread = null
  }

  private fun handleClient(socket: java.net.Socket) {
    socket.use { s ->
      val reader = BufferedReader(InputStreamReader(s.getInputStream()))
      val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))

      val requestLine = reader.readLine() ?: return
      val parts = requestLine.split(" ")
      val method = parts.getOrNull(0) ?: "GET"
      val path = parts.getOrNull(1) ?: "/"

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
        path == "/" -> sendText(out, 200, "OpenClaw Android local gateway alive\n")
        path == "/health" -> sendJson(out, 200, "{\"ok\":true,\"service\":\"gateway-local\"}")
        path == "/status" -> {
          val payload =
            "{\"ok\":true,\"port\":$PORT,\"running\":${isRunning.get()},\"mode\":\"scaffold\",\"tokenReady\":${localToken.isNotBlank()},\"telegramConfigured\":${telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()},\"oauthReady\":${oauthAccessToken.isNotBlank()}}"
          sendJson(out, 200, payload)
        }
        path == "/token" && method == "GET" -> {
          sendJson(out, 200, "{\"token\":\"$localToken\"}")
        }
        path == "/v1/oauth/device/start" && method == "POST" -> {
          val clientId = jsonField(body, "clientId").ifBlank { "openclaw-android-local" }
          oauthDeviceCode = UUID.randomUUID().toString().replace("-", "")
          oauthUserCode = oauthDeviceCode.take(6).uppercase()
          val payload =
            "{\"ok\":true,\"clientId\":\"${escapeJson(clientId)}\",\"deviceCode\":\"$oauthDeviceCode\",\"userCode\":\"$oauthUserCode\",\"verificationUri\":\"openclaw://local-oauth\"}"
          sendJson(out, 200, payload)
        }
        path == "/v1/oauth/device/complete" && method == "POST" -> {
          val deviceCode = jsonField(body, "deviceCode")
          if (deviceCode.isBlank() || deviceCode != oauthDeviceCode) {
            sendJson(out, 400, "{\"ok\":false,\"error\":\"invalid_device_code\"}")
          } else {
            oauthAccessToken = UUID.randomUUID().toString().replace("-", "")
            prefs.edit { putString("oauthAccessToken", oauthAccessToken) }
            sendJson(out, 200, "{\"ok\":true,\"accessToken\":\"$oauthAccessToken\",\"tokenType\":\"Bearer\"}")
          }
        }
        path == "/v1/oauth/status" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 200, "{\"ok\":true,\"ready\":${oauthAccessToken.isNotBlank()},\"userCode\":\"$oauthUserCode\"}")
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
            sendJson(out, 200, "{\"ok\":true,\"running\":${isRunning.get()},\"port\":$PORT}")
          }
        }
        path == "/v1/config/telegram" && method == "POST" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val bot = jsonField(body, "botToken")
            val chat = jsonField(body, "chatId")
            if (bot.isBlank() || chat.isBlank()) {
              sendJson(out, 400, "{\"error\":\"invalid_payload\",\"need\":[\"botToken\",\"chatId\"]}")
            } else {
              telegramBotToken = bot
              telegramChatId = chat
              prefs.edit {
                putString("telegramBotToken", telegramBotToken)
                putString("telegramChatId", telegramChatId)
              }
              sendJson(out, 200, "{\"ok\":true,\"configured\":true}")
            }
          }
        }
        path == "/v1/config/telegram" && method == "GET" -> {
          if (!authorized(headers)) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            val masked = maskToken(telegramBotToken)
            sendJson(out, 200, "{\"ok\":true,\"configured\":${telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()},\"botToken\":\"$masked\",\"chatId\":\"${telegramChatId}\",\"lastError\":\"${escapeJson(lastTelegramError)}\"}")
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
              lastInboundText = text
              // Minimal pipeline: map inbound Telegram text into a local response.
              lastOutboundText = "[android-local] received: $text"
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
                lastOutboundText = text
                sendJson(out, 200, "{\"ok\":true,\"sent\":true}")
              } else {
                sendJson(out, 500, "{\"ok\":false,\"sent\":false}")
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
    return auth == "Bearer $localToken" || (oauthAccessToken.isNotBlank() && auth == "Bearer $oauthAccessToken")
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

  private fun statusText(code: Int): String =
    when (code) {
      200 -> "OK"
      400 -> "Bad Request"
      401 -> "Unauthorized"
      404 -> "Not Found"
      500 -> "Internal Server Error"
      501 -> "Not Implemented"
      else -> "OK"
    }

  private fun jsonField(body: String, key: String): String {
    val re = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
    return re.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
  }

  private fun escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

  private fun maskToken(token: String): String {
    if (token.length <= 8) return token
    return token.take(4) + "****" + token.takeLast(4)
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
      ok
    } catch (t: Throwable) {
      lastTelegramError = t.message ?: t.javaClass.simpleName
      false
    }
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
    const val PORT = 18789

    private val isRunning = AtomicBoolean(false)
    private val tokenRef = AtomicReference("")

    fun running(): Boolean = isRunning.get()

    fun currentToken(): String = tokenRef.get()

    fun start(context: Context) {
      val intent = Intent(context, GatewayLocalService::class.java)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, GatewayLocalService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }
  }
}
