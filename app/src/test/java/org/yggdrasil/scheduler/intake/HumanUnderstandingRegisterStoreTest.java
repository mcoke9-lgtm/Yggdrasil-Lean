package org.yggdrasil.scheduler.intake;

import static org.junit.Assert.*;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class HumanUnderstandingRegisterStoreTest {
    private Context context;

    @Before public void setUp() {
        context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("yim_human_understanding.db");
    }

    private JSONArray options() throws Exception {
        JSONArray values=new JSONArray();
        values.put(new JSONObject().put("option_id","a").put("human_name","Building Bridges to a Shared Future")
                .put("meaning","Connects present cooperation to a shared future")
                .put("strengths",new JSONArray().put("Human-readable").put("Collaborative"))
                .put("limitations",new JSONArray().put("Does not specify Mars in the title")));
        values.put(new JSONObject().put("option_id","b").put("human_name","A Shared Path Beyond Earth")
                .put("meaning","Emphasizes a shared future beyond Earth")
                .put("strengths",new JSONArray().put("Space-oriented"))
                .put("limitations",new JSONArray().put("Less emphasis on Earth-based relationships")));
        return values;
    }

    private void createEntry(HumanUnderstandingRegisterStore store) throws Exception {
        store.createEntry("entry-mars-bridge","MARS-BRIDGE-001","Peaceful cooperation linking Earth and Mars",
                "event:entry","human",new JSONArray().put("Mars").put("cooperation"),
                new JSONArray().put("MARS-BRIDGE-001"));
    }

    @Test public void persistsAppendOnlyIdentityAndMariProposalAcrossRestart() throws Exception {
        HumanUnderstandingRegisterStore store=new HumanUnderstandingRegisterStore(context,"living-tree");
        createEntry(store);
        store.appendMariProposal("entry-mars-bridge","proposal-1","event:proposal","mari","a",
                options(),new JSONArray().put("Mars exploration initiative")
                        .put("Earth-based cultural cooperation initiative"),new JSONArray());
        JSONObject snapshot=store.exportSnapshot();
        assertEquals(HumanUnderstandingRegisterStore.FORMAT,snapshot.getString("format"));
        assertEquals(1,snapshot.getInt("format_version"));
        assertEquals(2,snapshot.getJSONArray("events").length());
        assertEquals("entry.created",snapshot.getJSONArray("events").getJSONObject(0).getString("event_type"));
        assertEquals("name.proposed",snapshot.getJSONArray("events").getJSONObject(1).getString("event_type"));
        assertEquals("MARS-BRIDGE-001",snapshot.getJSONArray("events").getJSONObject(0)
                .getJSONObject("payload").getString("technical_id"));
        store.close();

        HumanUnderstandingRegisterStore reopened=new HumanUnderstandingRegisterStore(context,"living-tree");
        assertEquals(2,reopened.eventCount());
        assertEquals(2,reopened.exportSnapshot().getJSONArray("events").length());
        reopened.close();
    }

    @Test public void identicalProposalRetryDoesNotAppendDuplicateEvent() throws Exception {
        HumanUnderstandingRegisterStore store=new HumanUnderstandingRegisterStore(context,"living-tree");
        createEntry(store);
        JSONArray options=options();
        store.appendMariProposal("entry-mars-bridge","proposal-1","event:proposal","mari","a",
                options,new JSONArray().put("Combined initiative"),new JSONArray());
        store.appendMariProposal("entry-mars-bridge","proposal-1","event:proposal","mari","a",
                options,new JSONArray().put("Combined initiative"),new JSONArray());
        assertEquals(2,store.eventCount());
    }

    @Test public void conflictingTechnicalIdentityAndProposalReuseAreRejected() throws Exception {
        HumanUnderstandingRegisterStore store=new HumanUnderstandingRegisterStore(context,"living-tree");
        createEntry(store);
        try {
            store.createEntry("entry-mars-bridge","OTHER-ID","different purpose",
                    "event:conflict","human",new JSONArray(),new JSONArray());
            fail("Conflicting technical identity must be rejected");
        } catch (IllegalStateException expected) { }

        store.appendMariProposal("entry-mars-bridge","proposal-1","event:proposal","mari","a",
                options(),new JSONArray().put("Interpretation one"),new JSONArray());
        try {
            store.appendMariProposal("entry-mars-bridge","proposal-1","event:proposal","mari","b",
                    options(),new JSONArray().put("Interpretation two"),new JSONArray());
            fail("Proposal ID reuse with changed content must be rejected");
        } catch (IllegalArgumentException expected) { }
        assertEquals(2,store.eventCount());
    }

    @Test public void lifecycleReportsMissingMariProviderWithoutPretendingItRan() throws Exception {
        HumanUnderstandingRegisterStore store=new HumanUnderstandingRegisterStore(context,"living-tree");
        HumanUnderstandingLifecycle lifecycle=new HumanUnderstandingLifecycle(store,null);
        HumanUnderstandingLifecycle.Result result=lifecycle.registerTechnicalIdentity(
                "entry-mars-bridge","MARS-BRIDGE-001","Peaceful cooperation",
                "event:entry",new JSONArray(),new JSONArray());
        assertTrue(result.entryCreated);
        assertFalse(result.mariProviderConfigured);
        assertFalse(result.proposalCreated);
        assertEquals(1,store.eventCount());
    }

    @Test public void lifecycleCallsConfiguredMariWithMetadataOnlyAndStoresAlternatives() throws Exception {
        HumanUnderstandingRegisterStore store=new HumanUnderstandingRegisterStore(context,"living-tree");
        HumanUnderstandingLifecycle lifecycle=new HumanUnderstandingLifecycle(store,request -> {
            assertFalse(request.has("content"));
            assertEquals("MARS-BRIDGE-001",request.getString("technical_id"));
            JSONObject result=new JSONObject();
            result.put("proposal_id","proposal-1");
            result.put("preferred_option_id","a");
            result.put("options",options());
            result.put("competing_interpretations",new JSONArray().put("Mars initiative").put("Earth cultural initiative"));
            result.put("source_refs",new JSONArray().put("user-approved-context"));
            return result;
        });
        lifecycle.registerTechnicalIdentity("entry-mars-bridge","MARS-BRIDGE-001",
                "Peaceful cooperation","event:entry",new JSONArray(),new JSONArray());
        HumanUnderstandingLifecycle.Result result=lifecycle.requestMariProposal(
                "entry-mars-bridge","MARS-BRIDGE-001","Peaceful cooperation","event",
                new JSONArray().put("Mars"),new JSONArray(),"chat_exchange","English");
        assertTrue(result.mariProviderConfigured);
        assertTrue(result.proposalCreated);
        assertEquals("proposal-1",result.proposalId);
        assertEquals(2,store.eventCount());
        JSONObject proposal=store.exportSnapshot().getJSONArray("events").getJSONObject(1)
                .getJSONObject("payload");
        assertEquals("Mari",proposal.getString("proposer_role"));
        assertEquals(2,proposal.getJSONArray("options").length());
    }
}
