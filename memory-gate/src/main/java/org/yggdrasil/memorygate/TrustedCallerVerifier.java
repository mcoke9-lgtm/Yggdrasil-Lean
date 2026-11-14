package org.yggdrasil.memorygate;

import android.content.Context;
import android.os.Binder;
import android.os.Process;

/** Verifies the live Binder caller; never trusts a package name supplied by a client. */
final class TrustedCallerVerifier {
    private final Context context;
    private final String expectedPackage;

    TrustedCallerVerifier(Context context, String expectedPackage) {
        this.context = context.getApplicationContext();
        this.expectedPackage = expectedPackage;
    }

    boolean isTrustedBinderCaller() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid < 0 || callingUid == Process.myUid()) return false;

        final String[] packages = context.getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) return false;

        boolean packageMatches = false;
        for (String packageName : packages) {
            if (expectedPackage.equals(packageName)) {
                packageMatches = true;
                break;
            }
        }
        if (!packageMatches) return false;

        try {
            return context.getPackageManager().checkSignatures(
                    context.getPackageName(), expectedPackage)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private TrustedCallerVerifier() {
        throw new AssertionError("Use the explicit constructor");
    }
}
