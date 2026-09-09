import * as React from 'react';

import { Alert, Button, StyleSheet, Text, View } from 'react-native';
import {
  biometricsChanged,
  refreshTracker,
  verifyBiometric,
} from 'react-native-check-biometric-changed';

type Status = 'unknown' | 'trusted' | 'changed';

/**
 * Stand-in for a credential login your SERVER verifies.
 *
 * This is the step that actually re-establishes trust. A biometric cannot do
 * it: once an attacker has enrolled their own face, the OS considers their
 * face valid, so `verifyBiometric()` would happily approve them. Only a factor
 * they do not have — a password, an OTP — separates them from the owner.
 *
 * Replace this with a real authenticated call.
 */
function loginWithCredentials(): Promise<boolean> {
  return new Promise((resolve) => {
    Alert.alert(
      'Server login required',
      'In a real app this is a password or OTP your backend verifies. A biometric alone cannot re-trust the device.',
      [
        { text: 'Cancel', style: 'cancel', onPress: () => resolve(false) },
        { text: 'Server says OK', onPress: () => resolve(true) },
      ]
    );
  });
}

export default function App() {
  const [status, setStatus] = React.useState<Status>('unknown');
  const [lastError, setLastError] = React.useState<string>();

  const report = (error: unknown) => {
    const { code, message } = error as { code?: string; message?: string };
    setLastError(code ? `${code}: ${message}` : String(message ?? error));
  };

  const check = React.useCallback(async () => {
    setLastError(undefined);
    try {
      setStatus((await biometricsChanged()) ? 'changed' : 'trusted');
    } catch (error) {
      setStatus('unknown');
      report(error);
    }
  }, []);

  // A re-enrolment can happen while the app is backgrounded, so a real
  // integration also runs this on AppState 'active'.
  React.useEffect(() => {
    check();
  }, [check]);

  const reTrust = async () => {
    setLastError(undefined);
    try {
      // 1. Revoke first. Whatever the session was, it is not trustworthy now.
      //    (Your app would drop tokens and clear the keychain here.)

      // 2. Prove the OWNER is present, not merely someone enrolled.
      if (!(await loginWithCredentials())) {
        Alert.alert('Not re-trusted', 'The device stays revoked.');
        return;
      }

      // 3. Only then bind the baseline to the enrolment in front of you.
      if (!(await verifyBiometric())) {
        Alert.alert('Not re-trusted', 'Biometric was cancelled or failed.');
        return;
      }

      await refreshTracker();
      setStatus('trusted');
    } catch (error) {
      report(error);
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>check-biometric-changed</Text>

      <Text style={[styles.status, styles[status]]}>
        {status === 'changed'
          ? 'BIOMETRIC CHANGED — revoke the session'
          : status === 'trusted'
          ? 'Enrolment matches the baseline'
          : 'Unknown'}
      </Text>

      {lastError ? <Text style={styles.error}>{lastError}</Text> : null}

      <View style={styles.actions}>
        <Button title="Check enrolment" onPress={check} />
        <Button title="Re-trust this device" onPress={reTrust} />
      </View>

      <Text style={styles.hint}>
        To reproduce the attack: re-trust, then add a face or fingerprint in
        system settings, come back and tap check.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 24 },
  title: { fontSize: 20, fontWeight: '700', textAlign: 'center' },
  status: { marginTop: 24, textAlign: 'center', fontWeight: '600' },
  unknown: { color: '#64748B' },
  trusted: { color: '#047857' },
  changed: { color: '#B91C1C' },
  error: { marginTop: 12, color: '#B91C1C', textAlign: 'center', fontSize: 12 },
  actions: { marginTop: 28 },
  hint: { marginTop: 32, color: '#64748B', fontSize: 12, textAlign: 'center' },
});
