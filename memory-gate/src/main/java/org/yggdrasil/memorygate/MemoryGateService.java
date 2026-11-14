package org.yggdrasil.memorygate;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/**
 * Narrow protected Binder endpoint. The caller is checked both when binding
 * and again for every retrieval transaction. Grant contents remain in-process.
 */
public final class MemoryGateService extends Service {
    static final String TRUSTED_CLIENT_PACKAGE = "org.yggdrasil.memoryclient";
    private static final String DESCRIPTOR = "org.yggdrasil.memorygate.IMemoryGate";
    private static final int TRANSACTION_READ_RECORD = IBinder.FIRST_CALL_TRANSACTION;

    private TrustedCallerVerifier callerVerifier;
    private GrantRegistry grantRegistry;
    private MemoryRecordStore recordStore;

    private final IBinder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
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
        if (!callerVerifier.isTrustedBinderCaller()) return null;
        return binder;
    }

    /**
     * Reserved for a future in-process approval component. No client-facing
     * transaction can mint or alter a grant.
     */
    private String issueApprovedGrant(int approvedUid, MemoryAccessGrant grant) {
        if (approvedUid < 0 || approvedUid == Process.myUid()) return null;
        return grantRegistry.issueForApprovedCaller(approvedUid, grant);
    }
}
