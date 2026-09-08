package com.wmsfo.rednose

import android.content.Intent
import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.wmsfo.rednose.bridge.RedNoseModule

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "RedNose"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  // Capture the URL the Activity was launched with so getInitialEnrollUrl()
  // can return it once to JS on first read (red-nose.md 9.1).
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleIntent(intent)
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
