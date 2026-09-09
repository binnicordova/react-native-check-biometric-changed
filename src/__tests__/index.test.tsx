jest.mock('react-native', () => ({
  Platform: { select: () => '' },
  NativeModules: {
    CheckBiometricChanged: {
      biometricsChanged: jest.fn(),
      verifyBiometric: jest.fn(),
      refreshTracker: jest.fn(),
    },
  },
}));

import { NativeModules } from 'react-native';
import {
  BiometricErrorCode,
  biometricsChanged,
  refreshTracker,
  verifyBiometric,
} from '../index';

const native = NativeModules.CheckBiometricChanged;

beforeEach(() => {
  jest.clearAllMocks();
});

describe('biometricsChanged', () => {
  it('reports a changed enrolment', async () => {
    native.biometricsChanged.mockResolvedValue(true);
    await expect(biometricsChanged()).resolves.toBe(true);
  });

  it('reports an unchanged enrolment', async () => {
    native.biometricsChanged.mockResolvedValue(false);
    await expect(biometricsChanged()).resolves.toBe(false);
  });

  it('normalises a non-boolean native result to a boolean', async () => {
    native.biometricsChanged.mockResolvedValue(1);
    const result = await biometricsChanged();
    expect(result).toBe(true);
    expect(typeof result).toBe('boolean');
  });

  // Regression guard: the rejection must stay an Error carrying `code`.
  // Callers have to tell a transient lockout apart from an unusable device,
  // and a stringified rejection destroys that distinction.
  it('preserves the native error code on rejection', async () => {
    const nativeError = Object.assign(new Error('locked out'), {
      code: BiometricErrorCode.LOCKED_OUT,
    });
    native.biometricsChanged.mockRejectedValue(nativeError);

    await expect(biometricsChanged()).rejects.toMatchObject({
      code: BiometricErrorCode.LOCKED_OUT,
    });
  });
});

describe('verifyBiometric', () => {
  it('resolves true when the OS confirms a human', async () => {
    native.verifyBiometric.mockResolvedValue(true);
    await expect(verifyBiometric()).resolves.toBe(true);
  });

  it('resolves false when the user cancels or fails', async () => {
    native.verifyBiometric.mockResolvedValue(false);
    await expect(verifyBiometric()).resolves.toBe(false);
  });

  it('rejects with a code when the prompt cannot be presented', async () => {
    native.verifyBiometric.mockRejectedValue(
      Object.assign(new Error('no activity'), {
        code: BiometricErrorCode.NO_ACTIVITY,
      })
    );

    await expect(verifyBiometric()).rejects.toMatchObject({
      code: BiometricErrorCode.NO_ACTIVITY,
    });
  });
});

describe('refreshTracker', () => {
  it('resolves true once the baseline is recorded', async () => {
    native.refreshTracker.mockResolvedValue(true);
    await expect(refreshTracker()).resolves.toBe(true);
  });

  it('rejects with a code when the baseline cannot be stored', async () => {
    native.refreshTracker.mockRejectedValue(
      Object.assign(new Error('keychain'), {
        code: BiometricErrorCode.KEYCHAIN_ERROR,
      })
    );

    await expect(refreshTracker()).rejects.toMatchObject({
      code: BiometricErrorCode.KEYCHAIN_ERROR,
    });
  });
});

describe('the documented usage flow', () => {
  it('re-trusts the device only after a human is verified', async () => {
    native.biometricsChanged.mockResolvedValue(true);
    native.verifyBiometric.mockResolvedValue(true);
    native.refreshTracker.mockResolvedValue(true);

    expect(await biometricsChanged()).toBe(true);
    if (await verifyBiometric()) {
      await refreshTracker();
    }

    expect(native.refreshTracker).toHaveBeenCalledTimes(1);
  });

  it('leaves the baseline alone when verification fails', async () => {
    native.biometricsChanged.mockResolvedValue(true);
    native.verifyBiometric.mockResolvedValue(false);

    if (await verifyBiometric()) {
      await refreshTracker();
    }

    expect(native.refreshTracker).not.toHaveBeenCalled();
  });
});
