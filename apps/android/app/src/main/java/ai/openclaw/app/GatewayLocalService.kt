package ai.openclaw.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
  private var localToken: String = ""

  override fun onCreate() {
    super.onCreate()
    localToken = UUID.randomUUID().toString().replace("-", "")
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
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startServer() {
    if (isRunning.get()) return

    serverThread =
      thread(start = true, name = "openclaw-local-gateway") {
        try {
          // Bind all interfaces for LAN reachability on Android device.
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

      when {
        path == "/" -> sendText(out, 200, "OpenClaw Android local gateway alive\n")
        path == "/health" -> sendJson(out, 200, "{\"ok\":true,\"service\":\"gateway-local\"}")
        path == "/status" -> {
          val body =
            "{\"ok\":true,\"port\":$PORT,\"running\":${isRunning.get()},\"mode\":\"scaffold\"}"
          sendJson(out, 200, body)
        }
        path == "/token" && method == "GET" -> {
          // Local-only bootstrap helper for operator during dev.
          sendJson(out, 200, "{\"token\":\"$localToken\"}")
        }
        path.startsWith("/v1/") -> {
          val auth = headers["authorization"].orEmpty()
          val expected = "Bearer $localToken"
          if (auth != expected) {
            sendJson(out, 401, "{\"error\":\"unauthorized\"}")
          } else {
            sendJson(out, 501, "{\"error\":\"not_implemented\",\"message\":\"Gateway protocol adapter pending\"}")
          }
        }
        else -> sendJson(out, 404, "{\"error\":\"not_found\"}")
      }
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

  private fun statusText(code: Int): String =
    when (code) {
      200 -> "OK"
      401 -> "Unauthorized"
      404 -> "Not Found"
      501 -> "Not Implemented"
      else -> "OK"
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

    fun running(): Boolean = isRunning.get()

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
