package com.wmsfo.rednose

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.wmsfo.rednose.bridge.RedNoseModule

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "RedNose"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  // Capture the URL the Activity was launched with so getInitialEnrollUrl()
  // can return it once to JS on first read (red-nose.md 9.1).
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    hideSystemBars()
    warmCodeScannerModule()
    handleIntent(intent)
  }

  // Ask Play services to install the code-scanner module now so the first
  // scanQrCode() call does not wait (red-nose.md 9.1).
  // Best effort: a warm-up problem must never take the launcher activity down.
  private fun warmCodeScannerModule() {
    try {
      // ML Kit normally initialises itself through a content provider; on this
      // persistent system app that has not happened by the time onCreate runs.
      MlKit.initialize(applicationContext)
      val client = ModuleInstall.getClient(this)
      val request = ModuleInstallRequest.newBuilder()
        .addApi(GmsBarcodeScanning.getClient(this))
        .build()
      client.installModules(request)
        .addOnSuccessListener { Log.i("rednose", "code scanner module warm-up requested") }
        .addOnFailureListener { e -> Log.w("rednose", "code scanner module warm-up failed: ${e.message}") }
    } catch (e: Throwable) {
      Log.w("rednose", "code scanner warm-up skipped: ${e.message}")
    }
  }

  // Immersive-sticky: no status or navigation bar on the kiosk phone (red-nose.md 14.2).
  // A swipe shows them transiently; regaining focus hides them again.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) hideSystemBars()
  }

  private fun hideSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    val data = intent?.data ?: return
    if (intent.action != Intent.ACTION_VIEW) return
    if (data.scheme != "rednose") return
    val url = data.toString()
    val ctx = reactHost?.currentReactContext
    val module = ctx?.getNativeModule(RedNoseModule::class.java)
    if (module != null) {
      module.handleEnrollUrl(url)
      return
    }
    // ReactContext not yet ready: stash on the class so the module can pick it up
    // when it initializes.
    RedNoseModule.pendingInitialUrl = url
  }
}
