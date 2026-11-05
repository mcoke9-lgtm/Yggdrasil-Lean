package org.yggdrasil.scheduler;

import android.app.Activity;
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
import org.yggdrasil.scheduler.builder.BuilderConversationProtocol;
import org.yggdrasil.scheduler.builder.BuilderConversationStore;
import org.yggdrasil.scheduler.builder.BuilderModelResponder;
import org.yggdrasil.scheduler.builder.BuilderProviderRegistry;
import org.yggdrasil.scheduler.builder.BuilderProviderRouter;
import org.yggdrasil.scheduler.builder.BuilderRole;
import org.yggdrasil.scheduler.builder.NavigatingDifficultSpaces;
import org.yggdrasil.scheduler.builder.SecureBuilderProviderSettings;
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

    @Override protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
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
        root.addView(text("Nine distinct AI roles sharing one recorded thread. They are coordinated by this app, not nine independent processes.", 14));

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
        BuilderConversationStore store = new BuilderConversationStore(getApplicationContext());
        try {
            List<String> ids = store.listThreadIds();
            if (ids.isEmpty()) {
                status.setText("No saved conversation threads yet.");
                return;
            }
            String[] labels = ids.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("Open saved conversation")
                    .setItems(labels, (dialog, which) -> {
                        setActiveThread(labels[which]);
                        refreshHistory();
                        status.setText("Loaded saved conversation history.");
                    }).setNegativeButton("Cancel", null).show();
        } finally {
            store.close();
        }
    }

    private void refreshHistory() {
        if (history == null || busy) return;
        history.removeAllViews();
        BuilderConversationStore store = new BuilderConversationStore(getApplicationContext());
        try {
            List<BuilderConversationProtocol.Turn> turns = store.readThread(activeThreadId);
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
        } finally {
            store.close();
        }
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
        String disclosureKey = config.endpoint + "\n" + config.model;
        String approved = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(KEY_DISCLOSURE, null);
        if (disclosureKey.equals(approved)) {
            performSend(message, selected);
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
                    performSend(message, selected);
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
            BuilderConversationStore store = null;
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
                BuilderModelResponder responder = new BuilderModelResponder(router, 2048);
                store = new BuilderConversationStore(getApplicationContext());
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
