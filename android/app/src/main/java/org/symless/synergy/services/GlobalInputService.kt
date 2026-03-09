/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.symless.synergy.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import arrow.core.raise.catch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.symless.synergy.R
import org.symless.synergy.client.events.KeyboardEvent
import org.symless.synergy.client.events.MouseEvent
import org.symless.synergy.client.events.ScreenEvent
import org.symless.synergy.client.models.ClipboardData
import org.symless.synergy.client.util.Keyboard
import org.symless.synergy.client.util.logging.KLoggingManager
import org.symless.synergy.components.GlobalKeyboardManager
import org.symless.synergy.ext.canDrawOverlays
import org.symless.synergy.ext.getScreenSize
import org.symless.synergy.ext.isTelevision
import org.symless.synergy.ext.sendServiceConnectionEvent
import org.symless.synergy.ext.sendServiceDisconnectionEvent

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@SuppressLint("ServiceCast", "NewApi")
class GlobalInputService : AccessibilityService() {

  private var pickerShownForPackage: String? = null

  private val isTV by lazy { applicationContext.isTelevision() }

  private val inputHelper = InputHelperClient()
  @Volatile private var helperConnectAttempted = false

  /** Client responsible for communicating with the connection service. */
  private lateinit var serviceClient: ConnectionServiceClient

  /**
   * Keyboard manager for handling keyboard events, calculating state and
   * triggering actions.
   */
  private lateinit var keyboardManager: GlobalKeyboardManager

  private val clipboard by lazy {
    getSystemService(ClipboardManager::class.java)
  }

  private val imeManager by lazy {
    getSystemService(InputMethodManager::class.java)
  }

  private val notificationManager by lazy {
    getSystemService(NotificationManager::class.java)
  }

  private val displayManager by lazy {
    getSystemService(DisplayManager::class.java)
  }

  private val wifiManager by lazy {
    applicationContext.getSystemService(WifiManager::class.java)
  }

  /**
   * Manager for keeping the screen on during input activity via wakelock.
   * The wakelock is throttled to refresh every 5 seconds during continued activity.
   */
  private lateinit var screenWakelockManager: ScreenWakelockManager

  /**
   * WiFi lock for low latency mode when cursor is active on this screen.
   */
  private var wifiLowLatencyLock: WifiManager.WifiLock? = null

  /**
   * Flow to observe if the home screen is currently active. This is used to
   * determine if the current screen is the home screen.
   * > Example: Used for checking if the user is on the home screen in the
   * > global input service.
   */
  private val isHomeScreenActiveFlow = MutableStateFlow(false)

  /** Check if the home screen is currently active. */
  private val isHomeScreenActive: Boolean
    get() = isHomeScreenActiveFlow.value

  /**
   * Set of known home packages. Populated when the service is connected via
   * `fetchHomePackages`.
   */
  private val knownHomePackages = mutableSetOf<String>()

  private val activePackageName: String?
    get() = rootInActiveWindow?.packageName?.toString()

  private val keyboardWindowInfo: AccessibilityWindowInfo?
    get() =
      windows.find { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

  private val isKeyboardOpened: Boolean
    get() = keyboardWindowInfo != null

  /**
   * Flag to indicate if a global input action is currently pending. This is
   * used to prevent multiple gestures from being dispatched at the same time.
   */
  @Volatile private var globalInputPending = false

  /**
   * Handler for posting global input actions to the main thread. This is used
   * to ensure that gestures are dispatched on the main thread.
   */
  private val globalInputHandler = Handler(Looper.getMainLooper())

  /**
   * Callback for gesture results. This is used to handle the completion or
   * cancellation of gestures.
   */
  private val gestureResultCallback =
    object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        super.onCompleted(gestureDescription)
        globalInputPending = false
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        super.onCancelled(gestureDescription)
        globalInputPending = false
      }
    }

  /**
   * Layout parameters for the mouse pointer view. This is used to position the
   * mouse pointer on the screen.
   */
  private lateinit var mousePointerLayout: WindowManager.LayoutParams

  /**
   * View for the mouse pointer. This is the view that will be displayed on the
   * screen to represent the mouse pointer.
   */
  private lateinit var mousePointerView: View

  /**
   * Flag to indicate if the mouse pointer is currently visible. This is used to
   * control the visibility of the mouse pointer view.
   */
  @Volatile private var mousePointerVisible = false

  /**
   * Flag to indicate if the accessibility service is fully connected and ready.
   * The service must be connected before we can add overlay windows.
   */
  @Volatile private var isServiceConnected = false

  /**
   * The display ID where gestures should be dispatched.
   * Updated when showing the mouse pointer to match the active display.
   * This ensures touch events are sent to the correct display
   */
  @Volatile private var activeDisplayId: Int = android.view.Display.DEFAULT_DISPLAY

  /**
   * Tracks the current mouse button state for drag detection.
   * Stores button ID and the position where the button was pressed.
   */
  private data class MouseButtonState(
    val buttonId: UInt,
    val downX: Int,
    val downY: Int,
    val downTime: Long = System.currentTimeMillis()
  )

  /**
   * Current mouse button down state, null if no button is pressed.
   * Used for drag-and-drop implementation.
   */
  @Volatile private var mouseButtonDown: MouseButtonState? = null

  /**
   * Tracks the active drag gesture state. When non-null, a drag is in progress.
   * For multi-touch support, lastStrokes contains one stroke per finger:
   * - Index 0: Primary finger (main touch point)
   * - Index 1 (if present): Secondary finger (offset to the right)
   * - Index 2 (if present): Tertiary finger (further right)
   */
  private data class DragState(
    val startX: Float,
    val startY: Float,
    var lastDispatchedX: Float, // Position where last stroke ended
    var lastDispatchedY: Float, // Position where last stroke ended
    var targetX: Float,          // Target position for next stroke
    var targetY: Float,          // Target position for next stroke
    var lastStrokes: List<StrokeDescription> = emptyList(), // List of stroke descriptions for multi-touch
    var isEnding: Boolean = false, // Flag to indicate drag should end after current gesture
    var initialHoldDuration: Long = 0, // Duration of initial hold before drag starts (0 = speculative hold)
    var fingerCount: Int = 1 // Number of fingers in multi-touch (1-3)
  )

  /**
   * Current drag state, null if no drag is in progress.
   */
  @Volatile private var activeDragState: DragState? = null

  /**
   * Flag to prevent dispatching new drag updates while one is in progress.
   */
  @Volatile private var dragGestureInProgress = false

  /**
   * Threshold for movement to be considered a drag (in pixels).
   * If mouse moves more than this distance while button is down, it's a drag.
   */
  private val dragThreshold = 10

  /**
   * Offsets for multi-touch fingers relative to the primary finger position.
   * Index 0 is always at (0, 0) - the primary finger.
   * Index 1 (2-finger touch): offset to the right by this amount
   * Index 2 (3-finger touch): further right by this amount
   */
  private val multiTouchFingerOffsets = listOf(
    Pair(0, 0),      // Primary finger (index 0)
    Pair(100, 0),     // Secondary finger (index 1) - 100 pixels right
    Pair(200, 0)      // Tertiary finger (index 2) - 200 pixels right
  )

  private val keyboardWasOpen = AtomicBoolean(false)

  /** WindowManager instance for managing the mouse pointer view. */
  private lateinit var windowManager: WindowManager

  /**
   * Listener for display add/remove/change events.
   * Used to detect when for example PC mode is toggled (creates/removes virtual display).
   */
  private val displayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) {
      log.info { "Display added: ID=$displayId" }

      if (!serviceClient.stateFlow.value.isEnabled) {
        log.debug { "Display added but service not enabled, skipping processing" }
        return
      }

      logDisplayInformation()

      // If mouse pointer is visible, we may need to move it to the new display
      if (mousePointerVisible) {
        log.info { "Mouse pointer is visible, checking if display change requires pointer relocation" }
        // Hide and re-show to trigger display detection
        hideMousePointer()
        Handler(Looper.getMainLooper()).post {
          showMousePointer()
        }
      }
    }

    override fun onDisplayRemoved(displayId: Int) {
      log.info { "Display removed: ID=$displayId" }

      if (!serviceClient.stateFlow.value.isEnabled) {
        log.debug { "Display removed but service not enabled, skipping processing" }
        return
      }

      logDisplayInformation()

      // If mouse pointer is visible, we may need to move it back to primary display
      if (mousePointerVisible) {
        log.info { "Mouse pointer is visible, checking if display change requires pointer relocation" }
        // Hide and re-show to trigger display detection
        hideMousePointer()
        Handler(Looper.getMainLooper()).post {
          showMousePointer()
        }
      }
    }

    override fun onDisplayChanged(displayId: Int) {
      log.debug { "Display changed: ID=$displayId" }
      // Don't need to do anything for simple display changes like brightness
    }
  }

  private val serviceScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun createNotificationChannel() {

    val name =
      resources.getText(R.string.global_input_service_notification_channel_name)
    val desc =
      resources.getText(
        R.string.global_input_service_notification_channel_description
      )
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val chan =
      NotificationChannel(CHANNEL_ID, name, importance).apply {
        description = desc.toString()
      }
    notificationManager.createNotificationChannel(chan)
  }

  private fun sendStatusNotification(
    text: String,
    customizer: (NotificationCompat.Builder.() -> Unit)? = null,
  ) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS,
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      log.warn {
        "POST_NOTIFICATIONS permission not granted, cannot send notification."
      }
      return
    }
    val notificationBuilder =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.synergy_icon_fit) // your icon
        .setContentTitle(
          resources.getText(
            R.string.global_input_service_notification_channel_name
          )
        )
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
    if (customizer != null) {
      customizer(notificationBuilder)
    }

    val notification = notificationBuilder.build()
    // TODO: Add action to open keyboard settings
    NotificationManagerCompat.from(this)
      .notify(NOTIF_IME_NOT_SETUP_ID, notification)
  }

  private fun getCurrentImeInfo(): InputMethodInfo? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      imeManager.currentInputMethodInfo
    } else {
      val currentImeId = Settings.Secure.getString(
        contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
      )
      imeManager.inputMethodList.find { it.id == currentImeId }
    }
  }

  private fun isSynergyKeyboardActive(
    imeInfo: InputMethodInfo? = getCurrentImeInfo()
  ): Boolean {
    return imeInfo?.let { current -> synergyImeInfo?.id == current.id }
      ?: false
  }

  private fun checkIMESetup() {
    val kbOpen = isKeyboardOpened
    val kbWasOpen = keyboardWasOpen.load()
    if (!kbOpen) {
      keyboardWasOpen.store(false)
      return
    }

    if (kbWasOpen) return

    if (!serviceClient.stateFlow.value.isConnected || !serviceClient.stateFlow.value.isEnabled) {
      return
    }

    keyboardWasOpen.store(true)

    val imeInfo = getCurrentImeInfo()
    if (imeInfo != null) {
      log.debug { "Current IME: ${imeInfo.packageName}" }
      when {
        isSynergyKeyboardActive(imeInfo) -> {
          log.debug { "Current IME is synergy, no action needed." }
        }
        !isSynergyImeEnabled -> {
          log.warn { "IME is not enabled, showing notification." }
          sendStatusNotification(
            resources.getString(
              R.string.global_input_service_notification_ime_not_setup
            )
          ) {
            setContentIntent(
              PendingIntent.getActivity(
                this@GlobalInputService,
                0,
                Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE,
              )
            )
          }
        }
        with(activePackageName) {
          this == null || pickerShownForPackage == this
        } -> {
          log.debug {
            "IME picker was already shown for package $pickerShownForPackage"
          }
        }
        else -> {
          pickerShownForPackage = activePackageName
          val imeId = synergyImeInfo?.id
          if (
            imeId != null && softKeyboardController.switchToInputMethod(imeId)
          ) {
            log.debug { "softKeyboardController set IME to $imeId" }
          } else {
            log.debug { "Could not auto-switch to Synergy IME, skipping picker" }
          }
        }
      }
    } else {
      log.warn { "No current IME found." }
    }
  }

  @SuppressLint("RtlHardcoded")
  override fun onCreate() {
    super.onCreate()
    log.debug { "onCreate:${GlobalInputService::class.simpleName}" }

    createNotificationChannel()

    // Initialize the screen wakelock manager for keeping screen on during input activity
    screenWakelockManager = ScreenWakelockManager(this, autoReleaseTimeoutMs = 5000L)

    keyboardManager = GlobalKeyboardManager(this)
    serviceScope.launch {
      keyboardManager.actionFlow.collect { action ->
        log.info { "Action: ${action.label}(${action.actionId})" }
        if (isTV && inputHelper.isConnected) {
          handleActionViaHelper(action.actionId)
        } else {
          when (action.actionId) {
            in 16..19 -> {
              if (isTV) navigateFocusTV(action.actionId)
            }
            20 -> clickFocused()
            21 -> {
              if (isTV && !performGlobalAction(action.actionId)) clickFocused()
            }
            else -> {
              val ok = performGlobalAction(action.actionId)
              log.info { "performGlobalAction(${action.actionId} ${action.label})=$ok" }
            }
          }
        }
      }
    }

    serviceClient =
      ConnectionServiceClient(this) { event ->
        globalInputHandler.post {
          try {
            when (event) {
              is MouseEvent -> {
                onMouseEvent(event)
              }

              is KeyboardEvent -> {
                onKeyboardEvent(event)
              }

              is ScreenEvent.SetClipboard -> {
                val data = event.data
                log.debug { "SetClipboard(variantCount=${data.variants.size})" }
                val knownFormats =
                  arrayOf(
                    ClipboardData.Format.Text // ClipboardData.Format.Bitmap
                  )

                for (format in knownFormats) {
                  val variant = data.variants[format] ?: continue

                  // TODO: Implement `Converter` concept
                  val clipData =
                    when (format) {
                      ClipboardData.Format.Text -> {
                        log.debug { "SetClipboard: Text(${variant.data.size})" }
                        val text = String(variant.data, Charsets.UTF_8)
                        ClipData.newPlainText("synergy_clipboard", text)
                      }

                      else -> {
                        log.warn {
                          "SetClipboard: No converter for format $format"
                        }
                        continue
                      }
                    }

                  log.debug { "Clipboard variant ready: $variant" }
                  // Only set if content actually changed (avoids Android 13+ clipboard popup)
                  val currentClip = clipboard.primaryClip
                  val currentText = currentClip?.getItemAt(0)?.text?.toString()
                  val newText = clipData.getItemAt(0)?.text?.toString()
                  if (currentText != newText) {
                    clipboard.setPrimaryClip(clipData)
                  }
                  break
                }
              }

              else -> {}
            }
          } catch (err: Exception) {
            log.error(err) {
              "Error running on globalInputHandler: ${err.message}"
            }
          }
        }
      }

    mousePointerView = View.inflate(this, R.layout.mouse_pointer, null)
    mousePointerLayout =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      )

    mousePointerLayout.layoutInDisplayCutoutMode =
      LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    mousePointerLayout.gravity = Gravity.TOP or Gravity.LEFT
    mousePointerLayout.x = 200
    mousePointerLayout.y = 200

    // Initialize WindowManager to default - will be properly initialized in onServiceConnected()
    // when the accessibility service context is fully ready
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    // Log display information at startup
    log.info { "Service onCreate - logging initial display configuration" }
    logDisplayInformation()

    // Register display listener to detect display changes such as PC mode toggle on some devices
    displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    log.debug { "Display listener registered" }

    startService(Intent(applicationContext, ConnectionService::class.java))
    serviceClient.bind()

    // Monitor connection state to reset IME state when disconnected
    monitorConnectionState()
  }

  private fun acquireWifiLowLatencyLock() {
    try {
      // Release existing lock if any
      releaseWifiLowLatencyLock()

      // WIFI_MODE_FULL_LOW_LATENCY requires API 29+
      val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
      else
        @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
      wifiLowLatencyLock = wifiManager.createWifiLock(
        mode,
        "SynergyLowLatency"
      ).apply {
        acquire()
        log.info { "WiFi low latency lock acquired" }
      }
    } catch (err: Exception) {
      log.error(err) { "Error acquiring WiFi low latency lock" }
    }
  }

  private fun releaseWifiLowLatencyLock() {
    wifiLowLatencyLock?.let { lock ->
      try {
        if (lock.isHeld) {
          lock.release()
          log.info { "WiFi low latency lock released" }
        }
      } catch (err: Exception) {
        log.error(err) { "Error releasing WiFi low latency lock" }
      } finally {
        wifiLowLatencyLock = null
      }
    }
  }

  private fun tryConnectHelper() {
    if (inputHelper.isConnected) return
    if (inputHelper.connect()) {
      log.info { "Input helper connected -- real input injection enabled" }
      return
    }

    // Helper not running -- try to start it via local ADB (Fire TV has ADB TCP on localhost:5555)
    log.info { "Helper not running, attempting auto-start via local ADB" }
    val adb = org.symless.synergy.helper.AdbClient(applicationContext)
    if (adb.startHelperIfNeeded()) {
      // Give it a moment to start listening
      Thread.sleep(2000)
      if (inputHelper.connect()) {
        log.info { "Helper auto-started and connected via local ADB" }
        return
      }
    }
    log.info { "Could not auto-start helper -- using accessibility fallback" }
  }

  /**
   * Monitor connection state and reset IME-related state when disconnected.
   * Also manages mouse pointer visibility based on screen active state.
   * Also manages WiFi low latency lock based on screen active state.
   */
  private fun monitorConnectionState() {
    serviceScope.launch {
      serviceClient.stateFlow.collect { state ->
        log.debug {
          "Connection state changed in GlobalInputService: isConnected=${state.isConnected}, isEnabled=${state.isEnabled}, isActive=${state.screen.isActive}"
        }

        // Show/hide mouse pointer based on connection state AND screen active state
        // The pointer should only be visible when:
        // 1. Connected to server (isConnected)
        // 2. Service is enabled (isEnabled)
        // 3. Cursor is on THIS client's screen (screen.isActive)
        val shouldShowPointer = state.isConnected && state.isEnabled && state.screen.isActive

        if (shouldShowPointer) {
          log.info { "Cursor entered this client, showing mouse pointer" }
          if (isTV && !inputHelper.isConnected) {
            launch(Dispatchers.IO) { tryConnectHelper() }
          }
          withContext(Dispatchers.Main) {
            showMousePointer()
          }
          acquireWifiLowLatencyLock()
        } else {
          if (state.isConnected && state.isEnabled && !state.screen.isActive) {
            log.info { "Cursor left this client, hiding mouse pointer" }
          } else {
            log.info { "Connection inactive, hiding mouse pointer" }
          }
          withContext(Dispatchers.Main) {
            hideMousePointer()
          }
          releaseWifiLowLatencyLock()
        }

        // When disconnected or disabled, reset IME tracking state
        if (!state.isConnected || !state.isEnabled) {
          log.debug { "Connection lost or disabled, resetting IME state" }

          // Reset IME picker tracking
          pickerShownForPackage = null
          keyboardWasOpen.store(false)
        }
      }
    }
  }

  private fun handleActionViaHelper(actionId: Int) {
    val keyCode = when (actionId) {
      16 -> KeyEvent.KEYCODE_DPAD_UP
      17 -> KeyEvent.KEYCODE_DPAD_DOWN
      18 -> KeyEvent.KEYCODE_DPAD_LEFT
      19 -> KeyEvent.KEYCODE_DPAD_RIGHT
      20 -> KeyEvent.KEYCODE_DPAD_CENTER
      21 -> KeyEvent.KEYCODE_MENU
      1 -> KeyEvent.KEYCODE_BACK
      2 -> KeyEvent.KEYCODE_HOME
      3 -> KeyEvent.KEYCODE_APP_SWITCH
      else -> {
        val ok = performGlobalAction(actionId)
        log.info { "performGlobalAction($actionId)=$ok (helper mode, no keycode mapping)" }
        return
      }
    }
    serviceScope.launch(Dispatchers.IO) {
      inputHelper.sendKey(keyCode, KeyEvent.ACTION_DOWN)
      inputHelper.sendKey(keyCode, KeyEvent.ACTION_UP)
    }
  }

  /**
   * Handle keyboard events from the client. This is where we process keyboard
   * events and pass them to the keyboard manager.
   * > Example: Used for handling key presses in the global input service.
   */
  private fun onKeyboardEvent(event: KeyboardEvent) {
    log.debug { "onKeyboardEvent: $event" }

    // Keep the screen on during keyboard activity
    screenWakelockManager.onInputActivity()

    // always process modifier key events (both Down and Up) to keep keyboardManager state synchronized
    val modKey = Keyboard.findModifierKey(event.id.toInt())
    if (modKey != null) {
      log.debug { "Modifier key event: $modKey, type=${event.type}" }
      keyboardManager.process(event)
      return
    }

    // Only handle Down and Repeat events for non-modifier keys - ignore Up to avoid duplicates
    if (event.type == KeyboardEvent.Type.Up) {
      return
    }

    keyboardManager.process(event)
  }

  /**
   * Called when the service is destroyed. This is where we clean up resources
   * and remove the mouse pointer view.
   */
  override fun onDestroy() {
    log.warn { "Accessibility service being destroyed" }

    // Unregister display listener
    try {
      displayManager.unregisterDisplayListener(displayListener)
      log.debug { "Display listener unregistered" }
    } catch (err: Exception) {
      log.error(err) { "Error unregistering display listener" }
    }

    // Mark service as no longer connected
    isServiceConnected = false

    // Broadcast that the service is disconnected
    sendServiceDisconnectionEvent<GlobalInputService>()

    inputHelper.disconnect()
    serviceScope.cancel()
    serviceClient.unbind()
    hideMousePointer()  // Use the safe hide method instead of direct removeView
    screenWakelockManager.cleanup()  // Clean up wakelock resources
    releaseWifiLowLatencyLock()  // Release WiFi low latency lock
    super.onDestroy()
  }

  /**
   * Called when the device configuration changes (e.g., screen rotation, density change, PC mode toggle).
   * This handles updating the mouse pointer overlay to work with the new screen configuration.
   */
  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)

    if (!serviceClient.stateFlow.value.isEnabled) {
      log.debug { "Configuration changed but service not enabled, skipping processing" }
      return
    }

    val newScreenSize = getScreenSize(activeDisplayId)
    log.info { "Configuration changed - new screen size: ${newScreenSize.px.width}x${newScreenSize.px.height}, density: ${newScreenSize.scale}" }

    // Skip updating if screen size is invalid (can happen during rotation transitions)
    if (newScreenSize.px.width <= 0 || newScreenSize.px.height <= 0) {
      log.warn { "Invalid screen size during configuration change, skipping mouse pointer update" }
      return
    }

    // Update the server with new screen dimensions
    try {
      val result = serviceClient.updateScreenDimensions(newScreenSize.px.width, newScreenSize.px.height)
      if (result?.ok == true) {
        log.info { "Successfully updated server with new screen dimensions: ${newScreenSize.px.width}x${newScreenSize.px.height}" }
      } else {
        log.warn { "Failed to update server with new screen dimensions: ${result?.message}" }
      }
    } catch (err: Exception) {
      log.error(err) { "Error updating server with new screen dimensions" }
    }

    // If the mouse pointer is currently visible, we need to update its position
    // to ensure it stays within the new screen bounds
    if (mousePointerVisible) {
      // Clamp the current position to the new screen bounds
      val clampedX = mousePointerLayout.x.coerceIn(0, newScreenSize.px.width - 1)
      val clampedY = mousePointerLayout.y.coerceIn(0, newScreenSize.px.height - 1)

      if (clampedX != mousePointerLayout.x || clampedY != mousePointerLayout.y) {
        log.info { "Mouse pointer position adjusted from (${mousePointerLayout.x}, ${mousePointerLayout.y}) to ($clampedX, $clampedY) for new screen bounds" }
        mousePointerLayout.x = clampedX
        mousePointerLayout.y = clampedY
      }

      // Update the view layout with the adjusted parameters
      try {
        windowManager.updateViewLayout(mousePointerView, mousePointerLayout)
        log.info { "Mouse pointer overlay updated for new configuration" }
      } catch (err: Exception) {
        log.error(err) { "Error updating mouse pointer layout after configuration change" }
        // If update fails, try to reinitialize the pointer
        if (reinitializeMousePointer()) {
          try {
            windowManager.addView(mousePointerView, mousePointerLayout)
            mousePointerVisible = true
            log.info { "Mouse pointer reinitialized after configuration change" }
          } catch (retryErr: Exception) {
            log.error(retryErr) { "Failed to reinitialize mouse pointer after configuration change" }
            mousePointerVisible = false
          }
        }
      }
    }

    // Log display information after configuration change
    logDisplayInformation()
  }

  /**
   * Log detailed information about all displays on the device.
   */
  private fun logDisplayInformation() {
    try {
      val displays = displayManager.displays
      log.info { "=== Display Information (${displays.size} display(s)) ===" }

      displays.forEachIndexed { index, display ->
        val displayContext = createDisplayContext(display)
        val dm = displayContext.resources.displayMetrics

        log.info {
          """
          Display #$index (ID: ${display.displayId}):
            Name: ${display.name}
            State: ${displayStateToString(display.state)}
            Size: ${dm.widthPixels}x${dm.heightPixels}
            Density: ${dm.density}
            Refresh Rate: ${display.refreshRate} Hz
            Rotation: ${rotationToString(display.rotation)}
            Is Default: ${display.displayId == android.view.Display.DEFAULT_DISPLAY}
            Flags: ${display.flags}
          """.trimIndent()
        }
      }

      // Log which display we're currently using for the WindowManager and gestures
      log.info { "Active display ID for WindowManager and gestures: $activeDisplayId" }

    } catch (err: Exception) {
      log.error(err) { "Error logging display information" }
    }
  }

  private fun displayStateToString(state: Int): String {
    return when (state) {
      android.view.Display.STATE_OFF -> "OFF"
      android.view.Display.STATE_ON -> "ON"
      android.view.Display.STATE_DOZE -> "DOZE"
      android.view.Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
      android.view.Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
      android.view.Display.STATE_VR -> "VR"
      else -> "UNKNOWN($state)"
    }
  }

  private fun rotationToString(rotation: Int): String {
    return when (rotation) {
      android.view.Surface.ROTATION_0 -> "0° (Portrait)"
      android.view.Surface.ROTATION_90 -> "90° (Landscape)"
      android.view.Surface.ROTATION_180 -> "180° (Reverse Portrait)"
      android.view.Surface.ROTATION_270 -> "270° (Reverse Landscape)"
      else -> "UNKNOWN($rotation)"
    }
  }

  /**
   * Get the active display where we want to show the mouse pointer.
   */
  private fun getActiveDisplay(): android.view.Display {
    try {
      val displays = displayManager.displays

      log.debug { "Selecting active display from ${displays.size} available display(s)" }

      // Single display - use it
      if (displays.size == 1) {
        return displays[0]
      }

      // Multiple displays - use the one with the largest display ID
      val activeDisplay = displays.maxByOrNull { it.displayId }!!
      log.info { "Using display with largest ID: ${activeDisplay.displayId} (${activeDisplay.name})" }
      return activeDisplay

    } catch (err: Exception) {
      log.error(err) { "Error selecting display, using first display" }
      return displayManager.displays[0]
    }
  }

  /**
   * Perform a tap/click gesture at the specified position.
   * Used for simulating mouse clicks, long presses, and context menus.
   *
   * @param x The X coordinate where to tap
   * @param y The Y coordinate where to tap
   * @param duration How long to hold the touch in milliseconds
   *
   * > Example: Used for left clicks, middle clicks (long press), and right clicks (context menu).
   */
  private fun tapGesture(
    x: Float = mousePointerLayout.x.toFloat(),
    y: Float = mousePointerLayout.y.toFloat(),
    duration: Long = 100,
  ) {
    log.info { "Tap gesture at [$x, $y] with duration ${duration}ms" }

    val path = Path().apply { moveTo(x, y) }
    val gesture =
      GestureDescription.Builder()
        .setDisplayId(activeDisplayId)
        .addStroke(StrokeDescription(path, 0, duration))
        .build()
    dispatchGesture(gesture, gestureResultCallback, globalInputHandler)
  }

  /**
   * Dispatch a complete long-press gesture (single finger held 600ms) to trigger context menus.
   * The gesture runs to completion on its own; button UP for the right-click is ignored.
   */
  private fun dispatchLongPress(x: Float, y: Float) {
    log.info { "Long-press gesture at [$x, $y]" }

    val path = Path().apply { moveTo(x, y) }
    val gesture =
      GestureDescription.Builder()
        .setDisplayId(activeDisplayId)
        .addStroke(StrokeDescription(path, 0, 600))
        .build()
    dispatchGesture(gesture, gestureResultCallback, globalInputHandler)
  }

  /**
   * Start a speculative hold gesture that can be converted to a drag.
   * This provides immediate tactile feedback when the mouse button is pressed.
   * The gesture completes immediately (due to willContinue=true), but the drag state
   * remains active, waiting for either mouse movement (convert to drag) or button release (end cleanly).
   *
   * Supports multi-touch by creating multiple stroke descriptions for simulated fingers.
   * The number of fingers is determined by the button ID (can be extended to support multi-touch).
   *
   * @param x The X coordinate where to start the hold
   * @param y The Y coordinate where to start the hold
   * @param fingerCount Number of fingers to simulate (1, 2, or 3). Defaults to 1.
   */
  private fun startSpeculativeHold(x: Float, y: Float, fingerCount: Int = 1) {
    // If there's already an active drag, ignore this
    if (activeDragState != null) {
      log.warn { "Speculative hold ignored - drag already active" }
      return
    }

    val clampedFingerCount = fingerCount.coerceIn(1, 3)

    log.debug { "Starting speculative hold at [$x, $y] with $clampedFingerCount finger(s)" }

    dragGestureInProgress = true

    // Create touch-down with willContinue=true for each finger
    // Note: With willContinue=true, this will complete almost immediately regardless of duration
    // The drag state remains active until mouse moves (-> drag) or button up (-> release)
    val strokes = mutableListOf<StrokeDescription>()
    val gestureBuilder = GestureDescription.Builder().setDisplayId(activeDisplayId)

    for (fingerIndex in 0 until clampedFingerCount) {
      val (offsetX, offsetY) = multiTouchFingerOffsets[fingerIndex]
      val fingerX = x + offsetX
      val fingerY = y + offsetY

      val path = Path().apply { moveTo(fingerX, fingerY) }
      val stroke = StrokeDescription(path, (fingerIndex * 5).toLong(), 100, true) // duration doesn't matter with willContinue=true
      strokes.add(stroke)
      gestureBuilder.addStroke(stroke)
    }

    val gesture = gestureBuilder.build()

    // Create drag state to track this speculative hold
    val dragState = DragState(
      startX = x,
      startY = y,
      lastDispatchedX = x,
      lastDispatchedY = y,
      targetX = x,
      targetY = y,
      lastStrokes = strokes,
      initialHoldDuration = 0, // Will be set when/if converted to actual drag
      fingerCount = clampedFingerCount
    )
    activeDragState = dragState

    dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        log.debug { "Speculative hold gesture dispatched successfully ($clampedFingerCount finger(s))" }
        dragGestureInProgress = false
        // With willContinue=true, this will complete almost immediately (~20ms)
        // The drag state remains active - we're waiting for either:
        // 1. Mouse movement -> convert to drag
        // 2. Mouse button up -> end cleanly
        // Do NOT clean up activeDragState here!

        // Check if we already have a pending end gesture (a very fast click happened)
        if (activeDragState?.isEnding == true) {
          log.debug { "Dispatching deferred end gesture after speculative hold completed" }
          dispatchFinalStroke()
        }
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Speculative hold cancelled" }
        activeDragState = null
        dragGestureInProgress = false
      }
    }, globalInputHandler)
  }

  /**
   * Update drag to new position by continuing the stroke.
   * Updates target and dispatches immediately to minimize position skipping.
   */
  private fun updateDragGesture(toX: Float, toY: Float) {
    val dragState = activeDragState ?: return

    // Always update target position
    dragState.targetX = toX
    dragState.targetY = toY

    // If previous gesture still in progress, position is queued and will be dispatched on completion
    if (dragGestureInProgress) {
      return
    }

    // Dispatch continuation now that previous gesture is complete
    dispatchDragContinuation()
  }

  /**
   * Dispatch a drag continuation stroke from the last completed position to the current target position.
   * Must only be called when no gesture is in progress (dragGestureInProgress == false).
   * Handles multi-touch by continuing each finger's stroke independently with their relative offsets.
   */
  private fun dispatchDragContinuation() {
    val dragState = activeDragState ?: return

    // Check if we're ending the drag
    if (dragState.isEnding) {
      dispatchFinalStroke()
      return
    }

    // Only dispatch if we haven't already reached the target position
    if (dragState.lastDispatchedX == dragState.targetX &&
        dragState.lastDispatchedY == dragState.targetY) {
      log.debug { "Already at target position [${dragState.targetX}, ${dragState.targetY}], skipping" }
      return
    }

    val lastStrokes = dragState.lastStrokes
    if (lastStrokes.isEmpty()) {
      log.warn { "No last strokes to continue from" }
      return
    }

    val fromX = dragState.lastDispatchedX
    val fromY = dragState.lastDispatchedY
    val toX = dragState.targetX
    val toY = dragState.targetY

    log.debug { "Dispatching drag continuation from [$fromX, $fromY] to [$toX, $toY] (${lastStrokes.size} finger(s))" }

    dragGestureInProgress = true

    // Continue each finger's stroke independently with their relative offsets
    val continuedStrokes = mutableListOf<StrokeDescription>()
    val gestureBuilder = GestureDescription.Builder().setDisplayId(activeDisplayId)

    for ((fingerIndex, lastStroke) in lastStrokes.withIndex()) {
      val (offsetX, offsetY) = multiTouchFingerOffsets[fingerIndex]
      val fromFingerX = fromX + offsetX
      val fromFingerY = fromY + offsetY
      val toFingerX = toX + offsetX
      val toFingerY = toY + offsetY

      val path = Path().apply {
        moveTo(fromFingerX, fromFingerY)
        lineTo(toFingerX, toFingerY)
      }

      // Continue with 50ms duration, startTime=0 for immediate movement
      // Note: The hold has already been provided by the speculative hold gesture
      val stroke = lastStroke.continueStroke(path, 0, 50, true) // willContinue = true
      continuedStrokes.add(stroke)
      gestureBuilder.addStroke(stroke)
    }

    dragState.lastStrokes = continuedStrokes
    dragState.lastDispatchedX = toX
    dragState.lastDispatchedY = toY

    val gesture = gestureBuilder.build()

    dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        log.debug { "Drag continuation completed" }
        dragGestureInProgress = false

        // If position has changed while we were dispatching, send another continuation
        if (activeDragState != null) {
          // Check if there's a new position to continue to
          dispatchDragContinuation()
        }
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Drag continuation cancelled" }
        activeDragState = null
        dragGestureInProgress = false
      }
    }, globalInputHandler)
  }

  /**
   * End the active drag gesture by completing the final stroke.
   */
  private fun endDragGesture(endX: Float, endY: Float) {
    val dragState = activeDragState
    if (dragState == null) {
      log.warn { "endDragGesture called but no active drag state" }
      return
    }

    // Update target to end position and mark as ending
    dragState.targetX = endX
    dragState.targetY = endY
    dragState.isEnding = true

    // If a gesture is in progress, let it complete first
    if (dragGestureInProgress) {
      log.debug { "Gesture in progress, will end after completion at [$endX, $endY]" }
      return
    }

    // Dispatch the final stroke
    dispatchFinalStroke()
  }

  /**
   * Dispatch the final drag stroke that releases the touch.
   * Handles multi-touch by ending each finger's stroke independently.
   */
  private fun dispatchFinalStroke() {
    val dragState = activeDragState ?: return

    val lastStrokes = dragState.lastStrokes
    if (lastStrokes.isEmpty()) {
      log.warn { "dispatchFinalStroke called but no last strokes" }
      activeDragState = null
      return
    }

    val endX = dragState.targetX
    val endY = dragState.targetY

    // Determine if this is a speculative hold that never became a drag
    val isSpeculativeHoldClick = dragState.initialHoldDuration == 0L

    if (isSpeculativeHoldClick) {
      log.debug { "Ending speculative hold as click at [$endX, $endY] (${lastStrokes.size} finger(s))" }
    } else {
      log.debug { "Ending drag gesture from [${dragState.lastDispatchedX}, ${dragState.lastDispatchedY}] to [$endX, $endY] (${lastStrokes.size} finger(s))" }
    }

    dragGestureInProgress = true

    // End each finger's stroke independently with their relative offsets
    val finalStrokes = mutableListOf<StrokeDescription>()
    val gestureBuilder = GestureDescription.Builder().setDisplayId(activeDisplayId)

    for ((fingerIndex, lastStroke) in lastStrokes.withIndex()) {
      val (offsetX, offsetY) = multiTouchFingerOffsets[fingerIndex]
      val fromFingerX = dragState.lastDispatchedX + offsetX
      val fromFingerY = dragState.lastDispatchedY + offsetY
      val toFingerX = endX + offsetX
      val toFingerY = endY + offsetY

      val path = Path().apply {
        moveTo(fromFingerX, fromFingerY)
        lineTo(toFingerX, toFingerY)
      }

      // Final stroke: short duration, willContinue = false to release touch
      val stroke = lastStroke.continueStroke(path, 0, 10, false)
      finalStrokes.add(stroke)
      gestureBuilder.addStroke(stroke)
    }

    val gesture = gestureBuilder.build()

    dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        log.debug { "Drag end gesture completed" }
        activeDragState = null
        dragGestureInProgress = false
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Drag end gesture cancelled" }
        activeDragState = null
        dragGestureInProgress = false
      }
    }, globalInputHandler)
  }

  /**
   * Check if the mouse has moved enough to be considered a drag operation.
   * Returns true if movement exceeds dragThreshold.
   */
  private fun isDragMovement(startX: Int, startY: Int, currentX: Int, currentY: Int): Boolean {
    val deltaX = abs(currentX - startX)
    val deltaY = abs(currentY - startY)
    val distance = kotlin.math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble())
    return distance > dragThreshold
  }

  /**
   * Move the mouse pointer to the specified coordinates. This is used to update
   * the position of the mouse pointer on the screen.
   * > Example: Used for mouse movement in the global input service.
   */
  private fun moveMousePointer(x: Int, y: Int) {
    if (!mousePointerVisible) return

    val screenSize = getScreenSize(activeDisplayId)
    log.debug {
      "Cursor move to [${x}, ${y}] with size(${screenSize.px.width},${screenSize.px.height})"
    }
    mousePointerLayout.x = x
    mousePointerLayout.y = y

    windowManager.updateViewLayout(mousePointerView, mousePointerLayout)
  }

  /**
   * Handle mouse events from the server. This processes mouse movement, clicks, and wheel events.
   * Tracks button state for drag-and-drop support.
   * > Example: Used for mouse movement and clicking in the global input service.
   */
  private fun onMouseEvent(event: MouseEvent) {
    // On TV, use native mouse input injection instead of touch gestures
    if (isTV) {
      onMouseEventTV(event)
      return
    }

    when (event.type) {
      MouseEvent.Type.Move -> {
        val currentX = event.x
        val currentY = event.y
        moveMousePointer(currentX, currentY)

        // Keep the screen on during mouse movement
        screenWakelockManager.onInputActivity()

        // Check if we're in a drag operation
        mouseButtonDown?.let { buttonState ->
          val dragState = activeDragState
          if (dragState != null) {
            // Check if this is a speculative hold that needs to be converted to actual drag
            if (dragState.initialHoldDuration == 0L && isDragMovement(buttonState.downX, buttonState.downY, currentX, currentY)) {
              // Convert speculative hold to drag
              val buttonDownDuration = System.currentTimeMillis() - buttonState.downTime
              log.debug { "Converting speculative hold to drag: from [${buttonState.downX},${buttonState.downY}] to [$currentX,$currentY], held for ${buttonDownDuration}ms" }
              dragState.initialHoldDuration = buttonDownDuration
              dragState.targetX = currentX.toFloat()
              dragState.targetY = currentY.toFloat()
              // Now that it's converted, dispatch the first drag continuation
              if (!dragGestureInProgress) {
                dispatchDragContinuation()
              }
            } else if (dragState.initialHoldDuration == 0L) {
              // Still in speculative hold (hasn't exceeded threshold), just update target position
              dragState.targetX = currentX.toFloat()
              dragState.targetY = currentY.toFloat()
            } else {
              // Already dragging, update the path
              updateDragGesture(currentX.toFloat(), currentY.toFloat())
            }
          }
        }
      }

      MouseEvent.Type.MoveRelative -> {
        val newX = mousePointerLayout.x + event.x
        val newY = mousePointerLayout.y + event.y
        moveMousePointer(newX, newY)

        // Keep the screen on during mouse movement
        screenWakelockManager.onInputActivity()

        // Check if we're in a drag operation
        mouseButtonDown?.let { buttonState ->
          val dragState = activeDragState
          if (dragState != null) {
            // Check if this is a speculative hold that needs to be converted to actual drag
            if (dragState.initialHoldDuration == 0L && isDragMovement(buttonState.downX, buttonState.downY, newX, newY)) {
              // Convert speculative hold to drag
              val buttonDownDuration = System.currentTimeMillis() - buttonState.downTime
              log.debug { "Converting speculative hold to drag (relative): from [${buttonState.downX},${buttonState.downY}] to [$newX,$newY], held for ${buttonDownDuration}ms" }
              dragState.initialHoldDuration = buttonDownDuration
              dragState.targetX = newX.toFloat()
              dragState.targetY = newY.toFloat()
              // Now that it's converted, dispatch the first drag continuation
              if (!dragGestureInProgress) {
                dispatchDragContinuation()
              }
            } else if (dragState.initialHoldDuration == 0L) {
              // Still in speculative hold (hasn't exceeded threshold), just update target position
              dragState.targetX = newX.toFloat()
              dragState.targetY = newY.toFloat()
            } else {
              // Already dragging, update the path
              updateDragGesture(newX.toFloat(), newY.toFloat())
            }
          }
        }
      }

      MouseEvent.Type.Down -> {
        // Track button down state for drag detection
        mouseButtonDown = MouseButtonState(
          buttonId = event.id,
          downX = mousePointerLayout.x,
          downY = mousePointerLayout.y
        )
        log.debug { "Mouse button down: id=${event.id}, pos=[${mousePointerLayout.x}, ${mousePointerLayout.y}]" }

        // For left button (id=1), start a speculative hold gesture immediately
        // This provides immediate feedback and can be converted to drag if mouse moves
        if (event.id.toInt() == 1) {
          startSpeculativeHold(mousePointerLayout.x.toFloat(), mousePointerLayout.y.toFloat())
        }

        // For middle button (id=2), start a 3-finger speculative hold gesture immediately
        if (event.id.toInt() == 2) {
          startSpeculativeHold(mousePointerLayout.x.toFloat(), mousePointerLayout.y.toFloat(), 3)
        }

        // For right button (id=3), dispatch a long-press gesture to trigger context menus
        if (event.id.toInt() == 3) {
          dispatchLongPress(mousePointerLayout.x.toFloat(), mousePointerLayout.y.toFloat())
        }
      }

      MouseEvent.Type.Up -> {
        val buttonState = mouseButtonDown

        val buttonId = event.id.toInt()
        val currentX = mousePointerLayout.x.toFloat()
        val currentY = mousePointerLayout.y.toFloat()

        log.debug { "Mouse button up: id=$buttonId, pos=[$currentX, $currentY]" }

        // Right-click long-press is a self-completing gesture; nothing to do on UP
        if (buttonId == 3) {
          mouseButtonDown = null
          return
        }

        // Check if we have an active drag gesture
        val dragState = activeDragState
        if (dragState != null) {
          // Check if this was a speculative hold that was never converted to drag
          if (dragState.initialHoldDuration == 0L) {
            // Calculate click duration before clearing button state
            val clickDuration = buttonState?.let {
              System.currentTimeMillis() - it.downTime
            } ?: 100L
            log.debug { "Ending speculative hold (no drag occurred) - will act as click with duration ${clickDuration}ms" }

            // Clear button state now
            mouseButtonDown = null

            // End the drag gesture - will release the held touch
            endDragGesture(currentX, currentY)
            return
          } else {
            // This was an actual drag operation
            log.debug { "Ending active drag operation with button $buttonId" }
            mouseButtonDown = null // Clear button state
            endDragGesture(currentX, currentY)
            return
          }
        }

        // No active drag - clear button state before processing normal click
        mouseButtonDown = null

        // Calculate how long the button was held down
        val buttonDownDuration = buttonState?.let {
          System.currentTimeMillis() - it.downTime
        } ?: 100L // Default to 100ms if no button state

        // Perform different gestures based on button
        // Button mapping based on Synergy protocol:
        // 1 = Left button, 2 = Middle button, 3 = Right button, 4 = Side Back, 5 = Side Forward
        // Buttons 1-3 are handled by speculative hold for 1, 3 and 2 finger tap/hold/drag respectively
        when (buttonId) {
          4 -> {
            // Side Back button - trigger Android Back action
            log.debug { "Side Back button (button 4) - performing Back action" }
            performGlobalAction(GLOBAL_ACTION_BACK)
          }
          5 -> {
            // Side Forward button - trigger Recent Apps (task switcher)
            log.debug { "Side Forward button (button 5) - performing Recent Apps action" }
            performGlobalAction(GLOBAL_ACTION_RECENTS)
          }
          else -> {
            // Unknown buttons - default to normal click with actual duration
            log.info { "Button $buttonId click (default) at [$currentX, $currentY] held for ${buttonDownDuration}ms" }
            tapGesture(currentX, currentY, buttonDownDuration)
          }
        }
      }

      MouseEvent.Type.Wheel -> {
        // Single mouse wheel "click" seems to be 120 for me
        if (abs(event.y) < 30) return

        val scrollUp = event.y > 0
        log.debug { "Wheel [x=${event.x}, y=${event.y}, scrollUp=$scrollUp]" }

        // Check if Control key is held for pinch-zoom gestures
        val modifierKeys = keyboardManager.state.value.modifierKeys.modifierKeys
        val isControlHeld = modifierKeys.contains(Keyboard.ModifierKey.Control)

        if (isControlHeld) {
          // Control + wheel = pinch zoom gestures
          if (scrollUp) {
            // Wheel up + Control = spread (zoom in)
            spreadGesture()
          } else {
            // Wheel down + Control = pinch (zoom out)
            pinchGesture()
          }
        } else {
          // Normal scrolling without Control key
          scrollSwipe(up = scrollUp, delta = abs(event.y))

          // Horizontal scroll
          if (abs(event.x) > 30) {
            val scrollLeft = event.x < 0
            horizontalScrollSwipe(left = scrollLeft, delta = abs(event.x))
          }
        }
      }
    }
  }

  /**
   * Perform a spread gesture (zoom in) at the mouse pointer location.
   * Creates a two-finger gesture that spreads apart to simulate zoom in.
   */
  private fun spreadGesture() {
    val screenSize = getScreenSize(activeDisplayId)
    val pointerX = mousePointerLayout.x.toFloat()
    val pointerY = mousePointerLayout.y.toFloat()

    // Finger 1: swipe from center-right to right
    val path1 = Path().apply {
      moveTo(pointerX + 25, pointerY)
      lineTo(pointerX + 75, pointerY)
    }

    // Finger 2: swipe from center-left to left
    val path2 = Path().apply {
      moveTo(pointerX - 25, pointerY)
      lineTo(pointerX - 75, pointerY)
    }

    val stroke1 = StrokeDescription(path1, 0, 200, true) // willContinue=true to hold touch at the end
    val stroke2 = StrokeDescription(path2, 5, 200, true)

    val gesture = GestureDescription.Builder()
      .setDisplayId(activeDisplayId)
      .addStroke(stroke1)
      .addStroke(stroke2)
      .build()

    log.debug { "Spread gesture (zoom in) at [$pointerX, $pointerY]" }

    dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        log.debug { "Spread stroke completed, releasing with delay to stop gesture inertia" }
        // Release the spread gesture by continuing the existing strokes and lifting both fingers from their held positions.
        val point1 = Path().apply { moveTo(pointerX + 75, pointerY) }
        val point2 = Path().apply { moveTo(pointerX - 75, pointerY) }

        // Continue the existing strokes with a start delay, then lift (willContinue=false)
        val lastStroke1 = stroke1.continueStroke(point1, 50, 50, false)
        val lastStroke2 = stroke2.continueStroke(point2, 50, 50, false)

        val releaseGesture = GestureDescription.Builder()
          .setDisplayId(activeDisplayId)
          .addStroke(lastStroke1)
          .addStroke(lastStroke2)
          .build()
        dispatchGesture(releaseGesture, gestureResultCallback, globalInputHandler)
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Spread gesture cancelled" }
      }
    }, globalInputHandler)
  }

  /**
   * Perform a pinch gesture (zoom out) at the mouse pointer location.
   * Creates a two-finger gesture that comes together to simulate zoom out.
   * Uses willContinue=true to hold at the pinch point, then releases on completion callback.
   */
  private fun pinchGesture() {
    val screenSize = getScreenSize(activeDisplayId)
    val pointerX = mousePointerLayout.x.toFloat()
    val pointerY = mousePointerLayout.y.toFloat()

    // Finger 1: swipe from right to center-right
    val path1 = Path().apply {
      moveTo(pointerX + 75, pointerY)
      lineTo(pointerX + 25, pointerY)
    }

    // Finger 2: swipe from left to center-left
    val path2 = Path().apply {
      moveTo(pointerX - 75, pointerY)
      lineTo(pointerX - 25, pointerY)
    }

    val stroke1 = StrokeDescription(path1, 0, 200, true) // willContinue=true to hold touch at the end
    val stroke2 = StrokeDescription(path2, 5, 200, true)

    val gesture = GestureDescription.Builder()
      .setDisplayId(activeDisplayId)
      .addStroke(stroke1)
      .addStroke(stroke2)
      .build()

    log.debug { "Pinch gesture (zoom out) at [$pointerX, $pointerY]" }

    dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        log.debug { "Pinch stroke completed, releasing with delay to stop gesture inertia" }
        // Release the pinch gesture by continuing the existing strokes and lifting both fingers from their held positions.
        val point1 = Path().apply { moveTo(pointerX + 25, pointerY) }
        val point2 = Path().apply { moveTo(pointerX - 25, pointerY) }

        // Continue the existing strokes with a start delay, then lift (willContinue=false)
        val lastStroke1 = stroke1.continueStroke(point1, 50, 50, false)
        val lastStroke2 = stroke2.continueStroke(point2, 50, 50, false)

        val releaseGesture = GestureDescription.Builder()
          .setDisplayId(activeDisplayId)
          .addStroke(lastStroke1)
          .addStroke(lastStroke2)
          .build()
        dispatchGesture(releaseGesture, gestureResultCallback, globalInputHandler)
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.warn { "Pinch gesture cancelled" }
      }
    }, globalInputHandler)
  }

  /**
   * Perform a scroll swipe gesture at the mouse pointer location.
   * This creates a small vertical swipe to simulate scrolling.
   *
   * @param up true to scroll up (swipe down gesture), false to scroll down (swipe up gesture)
   * @param delta raw wheel delta from Synergy (120 per standard notch)
   */
  private fun scrollSwipe(up: Boolean = false, delta: Int = 120) {
    if (globalInputPending) {
      log.debug { "Scroll ignored - gesture already pending" }
      return
    }

    globalInputPending = true

    val screenSize = getScreenSize(activeDisplayId)
    val screenHeight = screenSize.px.height.toFloat()

    // Use pointer position as center of swipe
    val swipeX = mousePointerLayout.x.toFloat()
    val currentY = mousePointerLayout.y.toFloat()

    // Scale distance proportionally: ~5% of screen height per standard notch (120 units)
    val rawDistance = screenHeight * (delta.toFloat() / 120f) * 0.05f
    val scrollDistance = rawDistance.coerceIn(screenHeight * 0.02f, screenHeight * 0.25f)

    val (startY, endY) = when {
      // On home screen, wheel UP should pull up app drawer from bottom
      // TODO: homescreen special handling disabled for now, maybe make configurable later
      false && up && isHomeScreenActive -> {
        Pair(
          max(screenHeight * 0.6f - scrollDistance * 2, 0f),  // endY becomes start (top position)
          screenHeight * 0.6f  // startY becomes end (bottom position)
        )
      }
      // Normal scroll up in apps
      up -> {
        Pair(currentY + scrollDistance / 2, currentY - scrollDistance / 2)
      }
      // On home screen, wheel DOWN should pull down notification shade from top
      // TODO: homescreen special handling disabled for now, maybe make configurable later
      false && !up && isHomeScreenActive -> {
        Pair(
          min(screenHeight * 0.3f + scrollDistance * 2, screenHeight),  // endY becomes start (bottom position)
          screenHeight * 0.3f  // startY becomes end (top position)
        )
      }
      // Normal scroll down in apps
      else -> {
        Pair(currentY - scrollDistance / 2, currentY + scrollDistance / 2)
      }
    }

    // Ensure swipe stays within screen bounds
    val clampedStartY = startY.coerceIn(0f, screenHeight)
    val clampedEndY = endY.coerceIn(0f, screenHeight)

    val path = Path().apply {
      moveTo(swipeX, clampedEndY)
      lineTo(swipeX, clampedStartY)
    }

    // Swipe for 150ms for more responsive scrolling
    val stroke = StrokeDescription(path, 0, 150)
    val gesture = GestureDescription.Builder()
      .setDisplayId(activeDisplayId)
      .addStroke(stroke)
      .build()

    log.debug {
      "Scroll swipe: up=$up, homeScreen=$isHomeScreenActive, x=$swipeX, startY=$clampedStartY, endY=$clampedEndY, distance=${abs(clampedEndY - clampedStartY)}"
    }

    dispatchGesture(gesture, gestureResultCallback, globalInputHandler)
  }

  /**
   * Perform a horizontal scroll swipe gesture at the mouse pointer location.
   *
   * @param left true to scroll left, false to scroll right
   * @param delta raw horizontal wheel delta from Synergy (120 per standard notch)
   */
  private fun horizontalScrollSwipe(left: Boolean, delta: Int = 120) {
    if (globalInputPending) {
      log.debug { "Horizontal scroll ignored - gesture already pending" }
      return
    }

    globalInputPending = true

    val screenSize = getScreenSize(activeDisplayId)
    val screenWidth = screenSize.px.width.toFloat()

    val swipeY = mousePointerLayout.y.toFloat()
    val currentX = mousePointerLayout.x.toFloat()

    val rawDistance = screenWidth * (delta.toFloat() / 120f) * 0.05f
    val scrollDistance = rawDistance.coerceIn(screenWidth * 0.02f, screenWidth * 0.25f)

    val (startX, endX) = if (left) {
      Pair(currentX + scrollDistance / 2, currentX - scrollDistance / 2)
    } else {
      Pair(currentX - scrollDistance / 2, currentX + scrollDistance / 2)
    }

    val clampedStartX = startX.coerceIn(0f, screenWidth)
    val clampedEndX = endX.coerceIn(0f, screenWidth)

    val path = Path().apply {
      moveTo(clampedEndX, swipeY)
      lineTo(clampedStartX, swipeY)
    }

    val stroke = StrokeDescription(path, 0, 150)
    val gesture = GestureDescription.Builder()
      .setDisplayId(activeDisplayId)
      .addStroke(stroke)
      .build()

    log.debug {
      "Horizontal scroll swipe: left=$left, y=$swipeY, startX=$clampedStartX, endX=$clampedEndX, distance=${abs(clampedEndX - clampedStartX)}"
    }

    dispatchGesture(gesture, gestureResultCallback, globalInputHandler)
  }

  /**
   * Reinitialize the mouse pointer view and window manager.
   * Returns true if reinitialization succeeded and view is ready to be added.
   */
  private fun reinitializeMousePointer(): Boolean {
    log.info { "Reinitializing mouse pointer" }

    try {
      // Try to remove old view if it exists (may fail, that's ok)
      if (mousePointerVisible) {
        try {
          windowManager.removeView(mousePointerView)
        } catch (e: Exception) {
          log.debug { "Could not remove old mouse pointer view: ${e.message}" }
        }
        mousePointerVisible = false
      }

      // Save old position
      val oldX = mousePointerLayout.x
      val oldY = mousePointerLayout.y

      // Recreate the view - this gets a fresh view instance
      mousePointerView = View.inflate(this, R.layout.mouse_pointer, null)

      // Get the active display and its WindowManager
      val activeDisplay = getActiveDisplay()
      activeDisplayId = activeDisplay.displayId
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val displayContext = createDisplayContext(activeDisplay)
        windowManager = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
      } else {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
      }

      log.debug { "Reinitialized WindowManager for display ID: ${activeDisplay.displayId}" }

      // Recreate layout params with fresh instance
      mousePointerLayout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      )

      mousePointerLayout.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
      mousePointerLayout.gravity = Gravity.TOP or Gravity.LEFT
      mousePointerLayout.x = oldX
      mousePointerLayout.y = oldY

      // TYPE_ACCESSIBILITY_OVERLAY does not require SYSTEM_ALERT_WINDOW permission

      log.info { "Mouse pointer reinitialized successfully" }
      return true
    } catch (err: Exception) {
      log.error(err) { "Error reinitializing mouse pointer" }
      return false
    }
  }

  /**
   * Show the mouse pointer overlay.
   * Safe to call multiple times - will only add view if not already visible.
   */
  private fun showMousePointer() {
    if (mousePointerVisible) {
      log.debug { "Mouse pointer already visible, skipping" }
      return
    }

    if (!isServiceConnected) {
      log.warn { "Cannot show mouse pointer - accessibility service not yet connected" }
      // Broadcast that service is disconnected so UI can respond
      sendServiceDisconnectionEvent<GlobalInputService>()
      return
    }

    // TYPE_ACCESSIBILITY_OVERLAY does not require SYSTEM_ALERT_WINDOW permission

    // Log display configuration before showing pointer
    log.info { "Attempting to show mouse pointer - checking display configuration" }
    logDisplayInformation()

    try {
      // Detect the active display
      val activeDisplay = getActiveDisplay()
      val targetDisplayId = activeDisplay.displayId

      // Check if our current WindowManager is for a different display
      if (activeDisplayId != targetDisplayId) {
        log.info { "WindowManager display mismatch: current=$activeDisplayId, target=$targetDisplayId - reinitializing" }
        if (!reinitializeMousePointer()) {
          log.error { "Failed to reinitialize WindowManager for display $targetDisplayId" }
          return
        }
        log.info { "WindowManager reinitialized successfully for display $targetDisplayId" }
      }

      log.info { "Adding mouse pointer to display ID: $targetDisplayId" }

      windowManager.addView(mousePointerView, mousePointerLayout)

      mousePointerVisible = true
      screenWakelockManager.onInputActivity()
      log.info { "Mouse pointer shown on display $targetDisplayId" }
    } catch (err: IllegalStateException) {
      // View already added (race between onServiceConnected and monitorConnectionState)
      log.debug { "Mouse pointer view already added: ${err.message}" }
      mousePointerVisible = true
    } catch (err: android.view.WindowManager.BadTokenException) {
      log.error(err) { "BadTokenException when showing mouse pointer - window manager token invalid, attempting reinitialize" }
      mousePointerVisible = false

      // Try to reinitialize as a fallback
      if (reinitializeMousePointer()) {
        // Reinitialization succeeded, try adding view one more time with fresh WindowManager
        try {
          // reinitializeMousePointer() has already updated windowManager and activeDisplayId
          windowManager.addView(mousePointerView, mousePointerLayout)
          mousePointerVisible = true
          screenWakelockManager.onInputActivity()
          log.info { "Mouse pointer shown successfully after reinitialization on display $activeDisplayId" }
        } catch (retryErr: Exception) {
          log.error(retryErr) { "Error showing mouse pointer after reinitialization" }
        }
      } else {
        log.error { "Reinitialization failed, cannot show mouse pointer" }
      }
    } catch (err: Exception) {
      log.error(err) { "Error showing mouse pointer" }
      mousePointerVisible = false
    }
  }

  /**
   * Hide the mouse pointer overlay.
   * Safe to call multiple times - will only remove view if currently visible.
   */
  private fun hideMousePointer() {
    if (!mousePointerVisible) {
      log.debug { "Mouse pointer already hidden, skipping" }
      return
    }

    try {
      windowManager.removeView(mousePointerView)
      mousePointerVisible = false
      log.info { "Mouse pointer hidden" }
    } catch (err: Exception) {
      log.error(err) { "Error hiding mouse pointer" }
      // Mark as not visible even if removal failed (view may already be detached)
      mousePointerVisible = false
    }
  }

  /**
   * Inject a key event system-wide using the shell input command.
   * Used on TV devices where touch gestures don't work for navigation.
   */
  private fun injectKeyEvent(keyCode: Int) {
    log.info { "Injecting key event: $keyCode (TV mode)" }
    serviceScope.launch(Dispatchers.IO) {
      try {
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
      } catch (err: Exception) {
        log.error(err) { "Failed to inject key event $keyCode" }
      }
    }
  }

  /**
   * Inject a shell input command for mouse/touch events on TV.
   * Runs on IO dispatcher to avoid blocking the main thread.
   */
  private fun injectInputCommand(vararg args: String) {
    serviceScope.launch(Dispatchers.IO) {
      try {
        Runtime.getRuntime().exec(arrayOf("input", *args))
      } catch (err: Exception) {
        log.error(err) { "Failed to inject input command: ${args.joinToString(" ")}" }
      }
    }
  }

  /**
   * Handle mouse events on TV devices.
   * Injects real system-level mouse/key events so the OS treats them
   * as coming from a physical mouse.
   */
  private fun onMouseEventTV(event: MouseEvent) {
    val helper = if (inputHelper.isConnected) inputHelper else null

    when (event.type) {
      MouseEvent.Type.Move -> {
        moveMousePointer(event.x, event.y)
        screenWakelockManager.onInputActivity()
        if (helper != null) {
          serviceScope.launch(Dispatchers.IO) {
            helper.sendMouseMove(event.x, event.y)
          }
        }
      }
      MouseEvent.Type.MoveRelative -> {
        val newX = mousePointerLayout.x + event.x
        val newY = mousePointerLayout.y + event.y
        moveMousePointer(newX, newY)
        screenWakelockManager.onInputActivity()
        if (helper != null) {
          serviceScope.launch(Dispatchers.IO) {
            helper.sendMouseMove(newX, newY)
          }
        }
      }
      MouseEvent.Type.Down -> {
        if (helper != null) {
          val x = mousePointerLayout.x
          val y = mousePointerLayout.y
          val btn = event.id.toInt()
          serviceScope.launch(Dispatchers.IO) {
            helper.sendMouseDown(x, y, btn)
          }
        }
      }
      MouseEvent.Type.Up -> {
        if (helper != null) {
          val x = mousePointerLayout.x
          val y = mousePointerLayout.y
          val btn = event.id.toInt()
          serviceScope.launch(Dispatchers.IO) {
            helper.sendMouseUp(x, y, btn)
          }
        } else {
          when (event.id.toInt()) {
            1 -> injectClick(mousePointerLayout.x, mousePointerLayout.y)
            3 -> performGlobalAction(GLOBAL_ACTION_BACK)
            4 -> performGlobalAction(GLOBAL_ACTION_BACK)
            5 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
          }
        }
      }
      MouseEvent.Type.Wheel -> {
        if (abs(event.y) < 30 && abs(event.x) < 30) return
        if (helper != null) {
          val x = mousePointerLayout.x
          val y = mousePointerLayout.y
          val hscroll = if (abs(event.x) >= 30) event.x else 0
          val vscroll = if (abs(event.y) >= 30) event.y else 0
          serviceScope.launch(Dispatchers.IO) {
            helper.sendScroll(x, y, hscroll, vscroll)
          }
        } else {
          scrollSwipe(up = event.y > 0, delta = abs(event.y))
        }
      }
    }
  }

  private var injectMethodCache: java.lang.reflect.Method? = null

  /**
   * Click on TV -- same as pressing Enter/Select on a remote.
   */
  private fun injectClick(x: Int, y: Int) {
    clickFocused()
  }

  private fun getInjectMethod(): java.lang.reflect.Method? {
    if (injectMethodCache != null) return injectMethodCache
    return try {
      val im = getSystemService(android.content.Context.INPUT_SERVICE) as android.hardware.input.InputManager
      im.javaClass.getMethod(
        "injectInputEvent",
        android.view.InputEvent::class.java,
        Int::class.javaPrimitiveType
      ).also { injectMethodCache = it }
    } catch (e: Exception) {
      log.error(e) { "Cannot find InputManager.injectInputEvent" }
      null
    }
  }

  private fun makeMouseEvent(
    action: Int, x: Float, y: Float, downTime: Long, eventTime: Long, buttonState: Int
  ): MotionEvent {
    val props = arrayOf(MotionEvent.PointerProperties().apply {
      id = 0
      toolType = MotionEvent.TOOL_TYPE_MOUSE
    })
    val coords = arrayOf(MotionEvent.PointerCoords().apply {
      this.x = x
      this.y = y
      pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
      size = 1f
    })
    return MotionEvent.obtain(
      downTime, eventTime, action,
      1, props, coords,
      0, buttonState,
      1f, 1f, 0, 0,
      InputDevice.SOURCE_MOUSE, 0
    )
  }

  private fun injectMotionClick(x: Float, y: Float): Boolean {
    val method = getInjectMethod() ?: return false
    val im = getSystemService(android.content.Context.INPUT_SERVICE) as android.hardware.input.InputManager
    return try {
      val now = android.os.SystemClock.uptimeMillis()

      // Hover to position first so system knows where the pointer is
      val hover = makeMouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, now, now, 0)
      method.invoke(im, hover, 0)
      hover.recycle()

      val down = makeMouseEvent(MotionEvent.ACTION_DOWN, x, y, now, now + 5, MotionEvent.BUTTON_PRIMARY)
      val up = makeMouseEvent(MotionEvent.ACTION_UP, x, y, now, now + 25, 0)

      val r1 = method.invoke(im, down, 0) as Boolean
      val r2 = method.invoke(im, up, 0) as Boolean
      down.recycle()
      up.recycle()
      log.info { "Injected mouse click at ($x, $y): down=$r1 up=$r2" }
      r1
    } catch (e: Exception) {
      log.error(e) { "injectInputEvent failed: ${e.message}" }
      false
    }
  }

  fun injectKeyEventTV(keyCode: Int): Boolean {
    val method = getInjectMethod() ?: return false
    val im = getSystemService(android.content.Context.INPUT_SERVICE) as android.hardware.input.InputManager
    return try {
      val now = android.os.SystemClock.uptimeMillis()
      val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
      val up = KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
      val r1 = method.invoke(im, down, 0) as Boolean
      val r2 = method.invoke(im, up, 0) as Boolean
      log.info { "Injected key $keyCode: down=$r1 up=$r2" }
      r1
    } catch (e: Exception) {
      log.error(e) { "injectKeyEvent failed: ${e.message}" }
      false
    }
  }

  /**
   * Called when the service is started. This is where we set the service to be
   * sticky and return START_STICKY.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }

  val synergyImeInfo: InputMethodInfo?
    get() =
      imeManager.inputMethodList.find {
        it.packageName.lowercase().contains("synergy")
      }

  val isSynergyImeEnabled: Boolean
    get() =
      imeManager.enabledInputMethodList.any { it.id == synergyImeInfo?.id }

  private val onClipboardChanged =
    object : ClipboardManager.OnPrimaryClipChangedListener {
      override fun onPrimaryClipChanged() {
        catch({
          val clip = clipboard.primaryClip

          log.debug { "onPrimaryClipChanged(clip=$clip)" }
          if (clip == null) return@catch
          val clipDesc = clip.description
          val itemIdx =
            0.rangeUntil(clipDesc.mimeTypeCount).find { idx ->
              listOf(
                  ClipDescription.MIMETYPE_TEXT_PLAIN,
                  ClipDescription.MIMETYPE_TEXT_HTML,
                )
                .contains(clipDesc.getMimeType(idx))
            }

          if (itemIdx == null) {
            log.warn { "No compatible mimetypes in primary clip" }
            return@catch
          }
          val item = clip.getItemAt(itemIdx)
          val text = item.coerceToText(this@GlobalInputService).toString()
          if (text.isBlank()) {
            log.warn { "ignoring empty clipdata \"${text}\"" }
            return@catch
          }
          val clipboardData =
            ClipboardData(
              0,
              0,
              mapOf(
                ClipboardData.Format.Text to
                  ClipboardData.Variant(
                    ClipboardData.Format.Text,
                    text.toByteArray(),
                  )
              ),
            )
          serviceClient.setClipboardData(
            Bundle().apply { putSerializable("clipboardData", clipboardData) }
          )
        }) { err: Throwable ->
          log.error(err) { "unable to update clipboard" }
        }
      }
    }

  /**
   * Called when the service is connected to the system. This is where we set up
   * the cursor and fetch the home packages.
   */
  override fun onServiceConnected() {
    super.onServiceConnected()

    // Mark service as connected - now safe to add overlay windows
    isServiceConnected = true
    log.info { "Accessibility service connected, window overlays now available" }

    // Initialize WindowManager for the active display
    // On API < 30, createDisplayContext loses the accessibility token, so use the service's own WM
    val activeDisplay = getActiveDisplay()
    activeDisplayId = activeDisplay.displayId
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val displayContext = createDisplayContext(activeDisplay)
      windowManager = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
    } else {
      windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }
    log.info { "Initialized WindowManager for display ID: $activeDisplayId" }

    // Update screen dimensions to ConnectionService now that we know the active display
    val screenSize = getScreenSize(activeDisplayId)
    val result = serviceClient.updateScreenDimensions(screenSize.px.width, screenSize.px.height)
    if (result?.ok == true) {
      log.info { "Successfully initialized server with screen dimensions: ${screenSize.px.width}x${screenSize.px.height} for display $activeDisplayId" }
    } else {
      log.warn { "Failed to initialize server with screen dimensions: ${result?.message}" }
    }

    // If cursor was supposed to be showing but was blocked by isServiceConnected=false, show it now
    val state = serviceClient.stateFlow.value
    if (state.isConnected && state.isEnabled && state.screen.isActive && !mousePointerVisible) {
      log.info { "Cursor should be visible but was blocked, showing now" }
      showMousePointer()
    }

    val imeId = synergyImeInfo
    Log.i(TAG, "imeId=$imeId,imeEnabled=$isSynergyImeEnabled")
    clipboard.addPrimaryClipChangedListener(this.onClipboardChanged)

    sendServiceConnectionEvent<GlobalInputService>()

    fetchHomePackages()
    val pkgName = rootInActiveWindow?.packageName
    isHomeScreenActiveFlow.value =
      pkgName == null || knownHomePackages.contains(pkgName)
  }

  /**
   * All accessibility events are received here; we pay little attention to
   * them, but we do check for window state changes to determine if the home
   * screen is active.
   */
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    log.debug { "onAccessibilityEvent: ${event?.eventType}" }

    if (!serviceClient.stateFlow.value.isEnabled) {
      log.debug { "Service not enabled, ignoring accessibility event" }
      return
    }

    when (event?.eventType) {
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
        checkIMESetup()
      }
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        val pkgName = rootInActiveWindow?.packageName
        isHomeScreenActiveFlow.value =
          pkgName == null || knownHomePackages.contains(pkgName)
      }
      else -> {
        log.debug { "Ignored event: ${event?.eventType}" }
      }
    }
  }

  override fun onInterrupt() {
    // Required override.
  }

  /**
   * Fetch the list of known home packages. This is used to determine if the
   * current screen is the home screen.
   */
  private fun fetchHomePackages() {
    val intent =
      Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
    val resolveInfoList =
      packageManager.queryIntentActivities(
        intent,
        PackageManager.MATCH_DEFAULT_ONLY,
      )
    knownHomePackages.clear()
    for (info in resolveInfoList) {
      knownHomePackages.add(info.activityInfo.packageName)
    }
  }

  /**
   * Click the currently focused node. This is useful for clicking on input
   * fields or buttons that are currently focused.
   * > Example: It's used for the DPAD_CENTER action in the system actions.
   */
  private fun clickFocused() {
    val focusedNode = findFocus(FOCUS_INPUT)
    if (focusedNode != null) {
      logNodeHierarchy(focusedNode, 0)
      focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } else {
      log.warn { "No focused node found to click" }
    }
  }

  /**
   * Click the UI element at the given screen coordinates using the accessibility
   * node tree. Searches all windows, walks up to find a clickable ancestor.
   * Falls back to tapGesture if no clickable node is found.
   */
  private fun clickNodeAtPosition(x: Int, y: Int) {
    val allWindows = try { windows } catch (_: Exception) { null }
    if (allWindows == null) {
      log.warn { "No windows available, falling back to tap at ($x, $y)" }
      tapGesture(x.toFloat(), y.toFloat())
      return
    }

    // Collect ALL clickable nodes at this position across all windows
    val clickableNodes = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
    for (window in allWindows) {
      if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
      val root = window.root ?: continue
      collectClickableNodesAtPoint(root, x, y, clickableNodes)
    }

    if (clickableNodes.isEmpty()) {
      log.warn { "No clickable node at ($x, $y), falling back to tap" }
      tapGesture(x.toFloat(), y.toFloat())
      return
    }

    // Pick the smallest (most specific) clickable node by area
    val best = clickableNodes.minByOrNull { (_, b) -> b.width().toLong() * b.height().toLong() }!!
    log.info { "Clicking smallest node at ($x, $y): ${best.first.className} bounds=${best.second} text=${best.first.text} (${clickableNodes.size} candidates)" }
    best.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
  }

  private fun collectClickableNodesAtPoint(
    node: AccessibilityNodeInfo, x: Int, y: Int,
    result: MutableList<Pair<AccessibilityNodeInfo, Rect>>
  ) {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    if (!bounds.contains(x, y)) return

    if (node.isClickable) {
      result.add(node to Rect(bounds))
    }

    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      collectClickableNodesAtPoint(child, x, y, result)
    }
  }

  /**
   * Navigate focus in a direction using the accessibility node tree.
   * Finds the currently focused node, collects all focusable nodes, and
   * moves focus to the nearest one in the requested direction.
   */
  private fun navigateFocusTV(directionActionId: Int) {
    val root = rootInActiveWindow ?: return

    val focused = findFocus(FOCUS_INPUT)
    if (focused == null) {
      val first = findFirstFocusableNode(root)
      if (first != null) {
        log.info { "No focus, focusing first node: ${first.className}" }
        first.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      }
      return
    }

    val srcBounds = Rect().also { focused.getBoundsInScreen(it) }

    val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
    collectFocusableNodes(root, candidates, focused)

    val inDirection = candidates.filter { (_, bounds) ->
      // Skip parent containers that enclose the current focus
      if (bounds.contains(srcBounds)) return@filter false
      when (directionActionId) {
        16 -> bounds.centerY() < srcBounds.centerY()
        17 -> bounds.centerY() > srcBounds.centerY()
        18 -> bounds.centerX() < srcBounds.centerX()
        19 -> bounds.centerX() > srcBounds.centerX()
        else -> false
      }
    }

    val best = inDirection.minByOrNull { (_, bounds) ->
      val dx = abs(bounds.centerX() - srcBounds.centerX()).toDouble()
      val dy = abs(bounds.centerY() - srcBounds.centerY()).toDouble()
      when (directionActionId) {
        16, 17 -> dy + dx * 2.0
        18, 19 -> dx + dy * 2.0
        else -> sqrt(dx * dx + dy * dy)
      }
    }

    if (best != null) {
      val dir = when (directionActionId) {
        16 -> "UP"; 17 -> "DOWN"; 18 -> "LEFT"; 19 -> "RIGHT"; else -> "?"
      }
      log.info { "Focus $dir -> ${best.first.className} bounds=${best.second}" }
      best.first.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    } else {
      // No focusable node in this direction -- scroll the parent container
      val scrollable = findScrollableAncestor(focused)
      if (scrollable != null) {
        val action = when (directionActionId) {
          16, 18 -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
          17, 19 -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
          else -> null
        }
        if (action != null) {
          val ok = scrollable.performAction(action)
          val dir = when (directionActionId) {
            16 -> "UP"; 17 -> "DOWN"; 18 -> "LEFT"; 19 -> "RIGHT"; else -> "?"
          }
          log.info { "No node $dir, scrolled container: $ok" }
        }
      }
    }
  }

  private fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current = node.parent
    var depth = 0
    while (current != null && depth < 20) {
      if (current.isScrollable) return current
      current = current.parent
      depth++
    }
    return null
  }

  private fun collectFocusableNodes(
    node: AccessibilityNodeInfo,
    result: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
    exclude: AccessibilityNodeInfo? = null,
  ) {
    if (node != exclude && node.isFocusable && node.isVisibleToUser) {
      val bounds = Rect()
      node.getBoundsInScreen(bounds)
      if (!bounds.isEmpty) {
        result.add(node to bounds)
      }
    }
    for (i in 0 until node.childCount) {
      node.getChild(i)?.let { collectFocusableNodes(it, result, exclude) }
    }
  }

  private fun findFirstFocusableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isFocusable && node.isVisibleToUser) return node
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val found = findFirstFocusableNode(child)
      if (found != null) return found
    }
    return null
  }

  /// **
  // * Example: continuously move pointer in a circle.
  // * Call this from onServiceConnected or via a button in your overlay.
  // */
  // fun spinPointer(centerX: Int, centerY: Int, radius: Int, steps: Int = 36,
  // intervalMs: Long = 100) {
  //    for (i in 0 until steps) {
  //        uiHandler.postDelayed({
  //            val theta = 2 * Math.PI * i / steps
  //            val x = centerX + (radius * Math.cos(theta)).toInt()
  //            val y = centerY + (radius * Math.sin(theta)).toInt()
  //            movePointer(centerX, centerY, x - centerX, y - centerY)
  //        }, i * intervalMs)
  //    }
  // }

  companion object {
    private const val CHANNEL_ID = "synergy_service_channel"
    private const val NOTIF_IME_NOT_SETUP_ID = 1

    private val TAG = GlobalInputService::class.java.simpleName
    private val log =
      KLoggingManager.logger(GlobalInputService::class.java.simpleName)

    private fun logNodeHierarchy(nodeInfo: AccessibilityNodeInfo, depth: Int) {
      val bounds = Rect()
      nodeInfo.getBoundsInScreen(bounds)

      val sb = StringBuilder()
      if (depth > 0) {
        (0..<depth).forEach { _ -> sb.append("  ") }
        sb.append("\u2514 ")
      }
      sb.append(nodeInfo.className)
      sb.append(" (" + nodeInfo.childCount + ")")
      sb.append(" $bounds")
      if (nodeInfo.getText() != null) {
        sb.append(" - \"" + nodeInfo.getText() + "\"")
      }
      log.trace { sb.toString() }

      for (i in 0..<nodeInfo.childCount) {
        val childNode = nodeInfo.getChild(i)
        if (childNode != null) {
          logNodeHierarchy(childNode, depth + 1)
        }
      }
    }

    private fun findSmallestNodeAtPoint(
      sourceNode: AccessibilityNodeInfo,
      x: Int,
      y: Int,
    ): AccessibilityNodeInfo? {
      val bounds = Rect()
      sourceNode.getBoundsInScreen(bounds)

      if (!bounds.contains(x, y)) {
        return null
      }

      for (i in 0..<sourceNode.childCount) {
        val nearestSmaller =
          findSmallestNodeAtPoint(sourceNode.getChild(i), x, y)
        if (nearestSmaller != null) {
          return nearestSmaller
        }
      }
      return sourceNode
    }
  }
}
