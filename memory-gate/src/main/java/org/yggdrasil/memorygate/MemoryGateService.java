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
 * Narrow protected Binder endpoint. Android's signature permission gates
 * binding; the live caller is independently checked on every remote read.
 * The local approval method is an in-process Java call, not a Binder transaction.
 */
public final class MemoryGateService extends Service {
    static final String TRUSTED_CLIENT_PACKAGE = "org.yggdrasil.memoryclient";
    private static final String DESCRIPTOR = "org.yggdrasil.memorygate.IMemoryGate";
    private static final int TRANSACTION_READ_RECORD = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_CLAIM_GRANT = IBinder.FIRST_CALL_TRANSACTION + 1;

    private TrustedCallerVerifier callerVerifier;
    private GrantRegistry grantRegistry;
    private MemoryRecordStore recordStore;

    final class GateBinder extends Binder {
        boolean approveFromVisibleConfirmation(
                String recordId, String objective, String purpose, long durationMillis) {
            // This method is only directly callable by code in this app process.
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
            return grant != null
                    && grantRegistry.issueForApprovedCaller(clientUid, grant) != null;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code != TRANSACTION_READ_RECORD && code != TRANSACTION_CLAIM_GRANT) {
                return super.onTransact(code, data, reply, flags);
            }

            data.enforceInterface(DESCRIPTOR);
            final int callingUid = Binder.getCallingUid();
            if (callingUid == Process.myUid() || !callerVerifier.isTrustedBinderCaller()) {
                reply.writeNoException();
                if (code == TRANSACTION_CLAIM_GRANT) {
                    reply.writeString(null);
                } else {
                    reply.writeInt(0);
                }
                return true;
            }

            if (code == TRANSACTION_CLAIM_GRANT) {
                String claimedToken = grantRegistry.claimForUid(
                        callingUid, System.currentTimeMillis());
                reply.writeNoException();
                reply.writeString(claimedToken);
                return true;
            }

            final String grantToken = data.readString();
            final String recordId = data.readString();
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
    }

    private final GateBinder gateBinder = new GateBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        callerVerifier = new TrustedCallerVerifier(this, TRUSTED_CLIENT_PACKAGE);
        grantRegistry = new GrantRegistry();
        recordStore = new MemoryRecordStore(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Do not infer the original client from onBind: Android dispatches this
        // callback through its service lifecycle. Verify identity per transaction.
        return gateBinder;
    }
}
