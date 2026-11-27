import unittest

from yim001.intake import WorkIntakeQueue
from yim001.naming import HumanUnderstandingRegister
from yim001.lifecycle import HumanUnderstandingLifecycle, UnderstandingLifecycleError


def proposal(_request):
    return {
        "proposal_id": "mari-proposal-1",
        "options": [
            {"option_id": "a", "human_name": "A Shared Human Future",
             "meaning": "A human-readable interpretation.", "strengths": ["Inclusive"],
             "limitations": ["Broad"]},
            {"option_id": "b", "human_name": "A Path Beyond Earth",
             "meaning": "A space-oriented interpretation.", "strengths": ["Specific"],
             "limitations": ["Less Earth-focused"]},
        ],
        "preferred_option_id": "a",
        "competing_interpretations": ["technical", "cultural"],
        "source_refs": ["test:source"],
    }


class HumanUnderstandingLifecycleTests(unittest.TestCase):
    def make_workflow(self, provider=None):
        queue = WorkIntakeQueue("test-queue")
        queue.add(
            {"text": "Preserve this source exactly."},
            source_kind="chat_exchange", language="English", packet_id="packet-1",
        )
        return HumanUnderstandingLifecycle(
            queue, HumanUnderstandingRegister("test-register"), proposer=provider,
        )

    def test_packet_intake_creates_stable_identity_and_mari_proposal(self):
        workflow = self.make_workflow(proposal)
        result = workflow.register_packet(
            "packet-1", entry_id="entry-1", technical_id="MARS-BRIDGE-001",
            purpose="Coordinate voluntary cooperation.", event_prefix="intake-1",
            keywords=["Mars", "cooperation"], preserved_terms=["MARS-BRIDGE-001"],
        )
        self.assertTrue(result["entry_created"])
        self.assertTrue(result["proposal_created"])
        self.assertTrue(result["mari_provider_configured"])
        self.assertEqual(result["current_human_name"], None)
        self.assertEqual(result["proposal_count"], 1)
        self.assertTrue(result["decision_required_for_adoption"])

    def test_no_provider_creates_identity_without_faking_mari_execution(self):
        workflow = self.make_workflow()
        result = workflow.register_packet(
            "packet-1", entry_id="entry-1", technical_id="COMPONENT-001",
            purpose="A component.", event_prefix="intake-2",
        )
        self.assertFalse(result["mari_provider_configured"])
        self.assertEqual(result["proposal_count"], 0)
        self.assertIsNone(result["current_human_name"])

    def test_unknown_or_imported_packet_is_rejected(self):
        workflow = self.make_workflow()
        with self.assertRaisesRegex(UnderstandingLifecycleError, "not found"):
            workflow.register_packet(
                "missing", entry_id="e", technical_id="T-1",
                purpose="Purpose", event_prefix="x",
            )
        workflow.queue.mark_imported(
            "packet-1", primary_logical_location="root",
            contextual_logical_locations=[], record_id="record", version_id="version",
        )
        with self.assertRaisesRegex(UnderstandingLifecycleError, "only pending"):
            workflow.register_packet(
                "packet-1", entry_id="e", technical_id="T-1",
                purpose="Purpose", event_prefix="x",
            )

    def test_reprocessing_existing_entry_does_not_duplicate_identity_or_proposal(self):
        workflow = self.make_workflow(proposal)
        args = dict(
            packet_id="packet-1", entry_id="entry-1", technical_id="T-1",
            purpose="Purpose", event_prefix="same-prefix",
        )
        first = workflow.register_packet(**args)
        second = workflow.register_packet(**args)
        self.assertTrue(first["entry_created"])
        self.assertFalse(second["entry_created"])
        self.assertEqual(second["proposal_count"], 1)
        self.assertEqual(len(workflow.register.events), 2)

    def test_conflicting_reuse_of_entry_id_is_rejected(self):
        workflow = self.make_workflow()
        workflow.register_packet(
            "packet-1", entry_id="entry-1", technical_id="T-1",
            purpose="Purpose", event_prefix="one",
        )
        with self.assertRaisesRegex(UnderstandingLifecycleError, "conflicts"):
            workflow.register_packet(
                "packet-1", entry_id="entry-1", technical_id="T-2",
                purpose="Different", event_prefix="two",
            )


if __name__ == "__main__":
    unittest.main()
