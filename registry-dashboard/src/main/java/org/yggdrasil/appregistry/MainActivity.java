package org.yggdrasil.appregistry;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private LinearLayout cards;
    private JSONObject registry;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        registry = loadRegistry();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        root.addView(label("Yggdrasil App Registry", 24, true));
        root.addView(label("Local, read-only inventory • exact package scope", 14, false));
        Button refresh = new Button(this);
        refresh.setText("Refresh device inventory");
        refresh.setOnClickListener(v -> refreshInventory());
        root.addView(refresh);
        root.addView(label("Privacy: checks only package IDs listed in this registry. No network, device identifiers, broad app inventory, or special permissions.", 13, false));
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        root.addView(cards);
        setContentView(scroll);
        refreshInventory();
    }

    private JSONObject loadRegistry() {
        try (InputStream in = getAssets().open("registry.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) { return new JSONObject(); }
    }

    private void refreshInventory() {
        cards.removeAllViews();
        JSONArray apps = registry.optJSONArray("applications");
        if (apps == null) { cards.addView(label("Registry data unavailable; no changes made.", 15, true)); return; }
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            String id = app.optString("package_id", "");
            StringBuilder body = new StringBuilder();
            body.append(app.optString("display_name", "Unnamed app")).append("\n");
            body.append("Package: ").append(id.isEmpty() || "null".equals(id) ? "Not verified / not assigned" : id).append("\n");
            body.append(app.optString("purpose", "")).append("\n\n");
            body.append(id.isEmpty() || "null".equals(id) ? "Device check skipped — package identity unknown." : inspect(id));
            TextView card = label(body.toString(), 14, false);
            card.setTextIsSelectable(true);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            cards.addView(card, lp);
        }
        cards.addView(label("Last refreshed: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date()), 12, false));
    }

    private String inspect(String id) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(id,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            StringBuilder s = new StringBuilder("INSTALLED — device observed");
            s.append("\nVersion: ").append(info.versionName == null ? "unknown" : info.versionName);
            s.append("\nVersion code: ").append(info.getLongVersionCode());
            if (info.signingInfo == null) return s.append("\nSigner: unavailable").toString();
            s.append("\nCurrent signer SHA-256: ").append(fingerprints(info.signingInfo.getApkContentsSigners()));
            s.append("\nSigning history SHA-256: ").append(fingerprints(info.signingInfo.getSigningCertificateHistory()));
            return s.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "NOT FOUND / NOT VISIBLE to this app (not proof of absence)";
        } catch (Exception e) { return "UNVERIFIED — inspection error: " + e.getClass().getSimpleName(); }
    }

    private String fingerprints(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) return "unavailable";
        StringBuilder out = new StringBuilder();
        for (Signature sig : signatures) {
            if (out.length() > 0) out.append("\n");
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray());
                for (int i = 0; i < hash.length; i++) {
                    if (i > 0) out.append(':');
                    out.append(String.format(Locale.US, "%02X", hash[i] & 255));
                }
            } catch (Exception e) { out.append("unavailable"); }
        }
        return out.toString();
    }

    private TextView label(String value, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(0xff202124);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
}
