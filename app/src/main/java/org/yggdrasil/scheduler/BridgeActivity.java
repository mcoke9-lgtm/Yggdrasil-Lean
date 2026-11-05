package org.yggdrasil.scheduler;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import org.yggdrasil.scheduler.builder.BuilderBridgeEnvelope;
import org.yggdrasil.scheduler.builder.BuilderConversationProtocol;
import org.yggdrasil.scheduler.builder.BuilderEventJournal;
import org.yggdrasil.scheduler.builder.BuilderRuntimeJournalClient;
import java.util.List;
import java.util.UUID;

/** Narrow Android share target; incoming text requires preview and explicit confirmation. */
public final class BridgeActivity extends Activity {
    private static final String UI_PREFS="builder_conversation_ui";
    private static final String KEY_THREAD="active_thread_id";
    private String incomingText;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView message=new TextView(this); message.setPadding(32,32,32,32);
        message.setText("Yggdrasil Bridge\n\nReview incoming shared text before adding it to the Nine Builder conversation.");
        setContentView(message);
        if(!Intent.ACTION_SEND.equals(getIntent().getAction())||!"text/plain".equals(getIntent().getType())) {
            reject("Unsupported share request. Only plain text is accepted."); return;
        }
        CharSequence shared=getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        incomingText=shared==null?null:shared.toString().trim();
        if(incomingText==null||incomingText.isEmpty()) { reject("The shared message was empty."); return; }
        if(incomingText.length()>BuilderConversationProtocol.MAX_BODY_LENGTH) {
            reject("The shared message exceeds the 8,000-character limit. No content was imported."); return;
        }
        String preview=incomingText.length()>1400
                ? incomingText.substring(0,1400)+"\n\n[Preview shortened; full text will be imported]" : incomingText;
        new AlertDialog.Builder(this).setTitle("Incoming message to Yggdrasil")
            .setMessage("External shared text · sending-app identity is not verified.\n\n"+preview
                    +"\n\nAdd this text to the active Nine Builder thread?")
            .setNegativeButton("Cancel",(d,w)->finish())
            .setPositiveButton("Review and add",(d,w)->importWithConsent())
            .setOnCancelListener(d->finish()).show();
    }
    private void importWithConsent() {
        final String content=incomingText;
        new Thread(()->{
            BuilderRuntimeJournalClient conversation=new BuilderRuntimeJournalClient(getApplicationContext());
            BuilderEventJournal audit=new BuilderEventJournal(getApplicationContext());
            boolean turnAppended=false;
            try {
                String threadId=getSharedPreferences(UI_PREFS,MODE_PRIVATE).getString(KEY_THREAD,null);
                if(threadId==null||threadId.trim().isEmpty()) {
                    threadId=UUID.randomUUID().toString();
                    getSharedPreferences(UI_PREFS,MODE_PRIVATE).edit().putString(KEY_THREAD,threadId).apply();
                }
                List<BuilderConversationProtocol.Turn> existing=conversation.readThread(threadId);
                long now=System.currentTimeMillis();
                if(!existing.isEmpty()) now=Math.max(now,existing.get(existing.size()-1).timestampEpochMillis+1L);
                String turnId=UUID.randomUUID().toString();
                org.json.JSONObject pending=BuilderBridgeEnvelope.create("INBOUND","external_android_share_unverified",
                    "yggdrasil_nine_builder_conversation",threadId,turnId,content,"explicit_user_approval","pending",null,now);
                String pendingId=pending.getString("event_id");
                if(!audit.append("bridge-inbound-pending:"+pendingId,"CHATGPT_BRIDGE_INBOUND_PENDING",
                    "USER",now,pending.toString())) throw new IllegalStateException("Bridge audit event already exists; import stopped");
                BuilderConversationProtocol.Turn turn=new BuilderConversationProtocol.Turn(
                    BuilderConversationProtocol.CURRENT_VERSION,threadId,turnId,
                    BuilderConversationProtocol.Participant.USER,null,null,
                    BuilderConversationProtocol.TurnType.CONTRIBUTION,
                    BuilderConversationProtocol.EvidenceStatus.USER_CONFIRMED,now,content);
                conversation.append(turn); turnAppended=true;
                long committedAt=Math.max(now+1L,System.currentTimeMillis());
                org.json.JSONObject committed=BuilderBridgeEnvelope.create("INBOUND","external_android_share_unverified",
                    "yggdrasil_nine_builder_conversation",threadId,turnId,content,"explicit_user_approval",
                    "committed",pendingId,committedAt);
                audit.append("bridge-inbound-committed:"+committed.getString("event_id"),
                    "CHATGPT_BRIDGE_INBOUND_COMMITTED","USER",committedAt,committed.toString());
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Message added")
                    .setMessage("The shared text was added to the active Nine Builder thread. A local provenance record includes its SHA-256 digest and consent state; the sender identity remains unverified.")
                    .setPositiveButton("Open Nine Builder conversation",(d,w)->{
                        startActivity(new Intent(this,BuilderConversationActivity.class)); finish();
                    }).setNegativeButton("Close",(d,w)->finish()).show());
            } catch(Exception failure) {
                String detail=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
                boolean added=turnAppended;
                runOnUiThread(()->new AlertDialog.Builder(this)
                    .setTitle(added?"Message added; audit incomplete":"Import not completed")
                    .setMessage((added?"The conversation turn was saved, but the completion audit record could not be confirmed. "
                        :"The message was not confirmed as imported. ")+detail
                        +"\n\nNo existing conversation or recovery data was replaced.")
                    .setPositiveButton("Open Nine Builder conversation",(d,w)->{
                        startActivity(new Intent(this,BuilderConversationActivity.class)); finish();
                    }).setNegativeButton("Close",(d,w)->finish()).show());
            } finally { conversation.close(); audit.close(); }
        },"yggdrasil-bridge-import").start();
    }
    private void reject(String reason) {
        new AlertDialog.Builder(this).setTitle("Yggdrasil Bridge").setMessage(reason)
            .setPositiveButton("Close",(d,w)->finish()).setOnCancelListener(d->finish()).show();
    }
}
