import tempfile
import unittest
from pathlib import Path

from yim001.naming import (
    HumanUnderstandingRegister, NamingRegistryError, load_register, save_register,
)


def options():
    return [
        {
            "option_id": "shared-future",
            "human_name": "Building Bridges to a Shared Future",
            "meaning": "Connecting communities and disciplines to build a peaceful human future on Earth and beyond.",
            "strengths": ["Warm and inclusive", "Connects Earth-based cooperation with Mars"],
            "limitations": ["Does not explicitly name Mars"],
        },
        {
            "option_id": "path-beyond-earth",
            "human_name": "A Shared Path Beyond Earth",
            "meaning": "A cooperative pathway toward sustainable human presence beyond Earth.",
            "strengths": ["Concise", "Clearly signals the space objective"],
            "limitations": ["Less explicit about Vermont, Cuba, and other Earth-based contributors"],
        },
        {
            "option_id": "earth-to-mars",
            "human_name": "Earth-to-Mars Partnership",
            "meaning": "A practical partnership connecting Earth communities with Mars preparation.",
            "strengths": ["Direct and easy to understand", "Clearly identifies the destination"],
            "limitations": ["Can sound like a formal partnership before partners exist"],
        },
    ]


class HumanUnderstandingRegisterTests(unittest.TestCase):
    def make_register(self):
        register = HumanUnderstandingRegister("living-tree-human-understanding")
        register.create_entry(
            entry_id="mars-bridge-001",
            technical_id="MARS-BRIDGE-001",
            purpose="Coordinate voluntary cultural, scientific, and educational cooperation for sustainable human futures.",
            event_id="evt-entry-001",
            actor_id="human",
            keywords=["Mars", "Vermont", "Cuba", "education", "cooperation"],
            preserved_terms=["MARS-BRIDGE-001", "Mathematics on Mars"],
        )
        return register

    def test_mari_proposal_preserves_alternatives_and_tradeoffs(self):
        register = self.make_register()
        register.propose_name(
            entry_id="mars-bridge-001",
            proposal_id="mari-name-001",
            event_id="evt-proposal-001",
            actor_id="mari",
            options=options(),
            preferred_option_id="shared-future",
            competing_interpretations=[
                "The initiative is primarily about Mars exploration and settlement.",
                "The initiative is primarily about cultural change and international cooperation on Earth.",
                "The initiative must communicate both purposes without implying that institutional partnerships already exist.",
            ],
        )
        entry = register.entries()[0]
        self.assertEqual(entry["current_human_name"], None)
        self.assertEqual(len(entry["proposals"][0]["options"]), 3)
        self.assertEqual(entry["proposals"][0]["preferred_option_id"], "shared-future")
        self.assertEqual(len(entry["proposals"][0]["competing_interpretations"]), 3)

    def test_adoption_requires_explicit_human_authorization_assertion(self):
        register = self.make_register()
        register.propose_name(
            entry_id="mars-bridge-001", proposal_id="mari-name-001",
            event_id="evt-proposal-001", actor_id="mari", options=options(),
            preferred_option_id="shared-future", competing_interpretations=["technical", "cultural"],
        )
        with self.assertRaisesRegex(NamingRegistryError, "explicit human authorization"):
            register.decide_name(
                entry_id="mars-bridge-001", proposal_id="mari-name-001",
                event_id="evt-decision-001", actor_id="tyr", decision="adopted",
                selected_option_id="shared-future", authorized_by="user",
                authority_role="Týr", human_authorized=False,
            )

    def test_authorized_adoption_is_separate_append_only_event(self):
        register = self.make_register()
        register.propose_name(
            entry_id="mars-bridge-001", proposal_id="mari-name-001",
            event_id="evt-proposal-001", actor_id="mari", options=options(),
            preferred_option_id="shared-future", competing_interpretations=["technical", "cultural"],
        )
        register.decide_name(
            entry_id="mars-bridge-001", proposal_id="mari-name-001",
            event_id="evt-decision-001", actor_id="tyr", decision="adopted",
            selected_option_id="shared-future", authorized_by="human-approval-reference",
            authority_role="Týr", human_authorized=True,
        )
        entry = register.entries()[0]
        self.assertEqual(entry["current_human_name"], "Building Bridges to a Shared Future")
        self.assertEqual([event["sequence"] for event in register.events], [1, 2, 3])

    def test_duplicate_event_and_technical_identity_are_rejected(self):
        register = self.make_register()
        with self.assertRaisesRegex(NamingRegistryError, "duplicate event_id"):
            register.create_entry(
                entry_id="another", technical_id="OTHER-001", purpose="Other",
                event_id="evt-entry-001", actor_id="human",
            )
        with self.assertRaisesRegex(NamingRegistryError, "duplicate technical_id"):
            register.create_entry(
                entry_id="another", technical_id="MARS-BRIDGE-001", purpose="Other",
                event_id="evt-entry-002", actor_id="human",
            )

    def test_proposal_must_be_attributed_to_mari_and_keep_two_options(self):
        register = self.make_register()
        with self.assertRaisesRegex(NamingRegistryError, "attributed to Mari"):
            register.propose_name(
                entry_id="mars-bridge-001", proposal_id="bad", event_id="bad-1",
                actor_id="agent", proposer_role="Odin", options=options(),
                preferred_option_id="shared-future", competing_interpretations=["one"],
            )
        with self.assertRaisesRegex(NamingRegistryError, "at least two alternatives"):
            register.propose_name(
                entry_id="mars-bridge-001", proposal_id="bad2", event_id="bad-2",
                actor_id="mari", options=options()[:1],
                preferred_option_id="shared-future", competing_interpretations=["one"],
            )

    def test_save_load_restart_preserves_proposals_and_decisions(self):
        register = self.make_register()
        register.propose_name(
            entry_id="mars-bridge-001", proposal_id="mari-name-001",
            event_id="evt-proposal-001", actor_id="mari", options=options(),
            preferred_option_id="shared-future", competing_interpretations=["technical", "cultural"],
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "human-understanding-register.json"
            save_register(register, path)
            recovered = load_register(path)
            self.assertEqual(recovered.to_snapshot(), register.to_snapshot())
            self.assertEqual(recovered.entries()[0]["current_human_name"], None)

    def test_unknown_schema_version_and_corrupt_sequence_are_rejected(self):
        snapshot = self.make_register().to_snapshot()
        snapshot["format_version"] = 99
        with self.assertRaisesRegex(NamingRegistryError, "unsupported"):
            HumanUnderstandingRegister.from_snapshot(snapshot)
        snapshot = self.make_register().to_snapshot()
        snapshot["events"][0]["sequence"] = 2
        with self.assertRaisesRegex(NamingRegistryError, "contiguous"):
            HumanUnderstandingRegister.from_snapshot(snapshot)


if __name__ == "__main__":
    unittest.main()
