//
//  CheckBiometricChanged.swift
//  react-native-check-biometric-changed
//
//  Objective: answer whether the biometric enrolment backing a device-bound
//  session is still the one that was enrolled when the session was granted.
//
//  Mechanism: `LAContext.evaluatedPolicyDomainState` is an opaque blob the OS
//  changes whenever the biometric database changes — a face or finger added or
//  removed. We store one, and compare.
//

import Foundation
import LocalAuthentication

@objc(CheckBiometricChanged)
final class CheckBiometricChanged: NSObject {

  private static let policy: LAPolicy = .deviceOwnerAuthenticationWithBiometrics
  private static let verificationReason = "Confirm it is you to re-trust this device"

  // Error codes surfaced to JS. Callers need to tell a transient lockout apart
  // from a device that can never satisfy the check.
  private enum Code {
    static let unavailable = "BIOMETRICS_UNAVAILABLE"
    static let lockedOut = "BIOMETRICS_LOCKED_OUT"
    static let keychain = "KEYCHAIN_ERROR"
    static let verification = "VERIFICATION_ERROR"
  }

  @objc static func requiresMainQueueSetup() -> Bool {
    return false
  }

  // Held for the lifetime of an evaluation: a deallocated LAContext cancels the
  // prompt it is driving, and the completion handler would never resolve.
  private var verificationContext: LAContext?

  // MARK: - biometricsChanged

  /// `true` when the current enrolment differs from the stored baseline.
  ///
  /// With no baseline yet, the current enrolment is adopted as the baseline and
  /// `false` is returned — there is nothing to have changed from.
  @objc(biometricsChanged:withRejecter:)
  func biometricsChanged(
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    let context = LAContext()
    var evaluationError: NSError?
    let canEvaluate = context.canEvaluatePolicy(Self.policy, error: &evaluationError)
    let baseline = BiometricTrackerStore.load()

    guard canEvaluate else {
      let code = (evaluationError as? LAError)?.code

      // A lockout is transient and says nothing about enrolment. Surface it as
      // an error so callers do not tear down a session over a wrong thumb.
      if code == .biometryLockout {
        reject(Code.lockedOut, "Biometric authentication is locked out. Try again later.", evaluationError)
        return
      }

      // Enrolment was removed entirely. If we were tracking one, that is
      // precisely the change this module exists to report.
      if code == .biometryNotEnrolled, baseline != nil {
        resolve(true)
        return
      }

      reject(
        Code.unavailable,
        evaluationError?.localizedDescription ?? "Biometric authentication is not available on this device.",
        evaluationError
      )
      return
    }

    guard let current = context.evaluatedPolicyDomainState else {
      // Evaluable but stateless: nothing meaningful to compare against.
      if baseline != nil {
        resolve(true)
      } else {
        reject(Code.unavailable, "The system did not report a biometric enrolment state.", nil)
      }
      return
    }

    guard let baseline = baseline else {
      let status = BiometricTrackerStore.save(current)
      guard status == errSecSuccess else {
        reject(Code.keychain, "Could not record the biometric baseline (OSStatus \(status)).", nil)
        return
      }
      resolve(false)
      return
    }

    resolve(baseline != current)
  }

  // MARK: - verifyBiometric

  /// Presents the system biometric prompt. Resolves `true` only after the OS
  /// reports a successful evaluation.
  @objc(verifyBiometric:withRejecter:)
  func verifyBiometric(
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    let context = LAContext()
    var evaluationError: NSError?

    guard context.canEvaluatePolicy(Self.policy, error: &evaluationError) else {
      let code = (evaluationError as? LAError)?.code == .biometryLockout
        ? Code.lockedOut
        : Code.unavailable
      reject(
        code,
        evaluationError?.localizedDescription ?? "Biometric authentication is not available on this device.",
        evaluationError
      )
      return
    }

    verificationContext = context
    context.evaluatePolicy(Self.policy, localizedReason: Self.verificationReason) { [weak self] success, error in
      self?.verificationContext = nil

      if success {
        resolve(true)
        return
      }

      guard let laError = error as? LAError else {
        resolve(false)
        return
      }

      switch laError.code {
      case .biometryLockout:
        reject(Code.lockedOut, laError.localizedDescription, laError)
      case .authenticationFailed, .userCancel, .userFallback, .systemCancel, .appCancel:
        // A real person declined or failed. Not an error — just not verified.
        resolve(false)
      default:
        reject(Code.verification, laError.localizedDescription, laError)
      }
    }
  }

  // MARK: - refreshTracker

  /// Adopts the current enrolment as the trusted baseline.
  ///
  /// Security-critical: this is what re-trusts the device. Gate it behind a
  /// successful `verifyBiometric()` and, for anything high-value, a
  /// server-verified login too.
  @objc(refreshTracker:withRejecter:)
  func refreshTracker(
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    let context = LAContext()
    var evaluationError: NSError?

    guard context.canEvaluatePolicy(Self.policy, error: &evaluationError),
          let current = context.evaluatedPolicyDomainState
    else {
      reject(
        Code.unavailable,
        evaluationError?.localizedDescription ?? "There is no biometric enrolment to record.",
        evaluationError
      )
      return
    }

    let status = BiometricTrackerStore.save(current)
    guard status == errSecSuccess else {
      reject(Code.keychain, "Could not record the biometric baseline (OSStatus \(status)).", nil)
      return
    }

    resolve(true)
  }

}
