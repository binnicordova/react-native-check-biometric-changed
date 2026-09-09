package com.reactnativecheckbiometricchanged

import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real module against the real AndroidKeyStore on a real device.
 *
 * These assertions deliberately accept either outcome where the device decides
 * it — a device with no strong biometric enrolled cannot mint the tracker key,
 * and rejecting is the correct behaviour there. What is asserted is that the
 * module always settles, and settles coherently.
 */
@RunWith(AndroidJUnit4::class)
class CheckBiometricChangedModuleTest {

  private class Recorder : Promise {
    val latch = CountDownLatch(1)
    var value: Any? = null
    var code: String? = null

    private fun settle(resolved: Any?, errorCode: String?) {
      if (latch.count == 0L) return
      value = resolved
      code = errorCode
      latch.countDown()
    }

    fun await(): Recorder = apply {
      assertTrue("module never settled its promise", latch.await(15, TimeUnit.SECONDS))
    }

    override fun resolve(value: Any?) = settle(value, null)
    override fun reject(code: String?, message: String?) = settle(null, code)
    override fun reject(code: String?, throwable: Throwable?) = settle(null, code)
    override fun reject(code: String?, message: String?, throwable: Throwable?) = settle(null, code)
    override fun reject(throwable: Throwable?) = settle(null, "THROWABLE")
    override fun reject(throwable: Throwable?, userInfo: WritableMap?) = settle(null, "THROWABLE")
    override fun reject(code: String?, userInfo: WritableMap) = settle(null, code)
    override fun reject(code: String?, throwable: Throwable?, userInfo: WritableMap?) = settle(null, code)
    override fun reject(code: String?, message: String?, userInfo: WritableMap) = settle(null, code)
    override fun reject(
      code: String?,
      message: String?,
      throwable: Throwable?,
      userInfo: WritableMap?
    ) = settle(null, code)

    @Deprecated("legacy RN signature", ReplaceWith("reject(code, message)"))
    override fun reject(message: String?) = settle(null, "LEGACY")
  }

  private fun module(): CheckBiometricChangedModule {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return CheckBiometricChangedModule(ReactApplicationContext(context))
  }

  /**
   * Not an assertion so much as a record: these tests branch on what the
   * device offers, so the report should say what that was.
   */
  @Test
  fun device_biometric_posture() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val status = BiometricManager.from(context)
      .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    val label = when (status) {
      BiometricManager.BIOMETRIC_SUCCESS -> "SUCCESS (a Class 3 biometric is enrolled)"
      BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "NONE_ENROLLED"
      BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NO_HARDWARE"
      BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "HW_UNAVAILABLE"
      else -> "OTHER($status)"
    }
    println("BIOMETRIC_STRONG canAuthenticate = $label")
  }

  @Test
  fun biometricsChanged_settles_with_a_boolean_or_a_known_error_code() {
    val result = Recorder().also { module().biometricsChanged(it) }.await()

    if (result.code != null) {
      assertTrue(
        "unexpected error code: ${result.code}",
        result.code in setOf("BIOMETRICS_UNAVAILABLE", "KEYSTORE_ERROR")
      )
    } else {
      assertNotNull(result.value)
      assertTrue("expected a Boolean, got ${result.value}", result.value is Boolean)
    }
  }

  @Test
  fun a_freshly_minted_baseline_reports_unchanged() {
    val refreshed = Recorder().also { module().refreshTracker(it) }.await()

    if (refreshed.code != null) {
      // No strong biometric enrolled: the key cannot be minted, and saying so
      // is correct. Nothing further to assert.
      assertEquals("BIOMETRICS_UNAVAILABLE", refreshed.code)
      return
    }

    assertEquals(true, refreshed.value)

    // The enrolment has not moved since refreshTracker, so the probe must say
    // unchanged. This is the core mechanism.
    val checked = Recorder().also { module().biometricsChanged(it) }.await()
    assertEquals(null, checked.code)
    assertEquals(false, checked.value)
  }
}
