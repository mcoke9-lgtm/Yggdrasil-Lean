package org.yggdrasil.memoryattacker;

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

/** Deliberately untrusted package used to probe the Gate's per-call identity check. */
public final class MainActivity extends Activity {
    private static final String DESCRIPTOR = "org.yggdrasil.memorygate.IMemoryGate";
    private static final int TRANSACTION_READ_RECORD = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_CLAIM_GRANT = IBinder.FIRST_CALL_TRANSACTION + 1;

    private TextView status;
    private boolean binding;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                String token = transactClaim(service);
                boolean forgedReadSucceeded = transactRead(service,
                        token == null ? "forged-token" : token);
                status.setText(forgedReadSucceeded
                        ? "SECURITY FAILURE: untrusted package received content."
                        : "DENIED: untrusted package received no grant and no record content.");
            } catch (Exception failure) {
                status.setText("Denied or disconnected: " + failure.getClass().getSimpleName());
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
            status.setText("Gate disconnected. No content received.");
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
        status.setText("Adversarial Memory Client\n\nThis package is not the allow-listed trusted client. It will attempt grant claim and record retrieval.");
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        layout.addView(status);

        Button probe = new Button(this);
        probe.setText("Attempt unauthorized retrieval");
        probe.setOnClickListener(view -> probeGate());
        layout.addView(probe);
        setContentView(layout);
    }

    private void probeGate() {
        if (binding) return;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "org.yggdrasil.memorygate",
                "org.yggdrasil.memorygate.MemoryGateService"));
        status.setText("Attempting protected bind and unauthorized Binder calls...");
        binding = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!binding) status.setText("Android denied service binding.");
    }

    private static String transactClaim(IBinder service) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(TRANSACTION_CLAIM_GRANT, data, reply, 0)) {
                throw new RemoteException("Claim transaction unavailable");
            }
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactRead(IBinder service, String token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(token);
            data.writeString("project-fact-001");
            if (!service.transact(TRANSACTION_READ_RECORD, data, reply, 0)) {
                throw new RemoteException("Read transaction unavailable");
            }
            reply.readException();
            if (reply.readInt() != 1) return false;
            // Treat any returned record fields as a test failure.
            return reply.readString() != null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
