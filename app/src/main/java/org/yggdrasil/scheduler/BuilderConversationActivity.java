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
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;
import org.yggdrasil.scheduler.builder.BuilderConversationCoordinator;
import org.yggdrasil.scheduler.builder.BuilderRuntimeProtocol;
import org.yggdrasil.scheduler.builder.BuilderRuntimeService;
import org.yggdrasil.scheduler.builder.BuilderConversationProtocol;
import org.yggdrasil.scheduler.builder.BuilderConversationJournal;
import org.yggdrasil.scheduler.builder.BuilderRuntimeJournalClient;
import org.yggdrasil.scheduler.builder.BuilderModelResponder;
import org.yggdrasil.scheduler.builder.BuilderDisclosureFingerprint;
import org.yggdrasil.scheduler.builder.BuilderDisclosurePolicy;
import org.yggdrasil.scheduler.builder.BuilderOvernightScheduler;
import org.yggdrasil.scheduler.builder.BuilderOperationalLimits;
import org.yggdrasil.scheduler.builder.BuilderProviderRouter;
import org.yggdrasil.scheduler.builder.BuilderRoleProviderSettings;
import org.yggdrasil.scheduler.builder.SecureBuilderProviderSettings;
import org.yggdrasil.scheduler.builder.BuilderRole;
import org.yggdrasil.scheduler.builder.NavigatingDifficultSpaces;
import java.io.OutputStream;
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
    private static final String KEY_SENSITIVE_DISCLOSURE_PREFIX = "sensitive_disclosure_declared_";
    private final Map<BuilderRole, CheckBox> roleChecks = new EnumMap<>(BuilderRole.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private LinearLayout history;
    private EditText messageInput;
    private TextView status;
    private TextView runtimeStatus;
    private TextView routesStatus;
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
    private CheckBox sensitiveDisclosureCheck;
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
        root.addView(text("Nine Builder roles share one recorded thread. Each selected role uses its own configured model route; this app orchestrates replies sequentially, not as nine independent processes.", 14));
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

        LinearLayout threadActions = new LinearLayout(this);
        threadActions.setOrientation(LinearLayout.HORIZONTAL);
        threadActions.addView(button("New thread", this::newThread));
        threadActions.addView(button("Open saved thread", this::chooseThread));
        threadActions.addView(button("Export backup", this::requestExport));
        root.addView(threadActions);
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
        root.addView(button("Configure role-specific model routes", this::chooseRoleRoute));
        routesStatus = text(routeConfigurationSummary(), 12);
        root.addView(routesStatus);

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
        sensitiveDisclosureCheck = new CheckBox(this);
        sensitiveDisclosureCheck.setText("This message OR saved thread history contains sensitive or confidential information — require one-time approval for this submission");
        sensitiveDisclosureCheck.setChecked(getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(sensitiveDisclosurePreferenceKey(), false));
        sensitiveDisclosureCheck.setOnCheckedChangeListener((buttonView, checked) ->
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                        .putBoolean(sensitiveDisclosurePreferenceKey(), checked).apply());
        root.addView(sensitiveDisclosureCheck);
        root.addView(text("This is a user-declared safeguard, not automatic content detection. Mark it if either the new message or any saved history sent with it is sensitive.", 12));
        sendButton = button("Send to selected Builders", this::requestSend);
        root.addView(sendButton);
        root.addView(button("Schedule eight-hour Nine Builders run", this::scheduleOvernightRun));
        root.addView(button("Review latest improvement incident", this::requestImprovementReview));
        root.addView(button("Adopt latest verified correction (record only)", this::requestAdoptLatestCorrection));
        status = text("No model request has been sent from this screen.", 13);
        root.addView(status);
    }

    private String sensitiveDisclosurePreferenceKey() {
        return KEY_SENSITIVE_DISCLOSURE_PREFIX + activeThreadId;
    }

    private void setActiveThread(String id) {
        activeThreadId = id;
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_THREAD, id).apply();
        if (sensitiveDisclosureCheck != null) {
            boolean declared = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .getBoolean(sensitiveDisclosurePreferenceKey(), false);
            sensitiveDisclosureCheck.setChecked(declared);
        }
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
        if (requestCode != REQUEST_EXPORT_BACKUP || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        executor.execute(() -> {
            BuilderConversationJournal journal = new BuilderRuntimeJournalClient(getApplicationContext());
            try {
                String snapshot = journal.exportSnapshot().toString(2);
                try (OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new java.io.IOException("Could not open selected backup destination");
                    output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                if (!destroyed) runOnUiThread(() -> status.setText(
                        "Conversation backup exported from Builder-native host. Keep the JSON file somewhere safe."));
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> status.setText(
                        "Backup export failed: " + e.getClass().getSimpleName()
                                + ". Existing conversation history remains in Builder-native host."));
            } finally {
                journal.close();
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

    private void chooseRoleRoute() {
        BuilderRole[] roles = BuilderRole.values();
        String[] labels = new String[roles.length];
        for (int i = 0; i < roles.length; i++) labels[i] = roles[i].getDisplayName();
        new AlertDialog.Builder(this).setTitle("Choose a Builder to configure")
                .setItems(labels, (dialog, which) -> showRoleProviderSettings(roles[which]))
                .setNegativeButton("Cancel", null).show();
    }

    private void showRoleProviderSettings(BuilderRole role) {
        BuilderRoleProviderSettings store = new BuilderRoleProviderSettings(this);
        org.yggdrasil.scheduler.builder.SecureBuilderProviderSettings.Configuration current =
                store.configuration(role);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24, 8, 24, 8);
        EditText providerId = new EditText(this);
        providerId.setSingleLine(true);
        providerId.setHint("Provider ID");
        form.addView(providerId);
        EditText endpoint = new EditText(this);
        endpoint.setSingleLine(true);
        endpoint.setHint("HTTPS endpoint, or http://localhost:PORT/...");
        form.addView(endpoint);
        EditText model = new EditText(this);
        model.setSingleLine(true);
        model.setHint("Model ID");
        form.addView(model);
        EditText token = new EditText(this);
        token.setSingleLine(true);
        token.setHint("API token (encrypted on this device)");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(token);
        form.addView(text("This role has its own encrypted credential namespace. Leave token blank only to retain this role's existing credential. Saving does not contact the endpoint.", 12));
        if (current != null) {
            providerId.setText(current.providerId);
            endpoint.setText(current.endpoint);
            model.setText(current.model);
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(role.getDisplayName() + " model route")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save route", (d, which) -> {
                    try {
                        store.save(role, providerId.getText().toString(),
                                endpoint.getText().toString(), model.getText().toString(),
                                token.getText().toString());
                        routesStatus.setText(routeConfigurationSummary());
                        status.setText(role.getDisplayName()
                                + " route saved locally. Endpoint connectivity and model identity are not yet verified.");
                        token.setText("");
                    } catch (Exception failure) {
                        status.setText("Role route not saved: " + failure.getMessage());
                    }
                });
        if (current != null) {
            dialog.setNeutralButton("Remove route", (d, which) -> {
                try {
                    store.clear(role);
                    routesStatus.setText(routeConfigurationSummary());
                    status.setText(role.getDisplayName() + " route and its encrypted credential were removed.");
                } catch (Exception failure) {
                    status.setText("Route removal could not be confirmed: " + failure.getMessage());
                }
            });
        }
        dialog.show();
    }

    private String routeConfigurationSummary() {
        BuilderRoleProviderSettings settings = new BuilderRoleProviderSettings(this);
        StringBuilder summary = new StringBuilder("Role-specific routes (not yet connection-tested):\n");
        for (BuilderRole role : BuilderRole.values())
            summary.append(settings.routeSummary(role)).append('\n');
        summary.append("Baldr distinct route: ")
                .append(settings.hasDistinctBaldrRoute() ? "configured (not independently verified)"
                        : "NOT CONFIGURED / NOT DISTINCT");
        return summary.toString();
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

        if (difficultSpacesMode != null && difficultSpacesMode.isChecked())
            message = "NAVIGATING_DIFFICULT_SPACES_MODE\n" + message;
        final String preparedMessage = message;
        BuilderRoleProviderSettings settings = new BuilderRoleProviderSettings(this);
        try {
            settings.requireConfigured(selected);
        } catch (Exception missing) {
            status.setText("No model request sent. " + missing.getMessage());
            return;
        }
        boolean improvementReview = preparedMessage.contains("BUILDER_IMPROVEMENT_REVIEW_MODE");
        if (improvementReview) {
            if (!selected.contains(BuilderRole.BALDR)) {
                status.setText("No review sent. Baldr must be selected for an independent improvement review.");
                return;
            }
            try {
                settings.requireIndependentBaldrRoute();
            } catch (Exception missingBaldr) {
                status.setText("No review sent. " + missingBaldr.getMessage());
                return;
            }
        }

        List<BuilderDisclosureFingerprint.Route> disclosureRoutes = new ArrayList<>();
        StringBuilder routeNotice = new StringBuilder();
        for (BuilderRole role : selected) {
            org.yggdrasil.scheduler.builder.SecureBuilderProviderSettings.Configuration config =
                    settings.configuration(role);
            disclosureRoutes.add(new BuilderDisclosureFingerprint.Route(
                    role.name(), config.providerId, config.endpoint, config.model));
            routeNotice.append(role.getDisplayName()).append(" → ")
                    .append(config.providerId).append(" / ").append(config.model).append("\n")
                    .append(config.endpoint).append("\n\n");
        }
        String disclosureKey = BuilderDisclosureFingerprint.create(disclosureRoutes);
        String approved = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(KEY_DISCLOSURE, null);
        boolean sensitiveOrConfidential = sensitiveDisclosureCheck != null
                && sensitiveDisclosureCheck.isChecked();
        BuilderDisclosurePolicy.Decision consentDecision = BuilderDisclosurePolicy.decision(
                sensitiveOrConfidential, disclosureKey.equals(approved));
        if (consentDecision == BuilderDisclosurePolicy.Decision.NO_PROMPT) {
            performSend(preparedMessage, selected);
            return;
        }

        String notice = "Your message and the saved conversation history for this thread, together "
                + "with each selected Builder's role instructions and the shared timestamped operational-limits snapshot, "
                + "will be sent to these configured routes:\n\n"
                + routeNotice
                + "Each listed provider may process or retain submitted content under its own terms. "
                + "Do not include passwords, API keys, or information you do not want sent to these providers.\n\n"
                + (sensitiveOrConfidential
                    ? "You marked this message or saved thread history as sensitive/confidential. This is a one-time approval for this submission and the routes listed above only. It does not create or change persistent consent. Cancel leaves all approval state unchanged. "
                    : "This approval will be remembered for routine submissions within this route and disclosed-content scope. Mark the sensitive/confidential checkbox before sending whenever either the message or saved thread history contains sensitive information; that always requires fresh one-time approval. ")
                + "No request has been made yet.";
        new AlertDialog.Builder(this)
                .setTitle(sensitiveOrConfidential
                        ? "Approve this sensitive submission once?"
                        : "Remember approval for routine submissions?")
                .setMessage(notice)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(sensitiveOrConfidential ? "Allow once and send"
                        : "Remember and send", (dialog, which) -> {
                    if (consentDecision == BuilderDisclosurePolicy.Decision.PERSISTENT_CONSENT_REQUIRED) {
                        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                                .putString(KEY_DISCLOSURE, disclosureKey).apply();
                    }
                    performSend(preparedMessage, selected);
                }).show();
    }

    /** Starts a review only after the user explicitly presses the review control. */
    private void requestImprovementReview() {
        if (busy) return;
        status.setText("Finding the latest recorded improvement incident…");
        final String threadId = activeThreadId;
        executor.execute(() -> {
            BuilderConversationJournal journal = new BuilderRuntimeJournalClient(getApplicationContext());
            try {
                List<BuilderConversationProtocol.Turn> turns = journal.readThread(threadId);
                BuilderConversationProtocol.Turn incident = null;
                for (int i = turns.size() - 1; i >= 0; i--) {
                    if (turns.get(i).type == BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT) {
                        incident = turns.get(i);
                        break;
                    }
                }
                final BuilderConversationProtocol.Turn found = incident;
                if (!destroyed) runOnUiThread(() -> {
                    if (!threadId.equals(activeThreadId)) {
                        status.setText("The active thread changed while locating the incident. No review was sent.");
                        return;
                    }
                    if (busy) {
                        status.setText("Another Builder round started while locating the incident. No review was sent.");
                        return;
                    }
                    if (found == null) {
                        status.setText("No improvement incident is recorded in this thread. No review was sent.");
                        return;
                    }
                    messageInput.setText("BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id="
                            + found.turnId + "\nReview this incident independently. The affected Builder may explain, "
                            + "but must not certify its own correction. Check evidence, alternatives, constraints, "
                            + "implementation, recovery, provenance, and acceptance criteria. If Baldr is not selected, "
                            + "state that independent verification is missing.");
                    status.setText("Review prepared for incident " + found.turnId
                            + ". Review begins only when you send and approve the provider disclosure.");
                    requestSend();
                });
            } catch (Exception failure) {
                if (!destroyed) runOnUiThread(() -> status.setText(
                        "Could not locate an improvement incident: " + failure.getMessage()));
            } finally {
                journal.close();
            }
        });
    }

    /** Records user acceptance of a correction; it does not deploy or execute it. */
    private void requestAdoptLatestCorrection() {
        if (busy) return;
        final String threadId = activeThreadId;
        status.setText("Checking for a verified correction that is not yet adopted…");
        executor.execute(() -> {
            BuilderConversationJournal journal = new BuilderRuntimeJournalClient(getApplicationContext());
            try {
                List<BuilderConversationProtocol.Turn> turns = journal.readThread(threadId);
                String correctionId = org.yggdrasil.scheduler.builder.BuilderCorrectionLedger
                        .latestAdoptableCorrection(turns);
                if (!destroyed) runOnUiThread(() -> {
                    if (!threadId.equals(activeThreadId) || busy) {
                        status.setText("Thread or activity state changed. No adoption was recorded.");
                        return;
                    }
                    if (correctionId == null) {
                        status.setText("No unadopted VERIFIED correction is available.");
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Adopt this correction?")
                            .setMessage("Correction " + correctionId + " has a recorded VERIFIED milestone. "
                                    + "This records your acceptance as project practice only. It does not deploy code, "
                                    + "change settings, contact services, or authorize external actions.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Adopt correction", (dialog, which) -> {
                                if (!threadId.equals(activeThreadId) || busy) {
                                    status.setText("Thread or activity changed. No adoption was recorded.");
                                    return;
                                }
                                busy = true;
                                sendButton.setEnabled(false);
                                messageInput.setEnabled(false);
                                executor.execute(() -> {
                                    BuilderConversationJournal adoptionJournal =
                                            new BuilderRuntimeJournalClient(getApplicationContext());
                                    try {
                                        org.yggdrasil.scheduler.builder.BuilderCorrectionLedger
                                                .recordUserAdoption(adoptionJournal, threadId, correctionId);
                                        if (!destroyed) runOnUiThread(() -> {
                                            busy = false;
                                            sendButton.setEnabled(true);
                                            messageInput.setEnabled(true);
                                            status.setText("User adoption recorded for " + correctionId
                                                    + ". No deployment or external action was performed.");
                                            refreshHistory();
                                        });
                                    } catch (Exception failure) {
                                        if (!destroyed) runOnUiThread(() -> {
                                            busy = false;
                                            sendButton.setEnabled(true);
                                            messageInput.setEnabled(true);
                                            status.setText("Adoption was not recorded: " + failure.getMessage());
                                        });
                                    } finally {
                                        adoptionJournal.close();
                                    }
                                });
                            }).show();
                });
            } catch (Exception failure) {
                if (!destroyed) runOnUiThread(() -> status.setText(
                        "Could not inspect correction history: " + failure.getMessage()));
            } finally {
                journal.close();
            }
        });
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
                BuilderRoleProviderSettings settings =
                        new BuilderRoleProviderSettings(getApplicationContext());
                settings.requireConfigured(roles);
                if (message.contains("BUILDER_IMPROVEMENT_REVIEW_MODE")) {
                    if (!roles.contains(BuilderRole.BALDR))
                        throw new IllegalStateException("Baldr must be selected for an improvement review");
                    settings.requireIndependentBaldrRoute();
                }
                BuilderProviderRouter router = settings.buildRouter();
                // Load once per submission so every selected Builder receives the same
                // timestamped limits snapshot; never imply that this is live telemetry.
                String operationalLimitsJson = BuilderOperationalLimits
                        .load(getApplicationContext()).toPromptJson();
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
                if (result.interrupted) {
                    summary += " · INTERRUPTED by " + result.interruptedBy.getDisplayName()
                            + " (" + result.interruptionType.name() + ")";
                    if (!result.notRunRoles.isEmpty())
                        summary += " · paused before: " + roleNames(result.notRunRoles);
                }
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

    private void scheduleOvernightRun() {
        if (busy) {
            status.setText("Wait for the current conversation round to finish before scheduling overnight work.");
            return;
        }
        String taskPrompt = messageInput.getText().toString().trim();
        if (taskPrompt.isEmpty() || taskPrompt.length() > 4000) {
            status.setText("Enter an overnight task of 1–4000 characters.");
            return;
        }

        BuilderRoleProviderSettings settings = new BuilderRoleProviderSettings(this);
        List<BuilderRole> roles = new ArrayList<>();
        for (BuilderRole role : BuilderRole.values()) roles.add(role);
        final List<BuilderDisclosureFingerprint.Route> routes = new ArrayList<>();
        try {
            settings.requireConfigured(roles);
            settings.requireIndependentBaldrRoute();
            for (BuilderRole role : roles) {
                SecureBuilderProviderSettings.Configuration config = settings.configuration(role);
                routes.add(new BuilderDisclosureFingerprint.Route(role.name(),
                        config.providerId, config.endpoint, config.model));
            }
        } catch (Exception unavailable) {
            status.setText("No overnight work scheduled. " + unavailable.getMessage());
            return;
        }

        final String fingerprint;
        try {
            fingerprint = BuilderDisclosureFingerprint.digest(
                    BuilderDisclosureFingerprint.createOvernight(routes, taskPrompt));
        } catch (Exception invalid) {
            status.setText("No overnight work scheduled. Disclosure scope could not be prepared.");
            return;
        }

        NumberPicker hours = new NumberPicker(this);
        hours.setMinValue(0);
        hours.setMaxValue(24);
        hours.setValue(0);
        NumberPicker minutes = new NumberPicker(this);
        minutes.setMinValue(0);
        minutes.setMaxValue(59);
        minutes.setValue(0);
        LinearLayout timing = new LinearLayout(this);
        timing.setOrientation(LinearLayout.HORIZONTAL);
        timing.addView(hours, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        timing.addView(minutes, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        new AlertDialog.Builder(this)
                .setTitle("When should the overnight run begin?")
                .setMessage("Choose a delay from now. The run lasts up to eight hours after it actually starts; it will not consume its work window while waiting.")
                .setView(timing)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> {
                    long delay = (hours.getValue() * 60L + minutes.getValue()) * 60L * 1000L;
                    StringBuilder disclosure = new StringBuilder();
                    disclosure.append("The scheduled task prompt and the new, dedicated run-thread history will be sent to all nine configured model routes. Existing conversation history from this screen is NOT included. Each cycle repeats approximately every 15 minutes while the eight-hour maximum window remains open. The phone must retain network access and Android must allow the scheduled work to run. A persistent notification provides a Stop control.\n\n")
                            .append("This is one-time approval for this scheduled run only. The approval is bound to the exact task prompt and all nine route endpoints/model IDs. Later changes to the prompt or routes invalidate it.\n\n")
                            .append("Start delay: ").append(hours.getValue()).append("h ")
                            .append(minutes.getValue()).append("m\n\nTASK:\n")
                            .append(taskPrompt).append("\n\nROUTES:\n");
                    for (BuilderDisclosureFingerprint.Route route : routes) {
                        disclosure.append(route.role).append(" — ")
                                .append(route.providerId).append(" / ")
                                .append(route.modelId).append("\n")
                                .append(route.endpoint).append("\n");
                    }
                    if (sensitiveDisclosureCheck != null && sensitiveDisclosureCheck.isChecked()) {
                        disclosure.append("\nYou marked the task as sensitive/confidential. This approval is one-time and limited to the dedicated overnight run.");
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Approve and schedule this overnight run?")
                            .setMessage(disclosure.toString())
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Approve and schedule", (confirm, approved) -> {
                                try {
                                    BuilderOvernightScheduler.ScheduledRun run =
                                            BuilderOvernightScheduler.schedule(this, taskPrompt,
                                                    fingerprint, delay);
                                    status.setText("Scheduled eight-hour Nine Builders run "
                                            + run.runId.substring(0, 8) + " · starts in "
                                            + hours.getValue() + "h " + minutes.getValue()
                                            + "m · dedicated thread " + run.threadId.substring(0, 8)
                                            + ". No model call is made until the scheduled worker starts.");
                                } catch (Exception failure) {
                                    status.setText("Overnight run was not scheduled: " + failure.getMessage());
                                }
                            }).show();
                }).show();
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
