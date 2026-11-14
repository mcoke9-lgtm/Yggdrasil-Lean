package org.yggdrasil.memorygate;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

/**
 * First protected-service boundary.
 *
 * This service deliberately exposes no memory retrieval operation yet.
 * Binding is restricted by the signature permission in the manifest and
 * independently checked against the expected client package and signing
 * identity. A future retrieval API must re-check the Binder caller identity
 * for every transaction and validate grant provenance before reading data.
 */
public final class MemoryGateService extends Service {
    static final String TRUSTED_CLIENT_PACKAGE = "org.yggdrasil.memoryclient";

    private final IBinder binder = new Binder();

    @Override
    public IBinder onBind(Intent intent) {
        if (!isTrustedBindingCaller()) {
            return null;
        }
        return binder;
    }

    private boolean isTrustedBindingCaller() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid() || callingUid < 0) {
            return false;
        }

        final String[] packages = getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }

        boolean expectedPackagePresent = false;
        for (String packageName : packages) {
            if (TRUSTED_CLIENT_PACKAGE.equals(packageName)) {
                expectedPackagePresent = true;
                break;
            }
        }
        if (!expectedPackagePresent) {
            return false;
        }

        try {
            return getPackageManager().checkSignatures(
                    getPackageName(), TRUSTED_CLIENT_PACKAGE)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (RuntimeException unavailableOrInvalidPackage) {
            return false;
        }
    }
}
