package org.yggdrasil.memorygate;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/**
 * Narrow protected Binder endpoint. Remote callers are checked on every
 * retrieval. A same-app local binder is reserved for the visible approval UI.
 */
public final class MemoryGateService extends Service {
    static final String TRUSTED_CLIENT_PACKAGE = "org.yggdrasil.memoryclient";
    private static final String DESCRIPTOR = "org.yggdrasil.memorygate.IMemoryGate";
    private static final int TRANSACTION_READ_RECORD = IBinder.FIRST_CALL_TRANSACTION;

    private TrustedCallerVerifier callerVerifier;
    private GrantRegistry grantRegistry;
    private MemoryRecordStore recordStore;

    final class LocalBinder extends Binder {
        boolean approveFromVisibleConfirmation(
                String recordId, String objective, String purpose, long durationMillis) {
            if (Binder.getCallingUid() != Process.myUid()) return false;

            final int clientUid;
            try {
                clientUid = getPackageManager().getPackageUid(
                        TRUSTED_CLIENT_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException missingClient) {
                return false;
            }
            if (clientUid < 0 || clientUid == Process.myUid()) return false;
            if (getPackageManager().checkSignatures(
                    getPackageName(), TRUSTED_CLIENT_PACKAGE)
                    != PackageManager.SIGNATURE_MATCH) {
                return false;
            }

            long now = System.currentTimeMillis();
            if (durationMillis <= 0
                    || durationMillis > GrantApprovalPolicy.MAX_DURATION_MILLIS) {
                return false;
            }
            MemoryAccessGrant grant = GrantApprovalPolicy.create(
                    recordId, objective, purpose, now, now + durationMillis);
            if (grant == null) return false;

            return grantRegistry.issueForApprovedCaller(clientUid, grant) != null;
        }
    }

    private final LocalBinder localBinder = new LocalBinder();

    private final IBinder remoteBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code != TRANSACTION_READ_RECORD) {
                return super.onTransact(code, data, reply, flags);
            }

            data.enforceInterface(DESCRIPTOR);
            final int callingUid = Binder.getCallingUid();
            final String grantToken = data.readString();
            final String recordId = data.readString();

            if (callingUid == Process.myUid() || !callerVerifier.isTrustedBinderCaller()) {
                reply.writeNoException();
                reply.writeInt(0);
                return true;
            }

            MemoryAccessGrant grant = grantRegistry.resolve(
                    grantToken, callingUid, recordId, System.currentTimeMillis());
            if (grant == null) {
                reply.writeNoException();
                reply.writeInt(0);
                return true;
            }

            MemoryRecordStore.MemoryRecord record = recordStore.readWithGrant(
                    grant, recordId, System.currentTimeMillis());
            reply.writeNoException();
            if (record == null) {
                reply.writeInt(0);
            } else {
                reply.writeInt(1);
                reply.writeString(record.id);
                reply.writeString(record.sensitivity);
                reply.writeString(record.content);
            }
            return true;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        callerVerifier = new TrustedCallerVerifier(this, TRUSTED_CLIENT_PACKAGE);
        grantRegistry = new GrantRegistry();
        recordStore = new MemoryRecordStore(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (Binder.getCallingUid() == Process.myUid()) return localBinder;
        if (!callerVerifier.isTrustedBinderCaller()) return null;
        return remoteBinder;
    }
}
