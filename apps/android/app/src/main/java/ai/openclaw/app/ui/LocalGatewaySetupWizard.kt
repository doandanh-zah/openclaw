package ai.openclaw.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.openclaw.app.GatewayLocalService
import ai.openclaw.app.LocalGatewayClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WizardStepState {
  Pending,
  Running,
  Done,
  Error,
}

@Composable
fun LocalGatewaySetupWizard(
  modifier: Modifier = Modifier,
  onContinue: () -> Unit,
  onSkip: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var snapshot by remember { mutableStateOf<LocalGatewayClient.WizardSnapshot?>(null) }
  var snapshotError by rememberSaveable { mutableStateOf("") }
  var statusMessage by rememberSaveable { mutableStateOf("Checking local gateway…") }

  var botTokenInput by rememberSaveable { mutableStateOf("") }
  var selectedModelInput by rememberSaveable { mutableStateOf("") }
  var customModelInput by rememberSaveable { mutableStateOf("") }
  var pairingCodeInput by rememberSaveable { mutableStateOf("") }
  var gatewayNetworkModeInput by rememberSaveable { mutableStateOf("") }
  var gatewayTokenValue by rememberSaveable { mutableStateOf("") }

  var gatewayBusy by remember { mutableStateOf(false) }
  var oauthBusy by remember { mutableStateOf(false) }
  var telegramBusy by remember { mutableStateOf(false) }
  var telegramDiscoverBusy by remember { mutableStateOf(false) }
  var autoBusy by remember { mutableStateOf(false) }

  suspend fun refreshSnapshot() {
    val result =
      withContext(Dispatchers.IO) {
        LocalGatewayClient.fetchWizardSnapshot()
      }
    if (result.ok && result.value != null) {
      snapshot = result.value
      snapshotError = ""
      if (gatewayNetworkModeInput.isBlank()) {
        gatewayNetworkModeInput = result.value.gateway.networkMode
      }
      if (result.value.gateway.tokenReady) {
        val token =
          withContext(Dispatchers.IO) {
            LocalGatewayClient.getLocalToken()
          }
        if (token.isNotBlank()) {
          gatewayTokenValue = token
        }
      }
      if (result.value.telegram.pairingApproved) {
        pairingCodeInput = ""
      } else if (pairingCodeInput.isBlank() && result.value.telegram.pairingCode.isNotBlank()) {
        pairingCodeInput = result.value.telegram.pairingCode
      }
      if (selectedModelInput.isBlank() && customModelInput.isBlank() && result.value.model.selected.isNotBlank()) {
        if (result.value.model.selected in result.value.model.suggested) {
          selectedModelInput = result.value.model.selected
        } else {
          customModelInput = result.value.model.selected
        }
      }
    } else if (result.message.isNotBlank()) {
      snapshotError = result.message
    }
  }

  suspend fun startGateway(): Boolean {
    val selectedNetworkMode =
      gatewayNetworkModeInput.ifBlank {
        snapshot?.gateway?.networkMode ?: GatewayLocalService.NETWORK_MODE_LOCAL
      }
    gatewayBusy = true
    statusMessage =
      if (selectedNetworkMode == GatewayLocalService.NETWORK_MODE_LAN) {
        "Starting local gateway for devices on the same Wi-Fi…"
      } else {
        "Starting local gateway on this phone…"
      }
    return try {
      GatewayLocalService.start(context, networkMode = selectedNetworkMode)
      val result =
        withContext(Dispatchers.IO) {
          LocalGatewayClient.waitForGatewayReady()
        }
      if (result.ok && result.value != null) {
        snapshot = result.value
        snapshotError = ""
        gatewayNetworkModeInput = result.value.gateway.networkMode
        statusMessage =
          if (result.value.gateway.networkMode == GatewayLocalService.NETWORK_MODE_LAN) {
            result.value.gateway.lanUrl.ifBlank {
              "Local gateway is running in LAN mode on port ${result.value.gateway.port}."
            }
          } else {
            result.value.gateway.localUrl.ifBlank {
              "Local gateway is running on 127.0.0.1:${result.value.gateway.port}."
            }
          }
        true
      } else {
        snapshotError = result.message
        statusMessage = result.message.ifBlank { "Local gateway did not respond." }
        false
      }
    } finally {
      gatewayBusy = false
    }
  }

  suspend fun ensureGatewayReadyForSetup(): Boolean {
    val ready = snapshot?.gateway?.running == true && snapshot?.gateway?.tokenReady == true
    return if (ready) {
      true
    } else {
      startGateway()
    }
  }

  suspend fun startOAuth(): Boolean {
    if (!ensureGatewayReadyForSetup()) {
      return false
    }
    oauthBusy = true
    statusMessage = "Preparing ChatGPT login QR…"
    return try {
      val result =
        withContext(Dispatchers.IO) {
          LocalGatewayClient.startOAuthDeviceFlow()
        }
      if (result.ok) {
        refreshSnapshot()
        statusMessage =
          "QR login is ready. Open the link or scan the QR. This app will finish the login automatically after the browser callback."
        true
      } else {
        statusMessage = result.message.ifBlank { "Failed to prepare OAuth login." }
        false
      }
    } finally {
      oauthBusy = false
    }
  }

  suspend fun checkOAuthStatus(): Boolean {
    oauthBusy = true
    statusMessage = "Checking ChatGPT login status…"
    return try {
      refreshSnapshot()
      when {
        snapshot?.oauth?.ready == true -> {
          statusMessage = "ChatGPT login completed. Refresh token is stored locally."
          true
        }
        snapshot?.oauth?.lastError?.isNotBlank() == true -> {
          statusMessage = snapshot?.oauth?.lastError.orEmpty()
          false
        }
        snapshot?.oauth?.pending == true -> {
          statusMessage = "Still waiting for the browser callback. Finish the login in the browser, then come back here."
          false
        }
        else -> {
          statusMessage = "No active ChatGPT login is waiting. Create a fresh QR to continue."
          false
        }
      }
    } finally {
      oauthBusy = false
    }
  }

  suspend fun resetOAuth() {
    oauthBusy = true
    statusMessage = "Resetting OAuth state…"
    try {
      val result =
        withContext(Dispatchers.IO) {
          LocalGatewayClient.resetOAuth()
        }
      refreshSnapshot()
      statusMessage = result.message.ifBlank { if (result.ok) "OAuth reset." else "OAuth reset failed." }
    } finally {
      oauthBusy = false
    }
  }

  suspend fun saveDefaultModel(): Boolean {
    val chosenModel =
      customModelInput.trim().ifBlank {
        selectedModelInput.trim()
      }
    if (chosenModel.isBlank()) {
      statusMessage = "Choose a suggested model or type a custom provider/model value."
      return false
    }
    if (!ensureGatewayReadyForSetup()) {
      return false
    }
    oauthBusy = true
    statusMessage = "Saving default model…"
    return try {
      val result =
        withContext(Dispatchers.IO) {
          LocalGatewayClient.setDefaultModel(chosenModel)
        }
      refreshSnapshot()
      statusMessage = result.message.ifBlank { if (result.ok) "Default model saved." else "Model save failed." }
      result.ok
    } finally {
      oauthBusy = false
    }
  }

  suspend fun saveTelegramBotAndStart(): Boolean {
    val botToken = botTokenInput.trim()
    if (botToken.isBlank()) {
      statusMessage = "Paste Telegram Bot Token first."
      return false
    }
    if (!ensureGatewayReadyForSetup()) {
      return false
    }
    telegramBusy = true
    statusMessage = "Saving Telegram bot token and starting polling…"
    return try {
      val result =
        withContext(Dispatchers.IO) {
          LocalGatewayClient.saveTelegramBotToken(botToken = botToken, startPolling = true)
        }
      refreshSnapshot()
      statusMessage =
        result.message.ifBlank {
          if (result.ok) {
            "Telegram bot is live. Send /start to your bot, then approve the pairing code in this app."
          } else {
            "Telegram bot setup failed."
          }
        }
      result.ok
    } finally {
      telegramBusy = false
    }
  }

  suspend fun approveTelegramPairing(): Boolean {
    val code =
      pairingCodeInput.trim().ifBlank {
        snapshot?.telegram?.pairingCode.orEmpty()
      }
    if (code.isBlank()) {
      statusMessage = "No pending pairing code yet. Save the bot token, send /start to the bot, then wait for the code to appear."
      return false
    }
    if (!ensureGatewayReadyForSetup()) {
      return false
    }
    telegramBusy = true
    statusMessage = "Approving Telegram pairing…"
    return try {
      val result =
        withContext(Dispatchers.IO) {
          LocalGatewayClient.approveTelegramPairing(code)
        }
      refreshSnapshot()
      statusMessage =
        result.message.ifBlank {
          if (result.ok) "Telegram pairing approved." else "Telegram pairing approval failed."
        }
      result.ok
    } finally {
      telegramBusy = false
    }
  }

  suspend fun sendTelegramTest(): Boolean {
    if (!ensureGatewayReadyForSetup()) {
      return false
    }
    telegramBusy = true
    statusMessage = "Sending Telegram test message…"
    return try {
      val result =
        withContext(Dispatchers.IO) {
          LocalGatewayClient.sendTelegramTest("[openclaw-local] test from Android wizard")
        }
      refreshSnapshot()
      statusMessage = result.message.ifBlank { if (result.ok) "Test message sent." else "Telegram test failed." }
      result.ok
    } finally {
      telegramBusy = false
    }
  }

  suspend fun startTelegramPolling() {
    if (!ensureGatewayReadyForSetup()) {
      return
    }
    telegramBusy = true
    statusMessage = "Restarting Telegram polling…"
    try {
      val result =
        withContext(Dispatchers.IO) {
          LocalGatewayClient.startTelegramPolling()
        }
      refreshSnapshot()
      statusMessage = result.message.ifBlank { if (result.ok) "Polling restarted." else "Polling restart failed." }
    } finally {
      telegramBusy = false
    }
  }

  suspend fun runAutoSetup() {
    autoBusy = true
    statusMessage = "Running full auto setup…"
    try {
      if (!(snapshot?.gateway?.running == true && snapshot?.gateway?.tokenReady == true)) {
        val gatewayReady = startGateway()
        if (!gatewayReady) return
      }

      if (snapshot?.oauth?.ready != true) {
        if (snapshot?.oauth?.pending != true) {
          val started = startOAuth()
          if (!started) return
        }
        statusMessage =
          "Gateway is ready. Finish ChatGPT login in the browser. This step will complete automatically when the callback reaches localhost."
        return
      }

      if (snapshot?.model?.ready != true) {
        statusMessage = "OAuth is ready. Choose the default model for this Android gateway."
        return
      }

      if (botTokenInput.trim().isBlank() && snapshot?.telegram?.botTokenReady != true) {
        statusMessage = "Model is saved. Paste Telegram Bot Token to continue."
        return
      }

      if (snapshot?.telegram?.botTokenReady != true) {
        val botReady = saveTelegramBotAndStart()
        if (!botReady) return
      }

      if (snapshot?.telegram?.pairingApproved != true) {
        statusMessage =
          "Telegram bot is waiting. Send /start to your bot, then approve the pairing code here."
        return
      }

      if (snapshot?.telegram?.lastTestOk != true) {
        sendTelegramTest()
      }

      refreshSnapshot()
      statusMessage = "Wizard complete. Continue into the app."
    } finally {
      autoBusy = false
    }
  }

  LaunchedEffect(Unit) {
    refreshSnapshot()
    while (true) {
      delay(2_000)
      refreshSnapshot()
    }
  }

  val gateway = snapshot?.gateway
  val oauth = snapshot?.oauth
  val model = snapshot?.model
  val telegram = snapshot?.telegram
  val suggestedModels =
    model?.suggested?.takeIf { it.isNotEmpty() } ?: GatewayLocalService.DEFAULT_MODEL_CHOICES
  val effectiveGatewayNetworkMode =
    gatewayNetworkModeInput.ifBlank {
      gateway?.networkMode ?: GatewayLocalService.NETWORK_MODE_LOCAL
    }

  val gatewayState =
    when {
      gatewayBusy -> WizardStepState.Running
      gateway?.running == true && gateway?.tokenReady == true -> WizardStepState.Done
      snapshotError.isNotBlank() && snapshot == null -> WizardStepState.Error
      else -> WizardStepState.Pending
    }

  val oauthState =
    when {
      oauthBusy -> WizardStepState.Running
      oauth?.lastError?.isNotBlank() == true -> WizardStepState.Error
      oauth?.pending == true -> WizardStepState.Running
      oauth?.ready == true && model?.ready == true -> WizardStepState.Done
      oauth?.ready == true -> WizardStepState.Running
      else -> WizardStepState.Pending
    }

  val telegramBotState =
    when {
      telegramBusy || telegramDiscoverBusy -> WizardStepState.Running
      telegram?.botTokenReady == true && telegram?.requested == true -> WizardStepState.Done
      telegram?.lastError?.isNotBlank() == true && telegram?.botTokenReady != true -> WizardStepState.Error
      telegram?.botTokenReady == true || botTokenInput.isNotBlank() -> WizardStepState.Running
      else -> WizardStepState.Pending
    }

  val telegramPairingState =
    when {
      telegramBusy || telegramDiscoverBusy -> WizardStepState.Running
      telegram?.pairingApproved == true && telegram?.lastTestOk == true -> WizardStepState.Done
      telegram?.lastError?.isNotBlank() == true && (telegram?.pairingPending == true || telegram?.botTokenReady == true) ->
        WizardStepState.Error
      telegram?.pairingApproved == true || telegram?.pairingPending == true || telegram?.botTokenReady == true ->
        WizardStepState.Running
      else -> WizardStepState.Pending
    }

  val allDone =
    gatewayState == WizardStepState.Done &&
      oauthState == WizardStepState.Done &&
      telegramBotState == WizardStepState.Done &&
      telegramPairingState == WizardStepState.Done
  val qrContent = oauth?.verificationUriComplete?.ifBlank { oauth?.verificationUri.orEmpty() }.orEmpty()

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(mobileBackgroundGradient)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("OpenClaw Local Gateway", style = mobileDisplay, color = mobileText)
      Text(
        "Set up OpenClaw directly on this phone. The APK already bundles the Android-local gateway service so you can keep it running on-device with ChatGPT OAuth and Telegram.",
        style = mobileCallout,
        color = mobileTextSecondary,
      )
    }

    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      color = mobileCardSurface,
      border = BorderStroke(1.dp, mobileAccent.copy(alpha = 0.24f)),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Full Auto Setup", style = mobileTitle2, color = mobileText)
        Text(
          "Starts the Android-local gateway, prepares ChatGPT login, waits for model selection, then gets Telegram ready for pairing approval.",
          style = mobileCallout,
          color = mobileTextSecondary,
        )
        Button(
          onClick = {
            scope.launch {
              runAutoSetup()
            }
          },
          enabled = !autoBusy && !gatewayBusy && !oauthBusy && !telegramBusy,
          modifier = Modifier.fillMaxWidth().height(50.dp),
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(containerColor = mobileAccent, contentColor = Color.White),
        ) {
          Text("Run Full Auto Setup", style = mobileHeadline.copy(fontWeight = FontWeight.Bold))
        }
        Text(
          statusMessage,
          style = mobileCaption1,
          color = if (snapshotError.isBlank()) mobileTextSecondary else mobileWarning,
        )
      }
    }

    SetupStepCard(
      step = 1,
      title = "Install & Start Gateway",
      state = gatewayState,
      detail =
        when {
          gatewayState == WizardStepState.Done ->
            "The APK-bundled Android local gateway is running on this phone."
          gatewayState == WizardStepState.Running ->
            "Starting the bundled foreground service and waiting for a local health check."
          snapshotError.isNotBlank() ->
            snapshotError
          else ->
            "The install step is already bundled into the APK. Choose a bind mode, start the service, then prove it with the local page."
        },
      error = snapshotError.takeIf { gatewayState == WizardStepState.Error },
      content = {
        Text(
          "Access mode",
          style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
          color = mobileTextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val localSelected = effectiveGatewayNetworkMode == GatewayLocalService.NETWORK_MODE_LOCAL
          val lanSelected = effectiveGatewayNetworkMode == GatewayLocalService.NETWORK_MODE_LAN
          Button(
            onClick = { gatewayNetworkModeInput = GatewayLocalService.NETWORK_MODE_LOCAL },
            shape = RoundedCornerShape(12.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = if (localSelected) mobileAccent else mobileSurface,
                contentColor = if (localSelected) Color.White else mobileText,
              ),
            border = if (localSelected) null else BorderStroke(1.dp, mobileBorder),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text("This Phone", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
          OutlinedButton(
            onClick = { gatewayNetworkModeInput = GatewayLocalService.NETWORK_MODE_LAN },
            shape = RoundedCornerShape(12.dp),
            colors =
              ButtonDefaults.outlinedButtonColors(
                containerColor = if (lanSelected) mobileAccentSoft else Color.Transparent,
                contentColor = if (lanSelected) mobileAccent else mobileText,
              ),
            border = BorderStroke(1.dp, if (lanSelected) mobileAccent.copy(alpha = 0.4f) else mobileBorder),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text("Same Wi-Fi", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
        }
        Text(
          if (effectiveGatewayNetworkMode == GatewayLocalService.NETWORK_MODE_LAN) {
            "LAN mode binds 0.0.0.0 so other devices on the same network can connect with the local gateway token."
          } else {
            "Local-only mode binds 127.0.0.1 and blocks direct LAN access."
          },
          style = mobileCaption1,
          color = mobileTextSecondary,
        )
        InfoLine(label = "Install", value = gateway?.installState?.ifBlank { "bundled-apk" } ?: "bundled-apk", monospace = true)
        InfoLine(label = "Runtime", value = gateway?.kind?.ifBlank { "android-local-gateway" } ?: "android-local-gateway", monospace = true)
        if (!gateway?.localUrl.isNullOrBlank()) {
          InfoLine(label = "This phone", value = gateway?.localUrl.orEmpty(), monospace = true)
        }
        InfoLine(
          label = "Local /chat URL",
          value = gateway?.chatUrl?.ifBlank { "http://127.0.0.1:${GatewayLocalService.PORT}/chat?session=main" } ?: "http://127.0.0.1:${GatewayLocalService.PORT}/chat?session=main",
          monospace = true,
        )
        if (gateway?.chatPathMessage?.isNotBlank() == true) {
          Text(gateway?.chatPathMessage.orEmpty(), style = mobileCaption1, color = mobileTextSecondary)
        }
        if (gateway?.networkMode == GatewayLocalService.NETWORK_MODE_LAN) {
          InfoLine(
            label = "LAN URL",
            value = gateway?.lanUrl?.ifBlank { "Connect to this phone on the same Wi-Fi after start." }.orEmpty(),
            monospace = gateway?.lanUrl?.isNotBlank() == true,
          )
          if (gatewayTokenValue.isNotBlank()) {
            InfoLine(
              label = "LAN auth",
              value = "Use the local gateway token for /v1/* requests from other devices.",
            )
          }
        }
        if (gatewayTokenValue.isNotBlank()) {
          InfoLine(
            label = "Gateway token",
            value = gatewayTokenValue.take(12) + "...",
            monospace = true,
          )
        }
      },
      actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              scope.launch {
                startGateway()
              }
            },
            enabled = !gatewayBusy && !autoBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileAccent, contentColor = Color.White),
          ) {
            Text(
              if (gatewayState == WizardStepState.Done) "Apply Mode" else "Start",
              style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
            )
          }
          Button(
            onClick = {
              val target =
                gateway?.chatUrl?.ifBlank { "http://127.0.0.1:${GatewayLocalService.PORT}/chat?session=main" }
                  ?: "http://127.0.0.1:${GatewayLocalService.PORT}/chat?session=main"
              runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }.onFailure {
                statusMessage = "Could not open the local /chat URL on this device."
              }
            },
            enabled = gatewayState == WizardStepState.Done,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileSurface, contentColor = mobileText),
            border = BorderStroke(1.dp, mobileBorder),
          ) {
            Text("Open /chat", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
          Button(
            onClick = {
              if (gatewayTokenValue.isNotBlank()) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                  ClipData.newPlainText("openclaw_local_gateway_token", gatewayTokenValue),
                )
                statusMessage = "Gateway token copied. Use it as Bearer auth for LAN /v1/* requests."
              }
            },
            enabled = gatewayTokenValue.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileSurface, contentColor = mobileText),
            border = BorderStroke(1.dp, mobileBorder),
          ) {
            Text("Token", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
        }
      },
    )

    SetupStepCard(
      step = 2,
      title = "ChatGPT OAuth + Model",
      state = oauthState,
      detail =
        when {
          oauthState == WizardStepState.Done ->
            "ChatGPT is linked${oauth?.accountLabel?.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty()} and the default model ${model?.selected.orEmpty()} is saved locally."
          oauth?.ready == true ->
            "ChatGPT is linked. Pick the default model now so the gateway can start answering requests."
          oauthState == WizardStepState.Running && oauth?.pending == true ->
            "QR login is ready. Open the link or scan the QR. The app will detect the localhost callback automatically."
          oauthState == WizardStepState.Running ->
            "Preparing the QR login flow."
          else ->
            "Create a ChatGPT QR login flow for the Android-local gateway, then choose the default model after login."
        },
      error = oauth?.lastError?.takeIf { it.isNotBlank() },
      content = {
        if (qrContent.isNotBlank() && oauth?.ready != true) {
          QrCodePanel(content = qrContent)
          Text(
            "After the browser signs in, it returns to localhost automatically. Come back to the app if the browser stays on the success page.",
            style = mobileCaption1,
            color = mobileTextSecondary,
          )
          if (oauth?.userCode?.isNullOrBlank() == false) {
            InfoLine(label = "User code", value = oauth?.userCode.orEmpty(), monospace = true)
          }
          if (oauth?.verificationUriComplete?.isNullOrBlank() == false) {
            InfoLine(label = "Login link", value = oauth?.verificationUriComplete.orEmpty(), monospace = true)
          }
        }
        if (oauth?.ready == true) {
          HorizontalDivider(color = mobileBorder)
          Text("Default model", style = mobileHeadline, color = mobileText)
          Text(
            "Pick one of the suggested Codex models or type a custom provider/model ref for this Android gateway.",
            style = mobileCaption1,
            color = mobileTextSecondary,
          )
          suggestedModels.chunked(2).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              chunk.forEach { candidate ->
                val selected = selectedModelInput == candidate && customModelInput.isBlank()
                if (selected) {
                  Button(
                    onClick = { selectedModelInput = candidate; customModelInput = "" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = mobileAccent, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                  ) {
                    Text(candidate.substringAfter('/'), style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
                  }
                } else {
                  OutlinedButton(
                    onClick = { selectedModelInput = candidate; customModelInput = "" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mobileText),
                    border = BorderStroke(1.dp, mobileBorder),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                  ) {
                    Text(candidate.substringAfter('/'), style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
                  }
                }
              }
            }
          }
          OutlinedTextField(
            value = customModelInput,
            onValueChange = {
              customModelInput = it
              if (it.isNotBlank()) {
                selectedModelInput = ""
              }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Custom provider/model", style = mobileBody, color = mobileTextTertiary) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            textStyle = mobileBody.copy(color = mobileText),
            shape = RoundedCornerShape(14.dp),
            colors = wizardOutlinedColors(),
          )
          if (model?.selected?.isNotBlank() == true) {
            InfoLine(label = "Saved model", value = model?.selected.orEmpty(), monospace = true)
          }
          if (model?.message?.isNotBlank() == true) {
            Text(model?.message.orEmpty(), style = mobileCaption1, color = mobileTextSecondary)
          }
        }
      },
      actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              scope.launch {
                if (gatewayState != WizardStepState.Done) {
                  val gatewayReady = startGateway()
                  if (!gatewayReady) return@launch
                }
                startOAuth()
              }
            },
            enabled = !oauthBusy && !autoBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileAccent, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text(if (oauth?.pending == true) "Refresh QR" else "Create QR", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
          Button(
            onClick = {
              val target = oauth?.verificationUriComplete?.ifBlank { oauth?.verificationUri.orEmpty() }.orEmpty()
              if (target.isNotBlank()) {
                runCatching {
                  context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                  statusMessage = "Could not open the login link on this device."
                }
              }
            },
            enabled = qrContent.isNotBlank() && !oauthBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileSurface, contentColor = mobileText),
            border = BorderStroke(1.dp, mobileBorder),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text("Open Link", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
          Button(
            onClick = {
              scope.launch {
                if (oauth?.ready == true) {
                  saveDefaultModel()
                } else {
                  checkOAuthStatus()
                }
              }
            },
            enabled = (oauth?.pending == true || oauth?.ready == true) && !oauthBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileSuccess, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text(if (oauth?.ready == true) "Save Model" else "Check Status", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
        }
        TextButton(
          onClick = {
            scope.launch {
              resetOAuth()
            }
          },
          enabled = !oauthBusy && (oauth?.pending == true || oauth?.ready == true || oauth?.lastError?.isNotBlank() == true),
        ) {
          Text("Reset OAuth", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold), color = mobileAccent)
        }
      },
    )

    SetupStepCard(
      step = 3,
      title = "Set Up Telegram Bot",
      state = telegramBotState,
      detail =
        when {
          telegramBotState == WizardStepState.Done ->
            "Telegram bot token is saved and polling is running. Pairing approval is the next step."
          telegram?.botTokenReady == true ->
            "Telegram bot token is saved. Send /start to your bot so the app can create a pairing code."
          else ->
            "Paste the Bot Token only. Do not ask the user for Chat ID up front."
        },
      error = telegram?.lastError?.takeIf { it.isNotBlank() && telegram?.botTokenReady != true },
      content = {
        OutlinedTextField(
          value = botTokenInput,
          onValueChange = { botTokenInput = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          placeholder = { Text("Bot token", style = mobileBody, color = mobileTextTertiary) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          textStyle = mobileBody.copy(color = mobileText),
          shape = RoundedCornerShape(14.dp),
          colors = wizardOutlinedColors(),
        )
        Text(
          "Order: 1) paste Bot Token  2) tap Save Bot  3) open Telegram and send /start to your bot  4) approve the pairing code in Step 4.",
          style = mobileCaption1,
          color = mobileTextSecondary,
        )
        if (telegram != null) {
          if (telegram?.botTokenMasked?.isNotBlank() == true) {
            InfoLine(label = "Saved bot token", value = telegram?.botTokenMasked.orEmpty(), monospace = true)
          }
          InfoLine(label = "Polling", value = if (telegram?.polling == true) "Running" else "Stopped")
          if (telegram?.pairingMessage?.isNotBlank() == true) {
            Text(telegram?.pairingMessage.orEmpty(), style = mobileCaption1, color = mobileTextSecondary)
          }
        }
      },
      actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              scope.launch {
                saveTelegramBotAndStart()
              }
            },
            enabled = !telegramBusy && !telegramDiscoverBusy && !autoBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileAccent, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text(if (telegram?.botTokenReady == true) "Update Bot" else "Save Bot", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
          Button(
            onClick = {
              scope.launch {
                startTelegramPolling()
              }
            },
            enabled = telegram?.botTokenReady == true && !telegramBusy && !telegramDiscoverBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileSurface, contentColor = mobileText),
            border = BorderStroke(1.dp, mobileBorder),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text("Restart Poll", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
        }
      },
    )

    SetupStepCard(
      step = 4,
      title = "Approve Telegram Pairing",
      state = telegramPairingState,
      detail =
        when {
          telegramPairingState == WizardStepState.Done ->
            "Telegram pairing is approved for chat ${telegram?.chatId.orEmpty()} and the last test message succeeded."
          telegram?.pairingApproved == true ->
            "Telegram pairing is approved. Send a test message to confirm delivery."
          telegram?.pairingPending == true ->
            "A Telegram pairing request is waiting. Approve the code below so this phone can trust that Telegram DM."
          telegram?.botTokenReady == true ->
            "Send /start to your bot. The pairing code will appear here automatically."
          else ->
            "Start the Telegram bot first so the user can request pairing."
        },
      error = telegram?.lastError?.takeIf { it.isNotBlank() && telegram?.botTokenReady == true },
      content = {
        OutlinedTextField(
          value = pairingCodeInput,
          onValueChange = { pairingCodeInput = it.uppercase() },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          placeholder = { Text("Pairing code", style = mobileBody, color = mobileTextTertiary) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          textStyle = mobileBody.copy(color = mobileText),
          shape = RoundedCornerShape(14.dp),
          colors = wizardOutlinedColors(),
        )
        Text(
          "When someone sends /start to your bot, this app shows the pending pairing code here. Approve it to bind that Telegram DM to this phone.",
          style = mobileCaption1,
          color = mobileTextSecondary,
        )
        if (telegram != null) {
          if (telegram?.pairingCode?.isNotBlank() == true) {
            InfoLine(label = "Pending code", value = telegram?.pairingCode.orEmpty(), monospace = true)
          }
          if (telegram?.pairingChatId?.isNotBlank() == true) {
            InfoLine(label = "Pending chat", value = telegram?.pairingChatId.orEmpty(), monospace = true)
          }
          if (telegram?.chatId?.isNotBlank() == true) {
            InfoLine(label = "Approved chat", value = telegram?.chatId.orEmpty(), monospace = true)
          }
          if (telegram?.lastTestMessage?.isNotBlank() == true) {
            InfoLine(label = "Last test", value = telegram?.lastTestMessage.orEmpty())
          }
          if (telegram?.pairingMessage?.isNotBlank() == true) {
            Text(telegram?.pairingMessage.orEmpty(), style = mobileCaption1, color = mobileTextSecondary)
          }
        }
      },
      actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              scope.launch {
                approveTelegramPairing()
              }
            },
            enabled = !telegramBusy && !telegramDiscoverBusy && (pairingCodeInput.isNotBlank() || telegram?.pairingPending == true),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileAccent, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text("Approve", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
          Button(
            onClick = {
              scope.launch {
                sendTelegramTest()
              }
            },
            enabled = telegram?.pairingApproved == true && !telegramBusy && !telegramDiscoverBusy && !autoBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mobileSuccess, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text("Send Test", style = mobileCaption1.copy(fontWeight = FontWeight.Bold))
          }
        }
      },
    )

    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(18.dp),
      color = mobileSurface,
      border = BorderStroke(1.dp, mobileBorder),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Next Step", style = mobileHeadline, color = mobileText)
        Text(
          if (allDone) {
            "Gateway start, OAuth, model selection, Telegram bot setup, and pairing test are all green. Continue into the app for advanced or remote setup."
          } else {
            "You can skip into the app at any time, but the Android-local gateway should be running, the model should be saved, and Telegram pairing/test should be green before calling this setup done."
          },
          style = mobileCallout,
          color = mobileTextSecondary,
        )
        Button(
          onClick = onContinue,
          modifier = Modifier.fillMaxWidth().height(48.dp),
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = if (allDone) mobileSuccess else mobileSurface,
              contentColor = if (allDone) Color.White else mobileText,
            ),
          border = if (allDone) null else BorderStroke(1.dp, mobileBorder),
        ) {
          Text(
            if (allDone) "Continue to OpenClaw" else "Open App Anyway",
            style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
          )
        }
        if (onSkip != null) {
          TextButton(onClick = onSkip) {
            Text("Skip for now", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold), color = mobileAccent)
          }
        }
      }
    }
  }
}

@Composable
private fun SetupStepCard(
  step: Int,
  title: String,
  state: WizardStepState,
  detail: String,
  error: String? = null,
  content: (@Composable ColumnScope.() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit,
) {
  val (chipText, chipBg, chipBorder, dotColor) =
    when (state) {
      WizardStepState.Done ->
        listOf(mobileSuccess, mobileSuccessSoft, mobileSuccess.copy(alpha = 0.25f), mobileSuccess)
      WizardStepState.Running ->
        listOf(mobileAccent, mobileAccentSoft, mobileAccent.copy(alpha = 0.25f), mobileAccent)
      WizardStepState.Error ->
        listOf(mobileDanger, mobileDangerSoft, mobileDanger.copy(alpha = 0.25f), mobileDanger)
      WizardStepState.Pending ->
        listOf(mobileTextSecondary, mobileSurface, mobileBorder, mobileTextTertiary)
    }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = mobileCardSurface,
    border = BorderStroke(1.dp, mobileBorder),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Surface(
            modifier = Modifier.size(34.dp),
            color = mobileAccentSoft,
            shape = CircleShape,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(step.toString(), style = mobileHeadline, color = mobileAccent)
            }
          }
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = mobileTitle2, color = mobileText)
            Text(detail, style = mobileCallout, color = mobileTextSecondary)
          }
        }
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = chipBg,
          border = BorderStroke(1.dp, chipBorder),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
            Text(
              when (state) {
                WizardStepState.Pending -> "Pending"
                WizardStepState.Running -> "Running"
                WizardStepState.Done -> "Done"
                WizardStepState.Error -> "Error"
              },
              style = mobileCaption1,
              color = chipText,
            )
          }
        }
      }

      if (!error.isNullOrBlank()) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = mobileDangerSoft,
          border = BorderStroke(1.dp, mobileDanger.copy(alpha = 0.18f)),
        ) {
          Text(
            error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            style = mobileCaption1,
            color = mobileDanger,
          )
        }
      }

      if (content != null) {
        HorizontalDivider(color = mobileBorder)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
      }

      HorizontalDivider(color = mobileBorder)

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = actions,
      )
    }
  }
}

@Composable
private fun QrCodePanel(content: String) {
  val bitmap = remember(content) { generateQrBitmap(content) }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = mobileSurface,
    border = BorderStroke(1.dp, mobileBorder),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text("Login QR", style = mobileHeadline, color = mobileText)
      if (bitmap != null) {
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = "OAuth login QR code",
          modifier = Modifier.size(220.dp),
        )
      } else {
        Text("QR could not be generated on this build.", style = mobileCaption1, color = mobileWarning)
      }
      Text(
        "Scan from another device or open the login link directly on this phone.",
        style = mobileCaption1,
        color = mobileTextSecondary,
      )
    }
  }
}

@Composable
private fun InfoLine(label: String, value: String, monospace: Boolean = false) {
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(label, style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold), color = mobileTextSecondary)
    Text(
      value,
      style =
        if (monospace) {
          mobileCallout.copy(fontFamily = FontFamily.Monospace, color = mobileText)
        } else {
          mobileCallout.copy(color = mobileText)
        },
      color = mobileText,
    )
  }
}

@Composable
private fun wizardOutlinedColors() =
  OutlinedTextFieldDefaults.colors(
    focusedContainerColor = mobileSurface,
    unfocusedContainerColor = mobileSurface,
    focusedBorderColor = mobileAccent,
    unfocusedBorderColor = mobileBorder,
    focusedTextColor = mobileText,
    unfocusedTextColor = mobileText,
    cursorColor = mobileAccent,
  )

private fun generateQrBitmap(content: String, size: Int = 900): Bitmap? {
  if (content.isBlank()) return null
  return runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
      for (y in 0 until size) {
        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
      }
    }
    bitmap
  }.getOrNull()
}
