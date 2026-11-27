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
import org.yggdrasil.scheduler.builder.BuilderModelProvider;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public final class StagedIntakeNamingServiceTest {
    private Context context;
    @Before public void setUp() {
        context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("yim_intake_staging.db");
        context.deleteDatabase("yim_human_understanding.db");
    }
    private IntakeQueueValidator.ValidatedQueue queue() throws Exception {
        JSONObject packet=new JSONObject();
        packet.put("packet_id","packet-001");
        packet.put("captured_at","2026-11-27T12:00:00Z");
        packet.put("source_kind","project_note");
        packet.put("source_reference","private://original-source");
        packet.put("language","English");
        packet.put("content","SECRET_ORIGINAL_PACKET_CONTENT");
        packet.put("metadata",new JSONObject().put("private","metadata"));
        packet.put("placement_status","awaiting_placement");
        packet.put("primary_logical_location",JSONObject.NULL);
        packet.put("contextual_logical_locations",new JSONArray());
        packet.put("source_content_preserved_as_submitted",true);
        packet.put("imported_record_id",JSONObject.NULL);
        packet.put("imported_version_id",JSONObject.NULL);
        JSONObject root=new JSONObject();
        root.put("format",IntakeQueueValidator.FORMAT);
        root.put("format_version",IntakeQueueValidator.FORMAT_VERSION);
        root.put("queue_id","queue-001");
        root.put("packets",new JSONArray().put(packet));
        return IntakeQueueValidator.validate(root.toString());
    }
    private JSONObject proposal() throws Exception {
        JSONArray options=new JSONArray();
        options.put(new JSONObject().put("option_id","a").put("human_name","Shared Path")
                .put("meaning","A path shared by collaborators")
                .put("strengths",new JSONArray().put("Concise"))
                .put("limitations",new JSONArray().put("Broad")));
        options.put(new JSONObject().put("option_id","b").put("human_name","Building Bridges")
                .put("meaning","Building relationships across differences")
                .put("strengths",new JSONArray().put("Concrete"))
                .put("limitations",new JSONArray().put("Metaphorical")));
        return new JSONObject().put("proposal_id","provider-generated-id")
                .put("preferred_option_id","b").put("options",options)
                .put("competing_interpretations",new JSONArray().put("Collaboration").put("Infrastructure"))
                .put("source_refs",new JSONArray());
    }
    @Test public void registersPendingPacketAndCallsMariWithMetadataOnlyOnce() throws Exception {
        new IntakeStagingStore(context).stage(queue());
        AtomicInteger calls=new AtomicInteger();
        BuilderModelProvider provider=new BuilderModelProvider() {
            @Override public String providerId(){return "test";}
            @Override public Completion complete(Request request) throws Exception {
                calls.incrementAndGet();
                assertTrue(request.userMessage.contains("YIM-PACKET/queue-001/packet-001"));
                assertFalse(request.userMessage.contains("SECRET_ORIGINAL_PACKET_CONTENT"));
                assertFalse(request.userMessage.contains("private://original-source"));
                assertFalse(request.userMessage.contains("private metadata"));
                return new Completion(proposal().toString(),"test-model");
            }
        };
        StagedIntakeNamingService.Result first=new StagedIntakeNamingService(context,provider).processPending();
        assertEquals(1,first.registered);
        assertEquals(1,first.proposals);
        assertEquals(0,first.failures);
        assertTrue(first.proposalSummary.contains("YIM-PACKET/queue-001/packet-001"));
        assertTrue(first.proposalSummary.contains("Building Bridges"));
        assertTrue(first.proposalSummary.contains("Shared Path"));
        assertTrue(first.proposalSummary.contains("not been adopted"));
        assertEquals(1,calls.get());
        StagedIntakeNamingService.Result second=new StagedIntakeNamingService(context,provider).processPending();
        assertEquals(0,second.registered);
        assertEquals(0,second.proposals);
        assertEquals(0,second.failures);
        assertEquals(1,calls.get());
        HumanUnderstandingRegisterStore store=new HumanUnderstandingRegisterStore(context,"living-tree");
        assertEquals(2,store.eventCount());
    }
    @Test public void registersIdentityWithoutClaimingMariWhenNoProviderExists() throws Exception {
        new IntakeStagingStore(context).stage(queue());
        StagedIntakeNamingService.Result result=new StagedIntakeNamingService(context,null).processPending();
        assertEquals(1,result.registered);
        assertEquals(0,result.proposals);
        assertFalse(result.mariProviderConfigured);
        assertEquals(1,new HumanUnderstandingRegisterStore(context,"living-tree").eventCount());
    }
}
