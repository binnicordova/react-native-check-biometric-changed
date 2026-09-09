//
//  BiometricTrackerStore.swift
//  react-native-check-biometric-changed
//
//  Persists the biometric enrolment baseline.
//
//  The baseline is Apple's opaque `evaluatedPolicyDomainState` blob — never a
//  biometric template, and never anything derived from one. It lives in the
//  Keychain rather than UserDefaults so that it is not readable or writable by
//  an attacker with plain file access to the app container: a tamperable
//  baseline is a defeatable check.
//
//  Accessibility is `WhenUnlockedThisDeviceOnly`, which keeps the item out of
//  iCloud Keychain and off device backups. A baseline restored onto a different
//  device describes a different enrolment and would be meaningless there.
//

import Foundation
import Security

enum BiometricTrackerStore {

  private static let service = "com.reactnativecheckbiometricchanged.tracker"
  private static let account = "evaluatedPolicyDomainState"

  private static func baseQuery() -> [String: Any] {
    return [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
    ]
  }

  /// The stored baseline, or `nil` when no baseline has been recorded yet.
  static func load() -> Data? {
    var query = baseQuery()
    query[kSecReturnData as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne

    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else {
      return nil
    }
    return item as? Data
  }

  /// Records `state` as the trusted baseline. Returns the OSStatus of the write.
  @discardableResult
  static func save(_ state: Data) -> OSStatus {
    let query = baseQuery()
    let attributes: [String: Any] = [
      kSecValueData as String: state,
      kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    ]

    let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    guard updateStatus == errSecItemNotFound else {
      return updateStatus
    }

    var newItem = query
    newItem[kSecValueData as String] = state
    newItem[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    return SecItemAdd(newItem as CFDictionary, nil)
  }
}
