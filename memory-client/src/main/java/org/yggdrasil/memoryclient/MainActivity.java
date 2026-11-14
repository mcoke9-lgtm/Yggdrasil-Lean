package org.yggdrasil.memoryclient;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal cross-application client for the Memory Gate prototype.
 * It has no database access and cannot mint or modify grants.
 */
public final class MainActivity extends Activity {
    private static final String DESCRIPTOR = "org.yggdrasil.memorygate.IMemoryGate";
    private static final int TRANSACTION_READ_RECORD = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_CLAIM_GRANT = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final String RECORD_ID = "project-fact-001";

    private TextView status;
    private boolean binding;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                String token = claimGrant(service);
                if (token == null) {
                    status.setText("No approved grant is available for this client.");
                    return;
                }
                String result = readRecord(service, token);
                status.setText(result == null
                        ? "The Gate denied retrieval or the record was unavailable."
                        : "Authorized record received:\n\n" + result);
            } catch (Exception failure) {
                status.setText("Gate request failed closed: " + failure.getClass().getSimpleName());
            } finally {
                if (binding) {
                    unbindService(this);
                    binding = false;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            status.setText("Memory Gate disconnected. No content was received.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        status.setText("Trusted Memory Client\n\nThis app has no direct memory-store access. It can request only a grant approved by the Memory Gate.");
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        layout.addView(status);

        Button retrieve = new Button(this);
        retrieve.setText("Request approved test record");
        retrieve.setOnClickListener(view -> bindToGate());
        layout.addView(retrieve);

        setContentView(layout);
    }

    private void bindToGate() {
        if (binding) return;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "org.yggdrasil.memorygate",
                "org.yggdrasil.memorygate.MemoryGateService"));
        status.setText("Connecting to the signature-protected Memory Gate...");
        binding = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!binding) status.setText("The Gate could not be bound. Access denied.");
    }

    private static String claimGrant(IBinder service) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(TRANSACTION_CLAIM_GRANT, data, reply, 0)) {
                throw new RemoteException("Grant claim transaction was not handled");
            }
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String readRecord(IBinder service, String token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(token);
            data.writeString(RECORD_ID);
            if (!service.transact(TRANSACTION_READ_RECORD, data, reply, 0)) {
                throw new RemoteException("Record transaction was not handled");
            }
            reply.readException();
            if (reply.readInt() != 1) return null;
            String id = reply.readString();
            String sensitivity = reply.readString();
            String content = reply.readString();
            return "Record: " + id + "\nSensitivity: " + sensitivity + "\nContent: " + content;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
