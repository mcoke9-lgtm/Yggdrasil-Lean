package org.yggdrasil.scheduler;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;
import org.yggdrasil.scheduler.builder.BuilderEventJournal;
import org.yggdrasil.scheduler.builder.BuilderLocalExchange;
import org.yggdrasil.scheduler.builder.SecureBuilderProviderSettings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private SchedulerDb db;
    private LinearLayout root, taskList, history;
    private TextView status, builderStatus, providerStatus;
    private EditText builderNote;
    private String pendingContinuityExport;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingContinuityExport = state.getString("pending_continuity_export");
        db = new SchedulerDb(this); scheduleWorker(); buildUi(); refresh();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
    }
    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString("pending_continuity_export", pendingContinuityExport);
        super.onSaveInstanceState(outState);
    }
    @Override protected void onResume() { super.onResume(); if (db != null && taskList != null) refresh(); }
    private void scheduleWorker() {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SchedulerWorker.class, 15, TimeUnit.MINUTES).setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("yggdrasil-scheduler-poll", ExistingPeriodicWorkPolicy.KEEP, request);
    }
    private TextView text(String value, int size) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setPadding(0,8,0,8); return v; }
    private Button button(String label, Runnable action) { Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); return b; }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,24,24,32); scroll.addView(root); setContentView(scroll);
        root.addView(text("Yggdrasil Scheduler", 26));
        root.addView(text("Local-first Android host · background checks are periodic, not exact-time guarantees", 14));
        status = text("Persistent task store ready", 14); root.addView(status);
        root.addView(text("Builder Console · first local commands", 20));
        root.addView(button("Configure model provider", this::showProviderSettings));
        root.addView(button("Open Nine Builder conversation", () -> startActivity(
                new Intent(this, BuilderConversationActivity.class))));
        providerStatus = text(providerConfigurationSummary(), 13);
        root.addView(providerStatus);
        root.addView(text("Try: “remember that …” or “check memory”. This is a small local command router, not yet a general AI conversation.", 13));
        builderNote = new EditText(this);
        builderNote.setHint("Enter an instruction for the Builders");
        builderNote.setMinLines(2);
        builderNote.setMaxLines(4);
        builderNote.setGravity(android.view.Gravity.TOP);
        builderNote.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        builderNote.setContentDescription("Instruction for the local Builder console");
        root.addView(builderNote);
        root.addView(button("Send instruction", this::sendBuilderNote));
        builderStatus = text("Builder journal ready", 13);
        root.addView(builderStatus);
        root.addView(button("Check Builder journal integrity", this::refreshBuilderStatus));
        root.addView(button("Export Builder memory", this::beginContinuityExport));
        root.addView(button("Import Builder memory", this::beginContinuityImport));
        root.addView(button("Test recovery safeguards (safe)", this::runRecoverySafetyTest));
        root.addView(button("Add GitHub Actions monitor", this::showAddDialog));
        root.addView(text("Scheduled tasks", 20)); taskList = new LinearLayout(this); taskList.setOrientation(LinearLayout.VERTICAL); root.addView(taskList);
        root.addView(text("Recent activity", 20)); history = new LinearLayout(this); history.setOrientation(LinearLayout.VERTICAL); root.addView(history);
        root.addView(button("Refresh status", this::refresh));
        root.addView(button("Check now", () -> { Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(); androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(SchedulerWorker.class).setInputData(new androidx.work.Data.Builder().putBoolean("force_check", true).build()).setConstraints(c).build(); WorkManager.getInstance(this).enqueue(request); status.setText("Manual check queued; refresh in a moment"); }));
    }
    private void sendBuilderNote() {
        String instruction = builderNote.getText().toString().trim();
        org.yggdrasil.scheduler.builder.BuilderCommandRouter.Command command =
                new org.yggdrasil.scheduler.builder.BuilderCommandRouter().route(instruction);
        switch (command.action) {
            case RECORD_CONTINUITY_NOTE:
                try {
                    BuilderLocalExchange.Result result = new BuilderLocalExchange(this).recordContinuityHandoff(command.argument);
                    builderNote.setText("");
                    builderStatus.setText("Mari → Muninn · note recorded · "
                            + (result.journalIntegrityVerified ? "journal verified" : "integrity check failed")
                            + " · events " + new BuilderEventJournal(this).eventCount());
                } catch (Exception e) {
                    builderStatus.setText("Handoff failed: " + e.getMessage());
                }
                break;
            case CHECK_JOURNAL:
                refreshBuilderStatus();
                break;
            default:
                builderStatus.setText(command.explanation);
                break;
        }
    }
    private void refreshBuilderStatus() {
        try {
            BuilderEventJournal journal = new BuilderEventJournal(this);
            builderStatus.setText("Events: " + journal.eventCount() + " · SHA-256 chain: "
                    + (journal.verifyChain() ? "verified" : "FAILED"));
        } catch (Exception e) {
            builderStatus.setText("Journal check failed: " + e.getMessage());
        }
    }
    private static final int REQUEST_EXPORT_CONTINUITY = 7101;
    private static final int REQUEST_IMPORT_CONTINUITY = 7102;

    /**
     * Exercises tamper rejection against the live journal without intentionally
     * replacing it. The altered snapshot exists only in memory. If a regression
     * unexpectedly accepts it, restore the untouched in-memory snapshot before
     * reporting failure.
     */
    private void runRecoverySafetyTest() {
        try {
            BuilderEventJournal journal = new BuilderEventJournal(this);
            org.json.JSONObject original = journal.exportSnapshot();
            org.json.JSONArray events = original.optJSONArray("events");
            if (events == null || events.length() == 0) {
                builderStatus.setText("Recovery safety test needs at least one journal event; record a note first.");
                return;
            }
            long originalCount = journal.eventCount();
            boolean originalIntegrity = journal.verifyChain();
            org.json.JSONObject altered = new org.json.JSONObject(original.toString());
            org.json.JSONObject firstEvent = altered.getJSONArray("events").getJSONObject(0);
            firstEvent.put("payload", firstEvent.getString("payload") + " [intentional in-memory tamper test]");

            boolean rejected = false;
            String rejectionReason = "";
            try {
                journal.restoreSnapshot(altered);
            } catch (Exception expected) {
                rejected = true;
                rejectionReason = expected.getMessage() == null ? expected.getClass().getSimpleName() : expected.getMessage();
            }

            org.json.JSONObject immediatelyAfter = journal.exportSnapshot();
            boolean unchangedImmediately = original.toString().equals(immediatelyAfter.toString());
            if (!unchangedImmediately) {
                // Defensive recovery: even an unexpected partial mutation must not be left behind.
                journal.restoreSnapshot(original);
            }

            org.json.JSONObject after = journal.exportSnapshot();
            boolean restoredOriginal = original.toString().equals(after.toString());
            boolean integrity = journal.verifyChain();
            if (rejected && unchangedImmediately && restoredOriginal
                    && originalCount == journal.eventCount() && originalIntegrity && integrity) {
                builderStatus.setText("Recovery safety test PASSED · altered snapshot rejected · live journal unchanged · chain verified");
            } else {
                builderStatus.setText("Recovery safety test FAILED · "
                        + (unchangedImmediately ? "rejection was not confirmed" : "unexpected mutation detected; original snapshot restored")
                        + " · original data " + (restoredOriginal ? "preserved" : "NOT CONFIRMED")
                        + " · chain " + (integrity ? "verified" : "FAILED"));
            }
        } catch (Exception e) {
            builderStatus.setText("Recovery safety test could not complete: " + e.getMessage());
        }
    }

    private void beginContinuityExport() {
        try {
            pendingContinuityExport = new BuilderEventJournal(this).exportSnapshot().toString(2);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "yggdrasil-builder-continuity.json");
            startActivityForResult(intent, REQUEST_EXPORT_CONTINUITY);
        } catch (Exception e) {
            builderStatus.setText("Could not prepare export: " + e.getMessage());
        }
    }

    private void beginContinuityImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_CONTINUITY);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_CONTINUITY) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null || pendingContinuityExport == null)
                    throw new java.io.IOException("Could not open the selected destination");
                out.write(pendingContinuityExport.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                builderStatus.setText("Continuity snapshot exported successfully");
            } catch (Exception e) {
                builderStatus.setText("Export failed: " + e.getMessage());
            } finally { pendingContinuityExport = null; }
        } else if (requestCode == REQUEST_IMPORT_CONTINUITY) {
            previewContinuityImport(uri);
        }
    }

    private void previewContinuityImport(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("Could not open selected file");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0, read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > 10 * 1024 * 1024) throw new java.io.IOException("Snapshot exceeds 10 MB limit");
                bytes.write(buffer, 0, read);
            }
            org.json.JSONObject snapshot = new org.json.JSONObject(
                    new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            org.json.JSONArray events = snapshot.optJSONArray("events");
            org.json.JSONArray receipts = snapshot.optJSONArray("processed_messages");
            if (!"yggdrasil-builder-continuity".equals(snapshot.optString("format"))
                    || snapshot.optInt("format_version", -1) != 1 || events == null || receipts == null)
                throw new IllegalArgumentException("This is not a supported Builder continuity snapshot");
            new AlertDialog.Builder(this)
                    .setTitle("Replace local Builder memory?")
                    .setMessage("The selected snapshot contains " + events.length() + " events and "
                            + receipts.length() + " processed-message receipts. Import replaces the current Builder journal. "
                            + "The file's full integrity will be checked before any replacement.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Validate and import", (dialog, which) -> {
                        try {
                            BuilderEventJournal journal = new BuilderEventJournal(this);
                            journal.restoreSnapshot(snapshot);
                            builderStatus.setText("Imported " + journal.eventCount()
                                    + " events · integrity " + (journal.verifyChain() ? "verified" : "FAILED"));
                        } catch (Exception e) {
                            builderStatus.setText("Import rejected; existing journal preserved: " + e.getMessage());
                        }
                    }).show();
        } catch (Exception e) {
            builderStatus.setText("Import preview failed; existing journal unchanged: " + e.getMessage());
        }
    }


    private String providerConfigurationSummary() {
        try {
            SecureBuilderProviderSettings.Configuration config =
                    new SecureBuilderProviderSettings(this).readConfiguration();
            if (config == null) return "Model provider: not configured · no model requests will be sent";
            return "Model provider: " + config.providerId + " · " + config.model
                    + " · " + (config.hasCredential ? "credential stored securely" : "no credential")
                    + " · configuration saved; connection not tested";
        } catch (Exception e) {
            return "Model provider settings unavailable";
        }
    }

    private void showProviderSettings() {
        SecureBuilderProviderSettings settings = new SecureBuilderProviderSettings(this);
        SecureBuilderProviderSettings.Configuration current = settings.readConfiguration();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24, 8, 24, 8);
        EditText providerId = field(form, "Provider ID (e.g. hosted-main or local-main)");
        EditText endpoint = field(form, "HTTPS endpoint, or http://localhost:PORT/...");
        EditText model = field(form, "Model ID");
        EditText token = field(form, "API token (stored encrypted)");
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSingleLine(true);
        form.addView(text("Hosted providers require HTTPS and a token. Local HTTP is allowed only for localhost. "
                + "Leave the token blank to retain an existing credential. Saving does not test or contact the endpoint.", 13));
        if (current != null) {
            providerId.setText(current.providerId);
            endpoint.setText(current.endpoint);
            model.setText(current.model);
        } else {
            providerId.setText("hosted-main");
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Builder model provider")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save configuration", (d, which) -> {
                    try {
                        String selectedProviderId = providerId.getText().toString().trim();
                        String selectedEndpoint = endpoint.getText().toString().trim();
                        String secret = token.getText().toString();
                        // Never carry a saved bearer token to a different provider or endpoint.
                        boolean sameCredentialDestination =
                                SecureBuilderProviderSettings.canReuseCredential(
                                        current, selectedProviderId, selectedEndpoint);
                        if (secret.trim().isEmpty() && current != null && current.hasCredential
                                && sameCredentialDestination) {
                            secret = settings.readToken();
                        }
                        settings.save(selectedProviderId, selectedEndpoint,
                                model.getText().toString(), secret);
                        providerStatus.setText(providerConfigurationSummary());
                        builderStatus.setText("Provider configuration saved locally. No connection was attempted.");
                        token.setText("");
                    } catch (Exception e) {
                        builderStatus.setText("Provider settings not saved: " + e.getMessage());
                    }
                });
        if (current != null) {
            dialog.setNeutralButton("Remove provider", (d, which) -> {
                try {
                    settings.clear();
                    providerStatus.setText(providerConfigurationSummary());
                    builderStatus.setText("Provider settings and encrypted credential removed from this device.");
                } catch (Exception e) {
                    builderStatus.setText("Provider removal could not be fully confirmed: " + e.getMessage());
                }
            });
        }
        dialog.show();
    }

    private EditText field(LinearLayout layout, String hint) { EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint); layout.addView(e); return e; }
    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(24,8,24,8);
        EditText repo = field(form,"GitHub repository: owner/name"); EditText branch = field(form,"Branch name"); EditText workflow = field(form,"Exact workflow name"); EditText name = field(form,"Monitor name");
        EditText time = field(form,"Local time HH:MM (24-hour)"); time.setText("09:00");
        form.addView(text("Repeat on",16));
        String[] dayNames={"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
        CheckBox[] dayChecks=new CheckBox[7];
        for(int i=0;i<7;i++){ dayChecks[i]=new CheckBox(this); dayChecks[i].setText(dayNames[i]); dayChecks[i].setChecked(true); form.addView(dayChecks[i]); }
        new AlertDialog.Builder(this).setTitle("New GitHub monitor").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(dialog,which)->{
            try {
                String r=repo.getText().toString().trim(), b=branch.getText().toString().trim(), w=workflow.getText().toString().trim(), n=name.getText().toString().trim();
                if (!r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || b.isEmpty() || w.isEmpty() || n.isEmpty()) throw new IllegalArgumentException("Fill every field and use owner/name repository format");
                String[] parts=time.getText().toString().split(":"); if(parts.length!=2) throw new IllegalArgumentException("Use HH:MM time"); int h=Integer.parseInt(parts[0]), m=Integer.parseInt(parts[1]); if(h<0||h>23||m<0||m>59) throw new IllegalArgumentException("Time must be between 00:00 and 23:59");
                StringBuilder days=new StringBuilder(); for(int i=0;i<7;i++) if(dayChecks[i].isChecked()){ if(days.length()>0) days.append(","); days.append(i+1); }
                if(days.length()==0) throw new IllegalArgumentException("Choose at least one day");
                String id="gh-"+UUID.randomUUID().toString().substring(0,8); db.addTask(id,n,r,b,w,h,m,ZoneId.systemDefault().getId(),days.toString()); status.setText("Saved locally · timezone " + ZoneId.systemDefault().getId()); refresh();
            } catch(Exception e) { new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("OK",null).show(); }
        }).show();
    }
    private String daysLabel(String days) {
        String[] names={"Mon","Tue","Wed","Thu","Fri","Sat","Sun"}; StringBuilder out=new StringBuilder();
        for(String part:days.split(",")){ try{ int n=Integer.parseInt(part); if(n>=1&&n<=7){ if(out.length()>0) out.append(" "); out.append(names[n-1]); } }catch(NumberFormatException ignored){} }
        return out.toString();
    }
    private void refresh() {
        taskList.removeAllViews();
        for(SchedulerDb.Task t:db.tasks()) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(0,8,0,12);
            card.addView(text(t.name + (t.enabled ? " · ACTIVE" : " · PAUSED"),18));
            card.addView(text(t.repo + " / " + t.branch + " / " + t.workflow,13));
            card.addView(text(String.format(java.util.Locale.US,"%s %02d:%02d · %s · %s",daysLabel(t.days),t.hour,t.minute,t.zone,t.lastStatus),13));
            LinearLayout actions=new LinearLayout(this); actions.addView(button(t.enabled?"Pause":"Resume",()->{db.setEnabled(t.id,!t.enabled);refresh();})); actions.addView(button("Delete",()->new AlertDialog.Builder(this).setMessage("Delete this monitor?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{db.delete(t.id);refresh();}).show())); card.addView(actions); taskList.addView(card);
        }
        history.removeAllViews();
        java.util.List<SchedulerDb.Activity> activities = db.recentRuns(25);
        for (SchedulerDb.Activity item : activities) {
            String kind = item.detail != null && item.detail.startsWith("[manual] ") ? "MANUAL CHECK"
                    : item.detail != null && item.detail.startsWith("[scheduled] ") ? "SCHEDULED CHECK"
                    : "WORKFLOW EVENT";
            String detail = item.detail == null ? "" : item.detail.replaceFirst("^\\[(manual|scheduled)\\]\\s*", "");
            String when;
            try {
                when = java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.getDefault())
                        .withZone(ZoneId.systemDefault()).format(java.time.Instant.parse(item.createdAt));
            } catch (Exception ignored) { when = item.createdAt; }
            history.addView(text(when + " · " + kind + "\n" + item.taskName + " · " + item.status.toUpperCase(java.util.Locale.US)
                    + (detail.isEmpty() ? "" : "\n" + detail), 13));
        }
        if (activities.isEmpty()) history.addView(text("No activity yet",13));
    }
}
