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
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Phase-1 Android-local gateway spike:
 * - Runs as foreground service
 * - Binds local TCP 18789
 * - Responds simple HTTP 200 on any request
 *
 * This proves the app can host a long-running local endpoint without Termux/proot.
 */
class GatewayLocalService : Service() {
  private var serverThread: Thread? = null
  private var serverSocket: ServerSocket? = null

  override fun onCreate() {
    super.onCreate()
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
      val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
      val body = "OpenClaw Android local gateway stub alive\n"
      out.write("HTTP/1.1 200 OK\r\n")
      out.write("Content-Type: text/plain; charset=utf-8\r\n")
      out.write("Content-Length: ${body.toByteArray().size}\r\n")
      out.write("Connection: close\r\n\r\n")
      out.write(body)
      out.flush()
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
