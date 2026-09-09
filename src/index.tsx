import { NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-check-biometric-changed' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo managed workflow\n';

const CheckBiometricChanged = NativeModules.CheckBiometricChanged
  ? NativeModules.CheckBiometricChanged
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

/**
 * Codes carried on the `code` property of a rejected promise.
 *
 * The distinction matters: `LOCKED_OUT` is transient and says nothing about
 * enrolment, so it is not grounds for tearing down a session.
 */
export const BiometricErrorCode = {
  /** No usable biometric hardware, or nothing enrolled and no baseline yet. */
  UNAVAILABLE: 'BIOMETRICS_UNAVAILABLE',
  /** Too many failed attempts. Transient — retry later, do not revoke. */
  LOCKED_OUT: 'BIOMETRICS_LOCKED_OUT',
  /** iOS: the Keychain refused to store the baseline. */
  KEYCHAIN_ERROR: 'KEYCHAIN_ERROR',
  /** Android: the AndroidKeyStore could not be read or written. */
  KEYSTORE_ERROR: 'KEYSTORE_ERROR',
  /** The system prompt could not be presented or evaluated. */
  VERIFICATION_ERROR: 'VERIFICATION_ERROR',
  /** Android: no foreground activity to host the prompt. */
  NO_ACTIVITY: 'NO_ACTIVITY',
} as const;

export type BiometricErrorCode =
  typeof BiometricErrorCode[keyof typeof BiometricErrorCode];

/**
 * Whether the device's biometric enrolment differs from the stored baseline.
 *
 * `true` means a face or finger was added or removed since the baseline was
 * recorded: treat the device as untrusted, drop tokens and force a full login.
 *
 * On the first call, with no baseline recorded yet, the current enrolment is
 * adopted as the baseline and `false` is returned.
 *
 * Rejects with {@link BiometricErrorCode} when the enrolment cannot be read.
 */
export function biometricsChanged(): Promise<boolean> {
  return CheckBiometricChanged.biometricsChanged().then(Boolean);
}

/**
 * Presents the system biometric prompt.
 *
 * Resolves `true` only when the OS reports a successful authentication, and
 * `false` when the user cancels or fails. Rejects on lockout or when the
 * prompt cannot be presented.
 *
 * ⚠️ This proves *a currently enrolled* biometric was presented — never that it
 * is the owner's. After an attacker enrols their own face, theirs is enrolled
 * and this resolves `true` for them. It cannot, on its own, justify a
 * {@link refreshTracker} call after {@link biometricsChanged} returned `true`.
 */
export function verifyBiometric(): Promise<boolean> {
  return CheckBiometricChanged.verifyBiometric().then(Boolean);
}

/**
 * Records the current enrolment as the trusted baseline.
 *
 * Security-critical: this is what re-trusts the device, so calling it after a
 * detected change is what decides whether the change mattered. Gate it behind
 * a credential login your server verified — a factor an attacker holding the
 * device does not have — and only then a successful {@link verifyBiometric}.
 * Gating on the biometric alone hands the baseline to whoever just enrolled.
 */
export function refreshTracker(): Promise<boolean> {
  return CheckBiometricChanged.refreshTracker().then(Boolean);
}

/**
 * Scaffolding from the React Native library generator. Not part of the
 * security API.
 */
export function multiply(a: number, b: number): Promise<number> {
  return CheckBiometricChanged.multiply(a, b);
}
