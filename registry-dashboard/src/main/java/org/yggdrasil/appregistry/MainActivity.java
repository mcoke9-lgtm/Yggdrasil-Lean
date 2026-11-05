package org.yggdrasil.appregistry;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_SAVE_REPORT = 4102;
    private static final String REPORT_MIME = "application/json";
    private LinearLayout cards;
    private JSONObject registry;
    private String pendingSaveReport = "";

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

        root.addView(label("Report handoff — no attachment required", 17, true));
        Button copy = new Button(this);
        copy.setText("Copy JSON report");
        copy.setOnClickListener(v -> copyReport());
        root.addView(copy);
        Button share = new Button(this);
        share.setText("Share JSON report");
        share.setOnClickListener(v -> shareReport());
        root.addView(share);
        Button save = new Button(this);
        save.setText("Save JSON report");
        save.setOnClickListener(v -> saveReport());
        root.addView(save);

        root.addView(label("Reports contain only the four registered package records, versions, installation observations, and signer fingerprints. No serial number, account, location, broad app list, or private files are collected. Copy or share the text directly into ChatGPT; saving is optional.", 13, false));
        root.addView(label("Privacy: checks only package IDs listed in this registry. No network or special permissions.", 13, false));
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        root.addView(cards);
        setContentView(scroll);
        refreshInventory();
    }

    private JSONObject loadRegistry() {
        try {
            return new JSONObject("{\"applications\":["
                + "{\"display_name\":\"Yggdrasil Builder Host\",\"purpose\":\"Android Builder runtime host and conversation interface.\",\"package_id\":\"org.yggdrasil.builderhost\"},"
                + "{\"display_name\":\"Builder Key Steward\",\"purpose\":\"Key and signing material stewardship.\",\"package_id\":\"org.yggdrasil.keysteward\"},"
                + "{\"display_name\":\"Yggdrasil Scheduler\",\"purpose\":\"Independent scheduling and build monitoring prototype.\",\"package_id\":\"org.yggdrasil.scheduler\"},"
                + "{\"display_name\":\"Installed Signer Diagnostic\",\"purpose\":\"Read-only installed signing-certificate inspection.\",\"package_id\":\"org.yggdrasil.signingdiagnostic\"}"
                + "]}");
        } catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject buildInventoryReport() {
        JSONObject report = new JSONObject();
        try {
            report.put("schema", "yggdrasil.device-inventory-report");
            report.put("schema_version", "1.0.0");
            report.put("captured_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
            report.put("collector", "org.yggdrasil.appregistry");
            report.put("collection_method", "Android PackageManager; explicit registry package scope");
            report.put("android_api_level", Build.VERSION.SDK_INT);
            JSONArray applications = new JSONArray();
            JSONArray registered = registry.optJSONArray("applications");
            if (registered != null) {
                for (int i = 0; i < registered.length(); i++) {
                    JSONObject app = registered.optJSONObject(i);
                    if (app != null) applications.put(inspectRecord(app));
                }
            }
            report.put("applications", applications);
            report.put("privacy_scope", "Only the four package IDs embedded in the registry. No device identifiers, account data, location, broad app inventory, or file contents.");
        } catch (Exception ignored) { }
        return report;
    }

    private JSONObject inspectRecord(JSONObject app) {
        JSONObject record = new JSONObject();
        String id = app.optString("package_id", "");
        try {
            record.put("display_name", app.optString("display_name", "Unnamed app"));
            record.put("package_id", id);
            record.put("purpose", app.optString("purpose", ""));
            if (id.isEmpty() || "null".equals(id)) {
                record.put("status", "identity_unknown");
                record.put("observation", "Device check skipped because package identity is unknown.");
                return record;
            }
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = getPackageManager().getPackageInfo(id,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            } else {
                info = getPackageManager().getPackageInfo(id, PackageManager.GET_SIGNING_CERTIFICATES);
            }
            record.put("status", "installed");
            record.put("observation", "PackageManager returned an installed package record.");
            record.put("version_name", info.versionName == null ? JSONObject.NULL : info.versionName);
            record.put("version_code", info.getLongVersionCode());
            if (info.signingInfo == null) {
                record.put("current_signer_sha256", new JSONArray());
                record.put("signing_history_sha256", new JSONArray());
                record.put("signer_status", "unavailable");
            } else {
                record.put("current_signer_sha256", fingerprints(info.signingInfo.getApkContentsSigners()));
                record.put("signing_history_sha256", fingerprints(info.signingInfo.getSigningCertificateHistory()));
                record.put("signer_status", "read");
            }
        } catch (PackageManager.NameNotFoundException e) {
            putQuietly(record, "status", "not_found_or_not_visible");
            putQuietly(record, "observation", "PackageManager did not return this package. This does not distinguish absence from visibility limitations.");
        } catch (Exception e) {
            putQuietly(record, "status", "inspection_error");
            putQuietly(record, "error_class", e.getClass().getSimpleName());
            putQuietly(record, "observation", "Inspection did not complete; do not infer installation state.");
        }
        return record;
    }

    private void putQuietly(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) { }
    }

    private JSONArray fingerprints(Signature[] signatures) {
        JSONArray out = new JSONArray();
        if (signatures == null) return out;
        for (Signature sig : signatures) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray());
                StringBuilder value = new StringBuilder();
                for (int i = 0; i < hash.length; i++) {
                    if (i > 0) value.append(':');
                    value.append(String.format(Locale.US, "%02X", hash[i] & 255));
                }
                out.put(value.toString());
            } catch (Exception ignored) { }
        }
        return out;
    }

    private String displayRecord(JSONObject record) {
        StringBuilder body = new StringBuilder();
        body.append(record.optString("display_name", "Unnamed app")).append("\n");
        body.append("Package: ").append(record.optString("package_id", "unknown")).append("\n");
        body.append(record.optString("purpose", "")).append("\n\n");
        String status = record.optString("status", "unverified");
        if ("installed".equals(status)) {
            body.append("INSTALLED — device observed");
            body.append("\nVersion: ").append(record.optString("version_name", "unknown"));
            body.append("\nVersion code: ").append(record.optLong("version_code", -1));
            body.append("\nCurrent signer SHA-256: ").append(join(record.optJSONArray("current_signer_sha256")));
            body.append("\nSigning history SHA-256: ").append(join(record.optJSONArray("signing_history_sha256")));
        } else if ("not_found_or_not_visible".equals(status)) {
            body.append("NOT FOUND / NOT VISIBLE to this app (not proof of absence)");
        } else {
            body.append(status.toUpperCase(Locale.US)).append(" — ");
            body.append(record.optString("observation", "Inspection incomplete."));
        }
        return body.toString();
    }

    private String join(JSONArray values) {
        if (values == null || values.length() == 0) return "unavailable";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            if (i > 0) out.append("\n");
            out.append(values.optString(i));
        }
        return out.toString();
    }

    private void refreshInventory() {
        cards.removeAllViews();
        JSONArray apps = registry.optJSONArray("applications");
        if (apps == null) { cards.addView(label("Registry data unavailable; no changes made.", 15, true)); return; }
        JSONObject report = buildInventoryReport();
        JSONArray records = report.optJSONArray("applications");
        if (records == null) { cards.addView(label("Inventory report unavailable; no changes made.", 15, true)); return; }
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            TextView card = label(displayRecord(record), 14, false);
            card.setTextIsSelectable(true);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            cards.addView(card, lp);
        }
        cards.addView(label("Last refreshed: " + report.optString("captured_at", "unknown"), 12, false));
    }

    private String freshReportText() {
        JSONObject report = buildInventoryReport();
        try { return report.toString(2); }
        catch (Exception e) { return report.toString(); }
    }

    private void copyReport() {
        String report = freshReportText();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Yggdrasil device inventory JSON", report));
            toast("JSON report copied. Paste it directly into ChatGPT.");
        } else toast("Clipboard unavailable. Use Share JSON report instead.");
    }

    private void shareReport() {
        String report = freshReportText();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Yggdrasil device inventory report");
        send.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(send, "Share device inventory report"));
    }

    private void saveReport() {
        pendingSaveReport = freshReportText();
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType(REPORT_MIME);
        create.putExtra(Intent.EXTRA_TITLE, "yggdrasil-device-inventory.json");
        startActivityForResult(create, REQUEST_SAVE_REPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_REPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) { toast("No save destination selected."); return; }
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new java.io.IOException("Unable to open selected destination");
            output.write(pendingSaveReport.getBytes("UTF-8"));
            toast("JSON report saved.");
        } catch (Exception e) {
            toast("Report could not be saved: " + e.getClass().getSimpleName());
        } finally {
            pendingSaveReport = "";
        }
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
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
