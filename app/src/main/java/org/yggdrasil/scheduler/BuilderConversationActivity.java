package org.yggdrasil.scheduler;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.net.Uri;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.yggdrasil.scheduler.builder.BuilderConversationCoordinator;
import org.yggdrasil.scheduler.builder.BuilderRuntimeProtocol;
import org.yggdrasil.scheduler.builder.BuilderRuntimeService;
import org.yggdrasil.scheduler.builder.BuilderConversationProtocol;
import org.yggdrasil.scheduler.builder.BuilderConversationJournal;
import org.yggdrasil.scheduler.builder.BuilderRuntimeJournalClient;
import org.yggdrasil.scheduler.builder.BuilderModelResponder;
import org.yggdrasil.scheduler.builder.BuilderOperationalLimits;
import org.yggdrasil.scheduler.builder.BuilderOperationalSnapshotJournal;
import org.yggdrasil.scheduler.builder.BuilderRecoveryDrill;
import org.yggdrasil.scheduler.builder.BuilderRecoveryExport;
import org.yggdrasil.scheduler.builder.BuilderRecoveryIntegrity;
import org.yggdrasil.scheduler.builder.BuilderProviderRegistry;
import org.yggdrasil.scheduler.builder.BuilderProviderRouter;
import org.yggdrasil.scheduler.builder.BuilderRole;
import org.yggdrasil.scheduler.builder.NavigatingDifficultSpaces;
import org.yggdrasil.scheduler.builder.SecureBuilderProviderSettings;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User-directed Nine Builder conversation screen. Model calls occur only after
 * Send is pressed and the endpoint-specific data-sharing notice is accepted.
 */
public final class BuilderConversationActivity extends Activity {
    private static final String UI_PREFS = "builder_conversation_ui";
    private static final String KEY_THREAD = "active_thread_id";
    private static final String KEY_DISCLOSURE = "approved_endpoint_model";
    private final Map<BuilderRole, CheckBox> roleChecks = new EnumMap<>(BuilderRole.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private LinearLayout history;
    private EditText messageInput;
    private TextView status;
    private TextView runtimeStatus;
    private Messenger runtimeMessenger;
    private boolean runtimeBound;
    private final Messenger runtimeReply = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (runtimeStatus == null) return;
            if (message.what == BuilderRuntimeProtocol.MESSAGE_HEALTH) {
                Bundle data = message.getData();
                String runtimeId = data.getString(BuilderRuntimeProtocol.KEY_RUNTIME_ID, "unknown");
                java.util.ArrayList<String> roles = data.getStringArrayList(BuilderRuntimeProtocol.KEY_BUILDERS);
                String deviceJson = data.getString(BuilderRuntimeProtocol.KEY_DEVICE_INVENTORY_JSON, "{}");
                try {
                    org.json.JSONObject device = new org.json.JSONObject(deviceJson);
                    runtimeStatus.setText("Builder-native host: " + data.getString(BuilderRuntimeProtocol.KEY_STATUS)
                            + " · runtime " + runtimeId.substring(0, Math.min(8, runtimeId.length()))
                            + " · " + (roles == null ? 0 : roles.size()) + " role identities · separate process\n"
                            + device.optString("manufacturer") + " " + device.optString("model")
                            + " · Android " + device.optString("android_release")
                            + " · RAM " + formatBytes(device.optLong("memory_total_bytes"))
                            + " · available storage " + formatBytes(device.optLong("data_storage_available_bytes")));
                } catch (org.json.JSONException invalidInventory) {
                    runtimeStatus.setText("Builder-native host is ready; device inventory could not be parsed.");
                }
            } else if (message.what == BuilderRuntimeProtocol.MESSAGE_ERROR) {
                runtimeStatus.setText("Builder-native host error: " + message.getData().getString(BuilderRuntimeProtocol.KEY_ERROR));
            }
        }
    });
    private final ServiceConnection runtimeConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            runtimeMessenger = new Messenger(binder);
            try {
                Message request = Message.obtain(null, BuilderRuntimeProtocol.MESSAGE_GET_HEALTH);
                request.replyTo = runtimeReply;
                runtimeMessenger.send(request);
            } catch (RemoteException failure) {
                if (runtimeStatus != null) runtimeStatus.setText("Builder-native host connection failed; local conversation remains available.");
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            runtimeMessenger = null;
            if (runtimeStatus != null) runtimeStatus.setText("Builder-native host disconnected; Android will manage process recovery.");
        }
    };
    private TextView threadLabel;
    private Button sendButton;
    private CheckBox difficultSpacesMode;
    private String activeThreadId;
    private volatile boolean destroyed;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        activeThreadId = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(KEY_THREAD, null);
        if (activeThreadId == null || activeThreadId.trim().isEmpty())
            setActiveThread(UUID.randomUUID().toString());
        buildUi();
        refreshHistory();
    }

    @Override protected void onStart() {
        super.onStart();
        runtimeStatus.setText("Connecting to Builder-native runtime process…");
        runtimeBound = bindService(new Intent(this, BuilderRuntimeService.class), runtimeConnection, BIND_AUTO_CREATE);
        if (!runtimeBound) runtimeStatus.setText("Builder-native host unavailable; conversation UI remains available.");
    }

    @Override protected void onStop() {
        if (runtimeBound) {
            unbindService(runtimeConnection);
            runtimeBound = false;
        }
        runtimeMessenger = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "unknown";
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 20, 24, 28);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("Nine Builder Conversation", 24));
        root.addView(text("Nine distinct AI roles sharing one recorded thread. They are coordinated by this app, not nine independent AI workers.", 14));
        runtimeStatus = text("Builder-native host: waiting for connection", 13);
        root.addView(runtimeStatus);
        root.addView(text("Shared operational limits are included in every Builder prompt. Snapshot evidence is timestamped, not live telemetry.", 12));

        SecureBuilderProviderSettings.Configuration config =
                new SecureBuilderProviderSettings(this).readConfiguration();
        if (config == null) {
            root.addView(text("Model provider not configured. Return to the main screen and configure a hosted HTTPS or localhost model endpoint.", 14));
        } else {
            root.addView(text("Provider: " + config.providerId + " · model: " + config.model
                    + " · " + config.endpoint, 12));
        }

        root.addView(text("Recovery and backup", 18));
        root.addView(text("Step 1 — Export backup: save a Yggdrasil recovery JSON file using Android's save dialog. Step 2 — Run recovery drill: choose that saved file in Android's file picker. The app reads it and displays a report; it does not restore or change anything.", 13));
        LinearLayout threadActions = new LinearLayout(this);
        threadActions.setOrientation(LinearLayout.HORIZONTAL);
        threadActions.addView(button("New thread", this::newThread));
        threadActions.addView(button("Open saved thread", this::chooseThread));
        threadActions.addView(button("Export backup", this::requestExport));
        threadActions.addView(button("Run recovery drill", this::requestRecoveryDrill));
        root.addView(text("Recovery drill: choose a previously exported Yggdrasil JSON backup. The app checks its structure and checksum and shows a readiness report. This is read-only: it does not restore, overwrite, or delete anything. This first interface pass does not yet compare against a separately retained source capture, so the report will identify that limitation.", 13));
        root.addView(threadActions);
        root.addView(button("Share thread with ChatGPT", this::requestBridgeShare));
        threadLabel = text("", 12);
        root.addView(threadLabel);
        updateThreadLabel();

        root.addView(text("Choose the Builders for this round", 18));
        root.addView(text("All nine are selected by default. Each selected Builder receives the thread accumulated so far.", 13));
        for (BuilderRole role : BuilderRole.values()) {
            CheckBox check = new CheckBox(this);
            check.setText(role.getDisplayName() + " — " + role.getResponsibility());
            check.setChecked(true);
            roleChecks.put(role, check);
            root.addView(check);
        }

        difficultSpacesMode = new CheckBox(this);
        difficultSpacesMode.setText("Navigating Difficult Spaces mode — Thurisaz / Algiz protection navigation");
        difficultSpacesMode.setChecked(getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean("navigating_difficult_spaces_mode", false));
        difficultSpacesMode.setOnCheckedChangeListener((buttonView, checked) ->
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                        .putBoolean("navigating_difficult_spaces_mode", checked).apply());
        root.addView(difficultSpacesMode);
        root.addView(text("When enabled, the Builders investigate boundaries, identify protective authority, and choose an authorized path. They do not bypass safeguards.", 13));

        root.addView(text("Conversation history", 18));
        history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        root.addView(history);
        root.addView(button("Refresh history", this::refreshHistory));

        root.addView(text("Your message", 18));
        messageInput = new EditText(this);
        messageInput.setHint("Give the Nine Builders a question, task, or direction");
        messageInput.setMinLines(3);
        messageInput.setMaxLines(7);
        messageInput.setGravity(Gravity.TOP);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(messageInput);
        sendButton = button("Send to selected Builders", this::requestSend);
        root.addView(sendButton);
        status = text("No model request has been sent from this screen.", 13);
        root.addView(status);
    }

    private void setActiveThread(String id) {
        activeThreadId = id;
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_THREAD, id).apply();
        updateThreadLabel();
    }

    private void updateThreadLabel() {
        if (threadLabel != null)
            threadLabel.setText("Active thread: " + activeThreadId);
    }

    private void newThread() {
        if (busy) return;
        setActiveThread(UUID.randomUUID().toString());
        refreshHistory();
        status.setText("New conversation ready. Earlier threads remain saved.");
    }

    private void chooseThread() {
        if (busy) return;
        status.setText("Loading saved thread list from Builder-native host…");
        executor.execute(() -> {
            BuilderConversationJournal journal = new BuilderRuntimeJournalClient(getApplicationContext());
            try {
                List<String> ids = journal.listThreadIds();
                if (!destroyed) runOnUiThread(() -> {
                    if (ids.isEmpty()) {
                        status.setText("No saved conversation threads yet.");
                        return;
                    }
                    String[] labels = ids.toArray(new String[0]);
                    new AlertDialog.Builder(this).setTitle("Open saved conversation")
                            .setItems(labels, (dialog, which) -> {
                                setActiveThread(labels[which]);
                                refreshHistory();
                                status.setText("Loaded saved conversation history from Builder-native host.");
                            }).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception failure) {
                if (!destroyed) runOnUiThread(() -> status.setText(
                        "Could not load threads from Builder-native host: " + failure.getMessage()));
            } finally {
                journal.close();
            }
        });
    }

    private static final int REQUEST_EXPORT_BACKUP = 901;
    private static final int REQUEST_RECOVERY_DRILL = 902;
    private static final int MAX_RECOVERY_EXPORT_BYTES = 25 * 1024 * 1024;

    private void requestExport() {
        if (busy) {
            status.setText("Wait until the current Builder round finishes before exporting.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "yggdrasil-nine-builder-conversations.json");
        startActivityForResult(intent, REQUEST_EXPORT_BACKUP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_RECOVERY_DRILL) {
            runRecoveryDrill(data.getData());
            return;
        }
        if (requestCode != REQUEST_EXPORT_BACKUP) return;
        Uri destination = data.getData();
        executor.execute(() -> {
            BuilderConversationJournal journal = new BuilderRuntimeJournalClient(getApplicationContext());
            try {
                org.json.JSONObject conversationSnapshot = journal.exportSnapshot();
                org.json.JSONArray provenanceEvents = null;
                String provenanceStatus;
                String provenanceErrorClass = null;
                BuilderOperationalSnapshotJournal provenance =
                        new BuilderOperationalSnapshotJournal(getApplicationContext());
                try {
                    provenanceEvents = provenance.exportEvents();
                    provenanceStatus = provenanceEvents.length() == 0 ? "empty" : "available";
                } catch (Exception provenanceFailure) {
                    provenanceStatus = "unavailable";
                    provenanceErrorClass = provenanceFailure.getClass().getSimpleName();
                } finally {
                    provenance.close();
                }
                org.json.JSONObject combined = BuilderRecoveryExport.compose(
                        conversationSnapshot, provenanceEvents, provenanceStatus,
                        provenanceErrorClass);
                BuilderRecoveryIntegrity.Report integrity = BuilderRecoveryIntegrity.validate(combined);
                if (!integrity.valid) {
                    throw new IllegalStateException("Recovery integrity validation failed: "
                            + integrity.errors.toString());
                }
                if (integrity.provenanceAvailable
                        && !BuilderRecoveryIntegrity.reconcileProvenance(provenanceEvents, combined)) {
                    throw new IllegalStateException("Operational provenance did not reconcile with the source capture");
                }
                String snapshot = combined.toString(2);
                String provenanceResult = integrity.provenanceAvailable
                        ? "source events reconciled" : "source journal unavailable; limitation preserved";
                try (OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new java.io.IOException("Could not open selected backup destination");
                    output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                if (!destroyed) runOnUiThread(() -> status.setText(
                        "Recovery export validated; provenance: " + provenanceResult
                                + ". Keep the JSON file somewhere safe."));
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> status.setText(
                        "Backup export failed: " + e.getClass().getSimpleName()
                                + ". Existing conversation history remains in Builder-native host."));
            } finally {
                journal.close();
            }
        });
    }

    private void requestRecoveryDrill() {
        if (busy) {
            status.setText("Wait until the current Builder round finishes before running a recovery drill.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "Select a Yggdrasil recovery backup");
        startActivityForResult(intent, REQUEST_RECOVERY_DRILL);
    }

    private void runRecoveryDrill(Uri source) {
        status.setText("Reading selected backup for a read-only recovery assessment…");
        executor.execute(() -> {
            try {
                byte[] bytes;
                try (InputStream input = getContentResolver().openInputStream(source);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (input == null) throw new java.io.IOException("Selected backup could not be opened");
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_RECOVERY_EXPORT_BYTES)
                            throw new java.io.IOException("Backup exceeds the 25 MB assessment limit");
                        output.write(buffer, 0, count);
                    }
                    bytes = output.toByteArray();
                }
                org.json.JSONObject backup = new org.json.JSONObject(
                        new String(bytes, StandardCharsets.UTF_8));
                BuilderRecoveryDrill.Report report = BuilderRecoveryDrill.assess(backup, null);
                StringBuilder message = new StringBuilder();
                message.append("Read-only recovery assessment\n\n");
                message.append("Readiness: ").append(report.readiness.replace('_', ' ')).append("\n");
                message.append("Integrity: ").append(report.integrityValid ? "valid" : "failed").append("\n");
                message.append("Checksum: ").append(report.checksumValid ? "valid" : "failed").append("\n");
                message.append("Provenance: ").append(report.provenanceState).append("\n");
                message.append("Independent source comparison: ").append(report.sourceComparison).append("\n\n");
                message.append("Findings:\n");
                for (String finding : report.findings) message.append("• ").append(finding).append("\n");
                message.append("\nNo restoration, overwrite, or deletion was performed.");
                String resultText = message.toString();
                if (!destroyed) runOnUiThread(() -> {
                    status.setText("Recovery drill assessment complete: " + report.readiness.replace('_', ' '));
                    new AlertDialog.Builder(this)
                            .setTitle("Recovery drill report")
                            .setMessage(resultText)
                            .setPositiveButton("Close", null)
                            .show();
                });
            } catch (Exception failure) {
                if (!destroyed) runOnUiThread(() -> {
                    status.setText("Recovery drill could not assess the selected file.");
                    new AlertDialog.Builder(this)
                            .setTitle("Recovery drill could not run")
                            .setMessage("The selected file could not be read as a valid Yggdrasil recovery export.\n\n"
                                    + failure.getClass().getSimpleName() + "\n\n"
                                    + "No data was changed.")
                            .setPositiveButton("Close", null)
                            .show();
                });
            }
        });
    }

    private void refreshHistory() {
        if (history == null || busy) return;
        history.removeAllViews();
        history.addView(text("Loading canonical history from Builder-native host…", 13));
        final String threadId = activeThreadId;
        executor.execute(() -> {
            BuilderConversationJournal journal = new BuilderRuntimeJournalClient(getApplicationContext());
            try {
                List<BuilderConversationProtocol.Turn> turns = journal.readThread(threadId);
                if (!destroyed) runOnUiThread(() -> {
                    if (history == null || !threadId.equals(activeThreadId)) return;
                    history.removeAllViews();
                    if (turns.isEmpty()) {
                        history.addView(text("This thread is empty. Send a message to begin.", 13));
                        return;
                    }
                    for (BuilderConversationProtocol.Turn turn : turns) {
                        String speaker = turn.speaker == BuilderConversationProtocol.Participant.USER
                                ? "You" : displayName(turn.speaker.name());
                        String recipient = turn.recipient == null ? "" : " → " + displayName(turn.recipient.name());
                        TextView item = text(speaker + recipient + " · " + turn.type.name()
                                + " · " + turn.evidenceStatus.name() + "\n" + turn.body, 14);
                        item.setPadding(0, 10, 0, 14);
                        history.addView(item);
                    }
                });
            } catch (Exception failure) {
                if (!destroyed) runOnUiThread(() -> {
                    if (history != null) {
                        history.removeAllViews();
                        history.addView(text("Builder-native history unavailable: "
                                + failure.getMessage(), 13));
                    }
                    status.setText("Builder-native host journal read failed.");
                });
            } finally {
                journal.close();
            }
        });
    }

    private static String displayName(String participant) {
        switch (participant) {
            case "HUGINN": return "Huginn";
            case "MUNINN": return "Muninn";
            case "KVASIR": return "Kvasir";
            case "MARI": return "Mari";
            case "HODR": return "Höðr";
            case "TYR": return "Týr";
            case "LOKI": return "Loki";
            case "ODIN": return "Odin";
            case "BALDR": return "Baldr";
            default: return participant;
        }
    }


    private void requestBridgeShare() {
        if (busy) { status.setText("Wait until the current Builder round finishes before preparing a share."); return; }
        final String threadId=activeThreadId;
        status.setText("Preparing a reviewable text handoff from the active thread…");
        executor.execute(() -> {
            BuilderConversationJournal journal=new BuilderRuntimeJournalClient(getApplicationContext());
            try {
                List<BuilderConversationProtocol.Turn> turns=journal.readThread(threadId);
                if(turns.isEmpty()) throw new IllegalStateException("This thread has no messages to share");
                StringBuilder text=new StringBuilder();
                text.append("Yggdrasil Nine Builder conversation handoff\nThread: ").append(threadId)
                    .append("\nReview this context as user-supplied material; Builder roles are coordinated by one app runtime.\n\n");
                for(BuilderConversationProtocol.Turn turn:turns) {
                    text.append(turn.speaker==BuilderConversationProtocol.Participant.USER?"User":displayName(turn.speaker.name()))
                        .append(" · ").append(turn.type.name()).append(" · ").append(turn.evidenceStatus.name())
                        .append("\n").append(turn.body).append("\n\n");
                    if(text.length()>BuilderConversationProtocol.MAX_BODY_LENGTH)
                        throw new IllegalStateException("This thread exceeds the 8,000-character bridge limit. No text was shared.");
                }
                String prepared=text.toString().trim();
                runOnUiThread(() -> showBridgeShareEditor(threadId,prepared));
            } catch(Exception failure) {
                String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
                if(!destroyed) runOnUiThread(() -> status.setText("Bridge preparation stopped: "+reason));
            } finally { journal.close(); }
        });
    }

    private void showBridgeShareEditor(String threadId,String prepared) {
        EditText editor=new EditText(this); editor.setText(prepared);
        editor.setMinLines(6); editor.setMaxLines(14); editor.setGravity(Gravity.TOP);
        editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this).setTitle("Review context before sharing")
            .setMessage("Edit or remove anything you do not want to send. Nothing leaves Yggdrasil until you approve the next step.")
            .setView(editor).setNegativeButton("Cancel",null)
            .setPositiveButton("Review sharing",(dialog,which)->{
                String content=editor.getText().toString().trim();
                if(content.isEmpty()){status.setText("No text selected; nothing was shared.");return;}
                if(content.length()>BuilderConversationProtocol.MAX_BODY_LENGTH){
                    status.setText("Bridge limit is 8,000 characters; shorten the text before sharing.");return;
                }
                new AlertDialog.Builder(this).setTitle("Allow this text to leave Yggdrasil?")
                    .setMessage("The reviewed text will be passed to Android's share sheet. Choose ChatGPT or another recipient there. Yggdrasil cannot verify the recipient or control how that app processes the text.\n\nNo API request or server transfer is made by Yggdrasil.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Open share sheet",(confirm,choice)->dispatchBridgeShare(threadId,content)).show();
            }).show();
    }

    private void dispatchBridgeShare(String threadId,String content) {
        executor.execute(() -> {
            try {
                long now=System.currentTimeMillis();
                org.json.JSONObject event=org.yggdrasil.scheduler.builder.BuilderBridgeEnvelope.create(
                    "OUTBOUND","yggdrasil_nine_builder_conversation",
                    "android_share_sheet_user_selected_recipient",threadId,null,content,
                    "explicit_user_approval","prepared",null,now);
                String eventId=event.getString("event_id");
                try(org.yggdrasil.scheduler.builder.BuilderEventJournal audit=
                        new org.yggdrasil.scheduler.builder.BuilderEventJournal(getApplicationContext())){
                    if(!audit.append("bridge-outbound-prepared:"+eventId,
                        "CHATGPT_BRIDGE_OUTBOUND_PREPARED","USER",now,event.toString()))
                        throw new IllegalStateException("Bridge audit event already exists; share stopped");
                }
                if(!destroyed) runOnUiThread(() -> {
                    Intent send=new Intent(Intent.ACTION_SEND); send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT,content);
                    status.setText("Opening Android share sheet. The recipient is chosen by you.");
                    startActivity(Intent.createChooser(send,"Share with ChatGPT"));
                });
            } catch(Exception failure) {
                String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
                if(!destroyed) runOnUiThread(() -> status.setText(
                    "Share was not started because provenance could not be recorded: "+reason));
            }
        });
    }

    private void requestSend() {
        if (busy) return;
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) {
            status.setText("Write a message before sending.");
            return;
        }
        List<BuilderRole> selected = new ArrayList<>();
        for (BuilderRole role : BuilderRole.values()) {
            CheckBox check = roleChecks.get(role);
            if (check != null && check.isChecked()) selected.add(role);
        }
        if (selected.isEmpty()) {
            status.setText("Select at least one Builder.");
            return;
        }

        SecureBuilderProviderSettings settings = new SecureBuilderProviderSettings(this);
        SecureBuilderProviderSettings.Configuration config = settings.readConfiguration();
        if (config == null) {
            status.setText("No model provider is configured. No message was sent.");
            return;
        }
        if (difficultSpacesMode != null && difficultSpacesMode.isChecked()) {
            message = "NAVIGATING_DIFFICULT_SPACES_MODE\n" + message;
        }
        final String preparedMessage = message;
        String disclosureKey = config.endpoint + "\n" + config.model;
        String approved = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(KEY_DISCLOSURE, null);
        if (disclosureKey.equals(approved)) {
            performSend(preparedMessage, selected);
            return;
        }

        String notice = "Your message and the saved conversation history for this thread, together "
                + "with each selected Builder's role instructions, will be sent to:\n\n"
                + config.endpoint + "\nModel: " + config.model + "\n\n"
                + "The provider may process or retain submitted content under its own terms. "
                + "Do not include passwords, API keys, or information you do not want sent to this provider. "
                + "No request has been made yet.";
        new AlertDialog.Builder(this).setTitle("Allow this provider to receive the conversation?")
                .setMessage(notice)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Allow and send", (dialog, which) -> {
                    getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_DISCLOSURE, disclosureKey).apply();
                    performSend(preparedMessage, selected);
                }).show();
    }

    private void performSend(String message, List<BuilderRole> selectedRoles) {
        busy = true;
        sendButton.setEnabled(false);
        messageInput.setEnabled(false);
        status.setText("Sending to selected Builders. Each response will be recorded as it completes.");
        final String threadId = activeThreadId;
        final List<BuilderRole> roles = new ArrayList<>(selectedRoles);

        executor.execute(() -> {
            BuilderConversationJournal store = null;
            try {
                SecureBuilderProviderSettings settings =
                        new SecureBuilderProviderSettings(getApplicationContext());
                org.yggdrasil.scheduler.builder.OpenAiCompatibleModelProvider provider =
                        settings.buildProvider();
                BuilderProviderRegistry registry = new BuilderProviderRegistry(
                        Collections.singletonList(provider));
                Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
                for (BuilderRole role : BuilderRole.values()) routes.put(role, provider.providerId());
                BuilderProviderRouter router = new BuilderProviderRouter(registry, routes);
                // Load once per submission so every selected Builder receives the same
                // timestamped limits snapshot; never imply that this is live telemetry.
                String operationalLimitsJson = BuilderOperationalLimits
                        .load(getApplicationContext()).toPromptJson();
                // Persist snapshot provenance before dispatch. If this audit write fails,
                // do not send the conversation to a model without a traceable snapshot.
                try (BuilderOperationalSnapshotJournal provenance =
                             new BuilderOperationalSnapshotJournal(getApplicationContext())) {
                    provenance.append(threadId, operationalLimitsJson, roles,
                            System.currentTimeMillis());
                }
                BuilderModelResponder responder = new BuilderModelResponder(
                        router, 2048, operationalLimitsJson);
                store = new BuilderRuntimeJournalClient(getApplicationContext());
                BuilderConversationCoordinator coordinator =
                        new BuilderConversationCoordinator(store, responder);
                BuilderConversationCoordinator.Result result =
                        coordinator.submit(threadId, message, roles);
                List<BuilderConversationProtocol.Turn> turns = store.readThread(threadId);
                int successful = result.appendedTurns.size() - 1;
                String summary = "Recorded " + successful + " Builder replies";
                if (!result.failedRoles.isEmpty()) summary += " · failed: " + roleNames(result.failedRoles);
                if (!result.unroutedRecipients.isEmpty())
                    summary += " · not routed: " + roleNames(result.unroutedRecipients);
                final String finalSummary = summary;
                if (!destroyed) runOnUiThread(() -> {
                    busy = false;
                    sendButton.setEnabled(true);
                    messageInput.setEnabled(true);
                    messageInput.setText("");
                    status.setText(finalSummary);
                    renderTurns(turns);
                });
            } catch (Exception failure) {
                final String reason = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                if (!destroyed) runOnUiThread(() -> {
                    busy = false;
                    sendButton.setEnabled(true);
                    messageInput.setEnabled(true);
                    status.setText("Conversation request failed: " + reason
                            + ". Any user turn or earlier Builder replies already saved remain in history.");
                    refreshHistory();
                });
            } finally {
                if (store != null) store.close();
            }
        });
    }

    private void renderTurns(List<BuilderConversationProtocol.Turn> turns) {
        history.removeAllViews();
        for (BuilderConversationProtocol.Turn turn : turns) {
            String speaker = turn.speaker == BuilderConversationProtocol.Participant.USER
                    ? "You" : displayName(turn.speaker.name());
            String recipient = turn.recipient == null ? "" : " → " + displayName(turn.recipient.name());
            history.addView(text(speaker + recipient + " · " + turn.type.name()
                    + " · " + turn.evidenceStatus.name() + "\n" + turn.body, 14));
        }
    }

    private static String roleNames(List<BuilderRole> roles) {
        List<String> names = new ArrayList<>();
        for (BuilderRole role : roles) names.add(role.getDisplayName());
        return android.text.TextUtils.join(", ", names);
    }
}