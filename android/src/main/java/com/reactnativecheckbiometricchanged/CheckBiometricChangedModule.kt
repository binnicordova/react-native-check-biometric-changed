package com.reactnativecheckbiometricchanged

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Objective: answer whether the biometric enrolment backing a device-bound
 * session is still the one that was enrolled when the session was granted.
 *
 * Mechanism: a key in the AndroidKeyStore created with
 * `setInvalidatedByBiometricEnrollment(true)` is permanently invalidated by the
 * OS the moment a biometric is enrolled or removed. Initialising a `Cipher`
 * with that key is therefore a cheap, tamper-proof probe: it throws
 * [KeyPermanentlyInvalidatedException] exactly when the enrolment changed.
 *
 * The key never leaves hardware-backed storage, holds no application data, and
 * is only ever probed and regenerated. No biometric data is read or stored.
 */
class CheckBiometricChangedModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = NAME

  // region biometricsChanged

  /**
   * Resolves `true` when the current enrolment differs from the stored baseline.
   *
   * With no baseline yet, the current enrolment is adopted as the baseline and
   * `false` is returned — there is nothing to have changed from.
   */
  @ReactMethod
  fun biometricsChanged(promise: Promise) {
    try {
      val keyStore = loadKeyStore()
      val hasBaseline = keyStore.containsAlias(KEY_ALIAS)

      when (canAuthenticate()) {
        BiometricManager.BIOMETRIC_SUCCESS -> Unit

        // Enrolment was removed entirely. If we were tracking one, that is
        // precisely the change this module exists to report.
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
          if (hasBaseline) {
            promise.resolve(true)
          } else {
            promise.reject(E_UNAVAILABLE, "No biometric is enrolled on this device.")
          }
          return
        }

        else -> {
          promise.reject(E_UNAVAILABLE, "Biometric authentication is not available on this device.")
          return
        }
      }

      if (!hasBaseline) {
        generateTrackerKey()
        promise.resolve(false)
        return
      }

      // Probing the key is what actually detects the change. `init` on an
      // auth-per-use key does not itself require authentication — it only
      // throws once the OS has invalidated the key.
      promise.resolve(!isTrackerKeyStillValid(keyStore))
    } catch (error: Exception) {
      promise.reject(E_KEYSTORE, error.message ?: "Could not read the biometric baseline.", error)
    }
  }

  // endregion

  // region verifyBiometric

  /**
   * Presents the system biometric prompt. Resolves `true` only after the OS
   * reports a successful authentication.
   */
  @ReactMethod
  fun verifyBiometric(promise: Promise) {
    val activity = currentActivity as? FragmentActivity
    if (activity == null) {
      promise.reject(E_NO_ACTIVITY, "No foreground activity is available to host the biometric prompt.")
      return
    }

    if (canAuthenticate() != BiometricManager.BIOMETRIC_SUCCESS) {
      promise.reject(E_UNAVAILABLE, "Biometric authentication is not available on this device.")
      return
    }

    // The prompt can report success and then an error (or vice versa) across
    // callbacks; a promise may only settle once.
    val settled = AtomicBoolean(false)

    UiThreadUtil.runOnUiThread {
      val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          if (settled.compareAndSet(false, true)) promise.resolve(true)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          if (!settled.compareAndSet(false, true)) return
          when (errorCode) {
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
              promise.reject(E_LOCKED_OUT, errString.toString())

            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE ->
              promise.reject(E_UNAVAILABLE, errString.toString())

            // Cancelled, dismissed or timed out: a real person declined.
            // Not an error — just not verified.
            else -> promise.resolve(false)
          }
        }

        // A single bad finger. The prompt stays up for another attempt, so the
        // promise must not settle here.
        override fun onAuthenticationFailed() = Unit
      }

      val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(PROMPT_TITLE)
        .setSubtitle(PROMPT_SUBTITLE)
        .setNegativeButtonText(PROMPT_NEGATIVE)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setConfirmationRequired(true)
        .build()

      try {
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
          .authenticate(info)
      } catch (error: Exception) {
        if (settled.compareAndSet(false, true)) {
          promise.reject(E_VERIFICATION, error.message ?: "Could not present the biometric prompt.", error)
        }
      }
    }
  }

  // endregion

  // region refreshTracker

  /**
   * Adopts the current enrolment as the trusted baseline by discarding the old
   * key and minting a new one against it.
   *
   * Security-critical: this is what re-trusts the device. Gate it behind a
   * successful [verifyBiometric] and, for anything high-value, a server-verified
   * login too.
   */
  @ReactMethod
  fun refreshTracker(promise: Promise) {
    try {
      if (canAuthenticate() != BiometricManager.BIOMETRIC_SUCCESS) {
        promise.reject(E_UNAVAILABLE, "There is no biometric enrolment to record.")
        return
      }

      val keyStore = loadKeyStore()
      if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
      generateTrackerKey()
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject(E_KEYSTORE, error.message ?: "Could not record the biometric baseline.", error)
    }
  }

  // endregion

  // region multiply

  /**
   * Scaffolding from the React Native library generator. Not part of the
   * security API; kept so existing imports keep resolving.
   */
  @ReactMethod
  fun multiply(a: Int, b: Int, promise: Promise) {
    promise.resolve(a * b)
  }

  // endregion

  // region internals

  private fun canAuthenticate(): Int =
    BiometricManager.from(reactApplicationContext)
      .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

  private fun loadKeyStore(): KeyStore =
    KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  /**
   * `false` when the OS has invalidated the key, which it does only on a
   * biometric enrolment change. An unrecoverable key is treated the same way:
   * a baseline we cannot verify is a baseline we must not trust.
   */
  private fun isTrackerKeyStillValid(keyStore: KeyStore): Boolean = try {
    val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
    Cipher.getInstance(TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key)
    true
  } catch (invalidated: KeyPermanentlyInvalidatedException) {
    false
  } catch (unrecoverable: UnrecoverableKeyException) {
    false
  }

  private fun generateTrackerKey() {
    val spec = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
      // Auth-per-use. Deliberately no validity duration: a time-based key makes
      // `Cipher.init` throw UserNotAuthenticatedException, which would drown the
      // invalidation signal we are actually probing for.
      .setUserAuthenticationRequired(true)
      .apply {
        // API 23 invalidates on enrolment change by default; API 24+ makes it
        // opt-in, so ask for it explicitly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          setInvalidatedByBiometricEnrollment(true)
        }
      }
      .build()

    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
      .apply { init(spec) }
      .generateKey()
  }

  // endregion

  companion object {
    private const val NAME = "CheckBiometricChanged"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "com.reactnativecheckbiometricchanged.tracker"
    // Matches KeyProperties.KEY_ALGORITHM_AES / BLOCK_MODE_CBC /
    // ENCRYPTION_PADDING_PKCS7, spelled out because `const val` will not take
    // an interpolated Java constant.
    private const val TRANSFORMATION = "AES/CBC/PKCS7Padding"

    private const val PROMPT_TITLE = "Confirm it is you"
    private const val PROMPT_SUBTITLE = "Verify your biometric to re-trust this device"
    private const val PROMPT_NEGATIVE = "Cancel"

    // Error codes surfaced to JS. Callers need to tell a transient lockout
    // apart from a device that can never satisfy the check.
    private const val E_UNAVAILABLE = "BIOMETRICS_UNAVAILABLE"
    private const val E_LOCKED_OUT = "BIOMETRICS_LOCKED_OUT"
    private const val E_KEYSTORE = "KEYSTORE_ERROR"
    private const val E_VERIFICATION = "VERIFICATION_ERROR"
    private const val E_NO_ACTIVITY = "NO_ACTIVITY"
  }
}
