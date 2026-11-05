package org.yggdrasil.signingdiagnostic;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Read-only, offline inspection of the two explicitly allow-listed Yggdrasil packages.
 * Does not export private material, modify packages, or contact a network.
 */
public final class MainActivity extends Activity {
    private static final String[] TARGETS = {
        "org.yggdrasil.builderhost",
        "org.yggdrasil.keysteward"
    };

    private LinearLayout results;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Installed App Signing Audit");
        title.setTextSize(23);
        root.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText("READ-ONLY · OFFLINE\nChecks only Builder Host and Key Steward. Reads installed package metadata and public signing certificates. It does not install, update, uninstall, clear data, access private keys, or use the network.");
        explanation.setTextSize(15);
        explanation.setPadding(0, dp(12), 0, dp(16));
        root.addView(explanation);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        Button refresh = new Button(this);
        refresh.setText("Refresh installed package report");
        refresh.setOnClickListener(v -> inspectTargets());
        root.addView(refresh);

        TextView note = new TextView(this);
        note.setText("Certificate fingerprints identify signing certificates, not the private signing keys. Share this report only with someone you trust.");
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);
        setContentView(scroll);
        inspectTargets();
    }

    private void inspectTargets() {
        results.removeAllViews();
        for (String packageName : TARGETS) {
            TextView card = new TextView(this);
            card.setTextSize(14);
            card.setPadding(0, dp(12), 0, dp(16));
            card.setText(inspect(packageName));
            results.addView(card);
        }
    }

    private String inspect(String packageName) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            StringBuilder out = new StringBuilder();
            out.append(packageName).append("\n");
            out.append("Status: INSTALLED\n");
            out.append("Version: ").append(info.versionName == null ? "(unknown)" : info.versionName)
                    .append(" (code ").append(info.getLongVersionCode()).append(")\n");
            SigningInfo signing = info.signingInfo;
            if (signing == null) {
                out.append("Signing information: unavailable\n");
                return out.toString();
            }
            out.append("Multiple current signers: ").append(signing.hasMultipleSigners() ? "yes" : "no").append("\n");
            Signature[] current = signing.getApkContentsSigners();
            out.append("Current APK signer certificate(s):\n");
            appendFingerprints(out, current);
            if (!signing.hasMultipleSigners()) {
                Signature[] history = signing.getSigningCertificateHistory();
                if (history != null && history.length > 1) {
                    out.append("Signing certificate history (oldest to newest):\n");
                    appendFingerprints(out, history);
                }
            }
            return out.toString();
        } catch (PackageManager.NameNotFoundException missing) {
            return packageName + "\nStatus: NOT FOUND (not installed or not visible to this diagnostic)\n";
        } catch (Exception failure) {
            return packageName + "\nStatus: ERROR READING SIGNING METADATA (" +
                    failure.getClass().getSimpleName() + ")\n";
        }
    }

    private static void appendFingerprints(StringBuilder out, Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length == 0) {
            out.append("  (no signer certificates returned)\n");
            return;
        }
        for (int i = 0; i < signatures.length; i++) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray());
            out.append("  ").append(i + 1).append(": ").append(hex(digest)).append("\n");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 3 - 1);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(':');
            out.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xff));
        }
        return out.toString();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
