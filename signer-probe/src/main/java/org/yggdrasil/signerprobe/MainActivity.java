package org.yggdrasil.signerprobe;

import android.app.Activity;
import android.content.Intent;
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

/** Offline, read-only inspection of the installed Builder Host signing certificate. */
public final class MainActivity extends Activity {
    private static final String TARGET = "org.yggdrasil.builderhost";
    private TextView report;

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
        title.setText("Yggdrasil Signer Probe");
        title.setTextSize(23);
        root.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText("READ-ONLY · OFFLINE\nInspects only the installed Builder Host package. Reads its version and public APK signing-certificate fingerprints. It does not install, update, uninstall, clear data, access private keys, or use the network.");
        explanation.setTextSize(15);
        explanation.setPadding(0, dp(12), 0, dp(16));
        root.addView(explanation);

        report = new TextView(this);
        report.setTextSize(14);
        report.setTextIsSelectable(true);
        root.addView(report);

        Button refresh = new Button(this);
        refresh.setText("Refresh installed signer report");
        refresh.setOnClickListener(v -> inspect());
        root.addView(refresh);

        Button share = new Button(this);
        share.setText("Share report");
        share.setOnClickListener(v -> shareReport());
        root.addView(share);

        TextView note = new TextView(this);
        note.setText("A certificate fingerprint identifies a public signing certificate, not the private key. “Not found” means not installed or not visible; it is not proof of absence.");
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        setContentView(scroll);
        inspect();
    }

    private void inspect() {
        StringBuilder out = new StringBuilder();
        out.append("Package: ").append(TARGET).append("\n");
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    TARGET, PackageManager.GET_SIGNING_CERTIFICATES);
            out.append("Status: INSTALLED AND VISIBLE\n");
            out.append("Version: ").append(info.versionName == null ? "(unknown)" : info.versionName)
                    .append(" (code ").append(info.getLongVersionCode()).append(")\n");
            SigningInfo signing = info.signingInfo;
            if (signing == null) {
                out.append("Signing information: UNAVAILABLE\n");
            } else {
                out.append("Multiple current signers: ")
                        .append(signing.hasMultipleSigners() ? "yes" : "no").append("\n");
                out.append("Current APK signer certificate SHA-256:\n");
                appendFingerprints(out, signing.getApkContentsSigners());
                if (!signing.hasMultipleSigners()) {
                    Signature[] history = signing.getSigningCertificateHistory();
                    if (history != null && history.length > 1) {
                        out.append("Signing certificate history (oldest to newest):\n");
                        appendFingerprints(out, history);
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException missing) {
            out.append("Status: NOT FOUND OR NOT VISIBLE\n");
        } catch (Exception failure) {
            out.append("Status: ERROR READING SIGNING METADATA (")
                    .append(failure.getClass().getSimpleName()).append(")\n");
        }
        report.setText(out.toString());
    }

    private void shareReport() {
        String text = report.getText().toString();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Share signer report"));
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
