package ai.openclaw.app

import android.app.Application
import android.os.StrictMode

class NodeApp : Application() {
  val runtime: NodeRuntime by lazy { NodeRuntime(this) }

  override fun onCreate() {
    super.onCreate()
    // Experimental fork: keep Android-local gateway service alive without Termux/proot.
    GatewayLocalService.start(this)
    if (BuildConfig.DEBUG) {
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
          .detectAll()
          .penaltyLog()
          .build(),
      )
      StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
          .detectAll()
          .penaltyLog()
          .build(),
      )
    }
  }
}
