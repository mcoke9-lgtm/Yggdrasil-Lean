package org.yggdrasil.memorygate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;

    private final ServiceConnection approvalConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MemoryGateService.GateBinder local =
                    (MemoryGateService.GateBinder) service;
            boolean issued = local.approveFromVisibleConfirmation(
                    "project-fact-001",
                    "Validate the Memory Gate retrieval boundary",
                    "User-approved prototype test",
                    10L * 60L * 1000L);
            status.setText(issued
                    ? "A 10-minute, single-record grant is stored for the signed trusted client. Only that client can claim its token through the protected service."
                    : "No grant issued. The trusted client is absent, has a different signature, or approval validation failed.");
            unbindService(this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            status.setText("Approval service disconnected. No confirmation of grant issuance.");
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
        status.setText("Memory Gate prototype\n\nAccess is closed by default.\nNo trusted client is currently connected.");
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        layout.addView(status);

        Button approve = new Button(this);
        approve.setText("Review test-access request");
        approve.setOnClickListener(view -> showApprovalConfirmation());
        layout.addView(approve);

        setContentView(layout);
    }

    private void showApprovalConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Approve limited test access?")
                .setMessage("Client: " + MemoryGateService.TRUSTED_CLIENT_PACKAGE
                        + "\nRecord: project-fact-001"
                        + "\nObjective: Validate the Memory Gate retrieval boundary"
                        + "\nPurpose: User-approved prototype test"
                        + "\nDuration: 10 minutes"
                        + "\n\nOnly this one fictional test record is in scope. "
                        + "The client must be installed and signed with the Memory Gate signing identity.")
                .setNegativeButton("Cancel", (dialog, which) ->
                        status.setText("No access was approved."))
                .setPositiveButton("Approve this request", (dialog, which) -> {
                    status.setText("Checking client identity and issuing the limited grant...");
                    Intent service = new Intent(this, MemoryGateService.class);
                    bindService(service, approvalConnection, Context.BIND_AUTO_CREATE);
                })
                .show();
    }
}
