package org.yggdrasil.scheduler.intake;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Host adapter for the YIM-001 human-understanding lifecycle.
 * The host may inject a Mari provider; absent a provider, no Character call is
 * claimed. Original intake content is never included in the proposal request.
 */
public final class HumanUnderstandingLifecycle {
    public interface MariProposalProvider {
        JSONObject propose(JSONObject descriptiveRequest) throws Exception;
    }

    public static final class Result {
        public final String entryId;
        public final String technicalId;
        public final boolean entryCreated;
        public final boolean mariProviderConfigured;
        public final boolean proposalCreated;
        public final String proposalId;

        Result(String entryId, String technicalId, boolean entryCreated,
                boolean configured, boolean proposalCreated, String proposalId) {
            this.entryId=entryId; this.technicalId=technicalId;
            this.entryCreated=entryCreated; this.mariProviderConfigured=configured;
            this.proposalCreated=proposalCreated; this.proposalId=proposalId;
        }
    }

    private final HumanUnderstandingRegisterStore store;
    private final MariProposalProvider mari;

    public HumanUnderstandingLifecycle(HumanUnderstandingRegisterStore store,
            MariProposalProvider mari) {
        if (store == null) throw new IllegalArgumentException("Human understanding store is required");
        this.store=store; this.mari=mari;
    }

    public Result registerTechnicalIdentity(String entryId, String technicalId, String purpose,
            String eventId, JSONArray keywords, JSONArray preservedTerms) throws Exception {
        int before=store.eventCount();
        store.createEntry(entryId, technicalId, purpose, eventId, "human",
                keywords == null ? new JSONArray() : keywords,
                preservedTerms == null ? new JSONArray() : preservedTerms);
        return new Result(entryId, technicalId, store.eventCount()>before,
                mari!=null, false, null);
    }

    /**
     * Requests alternatives from the configured Mari provider using descriptive
     * metadata only. Provider output is persisted as a proposal, never adopted.
     */
    public Result requestMariProposal(String entryId, String technicalId, String purpose,
            String eventPrefix, JSONArray keywords, JSONArray preservedTerms,
            String sourceKind, String language) throws Exception {
        if (mari == null) return new Result(entryId, technicalId, false, false, false, null);
        requireText(entryId,"entryId"); requireText(technicalId,"technicalId");
        requireText(purpose,"purpose"); requireText(eventPrefix,"eventPrefix");
        requireText(sourceKind,"sourceKind"); requireText(language,"language");
        JSONObject request=new JSONObject();
        request.put("entry_id",entryId); request.put("technical_id",technicalId);
        request.put("purpose",purpose);
        request.put("keywords",keywords==null?new JSONArray():new JSONArray(keywords.toString()));
        request.put("preserved_terms",preservedTerms==null?new JSONArray():new JSONArray(preservedTerms.toString()));
        request.put("source_kind",sourceKind); request.put("language",language);
        int before=store.eventCount();
        JSONObject proposal=mari.propose(request);
        if (proposal==null || proposal.length()!=5 || !proposal.has("proposal_id")
                || !proposal.has("options") || !proposal.has("preferred_option_id")
                || !proposal.has("competing_interpretations") || !proposal.has("source_refs"))
            throw new IllegalArgumentException("Mari provider returned an invalid proposal envelope");
        String proposalId=proposal.getString("proposal_id");
        store.appendMariProposal(entryId,proposalId,eventPrefix+":mari-proposal","mari",
                proposal.getString("preferred_option_id"),proposal.getJSONArray("options"),
                proposal.getJSONArray("competing_interpretations"),proposal.getJSONArray("source_refs"));
        return new Result(entryId,technicalId,false,true,store.eventCount()>before,proposalId);
    }

    private static void requireText(String value,String field) {
        if(value==null || value.trim().isEmpty()) throw new IllegalArgumentException(field+" is required");
    }
}
